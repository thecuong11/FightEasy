package com.flighteasy.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flighteasy.entity.Airport;
import com.flighteasy.entity.Booking;
import com.flighteasy.entity.EmailLog;
import com.flighteasy.entity.Flight;
import com.flighteasy.repository.EmailLogRepository;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final SpringTemplateEngine templateEngine;
    private final EmailLogRepository emailLogRepository;
    private final ObjectMapper objectMapper;

    @Value("${spring.mail.username}")
    private String fromEmail;

    public EmailService (JavaMailSender mailSender,
                         @Qualifier("emailTemplateEngine") SpringTemplateEngine templateEngine,
                         EmailLogRepository emailLogRepository, ObjectMapper objectMapper) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.emailLogRepository = emailLogRepository;
        this.objectMapper = objectMapper;
    }

    @Async("emailTaskExecutor")
    public void sendBookingConfirmation(Booking booking) {
        Context context = new Context();
        context.setVariable("pnrCode", booking.getPnrCode());
        context.setVariable("flightNumber", getFlightNumber(booking));
        context.setVariable("from", getOriginName(booking));
        context.setVariable("to", getDestinationName(booking));
        context.setVariable("departureTime", getDepartureTime(booking));
        context.setVariable("totalPrice", formatCurrency(booking.getTotalPrice()));
        context.setVariable("passengers", getPassengerList(booking));

        sendEmail(
                booking.getContactEmail(),
                "✈\uFE0F Xác nhận đặt vé - Mã PNR: " + booking.getPnrCode(),
                "booking-confirmed",
                context,
                booking.getPnrCode()
        );

    }

    @Async("emailTaskExecutor")
    public void sendBookingCancellation(Booking booking) {
        Context context = new Context();
        context.setVariable("pnrCode", booking.getPnrCode());
        context.setVariable("refundAmount", formatCurrency(booking.getRefundAmount()));
        context.setVariable("cancelReason", booking.getCancelReason());

        sendEmail(
                booking.getContactEmail(),
                "❌ Thông báo hủy vé - " + booking.getPnrCode(),
                "booking-cancelled",
                context,
                booking.getPnrCode()
        );
    }

    @Async("emailTaskExecutor")
    public void sendFlightDelayNotification(Flight flight, List<Booking> affectedBookings) {
        affectedBookings.forEach(booking -> {
            Context context = new Context();
            context.setVariable("pnrCode", booking.getPnrCode());
            context.setVariable("flightNumber", flight.getFlightNumber());
            context.setVariable("newDeparture", flight.getDepartureTime());
            context.setVariable("delayMinutes", flight.getDelayMinutes());

            sendEmail(
                    booking.getContactEmail(),
                    "⚠\uFE0F Thông báo chuyến bay trễ - " + flight.getFlightNumber(),
                    "flight-delayed",
                    context,
                    booking.getPnrCode()
            );
        });
    }

    @Async("emailTaskExecutor")
    public void sendCheckinReminder(Booking booking) {
        if (emailLogRepository.countByReferenceIdAndTemplateName(booking.getPnrCode(), "checkin-reminder") >= 5) {
            return;
        }

        Context context = new Context();
        context.setVariable("pnrCode", booking.getPnrCode());
        context.setVariable("flightNumber", getFlightNumber(booking));
        context.setVariable("departureTime", getDepartureTime(booking));

        sendEmail(
                booking.getContactEmail(),
                "\uD83D\uDD14 Nhắc nhở: Check-in chuyến bay của bạn",
                "checkin-reminder",
                context,
                booking.getPnrCode()
        );
    }

    @Async("emailTaskExecutor")
    public void sendPasswordResetEmail(String email, String resetToken) {
        Context context = new Context();
        context.setVariable("resetLink", "http://localhost:3000/reser-password?token=" + resetToken);
        context.setVariable("expiryMinutes", 60);

        sendEmail(email, "\uD83D\uDD11 Đặt lại mật khẩu FlightEasy", "password-reset", context, email);
    }

    @Async("emailTaskExecutor")
    public void sendWelcomeEmail(String email, String fullName) {
        Context context = new Context();
        context.setVariable("fullName", fullName);

        sendEmail(email, "\uD83D\uDC4B Chào mừng đến FlightEasy!", "welcome", context, email);
    }

    public void sendEmail(String to, String subject, String templateName, Context context, String referenceId) {
        String contextJson;
        try {
            contextJson = objectMapper.writeValueAsString(context.getVariableNames().stream()
                    .collect(Collectors.toMap(name -> name, context::getVariable)));
        } catch (Exception e) {
            contextJson = "{}";
        }

        EmailLog emailLog = EmailLog.builder()
                .recipient(to)
                .subject(subject)
                .templateName(templateName)
                .referenceId(referenceId)
                .status("PENDING")
                .attempts(0)
                .contextJson(contextJson)
                .build();
        emailLog = emailLogRepository.save(emailLog);

        doSend(emailLog, context, templateName);
    }

    public void retrySend(EmailLog emailLog) {
        Context context = new Context();
        try {
            Map<String, Object> variables = objectMapper.readValue(
                    emailLog.getContextJson() != null ? emailLog.getContextJson() : "{}",
                    new TypeReference<Map<String, Object>>() {});
            variables.forEach(context::setVariable);
        } catch (Exception e) {
            log.error("Failed to restore context for email {}: {}", emailLog.getId(), e.getMessage());
        }
        doSend(emailLog, context, emailLog.getTemplateName());
    }

    private void doSend(EmailLog emailLog, Context context, String templateName) {
        try {
            String htmlContent = templateEngine.process(templateName, context);
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(fromEmail, "FlightEasy ✈\uFE0F");
            helper.setTo(emailLog.getRecipient());
            helper.setSubject(emailLog.getSubject());
            helper.setText(htmlContent, true);

            mailSender.send(mimeMessage);

            emailLog.setStatus("SENT");
            emailLog.setSentAt(LocalDateTime.now());
            emailLog.setAttempts(emailLog.getAttempts() + 1);
            log.info("Email sent to {} (template={})", emailLog.getRecipient(), templateName);
        } catch (Exception ex) {
            emailLog.setAttempts(emailLog.getAttempts() + 1);
            emailLog.setLastError(ex.getMessage());
            log.info("Failed to send email to {}: {}", emailLog.getRecipient(), ex.getMessage());

            if (emailLog.getAttempts() >= 3) {
                emailLog.setStatus("FAILED");
                log.error("Email permanently failed after 3 attempts: {}", emailLog.getRecipient());
            } else {
                emailLog.setStatus("PENDING");
                emailLog.setNextRetryAt(calculateNextRetry(emailLog.getAttempts()));
            }
        } finally {
            emailLogRepository.save(emailLog);
        }
    }

    private LocalDateTime calculateNextRetry(int attempts) {
        return switch (attempts) {
            case 1 -> LocalDateTime.now().plusMinutes(1);
            case 2 -> LocalDateTime.now().plusMinutes(5);
            default -> LocalDateTime.now().plusMinutes(15);
        };
    }

    private String formatCurrency(BigDecimal amount) {
        if (amount == null) return "0 đ";
        return String.format("%.0f đ", amount);
    }

    private String getFlightNumber(Booking booking) {
        return booking.getSegments().stream().findFirst()
                .map(seg -> seg.getFlightClass().getFlight().getFlightNumber()).orElse("N/A");
    }

    private LocalDateTime getDepartureTime(Booking booking) {
        return booking.getSegments().stream().findFirst()
                .map(seg -> seg.getFlightClass().getFlight().getDepartureTime()).orElse(null);
    }

    private String getOriginName(Booking booking) {
        return booking.getSegments().stream()
                .findFirst()
                .map(seg -> {
                    Airport origin = seg.getFlightClass().getFlight().getOrigin();
                    return origin.getCity() + " (" + origin.getIataCode() + ")";
                })
                .orElse("N/A");
    }

    private String getDestinationName(Booking booking) {
        return booking.getSegments().stream()
                .findFirst()
                .map(seg -> {
                    Airport dest = seg.getFlightClass().getFlight().getDestination();
                    return dest.getCity() + " (" + dest.getIataCode() + ")";
                })
                .orElse("N/A");
    }

    private List<Map<String, String>> getPassengerList(Booking booking) {
        return booking.getSegments().stream()
                .flatMap(seg -> seg.getPassengers().stream())
                .map(p -> Map.of(
                        "fullName", p.getFirstName() + " " + p.getLastName(),
                        "seatNumber", p.getSeat() != null ? p.getSeat().getSeatNumber() : "N/A"
                ))
                .toList();
    }

    @Async("emailTaskExecutor")
    public void sendFlightCancellationNotification(Flight flight, List<Booking> affectedBookings) {
        affectedBookings.forEach(booking -> {
            Context context = new Context();
            context.setVariable("pnrCode", booking.getPnrCode());
            context.setVariable("flightNumber", flight.getFlightNumber());
            context.setVariable("from", getOriginName(booking));
            context.setVariable("to", getDestinationName(booking));
            context.setVariable("departureTime", getDepartureTime(booking));
            context.setVariable("refundAmount", formatCurrency(booking.getRefundAmount()));

            sendEmail(
                    booking.getContactEmail(),
                    "🛑 Chuyến bay " + flight.getFlightNumber() + " đã bị hủy - " + booking.getPnrCode(),
                    "flight-cancelled",
                    context,
                    booking.getPnrCode()
            );
        });
    }

}
