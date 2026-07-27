package com.flighteasy.event;

import com.flighteasy.entity.Payment;
import com.flighteasy.enums.PaymentStatus;
import com.flighteasy.repository.PaymentRepository;
import com.flighteasy.service.VNPayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookingRefundListener {
    private final PaymentRepository paymentRepository;
    private final VNPayService vnPayService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingRefundRequested(BookingRefundRequestedEvent event) {
        Payment payment = paymentRepository.findByBookingIdAndStatus(event.getBookingId(), PaymentStatus.SUCCESS)
                .orElse(null);

        if (payment == null) {
            log.warn("No SUCCESS payment found for booking {} - skip refund", event.getBookingId());
            return;
        }

        boolean success = vnPayService.refundTransaction(payment, event.getRefundAmount(), "SYSTEM");

        if (!success) {
            payment.setRefundStatus("FAILED");
            paymentRepository.save(payment);
            log.error("Refund failed for booking {} - payment id {} needs manual handling",
                    event.getBookingId(), payment.getId());
        }
    }
}
