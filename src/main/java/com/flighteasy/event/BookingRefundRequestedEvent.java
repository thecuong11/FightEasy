package com.flighteasy.event;


import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

@Getter
@RequiredArgsConstructor
public class BookingRefundRequestedEvent {
    private final Long bookingId;
    private final BigDecimal refundAmount;
}
