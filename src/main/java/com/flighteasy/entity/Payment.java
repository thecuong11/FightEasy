package com.flighteasy.entity;

import com.flighteasy.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private Long amountVnpay;

    private String currency = "VND";
    private String gateway = "VNPAY";

    @Enumerated(EnumType.STRING)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(unique = true)
    private String vnpTxnRef;

    private String vnpTransactionNo;
    private String vnpBankCode;
    private String vnpCardType;
    private String vnpResponseCode;
    private String vnpSecureHash;

    @Column(columnDefinition = "text")
    private String rawIpnData;

    @Column(columnDefinition = "text")
    private String rawReturnData;

    private BigDecimal refundAmount;
    private String refundTransId;
    private LocalDateTime refundedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refunded_by")
    private User refundedBy;

    @CreationTimestamp
    private LocalDateTime createdAt;
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column(name = "refund_status")
    private String refundStatus;
}
