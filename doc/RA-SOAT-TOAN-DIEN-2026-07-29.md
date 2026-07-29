# FlightEasy — Rà soát toàn diện Backend + Frontend

> Ngày rà soát: 2026-07-29
> Phạm vi: toàn bộ `src/main/java/com/flighteasy/**` (128 file) + toàn bộ `flighteasy-frontend/src/**` (33 file)
> Phương pháp: đối chiếu 2 báo cáo cũ (`BE_CODE_REVIEW.md`, `GAP_ANALYSIS_EMAIL_HUYCHUYEN_HOANTIEN.md`) với code hiện tại + rà soát mới toàn bộ controller/service/event/scheduler/security + toàn bộ frontend, cross-check API FE ↔ BE. Tất cả finding dưới đây đã được đọc trực tiếp từ source hiện tại (không suy đoán), kèm số dòng thực tế.

## Tóm tắt mức độ nghiêm trọng (chỉ tính phần MỚI/CHƯA fix)

| Mức | Số lượng | Ý nghĩa |
|---|---|---|
| 🔴 Critical | 7 | Tính năng lõi không hoạt động hoặc lỗ hổng bảo mật nghiêm trọng |
| 🟠 High | 6 | Tính năng bị hỏng một phần, ảnh hưởng trải nghiệm/tiền bạc |
| 🟡 Medium | 8 | Chức năng thiếu hoặc lỗi trong điều kiện cụ thể |
| 🟢 Low | 8 | Dead code, chất lượng, hiệu năng |

**Tin tốt:** hầu hết finding trong 2 báo cáo cũ (C-1..C-3, H-1..H-4, M-1, M-3..M-5, L-1, L-3 của `BE_CODE_REVIEW.md`; và cả 7 mục của `GAP_ANALYSIS...md`) **đã được fix** trong 2 commit gần nhất (`b20997f`, `5dbdc58`, 27/07/2026). Xem mục "Đã fix" ở cuối file. Báo cáo này tập trung vào phần **còn lại/mới phát hiện** — trong đó có 3 bug có thể khiến **đăng nhập, phân quyền admin, và trang "Vé của tôi" hỏng hoàn toàn**.

---

## PHẦN A — BACKEND: Bug nghiêm trọng CHƯA fix / MỚI phát hiện

### A1 · 🔴 CRITICAL — `RateLimitService` tự gọi đệ quy chính nó → **mọi request đăng nhập đều lỗi 500**

**File:** `src/main/java/com/flighteasy/service/RateLimitService.java:15-17`

```java
public Bucket resolveBucket(String ip) {
    return buckets.computeIfAbsent(ip, this::resolveBucket);   // BUG: map lại chính resolveBucket
}

private Bucket newBucket(String ip) { ... }   // không bao giờ được gọi
```

`AuthController.login()` gọi `rateLimitService.resolveBucket(ip)` cho **mọi** request login (dòng 36), trước cả khi xác thực. Vì mapping function của `computeIfAbsent` trỏ lại chính `resolveBucket` (thay vì `newBucket`), `ConcurrentHashMap` phát hiện đang cố ghi đè chính nó cho cùng 1 key trong lúc tính toán → ném `IllegalStateException: Recursive update`. `GlobalExceptionHandler` không có handler bắt exception này → lộ ra lỗi 500. **Kết quả: không ai đăng nhập được, kể cả admin, kể cả request đầu tiên (không cần vượt rate limit).**

**Sửa:**
```java
public Bucket resolveBucket(String ip) {
    return buckets.computeIfAbsent(ip, this::newBucket);
}
```

---

### A2 · 🔴 CRITICAL — `hasRole('ROLE_ADMIN')` bị double-prefix → **mọi endpoint admin trả 403 kể cả với admin thật**

**File:** `src/main/java/com/flighteasy/entity/User.java:38-56`

```java
@Column(name = "role")
private String role = "ROLE_USER";     // giá trị lưu DB đã có sẵn prefix "ROLE_"

@Override
public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority(role));   // authority thực tế = "ROLE_ADMIN"
}
```

**File:** `src/main/java/com/flighteasy/controller/AdminController.java:26`, `FlightController.java:39,45,51`
```java
@PreAuthorize("hasRole('ROLE_ADMIN')")
```

Đã xác nhận không có bean `GrantedAuthorityDefaults` nào override prefix mặc định (`security/SecurityConfig.java` — không có). Spring Security's `hasRole(x)` **tự động thêm tiền tố `ROLE_`** vào tham số, nên biểu thức trên thực chất kiểm tra authority `"ROLE_ROLE_ADMIN"` — không bao giờ khớp với authority thật là `"ROLE_ADMIN"`. **Toàn bộ `AdminController` (dashboard, bookings, airlines, flights, reports) và 3 endpoint admin trong `FlightController` (tạo sân bay, tạo chuyến bay, đổi status) trả về 403 Forbidden ngay cả khi đăng nhập đúng tài khoản admin.** Đây chính là nguyên nhân gốc khiến "admin panel không hoạt động" — không phải do frontend.

**Sửa (chọn 1 trong 2 cách, cách 1 gọn hơn và không ảnh hưởng chỗ khác):**

Cách 1 — đổi `hasRole` → `hasAuthority` ở cả 4 chỗ (khớp trực tiếp với authority đã prefix sẵn):
```java
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
```

Cách 2 — thêm 1 bean để tắt auto-prefix toàn cục (áp dụng nếu sau này có nhiều role khác cũng lưu kèm `ROLE_`):
```java
// SecurityConfig.java
@Bean
static GrantedAuthorityDefaults grantedAuthorityDefaults() {
    return new GrantedAuthorityDefaults(""); // không tự thêm "ROLE_"
}
```

---

### A3 · 🔴 CRITICAL — CORS `allowedOrigins` lỗi escape ký tự → **CORS thực chất không hoạt động**

**File:** `src/main/java/com/flighteasy/security/SecurityConfig.java:95`

```java
configuration.setAllowedOrigins(List.of("https://flighteasy.vn\", \"http://localhost:3000"));
```

Code compile được nhưng do escape sai vị trí, `List.of(...)` tạo ra **1 phần tử duy nhất** với giá trị theo nghĩa đen là `https://flighteasy.vn", "http://localhost:3000` (bao gồm cả dấu `"` và `, ` ở giữa) — không phải 2 origin riêng biệt. Spring so khớp Origin header theo kiểu exact-match nên **không bao giờ khớp** với origin thật trình duyệt gửi lên. Mọi request cross-origin từ frontend (dev server cổng 3000 hay domain production) đều bị **trình duyệt tự chặn** vì thiếu header `Access-Control-Allow-Origin` hợp lệ.

**Sửa:**
```java
configuration.setAllowedOrigins(List.of("https://flighteasy.vn", "http://localhost:3000"));
```

---

### A4 · 🔴 CRITICAL (IDOR) — `PaymentController` không kiểm tra booking có thuộc về user gọi API không

**File:** `src/main/java/com/flighteasy/controller/PaymentController.java:26-33,57-60`

```java
@PostMapping("/vnpay/create")
public ResponseEntity<CreatePaymentResponse> createPayment(@Valid @RequestBody CreatePaymentRequest request, HttpServletRequest httpRequest) {
    // không có @AuthenticationPrincipal User, không kiểm tra ownership
    ...
}

@GetMapping("/status/{pnr}")
public ResponseEntity<PaymentStatusResponse> getStatus(@PathVariable String pnr) {
    return ResponseEntity.ok(paymentService.getPaymentStatus(pnr)); // không kiểm tra ownership
}
```

