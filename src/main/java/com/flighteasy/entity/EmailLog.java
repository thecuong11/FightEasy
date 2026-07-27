package com.flighteasy.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "email_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String templateName;

    private String referenceId;
    private String status = "PENDING";
    private Integer attempts = 0;
    private String lastError;
    private LocalDateTime nextRetryAt;
    private LocalDateTime sentAt;

    @Column(columnDefinition = "text")
    private String contextJson;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
