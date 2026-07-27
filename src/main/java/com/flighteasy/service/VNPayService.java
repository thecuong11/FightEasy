package com.flighteasy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flighteasy.entity.Booking;
import com.flighteasy.entity.Payment;
import com.flighteasy.enums.BookingStatus;
import com.flighteasy.enums.PaymentStatus;
import com.flighteasy.event.BookingConfirmedEvent;
import com.flighteasy.repository.BookingRepository;
import com.flighteasy.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class VNPayService {

    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final RestTemplate vnpayRestTemplate;

    @Value("${vnpay.tmn-code}")
    private String tmnCode;

    @Value("${vnpay.hash-secret}")
    private String hashSecret;

    @Value("${vnpay.payment-url}")
    private String paymentUrl;

    @Value("${vnpay.api-url}")
    private String apiUrl;

    public String createPaymentUrl(Booking booking, Payment payment, String returnUrl, String ipAddress) {
        long amount = booking.getTotalPrice().multiply(BigDecimal.valueOf(100)).longValue();

        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", tmnCode);
        params.put("vnp_Amount", String.valueOf(amount));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", payment.getVnpTxnRef());
        params.put("vnp_OrderInfo", "Thanh toan ve may bay - " + booking.getPnrCode());
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", returnUrl);
        params.put("vnp_IpAddr", ipAddress != null ? ipAddress : "127.0.0.1");
        params.put("vnp_CreateDate", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        params.put("vnp_ExpireDate", LocalDateTime.now().plusMinutes(15).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));

        String hashData = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        String securityHash = hmacSha512(hashSecret, hashData);
        params.put("vnp_SecureHash", securityHash);

        String queryString = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        return paymentUrl + "?" + queryString;
    }

    public String processIPN(Map<String, String> params) {
        String receiveHash = params.get("vnp_SecureHash");
        String responseCode = params.get("vnp_ResponseCode");
        String txnRef = params.get("vnp_TxnRef");
        long amount = Long.parseLong(params.get("vnp_Amount"));

        if (!verifySignature(params, receiveHash)) {
            log.warn("IPN: Invalid signature for txnRef={}", txnRef);
            return buildIPNResponse("97", "Invalid signature");
        }

        Payment payment = paymentRepository.findByVnpTxnRef(txnRef).orElse(null);
        if (payment == null) {
            return buildIPNResponse("01", "Order not found");
        }

        if (payment.getStatus() != PaymentStatus.PENDING) {
            return buildIPNResponse("02", "Order already confirmed");
        }

        if (!payment.getAmountVnpay().equals(amount)) {
            log.error("IPN: Amount mismatch. Expected={}, Got={}", payment.getAmountVnpay(), amount);
            return buildIPNResponse("04", "Invalid amount");
        }

        try {
            payment.setRawIpnData(new ObjectMapper().writeValueAsString(params));
        } catch (Exception ignored) {}

        payment.setVnpTransactionNo(params.get("vnp_TransactionNo"));
        payment.setVnpResponseCode(responseCode);
        payment.setVnpBankCode(params.get("vnp_BankCode"));
        payment.setVnpCardType(params.get("vnp_CardType"));

        if ("00".equals(responseCode)) {
            payment.setStatus(PaymentStatus.SUCCESS);
            paymentRepository.save(payment);

            if (payment.getBooking().getStatus() == BookingStatus.CONFIRMED) {
                log.error("DUPLICATE IPN ignored for already-confirmed booking {} - txnRef={}",payment.getBooking().getPnrCode(), payment.getVnpTxnRef());
                return buildIPNResponse("01", "Order already confirmed");
            }
            confirmBooking(payment.getBooking());
            eventPublisher.publishEvent(new BookingConfirmedEvent(payment.getBooking()));
        } else {
            payment.setStatus(PaymentStatus.FAILED);
        }

        paymentRepository.save(payment);

        return buildIPNResponse("00", "Confirm success");
    }

    public boolean verifyReturnUrl(Map<String, String> params) {
        String receivedHash = params.get("vnp_SecureHash");
        return verifySignature(params, receivedHash);
    }

    private boolean verifySignature(Map<String, String> params, String receivedHash) {
        String hashData = params.entrySet().stream()
                .filter(e -> !e.getKey().equals("vnp_SecureHash") && !e.getKey().equals("vnp_SecureHashType"))
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        String expectedHash = hmacSha512(hashSecret, hashData);
        return expectedHash.equals(receivedHash);
    }

    private String hmacSha512(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
            mac.init(secretKey);
            byte[] hmacData = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));

            return Hex.encodeHexString(hmacData);
        } catch (Exception ex){
            throw new RuntimeException("Cannot generate HMAC", ex);
        }
    }

    private String buildIPNResponse(String rspCode, String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("rspCode", rspCode, "message", message));
        } catch (Exception e) {
            return "{\"RspCode\":\"99\",\"Message\":\"Internal error\"}";
        }

    }

    private void confirmBooking(Booking booking) {
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setConfirmedAt(LocalDateTime.now());
        booking.setExpiresAt(null);
        bookingRepository.save(booking);
    }

    public Map<String, Object> queryTransactionStatus(Payment payment) {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "querydr");
        params.put("vnp_TmnCode", tmnCode);
        params.put("vnp_TxnRef", payment.getVnpTxnRef());
        params.put("vnp_OrderInfo", "Kiem tra giao dich " + payment.getVnpTxnRef());
        params.put("vnp_TransactionDate", payment.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        params.put("vnp_IpAddr", "127.0.0.1");
        params.put("vnp_CreateDate", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        params.put("vnp_RequestId", UUID.randomUUID().toString());

        String hashData = params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        params.put("vnp_SecureHash", hmacSha512(hashSecret, hashData));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(params, headers);

        ResponseEntity<Map> response = vnpayRestTemplate.postForEntity(apiUrl, entity, Map.class);
        return response.getBody();
    }

    public void confirmFromReconciliation(Payment payment, Map<String, Object> queryResult) {

        if (payment.getStatus() != PaymentStatus.PENDING) {
            return;
        }

        payment.setVnpTransactionNo((String) queryResult.get("vnp_TransactionNo"));
        payment.setVnpResponseCode((String) queryResult.get("vnp_ResponseCode"));
        payment.setVnpBankCode((String) queryResult.getOrDefault("vnp_BankCode", ""));
        payment.setStatus(PaymentStatus.SUCCESS);

        try {
            payment.setRawIpnData(new ObjectMapper().writeValueAsString(queryResult));
        } catch (Exception e) {

        }

        Booking booking = payment.getBooking();

        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            log.error("DUPLICATE PAYMENT phát hiện qua reconciliation - booking {} đã CONFIRMED, " +
                    "txnRef={} cần được hoàn tiền th công, amount={}",
                    booking.getPnrCode(), payment.getVnpTxnRef(), payment.getAmount());

        } else {
            booking.setStatus(BookingStatus.CONFIRMED);
            booking.setConfirmedAt(LocalDateTime.now());
            booking.setExpiresAt(null);
            bookingRepository.save(booking);
            eventPublisher.publishEvent(new BookingConfirmedEvent(booking));
        }

        paymentRepository.save(payment);
        log.info("Reconciled payment {} -> SUCCESS (qua querydr, IPN gốc bị miss", payment.getVnpTxnRef());
    }

    public boolean refundTransaction(Payment payment, BigDecimal refundAmount, String user) {
        try {
            String vnpRequestId = UUID.randomUUID().toString().substring(0,8);
            String vnpCreateDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String vnpTransactionType = "02";

            Map<String, String> params = new HashMap<>();
            params.put("vnp_RequestId", vnpRequestId);
            params.put("vnp_Version", "2.1.0");
            params.put("vnp_Command", "refund");
            params.put("vnp_TmnCode", tmnCode);
            params.put("vnp_TransactionType", vnpTransactionType);
            params.put("vnp_TxnRef", payment.getVnpTxnRef());
            params.put("vnp_Amount", String.valueOf(refundAmount.multiply(BigDecimal.valueOf(100)).longValue()));
            params.put("vnp_TransactionNo", payment.getVnpTransactionNo());
            params.put("vnp_TransactionDate", payment.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
            params.put("vnp_CreateBy", user);
            params.put("vnp_CreateDate", vnpCreateDate);
            params.put("vnp_IpAddr", "127.0.0.1");

            String hashData = String.join("|",
                    vnpRequestId, params.get("vnp_Version"), params.get("vnp_Command"), tmnCode,
                    vnpTransactionType, payment.getVnpTxnRef(), params.get("vnp_Amount"),
                    payment.getVnpTransactionNo(), params.get("vnp_TransactionDate"), user, vnpCreateDate, "127.0.0.1");
            params.put("vnp_SecureHash", hmacSha512(hashSecret, hashData));

            Map<String, Object> response = vnpayRestTemplate.postForObject(apiUrl, params, Map.class);

            String responseCode = String.valueOf(response.get("vnp_ResponseCode"));
            if ("00".equals(responseCode)) {
                payment.setRefundAmount(refundAmount);
                payment.setRefundTransId(String.valueOf(response.get("vnp_TransactionNo")));
                payment.setRefundedAt(LocalDateTime.now());
                paymentRepository.save(payment);
                return true;
            }

            log.error("VNPay refund failed for txnRef={}, response={}", payment.getVnpTxnRef(), responseCode);
            return false;
        } catch (Exception e) {
            log.error("VNPay refund error for txnRef={}: {}", payment.getVnpTxnRef(), e.getMessage());
            return false;
        }
    }
}