Khác với `BookingController.getBooking()`/`cancelBooking()` (đã có check `booking.getUser().getId().equals(userId)`), 2 endpoint payment này chỉ cần JWT hợp lệ của **bất kỳ user nào** — không cần đúng chủ booking. PNR chỉ dài 6 ký tự (`ABCDEFGHJKLMNPQRSTUVWXYZ23456789`), có thể đoán được hoặc lộ qua URL/email. Kẻ tấn công đã đăng nhập có thể tạo link thanh toán hoặc xem trạng thái thanh toán của booking người khác.

**Sửa:**
```java
// PaymentController.java
@PostMapping("/vnpay/create")
public ResponseEntity<CreatePaymentResponse> createPayment(
        @Valid @RequestBody CreatePaymentRequest request,
        @AuthenticationPrincipal User user,
        HttpServletRequest httpRequest) {
    String clientIp = httpRequest.getHeader("X-Forwarded-For");
    if (clientIp == null) clientIp = httpRequest.getRemoteAddr();
    return ResponseEntity.ok(paymentService.createPaymentLink(request, clientIp, user.getId()));
}

@GetMapping("/status/{pnr}")
public ResponseEntity<PaymentStatusResponse> getStatus(@PathVariable String pnr, @AuthenticationPrincipal User user) {
    return ResponseEntity.ok(paymentService.getPaymentStatus(pnr, user.getId()));
}
```
```java
// PaymentService.java — thêm kiểm tra ownership ở đầu cả 2 method, giống BookingService:
Booking booking = bookingRepository.findByPnrCode(request.pnrCode())
        .orElseThrow(() -> new NotFoundException("Booking không tồn tại"));
if (!booking.getUser().getId().equals(userId)) {
    throw new ForbiddenException("Bạn không có quyền thao tác trên booking này");
}
```

---

### A5 · 🔴 CRITICAL — Endpoint `GET /api/v1/bookings/my` **không tồn tại trên backend** dù frontend gọi tới mỗi khi vào trang "Vé của tôi"

**File backend:** `src/main/java/com/flighteasy/controller/BookingController.java` (toàn bộ file — chỉ có 4 endpoint: `POST /bookings`, `GET /bookings/{pnr}`, `DELETE /bookings/{pnr}`, `GET /flights/{flightId}/seats`)

**File frontend:** `flighteasy-frontend/src/api/booking.api.ts:30`
```ts
getMyBookings: () => api.get<BookingResponse[]>("/v1/bookings/my"),
```

Trang **"Vé của tôi"** (`MyBookingsPage.tsx`) gọi endpoint này ngay khi mount — vì backend không có route khớp, request luôn trả `404`/`403` (bị `anyRequest().authenticated()` + không match route nào → Spring trả 403 do JwtAuthFilter set auth nhưng controller không tồn tại → thực chất là 404 Not Found từ Spring MVC). **Tính năng xem lịch sử đặt vé hoàn toàn không hoạt động**, dù giao diện đã làm xong 100%.

Điều thú vị: repository đã có sẵn query cho việc này nhưng **chưa từng được dùng ở đâu** — `BookingRepository.java:39`:
```java
List<Booking> findByUserIdOrderByCreatedAtDesc(Long userId);
```

**Sửa — thêm endpoint mới, tận dụng query có sẵn:**

```java
// BookingController.java — thêm method mới
@GetMapping("/bookings/my")
public ResponseEntity<java.util.List<BookingResponse>> getMyBookings(@AuthenticationPrincipal User user) {
    return ResponseEntity.ok(bookingService.getMyBookings(user.getId()));
}
```

```java
// BookingService.java — thêm method mới, đặt cạnh getBooking()
@Transactional(readOnly = true)
public java.util.List<BookingResponse> getMyBookings(Long userId) {
    return bookingRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .map(booking -> {
                FlightClass fc = booking.getSegments().stream()
                        .findFirst()
                        .orElseThrow(() -> new NotFoundException("Booking không có segment"))
                        .getFlightClass();
                return toBookingResponse(booking, fc, null);
            })
            .toList();
}
```

> ⚠️ Lưu ý thứ tự route: `@GetMapping("/bookings/my")` phải khai báo **trước** hoặc độc lập với `@GetMapping("/bookings/{pnr}")` — Spring MVC tự phân biệt được vì `my` không map vào path-variable khi có route tĩnh trùng khớp, nhưng nên đặt method `getMyBookings` lên trên `getBooking(pnr)` trong file cho rõ ràng, tránh nhầm lẫn khi đọc code.

---

### A6 · 🔴 CRITICAL — `ForbiddenException` không được `GlobalExceptionHandler` bắt → xem/hủy booking người khác trả về 500 thay vì 403

**File:** `src/main/java/com/flighteasy/exception/custom/ForbiddenException.java`
```java
public class ForbiddenException extends RuntimeException {   // không kế thừa AccessDeniedException
```

**File:** `src/main/java/com/flighteasy/exception/handler/GlobalExceptionHandler.java:110-114`
```java
@ExceptionHandler(AccessDeniedException.class)   // org.springframework.security.access.AccessDeniedException
public ResponseEntity<?> handleAccessDenied(AccessDeniedException ex) { ... }
```

