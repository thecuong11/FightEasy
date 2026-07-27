package com.flighteasy.repository;

import com.flighteasy.entity.Payment;
import com.flighteasy.enums.PaymentStatus;
import io.lettuce.core.dynamic.annotation.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByVnpTxnRef(String vnpTxnRef);

    @Query("SELECT p FROM Payment p WHERE p.booking.id = :bookingId ORDER BY p.createdAt DESC LIMIT 1")
    Optional<Payment> findLatestPaymentByBookingId(@Param("bookingId") Long bookingId);

    List<Payment> findByStatusAndCreatedAtBefore(PaymentStatus status, LocalDateTime date);

    Optional<Payment> findByBookingIdAndStatus(Long bookingId, PaymentStatus status);
}
