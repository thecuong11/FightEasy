package com.flighteasy.service;

import com.flighteasy.dto.BookingResponse;
import com.flighteasy.dto.CancelBookingResponse;
import com.flighteasy.dto.CreateBookingRequest;
import com.flighteasy.dto.PassengerRequest;
import com.flighteasy.entity.*;
import com.flighteasy.enums.BookingStatus;
import com.flighteasy.event.BookingCancelledEvent;
import com.flighteasy.event.BookingRefundRequestedEvent;
import com.flighteasy.event.FlightCancelledEvent;
import com.flighteasy.exception.custom.*;
import com.flighteasy.repository.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final FlightClassRepository flightClassRepository;
    private final SeatRepository seatRepository;
    private final PassengerRepository passengerRepository;
    private final BookingSegmentRepository bookingSegmentRepository;
    private final ApplicationEventPublisher eventPublisher;

    private static final BigDecimal SERVICE_FEE_PER_PERSON= BigDecimal.valueOf(27500);
    private final PaymentRepository paymentRepository;

    @Transactional
    public BookingResponse createBooking(CreateBookingRequest request, Long userId) {
        FlightClass flightClass = flightClassRepository
                .findByIdWithLock(request.flightClassId())
                .orElseThrow(() -> new NotFoundException("Hạng vé không tồn tại"));

        int passengerCount = request.getNonInfantPassengers();

        if (flightClass.getAvailableSeats() <  passengerCount) {
            throw new NotEnoughSeatsException("Chỉ còn " + flightClass.getAvailableSeats() + " ghế trống");
        }

        List<Long> seatIds = request.selectedSeatIds() != null ? request.selectedSeatIds() : List.of();
        if (!seatIds.isEmpty()) {
            List<Seat> seats = seatRepository.findAllByIdWithLock(seatIds);
            seats.forEach(seat -> {
                if (!seat.getIsAvailable()) {
                    throw new SeatUnavailableException("Ghế " + seat.getSeatNumber() + " đã được chọn");
                }
            });
        }

        List<String> isNumber = request.getPassengerIdNumbers();
        if (passengerRepository.existsDuplicateOnFlight(flightClass.getFlight().getId(), isNumber)) {
            throw new DuplicatePassengerException("Một hành khách đã có booking trên chuyến bay này");
        }

        BigDecimal subtotal = flightClass.getBasePrice().multiply(BigDecimal.valueOf(passengerCount));
        BigDecimal serviceFee = SERVICE_FEE_PER_PERSON.multiply(BigDecimal.valueOf(passengerCount));
        BigDecimal totalPrice = subtotal.add(serviceFee);

        Booking booking = Booking.builder()
                .pnrCode(generatePNR())
                .user(User.builder().id(userId).build())
                .status(BookingStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusMinutes(15))
                .contactEmail(request.contactEmail())
                .contactPhone(request.contactPhone())
                .subtotal(subtotal)
                .serviceFee(serviceFee)
                .totalPrice(totalPrice)
                .build();
        booking = bookingRepository.save(booking);

        BookingSegment segment = BookingSegment.builder()
                .booking(booking)
                .flightClass(flightClass)
                .segmentType("OUTBOUND")
                .segmentPrice(subtotal)
                .build();
        segment = bookingSegmentRepository.save(segment);

        for (PassengerRequest pr : request.passengers()) {
            Seat seat = (pr.seatId() != null && !"INFANT".equals(pr.passengerType()))
                    ? seatRepository.findById(pr.seatId()).orElse(null)
                    : null;

            Passenger passenger = Passenger.builder()
                    .bookingSegment(segment)
                    .firstName(pr.firstName())
                    .lastName(pr.lastName())
                    .dateOfBirth(pr.dateOfBirth())
                    .gender(pr.gender())
                    .nationality(pr.nationality())
                    .idType(pr.idType())
                    .idNumber(pr.idNumber())
                    .idExpiry(pr.idExpiry())
                    .passengerType(pr.passengerType() != null ? pr.passengerType() : "ADULT")
                    .seat(seat)
                    .extraBaggageKg(pr.extraBaggageKg() != null ? pr.extraBaggageKg() : 0)
                    .mealPreference(pr.mealPreference())
                    .build();
            passengerRepository.save(passenger);
        }

        if (!seatIds.isEmpty()) {
            seatRepository.markAsHeld(seatIds);
        }

        flightClass.setAvailableSeats(flightClass.getAvailableSeats() - passengerCount);
        flightClassRepository.save(flightClass);

        return toBookingResponse(booking, flightClass, request);
    }

    @Transactional(readOnly = true)
    public BookingResponse getBooking(String pnrCode, Long userId) {
        Booking booking = bookingRepository.findByPnrCode(pnrCode)
                .orElseThrow(() -> new NotFoundException("Booking không tồn tại: " + pnrCode));
        if (!booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("Bạn không có quyền xem booking này");
        }
        FlightClass fc = booking.getSegments().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Booking không có segment"))
                .getFlightClass();
        return toBookingResponse(booking, fc, null);
    }

    @Transactional
    public void expireBooking(Booking booking) {
        Booking fullBooking = bookingRepository.findByIdWithSegmentsAndPassengers(booking.getId())
                        .orElseThrow(() -> new NotFoundException("Booking không tồn tại"));

        if (fullBooking.getStatus() != BookingStatus.PENDING) {
            log.info("Booking {} already {}, skip expire", fullBooking.getPnrCode(), booking.getStatus());
            return;
        }

        if (fullBooking.getExpiresAt() == null || fullBooking.getExpiresAt().isAfter(LocalDateTime.now())) {
            log.info("Booking {} not yet expires, skip", fullBooking.getPnrCode());
            return;
        }

        fullBooking.setStatus(BookingStatus.EXPIRED);
        bookingRepository.save(fullBooking);

        releaseSeatsForBooking(fullBooking);
        log.info("Booking {} expired and seats released", fullBooking.getPnrCode());
    }

    @Transactional
    public CancelBookingResponse cancelBooking(String pnrCode, Long userId) {
        Booking booking = bookingRepository.findByPnrCode(pnrCode)
                .orElseThrow(() -> new NotFoundException("Booking không tồn tại: " + pnrCode));

        if (!booking.getUser().getId().equals(userId)) {
            throw new ForbiddenException("Bạn không có quyền hủy booking này");
        }

        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new InvalidBookingException("Chỉ có thể hủy booking đã xác nhận");
        }

        BigDecimal refundAmount = calculateRefund(booking);

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(LocalDateTime.now());
        booking.setRefundAmount(refundAmount);
        bookingRepository.save(booking);

        releaseSeatsForBooking(booking);

        if (refundAmount.compareTo(BigDecimal.ZERO) > 0) {
            eventPublisher.publishEvent(new BookingRefundRequestedEvent(booking.getId(), refundAmount));
        }

        return new CancelBookingResponse(pnrCode, refundAmount, LocalDateTime.now());
    }

    private BigDecimal calculateRefund(Booking booking) {
        FlightClass flightClass = booking.getSegments().stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Booking Không có segment"))
                .getFlightClass();

        if (Boolean.FALSE.equals(flightClass.getIsRefundable())) {
            return BigDecimal.ZERO;
        }

        LocalDateTime departureTime = flightClass.getFlight().getDepartureTime();
        long hoursUntilDeparture = Duration.between(LocalDateTime.now(), departureTime).toHours();

        if (hoursUntilDeparture < 24) {
            return BigDecimal.ZERO;
        }

        int feePercent = flightClass.getRefundFeePercent() != null ? flightClass.getRefundFeePercent() : 0;
        BigDecimal refundRate = BigDecimal.ONE.subtract(
                BigDecimal.valueOf(feePercent).divide(BigDecimal.valueOf(100)));

        return booking.getTotalPrice().multiply(refundRate);
    }

    private void releaseSeatsForBooking(Booking booking) {
        List<Long> seatIds = booking.getSegments().stream()
                .flatMap(seg -> seg.getPassengers().stream())
                .filter(p -> p.getSeat() != null)
                .map(p -> p.getSeat().getId())
                .toList();

        log.info("Releasing seats: {}", seatIds);

        if (!seatIds.isEmpty()) {
            seatRepository.releaseSeats(seatIds);
        }

        booking.getSegments().forEach(seg -> {
            FlightClass fc = seg.getFlightClass();
            int nonInfants = (int) seg.getPassengers().stream()
                    .filter(p -> !"INFANT".equals(p.getPassengerType())).count();
            fc.setAvailableSeats(fc.getAvailableSeats() +  nonInfants);
            flightClassRepository.save(fc);
        });
    }

    private String generatePNR() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(chars.charAt(ThreadLocalRandom.current().nextInt(chars.length())));
            }
            String pnr = sb.toString();
            if (!bookingRepository.existsByPnrCode(pnr)) return pnr;
        }
        throw new PnrGenerationException("Không thể tạo PNR duy nhất sau 10 lần thử");
    }

    private BookingResponse toBookingResponse(Booking booking, FlightClass fc, CreateBookingRequest request) {
        Flight flight = fc.getFlight();
        List<BookingResponse.PassengerInfo> passengerInfos;

        if (request != null) {
            passengerInfos = request.passengers().stream().map(p -> new BookingResponse.PassengerInfo(
                    p.firstName() + " " + p.lastName(),
                    p.seatId() != null ? p.seatId().toString() : "N/A",
                    p.idNumber()
            )).toList();
        } else {
            passengerInfos = booking.getSegments().stream()
                    .flatMap(seg -> seg.getPassengers().stream())
                    .map(p -> new BookingResponse.PassengerInfo(
                            p.getFirstName() + " " + p.getLastName(),
                            p.getSeat() != null ? p.getSeat().getSeatNumber() : "N/A",
                            p.getIdNumber()
                    )).toList();
        }

        return new BookingResponse(
                booking.getPnrCode(),
                booking.getStatus().name(),
                booking.getExpiresAt(),
                new BookingResponse.FlightInfo(
                        flight.getFlightNumber(),
                        flight.getOrigin().getIataCode(),
                        flight.getDestination().getIataCode(),
                        flight.getDepartureTime()
                ),
                passengerInfos,
                new BookingResponse.PricingInfo(
                        booking.getSubtotal(), booking.getServiceFee(),
                        booking.getTotalPrice(), booking.getCurrency()
                ),
                booking.getExpiresAt()
        );
    }

    @Transactional
    public Page<BookingResponse> getAllBookings(String status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Page<Booking> bookings;
        if (status != null && !status.isBlank()) {
            BookingStatus bookingStatus = BookingStatus.valueOf(status.toUpperCase());
            bookings = bookingRepository.findByStatus(bookingStatus, pageable);
        } else {
            bookings = bookingRepository.findAll(pageable);
        }

        return bookings.map(booking -> {
            FlightClass fc = booking.getSegments().stream()
                    .findFirst()
                    .orElseThrow(() -> new NotFoundException("Booking không có segment"))
                    .getFlightClass();
            return toBookingResponse(booking, fc, null);
        });
    }

    @Transactional
    public void cancelBookingByAdmin(String pnrCode, String reason) {
        Booking booking = bookingRepository.findByPnrCode(pnrCode)
                .orElseThrow(() -> new NotFoundException("Booking không tồn tại"));

        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            booking.setRefundAmount(booking.getTotalPrice());
        }

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(LocalDateTime.now());
        booking.setCancelReason(reason);
        bookingRepository.save(booking);

        releaseSeatsForBooking(booking);
        eventPublisher.publishEvent(new BookingCancelledEvent(booking));
    }

    @Transactional
    public void cancelBookingsForCancelledFlight(Flight flight) {
        List<Booking> affected = bookingRepository.findConfirmedBookingsByFlightId(flight.getId());

        for (Booking booking : affected) {
            booking.setStatus(BookingStatus.CANCELLED);
            booking.setCancelledAt(LocalDateTime.now());
            booking.setCancelReason("Chuyến bay " + flight.getFlightNumber() + " đã bị hãng hủy");
            booking.setRefundAmount(booking.getTotalPrice());
            bookingRepository.save(booking);

            releaseSeatsForBooking(booking);
            eventPublisher.publishEvent(new BookingRefundRequestedEvent(booking.getId(), booking.getTotalPrice()));
        }

        log.info("Flight {} cancelled: auto-cancelled {} confirmed bookings", flight.getFlightNumber(), affected.size());

        // Publish 1 event riêng mang theo cả danh sách booking, để listener gửi email + (sau này) trigger refund thật
        eventPublisher.publishEvent(new FlightCancelledEvent(flight, affected));
    }
}