`BookingService.getBooking()`/`cancelBooking()` ném `com.flighteasy.exception.custom.ForbiddenException` (không phải Spring's `AccessDeniedException`) khi user cố xem/hủy booking không thuộc về mình. Vì `ForbiddenException` không khớp handler nào, nó rơi xuống whitelabel error mặc định → client nhận **500 Internal Server Error** thay vì **403 Forbidden** — sai HTTP semantics, khó debug phía frontend (interceptor coi mọi lỗi non-401 là lỗi chung chung).

**Sửa:**
```java
// GlobalExceptionHandler.java — thêm handler mới
@ExceptionHandler(ForbiddenException.class)
public ResponseEntity<?> handleForbidden(ForbiddenException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("code", "FORBIDDEN", "message", ex.getMessage()));
}
```

---

### A7 · 🔴 CRITICAL (backlog, nên bổ sung ngay) — `GlobalExceptionHandler` không có handler bắt-tất-cả

**File:** `src/main/java/com/flighteasy/exception/handler/GlobalExceptionHandler.java` (toàn file, 15 handler cụ thể, không có fallback)

Bất kỳ `RuntimeException` nào không nằm trong 15 loại đã khai báo (ví dụ chính bug A1 ở trên) sẽ lộ ra whitelabel error page/500 mặc định của Spring Boot, không đúng format JSON chuẩn `{code, message}` mà toàn bộ API còn lại dùng. Nên có handler này **không phụ thuộc** vào việc đã sửa A1 hay chưa — đây là lưới an toàn chung.

**Sửa:**
```java
@ExceptionHandler(Exception.class)
public ResponseEntity<?> handleGeneric(Exception ex) {
    log.error("Unhandled exception", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("code", "INTERNAL_ERROR", "message", "Đã có lỗi xảy ra, vui lòng thử lại sau"));
}
```
(Cần thêm `@Slf4j` lên class và import `HttpStatus.INTERNAL_SERVER_ERROR` nếu chưa có.)

---

## PHẦN B — BACKEND: Bug mức cao (High)

### B1 · `VNPayService.buildIPNResponse()` trả sai case field JSON → VNPay có thể không nhận diện, tiếp tục gửi lại IPN

**File:** `src/main/java/com/flighteasy/service/VNPayService.java:173-179`
```java
private String buildIPNResponse(String rspCode, String message) {
    try {
        return objectMapper.writeValueAsString(Map.of("rspCode", rspCode, "message", message)); // sai case
    } catch (Exception e) {
        return "{\"RspCode\":\"99\",\"Message\":\"Internal error\"}"; // catch-block lại đúng case
    }
}
```
Chuẩn VNPay yêu cầu đúng field `RspCode`/`Message` (viết hoa chữ đầu — chính catch-block bên dưới đã dùng đúng, chứng tỏ đây là lỗi gõ khi refactor). Response thành công hiện trả `{"rspCode":"00","message":"..."}` — VNPay không parse được `RspCode` hợp lệ → coi là xử lý thất bại → **gửi lại IPN nhiều lần**, gây tải thừa dù nghiệp vụ đã chặn duplicate.

**Sửa:**
```java
return objectMapper.writeValueAsString(Map.of("RspCode", rspCode, "Message", message));
```

### B2 · `cancelBookingByAdmin()` set `refundAmount` hiển thị nhưng KHÔNG trigger hoàn tiền thật qua VNPay

**File:** `src/main/java/com/flighteasy/service/BookingService.java:313-329`
```java
@Transactional
public void cancelBookingByAdmin(String pnrCode, String reason) {
    ...
    if (booking.getStatus() == BookingStatus.CONFIRMED) {
        booking.setRefundAmount(booking.getTotalPrice());   // chỉ set con số hiển thị
    }
    ...
    releaseSeatsForBooking(booking);
    eventPublisher.publishEvent(new BookingCancelledEvent(booking));  // chỉ gửi email
    // THIẾU: eventPublisher.publishEvent(new BookingRefundRequestedEvent(...))
}
```
So sánh với `cancelBooking()` (user tự hủy, dòng 188-190) và `cancelBookingsForCancelledFlight()` (dòng 343) — cả 2 đều publish `BookingRefundRequestedEvent` để `BookingRefundListener` gọi `VNPayService.refundTransaction()` thật. Riêng nhánh admin-hủy **thiếu bước này**: khách nhận email + thấy `refundAmount` đúng số, nhưng **tiền không thực sự được hoàn**.

**Sửa:**
```java
@Transactional
public void cancelBookingByAdmin(String pnrCode, String reason) {
    Booking booking = bookingRepository.findByPnrCode(pnrCode)
            .orElseThrow(() -> new NotFoundException("Booking không tồn tại"));

    boolean wasConfirmed = booking.getStatus() == BookingStatus.CONFIRMED;
    if (wasConfirmed) {
        booking.setRefundAmount(booking.getTotalPrice());
    }

    booking.setStatus(BookingStatus.CANCELLED);
    booking.setCancelledAt(LocalDateTime.now());
    booking.setCancelReason(reason);
    bookingRepository.save(booking);

    releaseSeatsForBooking(booking);
    eventPublisher.publishEvent(new BookingCancelledEvent(booking));

    if (wasConfirmed) {
        eventPublisher.publishEvent(new BookingRefundRequestedEvent(booking.getId(), booking.getTotalPrice()));
    }
}
```

### B3 · `EmailService.retrySend()` mất kiểu `LocalDateTime` khi phục hồi context → vỡ `#temporals.format()` khi gửi lại

**File:** `src/main/java/com/flighteasy/service/EmailService.java:165-176`
```java
public void retrySend(EmailLog emailLog) {
    Context context = new Context();
    Map<String, Object> variables = objectMapper.readValue(
            emailLog.getContextJson() != null ? emailLog.getContextJson() : "{}",
            new TypeReference<Map<String, Object>>() {});   // mất type info, LocalDateTime → String
    variables.forEach(context::setVariable);
    ...
}
```
Ở lần gửi đầu, các biến `LocalDateTime` (`departureTime`, `newDeparture`...) được serialize thành chuỗi ISO khi lưu `contextJson`. Khi deserialize lại bằng `Map<String,Object>` (generic), Jackson trả về `String` thuần thay vì `LocalDateTime`. Các template `booking-confirmed.html`, `checkin-reminder.html`, `flight-cancelled.html`, `flight-delayed.html` đều gọi `#temporals.format(...)` trên biến này — nhận `String` sẽ ném `TemplateProcessingException`. **Bất kỳ email nào cần gửi lại (do SMTP lỗi tạm thời) và có field ngày giờ sẽ永 luôn thất bại**, kể cả khi mailserver đã hoạt động lại.

**Sửa (đơn giản, ít rủi ro nhất — lưu HTML đã render sẵn thay vì render lại lúc retry):**
```java
// entity/EmailLog.java — thêm cột mới
@Column(columnDefinition = "TEXT")
private String renderedHtml;
```
```java
// EmailService.java
public void sendEmail(String to, String subject, String templateName, Context context, String referenceId) {
    String contextJson;
    String renderedHtml;
    try {
        contextJson = objectMapper.writeValueAsString(context.getVariableNames().stream()
                .collect(Collectors.toMap(name -> name, context::getVariable)));
        renderedHtml = templateEngine.process(templateName, context);
    } catch (Exception e) {
        contextJson = "{}";
        renderedHtml = null;
    }

    EmailLog emailLog = EmailLog.builder()
            .recipient(to).subject(subject).templateName(templateName)
            .referenceId(referenceId).status("PENDING").attempts(0)
            .contextJson(contextJson).renderedHtml(renderedHtml)
            .build();
    emailLog = emailLogRepository.save(emailLog);

    doSendHtml(emailLog, renderedHtml);
}

public void retrySend(EmailLog emailLog) {
    if (emailLog.getRenderedHtml() != null) {
        doSendHtml(emailLog, emailLog.getRenderedHtml());
        return;
    }
    // fallback cho email cũ (trước khi có cột renderedHtml) — vẫn có thể lỗi nếu có field ngày giờ
    doSend(emailLog, new Context(), emailLog.getTemplateName());
}

private void doSendHtml(EmailLog emailLog, String htmlContent) {
    try {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
        helper.setFrom(fromEmail, "FlightEasy ✈️");
        helper.setTo(emailLog.getRecipient());
        helper.setSubject(emailLog.getSubject());
        helper.setText(htmlContent, true);
        mailSender.send(mimeMessage);

        emailLog.setStatus("SENT");
        emailLog.setSentAt(LocalDateTime.now());
        emailLog.setAttempts(emailLog.getAttempts() + 1);
    } catch (Exception ex) {
        emailLog.setAttempts(emailLog.getAttempts() + 1);
        emailLog.setLastError(ex.getMessage());
        emailLog.setStatus(emailLog.getAttempts() >= 3 ? "FAILED" : "PENDING");
        if (!"FAILED".equals(emailLog.getStatus())) {
            emailLog.setNextRetryAt(calculateNextRetry(emailLog.getAttempts()));
        }
    } finally {
        emailLogRepository.save(emailLog);
    }
}
```
(Cần thêm migration Flyway cho cột `rendered_html TEXT` trong bảng `email_log`.)

### B4 · `sendFlightDelayNotification()` viết xong nhưng **không bao giờ được gọi**

**File:** `src/main/java/com/flighteasy/service/FlightService.java:136-139`
```java
if (request.status() == FlightStatus.DELAYED && request.delayMinutes() != null){
    flight.setDelayMinutes(request.delayMinutes());
    flight.setDepartureTime(flight.getDepartureTime().plusMinutes(request.delayMinutes()));
    // THIẾU: publish event hoặc gọi emailService để báo khách hàng
}
```
Template `flight-delayed.html` đã tồn tại, method `EmailService.sendFlightDelayNotification(Flight, List<Booking>)` đã viết sẵn (`EmailService.java:87-103`) — nhưng đã grep xác nhận **0 nơi gọi**. Khi admin đổi status chuyến bay sang `DELAYED`, hành khách không hề được thông báo.

**Sửa:**
```java
// FlightService.java — cần inject BookingRepository và EmailService vào constructor (@RequiredArgsConstructor)
if (request.status() == FlightStatus.DELAYED && request.delayMinutes() != null){
    flight.setDelayMinutes(request.delayMinutes());
    flight.setDepartureTime(flight.getDepartureTime().plusMinutes(request.delayMinutes()));
    flightRepository.save(flight);

    List<Booking> affected = bookingRepository.findConfirmedBookingsByFlightId(flight.getId());
    emailService.sendFlightDelayNotification(flight, affected);
}
```

### B5 · `sendWelcomeEmail()` viết xong nhưng **không bao giờ được gọi**

**File:** `src/main/java/com/flighteasy/service/AuthService.java:47-60`
```java
public AuthResponse register(RegisterRequest req, HttpServletResponse response) {
    ...
    userRepository.save(user);
    // THIẾU: emailService.sendWelcomeEmail(...)
    return buildAuthResponse(user, null, null, response);
}
```
**Sửa:**
```java
userRepository.save(user);
emailService.sendWelcomeEmail(user.getEmail(), user.getFullName());
return buildAuthResponse(user, null, null, response);
```
(Cần inject `EmailService` vào `AuthService` nếu chưa có — kiểm tra lại, vì `forgotPassword()` đã inject rồi nên field có sẵn.)

### B6 · Link đặt lại mật khẩu trong email bị gõ sai route + hard-code domain

**File:** `src/main/java/com/flighteasy/service/EmailService.java:128`
```java
context.setVariable("resetLink", "http://localhost:3000/reser-password?token=" + resetToken);
```
`reser-password` sai chính tả của `reset-password`. Kể cả khi frontend có route đúng (`/reset-password` — xem phần H1), mọi email quên mật khẩu vẫn dẫn tới URL 404. Domain hard-code `localhost:3000` cũng sẽ sai khi lên production.

**Sửa:**
```java
// EmailService.java — thêm field cấu hình
@Value("${app.frontend-url:http://localhost:3000}")
private String frontendUrl;
...
context.setVariable("resetLink", frontendUrl + "/reset-password?token=" + resetToken);
```
```yaml
# application.yml
app:
  frontend-url: ${FRONTEND_URL:http://localhost:3000}
```

---

## PHẦN C — BACKEND: Medium / Low còn sót

| # | File:Dòng | Vấn đề | Sửa |
|---|---|---|---|
| C1 🟡 | `FlightSearchService.java:115` | `returnFuture` của tìm kiếm khứ hồi vẫn `CompletableFuture.supplyAsync(() -> search(returnRequest))` — quên truyền `searchExecutor` (chỉ nhánh `outboundFuture` được sửa) | Thêm `, searchExecutor` vào cuối lời gọi, giống `outboundFuture` |
| C2 🟡 | `AuthController.java:119` | `handleRateLimit()` trong `GlobalExceptionHandler` trả `Map.of("RATE_LIMIT_EXCEEDED", ex.getMessage())` — key/value bị đảo, không đúng format `{code, message}` như 14 handler còn lại → frontend không đọc được `message` (interceptor rơi vào message mặc định "Đã có lỗi xảy ra") | `Map.of("code", "RATE_LIMIT_EXCEEDED", "message", ex.getMessage())` |
| C3 🟢 | `config/ThymeleafEmailConfig.java:26` | `resolver.setCacheable(false)` hard-code mọi môi trường — `spring.thymeleaf.cache: true` trong `application.yml` không ảnh hưởng resolver custom này | Đọc qua `@Value("${spring.thymeleaf.cache:false}")` |
| C4 🟢 | `config/RestTemplateConfig.java:13` | Chỉ set `setConnectionRequestTimeout`, thiếu `setConnectTimeout` — không giới hạn thời gian TCP handshake tới VNPay | Thêm `factory.setConnectTimeout(5000);` |
| C5 🟢 | `service/FlightService.java:42` | Field `eventPublisher` được inject nhưng chưa từng dùng trong class (0 call site ngoài khai báo) | Xóa nếu không dùng, hoặc dùng cho B4 ở trên nếu chọn publish event thay vì gọi thẳng EmailService |
| C6 🟢 | `repository/EmailLogRepository.java:15` | `existsByReferenceIdAndTemplateName(...)` định nghĩa nhưng 0 nơi gọi | Xóa, hoặc dùng để chống gửi trùng email (tương tự cách `sendCheckinReminder` đã tự giới hạn 5 lần bằng `countByReferenceIdAndTemplateName`) |
| C7 🟢 | `service/DashboardService.java:12` | `import org.apache.commons.io.output.BrokenWriter;` — import thừa không dùng | Xóa dòng import |
| C8 🟢 | `entity/Payment.java` + `event/BookingRefundListener.java:35` | `refundStatus` chỉ từng được set giá trị `"FAILED"`, không bao giờ set `"SUCCESS"` khi refund thành công, và không có DTO/API nào cho admin tra cứu refund lỗi | Set `payment.setRefundStatus("SUCCESS")` trong nhánh thành công của `VNPayService.refundTransaction()`; cân nhắc thêm `GET /api/v1/admin/payments/failed-refunds` sau |

---

## PHẦN D — FRONTEND: Bug nghiêm trọng CHƯA fix

### D1 · 🔴 CRITICAL — `axios.ts` dùng `baseURL` tương đối, thiếu dấu `/` đầu → **request sai URL trên gần như mọi route nhiều-đoạn**

**File:** `flighteasy-frontend/src/lib/axios.ts:5`
```ts
const api = axios.create({
    baseURL: "api/",   // KHÔNG có "/" ở đầu
```

Vì không bắt đầu bằng `/`, mọi URL axios build (vd `api/v1/bookings/ABC123`) được trình duyệt coi là **đường dẫn tương đối theo route hiện tại**, không phải theo gốc site. Ví dụ minh họa (đã kiểm chứng bằng `URL` resolution):
- Ở `/booking/confirm/ABC123` → resolve thành `http://host/booking/confirm/api/v1/bookings/ABC123` (sai, phải là `http://host/api/...`)
- Ở `/admin/bookings`, `/admin/airports`, `/admin/airlines`, `/admin/flights` → thành `http://host/admin/api/v1/admin/...`
- Ở `/search/results` → thành `http://host/search/api/v1/flights/search`

Chỉ "vô tình" đúng khi user đang ở route 1-segment (`/`, `/login`, `/booking`...). **Đây là bug nền tảng nhất** — kết hợp với các bug D5/A5, gần như toàn bộ luồng sau khi rời trang chủ đều gọi sai API.

**Sửa:**
```ts
const api = axios.create({
    baseURL: "/api/",
    withCredentials: true,
    headers: { "Content-Type": "application/json" },
});
```
(Tốt hơn nữa: đọc từ biến môi trường `import.meta.env.VITE_API_BASE_URL` để dev/prod khác nhau dễ dàng, nhưng chỉ cần thêm `/` ở đầu là đã sửa được bug hiện tại.)

---

### D2 · 🟠 HIGH — 4 lỗi gõ nhầm path API → 404 chắc chắn (độc lập với bug D1)

| File:Dòng | Code hiện tại | Sửa | Ảnh hưởng |
|---|---|---|---|
| `api/booking.api.ts:28` | `` api.get(`/v1/nookings/${pnr}`) `` | `` api.get(`/v1/bookings/${pnr}`) `` | Trang xác nhận đặt vé (`BookingConfirmPage`) và nút "Xem chi tiết" từ My Bookings luôn trả về trang trắng |
| `api/payment.api.ts:22` | `` api.get(`/v1/paymnets/status/${pnr}`) `` | `` api.get(`/v1/payments/status/${pnr}`) `` | Trang kết quả thanh toán không bao giờ đọc được trạng thái thật, luôn rơi vào nhánh thất bại dù thanh toán VNPay thành công |
| `api/admin.api.ts:42` | `api.post("/v1/admin/sirlines", data)` | `api.post("/v1/admin/airlines", data)` | Tạo hãng bay trong admin panel luôn lỗi |
| `api/auth.api.ts:19,22` | `api.post("v1/auth/forgot-password", ...)` / `"v1/auth/reset-password"` | thêm `/` ở đầu: `"/v1/auth/forgot-password"` / `"/v1/auth/reset-password"` | Cùng lớp bug với D1 — thiếu `/` đầu khiến resolve sai route nếu gọi từ trang không phải `/` |

---

### D3 · 🟠 HIGH — `returnUrl` gửi cho VNPay bị gõ sai route → sau khi thanh toán thật, user luôn rơi vào trang trắng

**File:** `flighteasy-frontend/src/pages/booking/BookingConfirmPage.tsx:36`
```ts
const returnUrl = `${window.location.origin}/payment/reesult`;
```
`reesult` sai chính tả của `result`. App chỉ có route `/payment/result` (`App.tsx:65`), không có route `/payment/reesult` và cũng **không có 404/wildcard route** (xem D6) → sau khi thanh toán VNPay thật, user luôn thấy trang trắng thay vì `PaymentResultPage`.

**Sửa:**
```ts
const returnUrl = `${window.location.origin}/payment/result`;
```

---

## PHẦN E — FRONTEND: Chức năng thiếu hoàn toàn

### E1 · Trang Quên mật khẩu / Đặt lại mật khẩu — không tồn tại

Backend đã có đầy đủ (`POST /api/v1/auth/forgot-password`, `POST /api/v1/auth/reset-password`), `authApi.forgotPassword`/`resetPassword` đã stub sẵn, `LoginPage.tsx:89` có link trỏ tới `/forgot-password` — nhưng **không có component `ForgotPasswordPage`/`ResetPasswordPage` nào, không có route nào trong `App.tsx`**. Click vào link ra trang trắng.

**Code đề xuất — tạo mới `flighteasy-frontend/src/pages/auth/ForgotPasswordPage.tsx`:**
```tsx
import {useState} from "react";
import {Link} from "react-router-dom";
import {useForm} from "react-hook-form";
import {z} from "zod";
import {zodResolver} from "@hookform/resolvers/zod";
import toast from "react-hot-toast";
import {Plane} from "lucide-react";
import {authApi} from "@/api/auth.api.ts";

const schema = z.object({
    email: z.string().email("Email không hợp lệ"),
});
type FormData = z.infer<typeof schema>;

export default function ForgotPasswordPage() {
    const [sent, setSent] = useState(false);
    const [loading, setLoading] = useState(false);
    const {register, handleSubmit, formState: {errors}} = useForm<FormData>({resolver: zodResolver(schema)});

    const onSubmit = async (data: FormData) => {
        setLoading(true);
        try {
            await authApi.forgotPassword(data.email);
            setSent(true);
        } catch {
            toast.error("Có lỗi xảy ra, vui lòng thử lại");
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-blue-50 to-indigo-100 px-4">
            <div className="bg-white rounded-2xl shadow-xl w-full max-w-md p-8">
                <div className="text-center mb-8">
                    <div className="inline-flex items-center gap-2 text-blue-700">
                        <Plane className="w-8 h-8" />
                        <span className="text-2xl font-bold">FlightEasy</span>
                    </div>
                    <p className="mt-2 text-gray-500 text-sm">Quên mật khẩu</p>
                </div>

                {sent ? (
                    <div className="text-center text-sm text-gray-600">
                        <p>Nếu email tồn tại trong hệ thống, chúng tôi đã gửi hướng dẫn đặt lại mật khẩu.</p>
                        <p className="mt-2">Vui lòng kiểm tra hộp thư (kể cả mục spam).</p>
                    </div>
                ) : (
                    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
                        <div>
                            <label className="block text-sm font-medium text-gray-700 mb-1">Email</label>
                            <input
                                {...register("email")}
                                type="email"
                                placeholder="you@example.com"
                                className="w-full px-4 py-2.5 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500"
                            />
                            {errors.email && <p className="text-red-500 text-xs mt-1">{errors.email.message}</p>}
                        </div>
                        <button
                            type="submit"
                            disabled={loading}
                            className="w-full bg-blue-700 text-white py-2.5 rounded-lg font-medium hover:bg-blue-800 disabled:opacity-60"
                        >
                            {loading ? "Đang gửi..." : "Gửi hướng dẫn"}
                        </button>
                    </form>
                )}

                <p className="text-center text-sm text-gray-500 mt-6">
                    <Link to="/login" className="text-blue-600 hover:underline font-medium">Quay lại đăng nhập</Link>
                </p>
            </div>
        </div>
    );
}
```

**Code đề xuất — tạo mới `flighteasy-frontend/src/pages/auth/ResetPasswordPage.tsx`:**
```tsx
import {useState} from "react";
import {useNavigate, useSearchParams, Link} from "react-router-dom";
import {useForm} from "react-hook-form";
import {z} from "zod";
import {zodResolver} from "@hookform/resolvers/zod";
import toast from "react-hot-toast";
import {Plane} from "lucide-react";
import axios from "axios";
import {authApi} from "@/api/auth.api.ts";

const schema = z.object({
    newPassword: z.string().min(8, "Mật khẩu tối thiểu 8 ký tự"),
    confirmPassword: z.string(),
}).refine((d) => d.newPassword === d.confirmPassword, {
    message: "Mật khẩu xác nhận không khớp",
    path: ["confirmPassword"],
});
type FormData = z.infer<typeof schema>;

export default function ResetPasswordPage() {
    const navigate = useNavigate();
    const [searchParams] = useSearchParams();
    const token = searchParams.get("token") ?? "";
    const [loading, setLoading] = useState(false);
    const {register, handleSubmit, formState: {errors}} = useForm<FormData>({resolver: zodResolver(schema)});

    const onSubmit = async (data: FormData) => {
        if (!token) {
            toast.error("Liên kết không hợp lệ hoặc đã hết hạn");
            return;
        }
        setLoading(true);
        try {
            await authApi.resetPassword(token, data.newPassword);
            toast.success("Đặt lại mật khẩu thành công, vui lòng đăng nhập lại");
            navigate("/login");
        } catch (error) {
            if (axios.isAxiosError(error)) {
                toast.error(error.response?.data?.message || "Đặt lại mật khẩu thất bại");
            }
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-blue-50 to-indigo-100 px-4">
            <div className="bg-white rounded-2xl shadow-xl w-full max-w-md p-8">
                <div className="text-center mb-8">
                    <div className="inline-flex items-center gap-2 text-blue-700">
                        <Plane className="w-8 h-8" />
                        <span className="text-2xl font-bold">FlightEasy</span>
                    </div>
                    <p className="mt-2 text-gray-500 text-sm">Đặt lại mật khẩu</p>
                </div>

                <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-1">Mật khẩu mới</label>
                        <input {...register("newPassword")} type="password" placeholder="••••••••"
                               className="w-full px-4 py-2.5 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500" />
                        {errors.newPassword && <p className="text-red-500 text-xs mt-1">{errors.newPassword.message}</p>}
                    </div>
                    <div>
                        <label className="block text-sm font-medium text-gray-700 mb-1">Xác nhận mật khẩu</label>
                        <input {...register("confirmPassword")} type="password" placeholder="••••••••"
                               className="w-full px-4 py-2.5 border border-gray-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500" />
                        {errors.confirmPassword && <p className="text-red-500 text-xs mt-1">{errors.confirmPassword.message}</p>}
                    </div>
                    <button type="submit" disabled={loading}
                            className="w-full bg-blue-700 text-white py-2.5 rounded-lg font-medium hover:bg-blue-800 disabled:opacity-60">
                        {loading ? "Đang xử lý..." : "Đặt lại mật khẩu"}
                    </button>
                </form>

                <p className="text-center text-sm text-gray-500 mt-6">
                    <Link to="/login" className="text-blue-600 hover:underline font-medium">Quay lại đăng nhập</Link>
                </p>
            </div>
        </div>
    );
}
```

**Đăng ký route trong `App.tsx`:**
```tsx
import ForgotPasswordPage from "@/pages/auth/ForgotPasswordPage.tsx";
import ResetPasswordPage from "@/pages/auth/ResetPasswordPage.tsx";
// ...
<Route path="/forgot-password" element={<ForgotPasswordPage />} />
<Route path="/reset-password" element={<ResetPasswordPage />} />
```

---

### E2 · Không có trang 404 / wildcard route

**File:** `flighteasy-frontend/src/App.tsx` — không có `<Route path="*" .../>`.

**Sửa — thêm component + route:**
```tsx
// src/pages/NotFoundPage.tsx
import {Link} from "react-router-dom";
import {Plane} from "lucide-react";

export default function NotFoundPage() {
    return (
        <div className="min-h-screen flex flex-col items-center justify-center text-center px-4">
            <Plane className="w-16 h-16 text-gray-300 mb-4" />
            <h1 className="text-3xl font-bold text-gray-900">404 — Không tìm thấy trang</h1>
            <p className="text-gray-500 mt-2">Trang bạn tìm không tồn tại hoặc đã bị di chuyển.</p>
            <Link to="/" className="mt-6 text-blue-600 hover:underline font-medium">Về trang chủ</Link>
        </div>
    );
}
```
```tsx
// App.tsx — thêm route cuối cùng trong <Routes>
<Route path="*" element={<NotFoundPage />} />
```

### E3 · Nút "Thanh toán ngay" ở My Bookings điều hướng tới route không tồn tại

**File:** `flighteasy-frontend/src/pages/booking/MyBookingsPage.tsx:117-121`
```tsx
onClick={() => navigate(`/payment?pnr=${booking.pnrCode}&amount=${booking.pricing.totalPrice}`)}
```
Không có route `/payment` (chỉ có `/payment/result`), không trang nào đọc `pnr`/`amount` từ query string. Luồng thanh toán thật nằm ở `BookingConfirmPage.handlePay()`.

**Sửa — điều hướng về trang confirm đã có sẵn logic thanh toán:**
```tsx
onClick={() => navigate(`/booking/confirm/${booking.pnrCode}`)}
```

### E4 · Không có UI chọn ghế dù backend + type + API đã sẵn sàng

Backend có `GET /api/v1/flights/{flightId}/seats` (`SeatMapService`, hoạt động), type `SeatMapResponse`/`SeatRow`/`SeatInfo` đã định nghĩa (`booking.types.ts:25-41`), nhưng **không component nào render seat map**. `BookingPage.tsx` luôn gửi `selectedSeatIds: []` — hệ thống chấp nhận (backend không bắt buộc chọn ghế) nên booking vẫn tạo được, nhưng user không có quyền chọn ghế mình muốn.

**Code đề xuất — tạo mới `flighteasy-frontend/src/components/booking/SeatMap.tsx`:**
```tsx
import {useQuery} from "@tanstack/react-query";
import {flightApi} from "@/api/flight.api.ts";
import type {SeatInfo, SeatRow} from "@/types/booking.types.ts";
import clsx from "clsx";

interface SeatMapProps {
    flightId: number;
    classType: "FIRST" | "BUSINESS" | "ECONOMY";
    requiredSeats: number;
    selectedSeatNumbers: string[];
    onToggleSeat: (seat: SeatInfo) => void;
}

const classKeyMap: Record<SeatMapProps["classType"], "firstClass" | "business" | "economy"> = {
    FIRST: "firstClass",
    BUSINESS: "business",
    ECONOMY: "economy",
};

export default function SeatMap({flightId, classType, requiredSeats, selectedSeatNumbers, onToggleSeat}: SeatMapProps) {
    const {data, isLoading} = useQuery({
        queryKey: ["seat-map", flightId],
        queryFn: () => flightApi.getSeatMap(flightId),
    });

    if (isLoading) {
        return <div className="text-sm text-gray-400 py-4">Đang tải sơ đồ ghế...</div>;
    }

    const rows: SeatRow[] = data?.data?.[classKeyMap[classType]] ?? [];
    if (rows.length === 0) {
        return <div className="text-sm text-gray-400 py-4">Hạng vé này không hỗ trợ chọn ghế trước.</div>;
    }

    return (
        <div className="space-y-3">
            <p className="text-xs text-gray-500">
                Đã chọn {selectedSeatNumbers.length}/{requiredSeats} ghế
            </p>
            <div className="space-y-1.5">
                {rows.map((row) => (
                    <div key={row.rowNumber} className="flex items-center gap-2">
                        <span className="w-6 text-xs text-gray-400">{row.rowNumber}</span>
                        <div className="flex gap-1.5 flex-wrap">
                            {row.seats.map((seat) => {
                                const selected = selectedSeatNumbers.includes(seat.seatNumber);
                                const disabled = !seat.isAvailable || (!selected && selectedSeatNumbers.length >= requiredSeats);
                                return (
                                    <button
                                        key={seat.seatNumber}
                                        type="button"
                                        disabled={disabled}
                                        onClick={() => onToggleSeat(seat)}
                                        title={seat.isExtraLegroom ? "Ghế rộng chân (phụ thu)" : seat.seatNumber}
                                        className={clsx(
                                            "w-9 h-9 rounded-md text-xs font-medium border transition-colors",
                                            !seat.isAvailable && "bg-gray-100 text-gray-300 border-gray-100 cursor-not-allowed",
                                            seat.isAvailable && selected && "bg-blue-600 text-white border-blue-600",
                                            seat.isAvailable && !selected && "bg-white text-gray-700 border-gray-300 hover:border-blue-400",
                                            disabled && !selected && "opacity-40 cursor-not-allowed"
                                        )}
                                    >
                                        {seat.seatNumber}
                                    </button>
                                );
                            })}
                        </div>
                    </div>
                ))}
            </div>
        </div>
    );
}
```

**Gợi ý wiring vào `BookingPage.tsx`:** thêm state `selectedSeats: SeatInfo[]`, render `<SeatMap .../>` trong form, khi submit map `selectedSeats.map(s => s.seatNumber)` sang `seatId` tương ứng cho từng hành khách (cần API trả `seatId` kèm `seatNumber` trong `SeatInfo` — hiện `SeatInfo` chỉ có `seatNumber`, không có `id`; cần bổ sung field `id: number` vào DTO `SeatInfo` backend (`dto/SeatInfo.java`) và type frontend nếu muốn gán ghế theo ID thay vì chỉ hiển thị).

### E5 · Tìm kiếm khứ hồi (round-trip) chỉ là UI stub — `returnDate` không bao giờ được dùng

**File:** `flighteasy-frontend/src/pages/flight/SearchResultsPage.tsx:121-125` — luôn gọi `flightApi.searchFlights` (một chiều), không bao giờ gọi `flightApi.searchRoundTrip` dù `SearchPage.tsx` đã thu thập `returnDate` và append vào query string.

**Sửa tối thiểu (đọc `returnDate` và rẽ nhánh gọi đúng API):**
```tsx
// SearchResultsPage.tsx
const [searchParams] = useSearchParams();
const returnDate = searchParams.get("returnDate");

const {data, isLoading} = useQuery({
    queryKey: ["flight-search", searchParams.toString()],
    queryFn: () =>
        returnDate
            ? flightApi.searchRoundTrip({ /* map các param hiện có + returnDate */ ...params, returnDate })
            : flightApi.searchFlights(params),
});
```
Việc hiển thị đầy đủ UI 2 chặng (chọn chuyến đi + chuyến về + gộp giá) là một khối UI riêng, cần thiết kế thêm — phạm vi báo cáo này chỉ chỉ ra điểm gãy hiện tại (`returnDate` bị thu thập nhưng vứt bỏ) để không gây hiểu nhầm "khứ hồi đã hoạt động".

### E6 · Admin Dashboard: biểu đồ doanh thu & top tuyến bay có API nhưng chưa từng render

**File:** `flighteasy-frontend/src/api/admin.api.ts:7-11` (`getRevenueChart`, `getTopRoutes` — cả 2 đã định nghĩa, 0 nơi gọi). `package.json` hiện chưa có thư viện chart nào.

**Code đề xuất — thêm vào cuối `DashboardPage.tsx` (không cần cài thêm package, dùng CSS bar chart đơn giản):**
```tsx
// Thêm 2 query mới trong DashboardPage()
const {data: revenueData} = useQuery({
    queryKey: ["admin-revenue-chart"],
    queryFn: () => adminApi.getRevenueChart("MONTHLY"),
});
const {data: topRoutesData} = useQuery({
    queryKey: ["admin-top-routes"],
    queryFn: () => adminApi.getTopRoutes(5),
});

const revenuePoints = (revenueData?.data as {points: {date: string; revenue: number; bookings: number}[]} | undefined)?.points ?? [];
const maxRevenue = Math.max(1, ...revenuePoints.map((p) => p.revenue));
const topRoutes = (topRoutesData?.data as {origin: string; destination: string; airline: string; totalBookings: number; totalRevenue: number}[] | undefined) ?? [];
```
```tsx
{/* Thêm sau khối "Booking Status" trong JSX return */}
<div className="grid grid-cols-2 gap-4 mt-6">
    <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-5">
        <h2 className="font-semibold text-gray-800 mb-4">Doanh thu theo ngày (30 ngày qua)</h2>
        <div className="flex items-end gap-1 h-40">
            {revenuePoints.map((p) => (
                <div key={p.date} className="flex-1 flex flex-col items-center justify-end group relative">
                    <div
                        className="w-full bg-blue-500 rounded-t hover:bg-blue-600 transition-colors"
                        style={{height: `${(p.revenue / maxRevenue) * 100}%`, minHeight: 2}}
                        title={`${p.date}: ${formatCurrency(p.revenue)} (${p.bookings} booking)`}
                    />
                </div>
            ))}
            {revenuePoints.length === 0 && <p className="text-sm text-gray-400 m-auto">Chưa có dữ liệu</p>}
        </div>
    </div>

    <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-5">
        <h2 className="font-semibold text-gray-800 mb-4">Top tuyến bay</h2>
        <table className="w-full text-sm">
            <thead>
                <tr className="text-left text-gray-400 text-xs">
                    <th className="pb-2">Tuyến</th>
                    <th className="pb-2">Hãng</th>
                    <th className="pb-2 text-right">Booking</th>
                    <th className="pb-2 text-right">Doanh thu</th>
                </tr>
            </thead>
            <tbody>
                {topRoutes.map((r, i) => (
                    <tr key={i} className="border-t border-gray-50">
                        <td className="py-2 font-medium">{r.origin} → {r.destination}</td>
                        <td className="py-2 text-gray-500">{r.airline}</td>
                        <td className="py-2 text-right">{r.totalBookings}</td>
                        <td className="py-2 text-right">{formatCurrency(r.totalRevenue)}</td>
                    </tr>
                ))}
                {topRoutes.length === 0 && (
                    <tr><td colSpan={4} className="py-4 text-center text-gray-400">Chưa có dữ liệu</td></tr>
                )}
            </tbody>
        </table>
    </div>
</div>
```
(Nếu muốn biểu đồ đẹp/tương tác hơn về sau, cân nhắc cài `recharts` — nhưng bản CSS thuần này đủ dùng ngay không cần thêm dependency.)

---

## PHẦN F — FRONTEND: Bug mức trung bình khác

| # | File:Dòng | Vấn đề | Sửa |
|---|---|---|---|
| F1 | `pages/auth/LoginPage.tsx:38-43` + `lib/axios.ts:75-77` | Double toast: cả `LoginPage` và interceptor `axios.ts` đều `toast.error(...)` cho lỗi non-401 (vd tài khoản bị khóa 423, rate-limit 429) | Bỏ `toast.error` trong `LoginPage`'s catch, chỉ giữ 1 nguồn (interceptor) — hoặc ngược lại, không làm cả 2 |
| F2 | `lib/axios.ts:63` | `localStorage.removeItem("user")` — key này không tồn tại (zustand `persist` lưu ở key `"auth-storage"`, xem `authStore.ts:28`) — dòng chết, và state đăng nhập cũ (`auth-storage`) không được xóa khi refresh-token thất bại → UI có thể hiển thị sai trạng thái "đã đăng nhập" sau khi bị văng ra | Xóa dòng `removeItem("user")` chết, thay bằng gọi `useAuthStore.getState().logout()` để xóa đúng state |
| F3 | `pages/booking/BookingPage.tsx:34-48` | `useForm` không destructure `formState: {errors}`, dù nhiều field có `{required: true}` — lỗi validate không hiển thị gì cho user | Thêm `formState: {errors}` vào destructure, render `{errors.fieldName && <p>...</p>}` dưới từng input, theo đúng pattern đã dùng ở `LoginPage`/`RegisterPage` |
| F4 | `pages/auth/RegisterPage.tsx:47-49` | `catch { }` rỗng, không xử lý riêng `EMAIL_ALREADY_EXISTS` (409) — vẫn hiển thị được nhờ toast chung của interceptor nhưng không có lỗi field-level trên ô email | Bắt lỗi và set `setError("email", {message: "Email đã được đăng ký"})` khi `error.response?.data?.code === "EMAIL_ALREADY_EXISTS"` |
| F5 | `api/booking.api.ts:35-36` và `api/flight.api.ts` | `getSeatMap` bị định nghĩa **trùng lặp** ở cả 2 file, cùng gọi 1 endpoint, không nơi nào dùng cả 2 | Xóa 1 bản (giữ ở `flight.api.ts` vì logic hơn — ghế thuộc về flight, không phải booking), sửa `SeatMap.tsx` (E4) dùng bản còn lại |

---

## PHẦN G — Dead code tổng hợp (đã grep xác nhận 0 call site ngoài khai báo)

| File:Dòng | Đối tượng chết | Ghi chú |
|---|---|---|
| `service/RateLimitService.java:19-26` | `private Bucket newBucket(String ip)` | Sẽ **hết chết** ngay khi fix A1 (đây chính là hàm nên được gọi) |
| `service/FlightService.java:42` | field `eventPublisher` | Xem C5 |
| `repository/EmailLogRepository.java:15` | `existsByReferenceIdAndTemplateName(...)` | Xem C6 |
| `repository/BookingRepository.java:39` | `findByUserIdOrderByCreatedAtDesc(Long)` | Sẽ hết chết khi fix A5 |
| `service/EmailService.java:87-103` | `sendFlightDelayNotification(...)` | Sẽ hết chết khi fix B4 |
| `service/EmailService.java:135-140` | `sendWelcomeEmail(...)` | Sẽ hết chết khi fix B5 |
| `api/admin.api.ts:7-11` | `getRevenueChart`, `getTopRoutes` | Sẽ hết chết khi thêm E6 |
| `api/flight.api.ts` | `getFlightById`, `getSeatMap` | `getFlightById`: chưa có trang chi tiết chuyến bay nào dùng — cân nhắc thêm trang `/flights/:id` hoặc xóa nếu không cần; `getSeatMap`: hết chết khi thêm E4 |
| `api/booking.api.ts:35-36` | `getSeatMap` (bản trùng) | Xem F5 — nên xóa hẳn |
| `api/flight.api.ts` | `searchRoundTrip` | Sẽ hết chết khi fix E5 |
| `api/auth.api.ts:18-22` | `forgotPassword`, `resetPassword` | Sẽ hết chết khi thêm E1 |

---

## Bảng tổng hợp ưu tiên sửa (toàn bộ finding MỚI trong báo cáo này)

| # | Khu vực | File | Vấn đề | Mức |
|---|---|---|---|---|
| A1 | BE | `RateLimitService.java` | Đăng nhập luôn lỗi 500 | 🔴 Sửa ngay |
| A2 | BE | `SecurityConfig.java`/`FlightController.java`/`AdminController.java` | Admin API luôn 403 do double-prefix role | 🔴 Sửa ngay |
| A3 | BE | `SecurityConfig.java:95` | CORS vô hiệu do lỗi escape | 🔴 Sửa ngay |
| A4 | BE | `PaymentController.java`/`PaymentService.java` | IDOR — không kiểm tra chủ booking | 🔴 Sửa ngay |
| A5 | BE | `BookingController.java`, thiếu endpoint | "Vé của tôi" hoàn toàn không hoạt động | 🔴 Sửa ngay |
| D1 | FE | `lib/axios.ts:5` | `baseURL` thiếu `/` → sai URL trên đa số route | 🔴 Sửa ngay |
| A6 | BE | `GlobalExceptionHandler.java` | `ForbiddenException` → 500 thay vì 403 | 🔴 Sửa ngay |
| A7 | BE | `GlobalExceptionHandler.java` | Thiếu catch-all handler | 🟠 Sprint này |
| B1 | BE | `VNPayService.java:175` | IPN response sai case field | 🟠 Sprint này |
| B2 | BE | `BookingService.java:313-329` | Admin cancel không refund thật | 🟠 Sprint này |
| B3 | BE | `EmailService.java:165-176` | `retrySend` vỡ với field ngày giờ | 🟠 Sprint này |
| D2 | FE | `booking.api.ts`/`payment.api.ts`/`admin.api.ts`/`auth.api.ts` | 4 lỗi gõ path → 404 | 🟠 Sprint này |
| D3 | FE | `BookingConfirmPage.tsx:36` | `returnUrl` sai route sau thanh toán | 🟠 Sprint này |
| B4 | BE | `FlightService.java`/`EmailService.java` | Không báo khách khi chuyến bay trễ | 🟡 Sprint sau |
| B5 | BE | `AuthService.java` | Không gửi email chào mừng | 🟡 Sprint sau |
| B6 | BE | `EmailService.java:128` | Link reset password sai route | 🟡 Sprint sau |
| E1 | FE | (file mới) | Thiếu trang Quên/Đặt lại mật khẩu | 🟡 Sprint sau |
| E3 | FE | `MyBookingsPage.tsx:119` | Nút "Thanh toán ngay" trỏ route chết | 🟡 Sprint sau |
| E4 | FE | (file mới) | Thiếu UI chọn ghế | 🟡 Sprint sau |
| E5 | FE | `SearchResultsPage.tsx` | Khứ hồi là stub, `returnDate` bị bỏ | 🟡 Sprint sau |
| E6 | FE | `DashboardPage.tsx` | Biểu đồ doanh thu/top tuyến chưa render | 🟡 Sprint sau |
| C1 | BE | `FlightSearchService.java:115` | `returnFuture` vẫn dùng ForkJoinPool chung | 🟢 Khi rảnh |
| C2 | BE | `GlobalExceptionHandler.java:119` | `handleRateLimit` sai format key/value | 🟢 Khi rảnh |
| E2 | FE | `App.tsx` | Thiếu trang 404 | 🟢 Khi rảnh |
| F1-F5 | FE | nhiều file | Double toast, dead localStorage key, thiếu validate error, dead code trùng lặp | 🟢 Khi rảnh |
| C3-C8 | BE | nhiều file | Cache Thymeleaf, timeout RestTemplate, dead code rải rác | 🟢 Khi rảnh |

---

## Đã fix (tham khảo, không cần làm lại)

Từ `BE_CODE_REVIEW.md` (28/06/2026): **C-1, C-2, C-3, H-1, H-2, H-3 (một phần — xem B2), H-4, M-1, M-3, M-4, M-5, L-1, L-3** đã fix đúng trong commit `5dbdc58`/`b20997f`. **M-2, L-2** fix nửa vời (xem C1, C3 ở trên). **L-4 (CORS), L-5 (rate limit)** — code "trông như đã thêm" nhưng có bug khiến **không hoạt động thật** (xem A1, A3).

Từ `GAP_ANALYSIS_EMAIL_HUYCHUYEN_HOANTIEN.md` (06/07/2026): cả 7 mục (listener trùng, thiếu template, thiếu refund thật, `calculateRefund` bỏ qua `isRefundable`, `retrySend` mất context, nghi vấn bean Thymeleaf, listener no-op) đều đã được xử lý ở mức kiến trúc đúng — trừ mục "retrySend mất context" chỉ fix **một phần** (đã lưu snapshot nhưng lưu sai kiểu dữ liệu, xem B3) và refund-thật chưa áp dụng cho nhánh admin-hủy (xem B2).