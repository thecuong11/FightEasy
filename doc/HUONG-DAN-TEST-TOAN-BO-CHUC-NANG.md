# FlightEasy — Hướng dẫn test toàn bộ chức năng (Backend + Frontend)

> Ngày viết: 2026-07-29. Bổ sung cho `doc/huong-dan-postman-test.md` (đã hơi cũ, dùng path `/api/auth/...` thay vì `/api/v1/auth/...`) — file này dùng **đúng path v1 hiện tại** và bao phủ **toàn bộ** chức năng, gồm cả UI frontend, scheduler, email, webhook VNPay.
>
> ⚠️ **Đọc trước:** file `doc/RA-SOAT-TOAN-DIEN-2026-07-29.md` liệt kê các bug đang chặn một số luồng test bên dưới (đăng nhập luôn lỗi 500, admin API luôn 403, "Vé của tôi" không có endpoint...). Ở mỗi mục bị ảnh hưởng, hướng dẫn này ghi chú rõ **"⛔ Đang bị chặn bởi bug #X — cần fix trước khi test được"** kèm tham chiếu. Nếu bạn chưa áp dụng các fix đó, hãy fix theo file kia trước, rồi quay lại test theo checklist này.

---

## 0. Chuẩn bị môi trường

### 0.1 Khởi động hạ tầng (Postgres + Redis)
```bash
cd D:\work\flighteasy
docker-compose up -d
```
Kiểm tra 2 container đang chạy:
```bash
docker ps --filter "name=postgresql" --filter "name=redis-server"
```

### 0.2 Biến môi trường backend
Backend đọc các biến sau (không có default, phải set trước khi chạy — xem `application.yml`):

| Biến | Ví dụ giá trị dev |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/flighteasy` |
| `DB_USERNAME` | `postgres` |
| `DB_PASSWORD` | `admin` |
| `REDIS_HOST` | `localhost` |
| `REDIS_PORT` | `6379` |
| `JWT_SECRET` | chuỗi bất kỳ ≥ 32 ký tự, vd `dev-secret-key-thay-doi-khi-len-prod-1234` |
| `VNPAY_TMN_CODE` | mã merchant sandbox VNPay (đăng ký tại sandbox.vnpayment.vn) |
| `VNPAY_HASH_SECRET` | hash secret sandbox VNPay |

Mail server dùng SMTP giả tại `localhost:1025` (không auth, không TLS) — dùng [MailHog](https://github.com/mailhog/MailHog) hoặc [Mailpit](https://github.com/axllent/mailpit) để xem email gửi ra mà không cần mail thật:
```bash
docker run -d --name mailpit -p 1025:1025 -p 8025:8025 axllent/mailpit
```
Xem email đã gửi tại `http://localhost:8025`.

### 0.3 Chạy backend
```bash
cd D:\work\flighteasy
./mvnw spring-boot:run
```
Backend chạy tại `http://localhost:8080`. Flyway tự chạy migration khi khởi động (kiểm tra log không có lỗi `Migration checksum mismatch`).

### 0.4 Seed dữ liệu mẫu (nếu DB trống)
```sql
INSERT INTO airlines (iata_code, name, is_active) VALUES
    ('VN', 'Vietnam Airlines', true), ('VJ', 'VietJet Air', true);

INSERT INTO airports (iata_code, name, city, country, country_code, timezone, is_active) VALUES
    ('SGN', 'Tân Sơn Nhất', 'Hồ Chí Minh', 'Việt Nam', 'VN', 'Asia/Ho_Chi_Minh', true),
    ('HAN', 'Nội Bài', 'Hà Nội', 'Việt Nam', 'VN', 'Asia/Ho_Chi_Minh', true);

INSERT INTO aircraft_types (code, name, total_seats) VALUES ('A321', 'Airbus A321', 220);
```
Tạo tài khoản admin thủ công (chưa có endpoint promote-to-admin):
```sql
-- Sau khi đã /register 1 tài khoản qua API, update role:
UPDATE users SET role = 'ROLE_ADMIN' WHERE email = 'admin@flighteasy.vn';
```

### 0.5 Chạy frontend
```bash
cd D:\work\flighteasy\flighteasy-frontend
npm install
npm run dev
```
Frontend chạy tại `http://localhost:3000`, proxy `/api/**` → `http://localhost:8080` (xem `vite.config.ts`).

> ⛔ **Lưu ý quan trọng:** với bug D1 (`baseURL: "api/"` thiếu `/`) chưa fix, phần lớn API call từ trang không phải `/` sẽ gọi sai URL và **không đi qua proxy Vite đúng cách**. Khuyến nghị fix D1 (đổi thành `"/api/"`) **trước khi** test bất kỳ luồng UI nào ngoài trang chủ, nếu không kết quả test sẽ gây nhiễu (lỗi do bug đã biết, không phải bug mới).

### 0.6 Công cụ test API
Dùng Postman/Insomnia/curl tuỳ thích. Ví dụ dưới đây dùng `curl`. Đặt biến:
```bash
export BASE=http://localhost:8080/api/v1
```
(PowerShell: `$BASE = "http://localhost:8080/api/v1"`)

---

## 1. MODULE Auth — Đăng ký / Đăng nhập / Token

### 1.1 Đăng ký user
```bash
curl -X POST $BASE/auth/register -H "Content-Type: application/json" -d '{
  "fullName": "Nguyen Van Test",
  "email": "user1@test.com",
  "password": "Test@123456"
}'
```
**Kỳ vọng:** `201 Created`, body có `accessToken`, cookie `refresh_token` được set (kiểm tra header `Set-Cookie`, path phải là `/api/v1/auth`).

**Edge case cần test:**
- Đăng ký lại đúng email → `409 Conflict`, `{"code":"EMAIL_ALREADY_EXISTS", ...}`.
- Password rỗng/thiếu field → `400`, `{"code":"VALIDATION", "errors": {...}}`.
- ✅ Sau khi fix B5 (đã đề xuất trong file rà soát): kiểm tra Mailpit (`localhost:8025`) có nhận được email "Chào mừng đến FlightEasy!" không.

### 1.2 Đăng nhập
```bash
curl -X POST $BASE/auth/login -H "Content-Type: application/json" -d '{
  "email": "user1@test.com",
  "password": "Test@123456"
}' -v
```
> ⛔ **Đang bị chặn bởi bug A1** (`RateLimitService` đệ quy) — request này hiện trả `500 Internal Server Error` cho MỌI lần gọi. Áp dụng fix A1 trong `RA-SOAT-TOAN-DIEN-2026-07-29.md` trước, sau đó test lại như dưới.

**Sau khi fix A1, kỳ vọng:** `200 OK`, `accessToken` trong body, cookie `refresh_token` (HttpOnly, Secure, SameSite=Strict, path `/api/v1/auth`).

**Edge case:**
- Sai mật khẩu 5 lần liên tiếp → lần thứ 6 phải trả `423 Locked`, `{"code":"ACCOUNT_LOCKED", ...}` (kiểm tra `UserAttemptService`).
- Gọi login > 5 lần/phút từ cùng 1 IP → `429 Too Many Requests` (Bucket4j — `capacity(5)` refill 5/phút, xem `RateLimitService.java`). **Lưu ý:** hiện tại response body của lỗi này bị sai format do bug C2 (`{"RATE_LIMIT_EXCEEDED": "..."}` thay vì `{"code": "...", "message": "..."}`) — verify cả 2 trường hợp trước/sau khi fix C2.
- Email không tồn tại → `401`, `{"code":"INVALID_CREDENTIALS"}` (không được tiết lộ "email không tồn tại" khác với "sai mật khẩu" — kiểm tra message chung chung, tránh user enumeration).

### 1.3 Refresh token
```bash
curl -X POST $BASE/auth/refresh -b "refresh_token=<token_từ_bước_1.2>" -v
```
**Kỳ vọng:** `200 OK`, `accessToken` **mới** (khác với token cũ — đây chính là bug C-1 cũ, đã fix, verify lại cho chắc). Refresh token cũ trong DB phải có `is_used = true` sau khi gọi (kiểm tra bảng `refresh_tokens`).

**Edge case — token reuse detection:**
1. Login → lấy `refresh_token` A.
2. Gọi `/refresh` với A → nhận `refresh_token` B (A bị đánh dấu used).
3. Gọi lại `/refresh` với A (token đã dùng) lần nữa → kỳ vọng **tất cả session của user bị revoke** (`TokenReuseException`, `401`), kể cả token B vừa cấp — verify bằng cách thử dùng B ngay sau đó, phải bị từ chối.

### 1.4 Logout / Logout-all
```bash
curl -X POST $BASE/auth/logout -b "refresh_token=<token>" -H "Authorization: Bearer <accessToken>"
curl -X POST $BASE/auth/logout-all -H "Authorization: Bearer <accessToken>"
```
**Kỳ vọng logout:** cookie `refresh_token` bị xoá (`Set-Cookie` với `Max-Age=0`), access token cũ bị đưa vào blacklist Redis — verify bằng cách gọi lại 1 API cần auth với access token đó, phải bị `401`.
**Kỳ vọng logout-all:** tất cả refresh token của user trong DB chuyển `is_used = true`/bị revoke.

### 1.5 Quên mật khẩu / Đặt lại mật khẩu
```bash
curl -X POST $BASE/auth/forgot-password -H "Content-Type: application/json" -d '{"email":"user1@test.com"}'
```
**Kỳ vọng:** luôn trả `200` với message chung chung dù email có tồn tại hay không (tránh user enumeration) — verify cả 2 trường hợp email tồn tại/không tồn tại đều trả cùng response.
Kiểm tra Mailpit nhận được email "Đặt lại mật khẩu FlightEasy" (nếu email tồn tại), lấy `token` từ link trong email (sau khi fix B6, link đúng dạng `/reset-password?token=...`).
```bash
curl -X POST $BASE/auth/reset-password -H "Content-Type: application/json" -d '{
  "token": "<token_lấy_từ_email>",
  "newPassword": "NewPass@123"
}'
```
**Kỳ vọng:** `200`, sau đó login bằng mật khẩu mới phải thành công, login bằng mật khẩu cũ phải thất bại.
**Edge case:** token hết hạn (>1h) hoặc dùng lại token đã dùng 1 lần → `401`, `{"code":"INVALID_TOKEN"}` hoặc tương tự (xem `TokenExpiredException`).

**Test UI (frontend):**
- ⛔ Trước khi thêm E1 (trang Forgot/Reset Password) trong file rà soát, click "Quên mật khẩu?" ở `/login` ra trang trắng — đây là hành vi lỗi cần fix trước, không phải test pass/fail thông thường.
- Sau khi thêm E1: điền email ở `/forgot-password` → thấy thông báo "đã gửi hướng dẫn" → mở link trong Mailpit → điền mật khẩu mới ở `/reset-password?token=...` → redirect về `/login` → đăng nhập bằng mật khẩu mới thành công.

---

## 2. MODULE Sân bay & Chuyến bay (Flight Management)

### 2.1 Xem danh sách sân bay (public)
```bash
curl $BASE/airports
curl $BASE/airports/SGN
```

### 2.2 Admin tạo sân bay / chuyến bay
> ⛔ **Đang bị chặn bởi bug A2** (`hasRole('ROLE_ADMIN')` double-prefix) — mọi request dưới đây hiện trả `403 Forbidden` dù dùng đúng token admin. Fix A2 trước khi test.

```bash
# Lấy admin token trước (đăng nhập tài khoản đã UPDATE role = ROLE_ADMIN)
curl -X POST $BASE/admin/airports -H "Authorization: Bearer <admin_token>" -H "Content-Type: application/json" -d '{
  "iataCode": "DAD", "name": "Đà Nẵng", "city": "Đà Nẵng",
  "country": "Việt Nam", "countryCode": "VN", "timezone": "Asia/Ho_Chi_Minh"
}'

curl -X POST $BASE/admin/flights -H "Authorization: Bearer <admin_token>" -H "Content-Type: application/json" -d '{
  "flightNumber": "VN123",
  "airlineId": 1,
  "originIata": "SGN",
  "destinationIata": "HAN",
  "departureTime": "2026-08-15T08:00:00",
  "arrivalTime": "2026-08-15T10:10:00",
  "durationMinutes": 130,
  "flightClasses": [
    {"classType": "ECONOMY", "basePrice": 1500000, "totalSeats": 180, "baggageAllowanceKg": 20, "isRefundable": true},
    {"classType": "BUSINESS", "basePrice": 4500000, "totalSeats": 20, "baggageAllowanceKg": 30, "isRefundable": true}
  ]
}'
```
**Kỳ vọng sau khi fix A2:** `201 Created`. Test với token **không phải admin** (role `ROLE_USER`) → phải nhận `403`.

### 2.3 Đổi trạng thái chuyến bay
```bash
curl -X PATCH $BASE/admin/flights/1/status -H "Authorization: Bearer <admin_token>" -H "Content-Type: application/json" -d '{"status": "BOARDING"}'
```
**Test toàn bộ state machine hợp lệ/không hợp lệ** (xem `FlightService.java` transition map):
| Từ | Sang hợp lệ | Sang KHÔNG hợp lệ (phải trả `400 INVALID_LIGHT`) |
|---|---|---|
| SCHEDULED | BOARDING, DELAYED, CANCELLED | DEPARTED, ARRIVED |
| DELAYED | BOARDING, CANCELLED | ARRIVED |
| BOARDING | DEPARTED | SCHEDULED, CANCELLED |
| DEPARTED | ARRIVED | bất kỳ trạng thái khác |
| ARRIVED | (không có) | mọi chuyển đổi |
| CANCELLED | (không có) | mọi chuyển đổi |

**Test riêng cho DELAYED:**
```bash
curl -X PATCH $BASE/admin/flights/1/status -H "Authorization: Bearer <admin_token>" -H "Content-Type: application/json" -d '{"status": "DELAYED", "delayMinutes": 90}'
```
Verify `departureTime` cộng thêm đúng 90 phút. Sau khi fix B4: verify Mailpit nhận được email "Thông báo chuyến bay trễ" cho **mọi booking CONFIRMED** trên chuyến bay đó.

**Test riêng cho CANCELLED (quan trọng nhất — luồng nghiệp vụ phức tạp):**
1. Tạo booking CONFIRMED trên 1 chuyến bay (xem Module 4+5 để có booking CONFIRMED thật, cần thanh toán qua VNPay sandbox).
2. Đổi status chuyến bay đó sang `CANCELLED`.
3. Verify: booking tự động chuyển `CANCELLED`, `cancelReason` = "Chuyến bay ... đã bị hãng hủy", `refundAmount` = 100% `totalPrice` (không phụ thuộc `isRefundable` — hủy do hãng luôn hoàn 100%).
4. Verify ghế đã đặt được giải phóng (`GET /api/v1/flights/{id}/seats` — ghế đó phải `isAvailable: true` trở lại).
5. Verify email "Chuyến bay ... đã bị hủy" (template `flight-cancelled.html`) được gửi tới khách.
6. Verify `Payment.refundStatus` chuyển sang giá trị hợp lệ sau khi `VNPayService.refundTransaction()` chạy (nghe qua `BookingRefundListener`) — kiểm tra log hoặc query trực tiếp DB bảng `payments`.

---

## 3. MODULE Tìm kiếm chuyến bay

### 3.1 Tìm kiếm một chiều
```bash
curl "$BASE/flights/search?from=SGN&to=HAN&departDate=2026-08-15&classType=ECONOMY&adults=1&children=0&infants=0&page=0&size=10"
```
**Test các filter:** `minPrice`, `maxPrice`, `airlines` (nhiều mã, phân tách dấu phẩy), `maxDuration`, `departTimeRange` (enum, xem `TimeRange.java`), `sortBy` (`PRICE_ASC`, `PRICE_DESC`, `DURATION`, ...).

**Test cache Redis:**
1. Gọi search lần 1 → đo thời gian phản hồi (chậm hơn, query DB thật).
2. Gọi lại **y hệt** tham số → phải nhanh hơn đáng kể (cache hit) — verify bằng cách xem log SQL (nếu bật `show-sql`) không chạy lại query.
3. Gọi với `page=1` (khác `page=0`, giữ nguyên filter khác) → **phải trả dữ liệu trang 2 thật**, không phải lặp lại trang 1 (đây là bug M-1 cũ, đã fix — verify lại).

### 3.2 Tìm kiếm khứ hồi
```bash
curl "$BASE/flights/search/round-trip?from=SGN&to=HAN&departDate=2026-08-15&returnDate=2026-08-20&classType=ECONOMY&adults=1"
```
**Kỳ vọng:** trả về cả `outbound` và `return` trong `RoundTripSearchResponse`.
> ⛔ Frontend hiện KHÔNG gọi endpoint này (bug E5) — test bằng curl/Postman trực tiếp để verify backend đúng, UI cần fix E5 trước khi test qua trình duyệt được.

**Edge case:** `departDate` sau `returnDate` → `400`, `{"code":"INVALID_SEARCH"}`.

---

## 4. MODULE Đặt vé & Chọn ghế

### 4.1 Xem sơ đồ ghế
```bash
curl $BASE/flights/1/seats
```
**Kỳ vọng:** JSON có 3 nhóm `firstClass`/`business`/`economy`, mỗi ghế có `seatNumber`, `isAvailable`, `extraFee`, `isExtraLegroom`. Đây là endpoint **public** (không cần token — nằm trong whitelist `/api/v1/flights/**`), verify gọi không kèm `Authorization` header vẫn `200`.

**Test UI:** ⛔ chưa có UI chọn ghế (bug E4) — test qua API trực tiếp cho tới khi thêm component `SeatMap`.

### 4.2 Tạo booking
```bash
curl -X POST $BASE/bookings -H "Authorization: Bearer <user_token>" -H "Content-Type: application/json" -d '{
  "flightClassId": 1,
  "contactEmail": "user1@test.com",
  "contactPhone": "0901234567",
  "passengers": [
    {"firstName": "Van", "lastName": "Nguyen", "dateOfBirth": "1995-01-01", "gender": "MALE",
     "nationality": "VN", "idType": "CCCD", "idNumber": "079095001234", "passengerType": "ADULT"}
  ],
  "selectedSeatIds": []
}'
```
**Kỳ vọng:** `201`, trả `pnrCode` (6 ký tự), `status: PENDING`, `expiresAt` = now + 15 phút. `availableSeats` của flightClass giảm đúng số hành khách không phải INFANT.

**Edge case cần test:**
- Đặt vượt số ghế còn trống → `409`, `{"code":"NOT_ENOUNGH_SEAT"}` (kiểm tra đúng chính tả field trả về trong code thật).
- Trùng số CCCD/passport với 1 hành khách đã có booking khác **trên cùng chuyến bay** → `409`, `{"code":"DUPLICATE_PASSENGER"}`.
- Chọn ghế đã bị người khác giữ (concurrent test — mở 2 request song song cùng `seatId`) → 1 request thành công, request kia `409 SEAT_UNAVAILABLE`. Đây là test **race condition** quan trọng nhất của hệ thống (pessimistic locking `findAllByIdWithLock`) — nên test bằng cách bắn 2 request gần như đồng thời (`curl` song song hoặc script k6/JMeter), không test tuần tự vì sẽ không phát hiện race condition.

### 4.3 Xem chi tiết booking
```bash
curl $BASE/bookings/ABC123 -H "Authorization: Bearer <user_token>"
```
**Edge case IDOR:** đăng nhập user B, gọi API với PNR của user A → phải `403 FORBIDDEN` (sau fix A6; hiện tại trả `500` do `ForbiddenException` không được handler bắt — verify đúng behavior mong muốn sau khi fix).

### 4.4 Xem "Vé của tôi"
> ⛔ **Đang bị chặn bởi bug A5** — endpoint `GET /api/v1/bookings/my` chưa tồn tại. Thêm endpoint theo file rà soát trước khi test được mục này.

Sau khi thêm:
```bash
curl $BASE/bookings/my -H "Authorization: Bearer <user_token>"
```
**Kỳ vọng:** mảng booking của đúng user đang đăng nhập, sắp xếp mới nhất trước (`ORDER BY created_at DESC`).

**Test UI:** vào `/bookings` sau khi đăng nhập → thấy danh sách đúng booking của mình, không thấy booking user khác.

### 4.5 Hủy booking (user tự hủy)
```bash
curl -X DELETE $BASE/bookings/ABC123 -H "Authorization: Bearer <user_token>"
```
**Test bảng chính sách hoàn tiền** (theo `calculateRefund()` — phụ thuộc `isRefundable`, `refundFeePercent` của hạng vé và thời gian còn lại tới giờ bay):

| `isRefundable` | Giờ bay còn lại | `refundFeePercent` | `refundAmount` kỳ vọng |
|---|---|---|---|
| `false` | bất kỳ | bất kỳ | `0` |
| `true` | < 24h | bất kỳ | `0` |
| `true` | ≥ 24h | `0` | 100% `totalPrice` |
| `true` | ≥ 24h | `20` | 80% `totalPrice` |

Verify sau khi hủy: ghế được giải phóng, email "Thông báo hủy vé" gửi tới `contactEmail`, và nếu `refundAmount > 0` verify `BookingRefundRequestedEvent` được publish (kiểm tra log `BookingRefundListener` gọi `VNPayService.refundTransaction()`).

**Edge case:** hủy booking đang `PENDING` (chưa thanh toán) → `409`, `{"code":"BOOKING_STATUS_INVALID"}` ("Chỉ có thể hủy booking đã xác nhận"). Hủy booking không phải của mình → `403`.

---

## 5. MODULE Thanh toán VNPay

> Dùng [VNPay Sandbox](https://sandbox.vnpayment.vn) — thẻ test NCB: `9704198526191432198`, tên `NGUYEN VAN A`, ngày phát hành `07/15`, OTP `123456` (giá trị mẫu chuẩn của VNPay, kiểm tra lại tài liệu sandbox mới nhất nếu có thay đổi).

### 5.1 Tạo link thanh toán
```bash
curl -X POST $BASE/payments/vnpay/create -H "Authorization: Bearer <user_token>" -H "Content-Type: application/json" -d '{
  "pnrCode": "ABC123",
  "returnUrl": "http://localhost:3000/payment/result"
}'
```
> ⛔ **Đang bị chặn bởi bug A4 (IDOR)** — hiện tại endpoint này không kiểm tra booking thuộc về user gọi API. Test cả 2 trường hợp: (1) đúng chủ booking → phải luôn hoạt động bình thường trước/sau fix; (2) **không phải chủ booking** → trước khi fix sẽ **sai** (vẫn tạo được link thanh toán cho booking người khác — đây chính là lỗ hổng cần verify đã bị chặn sau khi fix, phải trả `403`).

**Kỳ vọng:** trả `paymentUrl` (dẫn tới `sandbox.vnpayment.vn`), `txnRef`, `amount`, `expiresAt`. Mở `paymentUrl` trên trình duyệt → điền thông tin thẻ test → hoàn tất thanh toán trên trang VNPay.

### 5.2 IPN callback (VNPay gọi ngầm, test giả lập)
VNPay gọi `GET /api/v1/payments/vnpay/ipn?...` ngay sau khi thanh toán xong (server-to-server, không qua trình duyệt user). Để test mà không cần chờ VNPay gọi thật, có thể tự build URL với đúng chữ ký (xem `VNPayService.hmacSha512`) hoặc đơn giản hơn: **thực hiện thanh toán thật trên sandbox rồi quan sát log backend** — log phải hiện `VNPay IPN received: txnRef=...`.

**Kỳ vọng sau khi IPN nhận `responseCode=00`:**
- `payments.status` = `SUCCESS`.
- `bookings.status` chuyển từ `PENDING` → `CONFIRMED`.
- Email "Xác nhận đặt vé" gửi tới `contactEmail` (kiểm tra Mailpit).
- Response trả về VNPay đúng format `{"RspCode": "00", "Message": "Confirm success"}` — **verify đúng case chữ hoa `RspCode`/`Message`** (bug B1, hiện tại trả sai case `rspCode`/`message`).

**Test duplicate IPN (VNPay có thể gọi lại):**
Gọi lại y hệt IPN request lần 2 → booking đã `CONFIRMED` → verify **không** gửi email lần 2, **không** cộng thêm gì, response `{"RspCode": "01", "Message": "Order already confirmed"}` (idempotent — không phải bug C-3 cũ nữa, đã fix, verify lại cho chắc).

**Test amount mismatch:** giả lập IPN với `vnp_Amount` khác với `amountVnpay` đã lưu → `{"RspCode": "04", "Message": "Invalid amount"}`, booking KHÔNG được confirm.

**Test sai chữ ký:** đổi 1 ký tự trong `vnp_SecureHash` → `{"RspCode": "97", "Message": "Invalid signature"}`.

### 5.3 Trang return (user quay lại từ VNPay)
Sau khi thanh toán trên sandbox, VNPay redirect trình duyệt về `returnUrl` kèm query params (`vnp_ResponseCode`, `vnp_TxnRef`,...).
> ⛔ Với bug D3 chưa fix, `returnUrl` được gửi đi là `/payment/reesult` (sai chính tả) — route này không tồn tại trong `App.tsx`, user sẽ thấy trang trắng thay vì `PaymentResultPage`. Fix D3 trước khi test luồng UI đầy đủ.

Sau khi fix D3 + D2 (payment status API) + D1 (baseURL):
1. Trang `/payment/result` hiển thị trạng thái loading trong lúc gọi `GET /api/v1/payments/status/{pnr}`.
2. Nếu `status === "SUCCESS"` → hiển thị UI thành công, có nút xem chi tiết booking / về trang chủ.
3. Nếu thất bại (`vnp_ResponseCode !== "00"`) → hiển thị UI thất bại, gợi ý thử lại thanh toán.

### 5.4 Đối chiếu thanh toán treo (Reconciliation Scheduler)
`PaymentReconciliationScheduler` chạy định kỳ, tìm các `Payment` ở trạng thái `PENDING` quá 60 phút (đã fix từ 3 ngày) và tự động query trạng thái thật qua `VNPayService.queryTransactionStatus()`.

**Test:** tạo 1 payment, không hoàn tất thanh toán, chờ scheduler chạy (hoặc trigger thủ công qua debugger/test riêng) → verify payment được đối chiếu và cập nhật đúng trạng thái cuối cùng dựa trên phản hồi thật từ VNPay.

---

## 6. MODULE Email & Scheduler

### 6.1 Danh sách toàn bộ loại email cần verify qua Mailpit

| Trigger | Template | Khi nào test được |
|---|---|---|
| Đăng ký thành công | `welcome.html` | Sau khi fix B5 |
| Đặt vé thành công (IPN confirm) | `booking-confirmed.html` | Module 5.2 |
| Hủy vé (user tự hủy) | `booking-cancelled.html` | Module 4.5 |
| Admin hủy booking | `booking-cancelled.html` | `PATCH /api/v1/admin/bookings/{pnr}/cancel` |
| Chuyến bay bị hủy | `flight-cancelled.html` | Module 2.3 (test CANCELLED) |
| Chuyến bay bị trễ | `flight-delayed.html` | Sau khi fix B4, Module 2.3 (test DELAYED) |
| Quên mật khẩu | `password-reset.html` | Module 1.5 |
| Nhắc check-in | `checkin-reminder.html` | Xem 6.2 |

**Với mỗi email:** verify subject đúng, nội dung không có biến hiển thị `null`/rỗng/lỗi Thymeleaf, các số tiền format đúng `X đ`, ngày giờ format đúng `dd/MM/yyyy HH:mm`.

### 6.2 CheckinReminderScheduler
Chạy định kỳ tìm booking `CONFIRMED` có chuyến bay khởi hành trong khoảng thời gian quy định (xem `BookingRepository.findConfirmedBookingsForCheckin`), gửi email nhắc check-in, tối đa 5 lần/booking (`EmailService.sendCheckinReminder` tự chặn qua `countByReferenceIdAndTemplateName`).

**Test:** tạo booking CONFIRMED với `departureTime` rơi vào khung giờ scheduler quét (kiểm tra `@Scheduled(cron=...)` trong file để biết khung giờ chính xác), verify email gửi đúng 1 lần, chạy lại scheduler thêm 5 lần nữa → email không vượt quá 5 lần tổng.

### 6.3 BookingExpiryScheduler
Booking `PENDING` quá 15 phút không thanh toán → tự động chuyển `EXPIRED`, giải phóng ghế.

**Test:** tạo booking, không thanh toán, đợi > 15 phút (hoặc set `expiresAt` về quá khứ trực tiếp trong DB để test nhanh) → verify status chuyển `EXPIRED`, ghế được giải phóng lại (`GET /flights/{id}/seats` thấy ghế `isAvailable: true`).

### 6.4 EmailRetryScheduler
Email `PENDING`/`FAILED` (chưa quá 3 lần) được thử gửi lại theo `nextRetryAt`.

**Test:** tắt Mailpit tạm thời → trigger 1 email (vd đặt vé) → email `status = PENDING`, `attempts = 1`, `nextRetryAt` = now + 1 phút → bật lại Mailpit → đợi scheduler chạy → verify email gửi thành công ở lần retry, `status = SENT`.

**Test riêng cho bug B3 (retrySend mất kiểu LocalDateTime):** trước khi fix B3, email có field ngày giờ (booking-confirmed, checkin-reminder, flight-delayed, flight-cancelled) mà phải retry sẽ **luôn** lỗi ở lần gửi lại — verify bằng cách xem `emailLog.lastError` có chứa `TemplateProcessingException`/lỗi liên quan `#temporals.format` không. Sau khi fix, retry phải thành công bình thường.

---

## 7. MODULE Admin Dashboard & Báo cáo

> ⛔ Toàn bộ mục này bị chặn bởi bug A2 (403 cho mọi request admin) — fix A2 trước.

### 7.1 KPI Dashboard
```bash
curl $BASE/admin/dashboard/kpis -H "Authorization: Bearer <admin_token>"
```
Verify từng số liệu khớp với dữ liệu thật trong DB (đếm booking hôm nay, doanh thu, tỷ lệ chuyển đổi...) bằng query SQL đối chiếu thủ công.

### 7.2 Biểu đồ doanh thu / Top tuyến bay
```bash
curl "$BASE/admin/dashboard/revenue-chart?period=MONTHLY" -H "Authorization: Bearer <admin_token>"
curl "$BASE/admin/dashboard/top-routes?limit=5" -H "Authorization: Bearer <admin_token>"
```
⛔ Chưa có UI hiển thị (bug E6) — test qua API trước, sau khi thêm code đề xuất trong file rà soát thì verify UI: vào `/admin` → thấy 2 khối biểu đồ/bảng có dữ liệu thật, không rỗng nếu đã có booking.

### 7.3 Quản lý booking (admin)
```bash
curl "$BASE/admin/bookings?status=CONFIRMED&page=0&size=10" -H "Authorization: Bearer <admin_token>"
curl -X PATCH "$BASE/admin/bookings/ABC123/cancel?reason=Trung%20lich" -H "Authorization: Bearer <admin_token>"
```
Verify sau khi hủy: `refundAmount` = 100% (không qua `calculateRefund`, luôn full — theo thiết kế hiện tại), email gửi tới khách, và **sau khi fix B2**: verify `BookingRefundRequestedEvent` được publish, `VNPayService.refundTransaction()` thực sự được gọi (kiểm tra log hoặc `payments.refund_trans_id` được set).

### 7.4 Quản lý hãng bay / sân bay / chuyến bay
```bash
curl $BASE/admin/airlines -H "Authorization: Bearer <admin_token>"
curl -X POST $BASE/admin/airlines -H "Authorization: Bearer <admin_token>" -H "Content-Type: application/json" -d '{"iataCode":"QH","name":"Bamboo Airways"}'
curl "$BASE/admin/flights?page=0&size=10" -H "Authorization: Bearer <admin_token>"
```
**Test UI riêng:** vào `/admin/airlines`, tạo hãng bay mới qua form — trước khi fix D2 (typo `sirlines`), request luôn lỗi; sau khi fix, verify hãng bay mới xuất hiện trong danh sách ngay (React Query invalidate).

### 7.5 Xuất báo cáo Excel
```bash
curl -X POST $BASE/admin/reports/export -H "Authorization: Bearer <admin_token>" -H "Content-Type: application/json" -d '{"fromDate":"2026-06-01","toDate":"2026-07-29","type":"REVENUE"}' --output bao-cao.xlsx
```
Mở file `.xlsx` bằng Excel/LibreOffice, verify dữ liệu khớp với DB, không có cell lỗi `#REF!`/rỗng bất thường.

**Test UI:** click "Xuất báo cáo" ở `/admin` → file `.xlsx` tự tải về với tên `bao-cao-<ngày>.xlsx`.

### 7.6 Phân quyền — test riêng, rất quan trọng
- Đăng nhập user thường (`ROLE_USER`), thử gọi bất kỳ endpoint `/api/v1/admin/**` nào → phải `403` (sau khi fix A2 — vì trước khi fix, **mọi** request kể cả admin thật cũng bị 403 nên test này sẽ "pass giả" không có ý nghĩa cho tới khi A2 được fix).
- Truy cập `/admin` trên UI bằng tài khoản không phải admin → `AdminRoute` phải redirect về `/` (frontend guard đã đúng, verify không đổi).
- Truy cập `/admin` khi chưa đăng nhập → redirect `/login`.

---

## 8. Test bảo mật / phi chức năng bổ sung

| # | Kịch bản | Kỳ vọng |
|---|---|---|
| S1 | Gọi bất kỳ API cần auth nào, không kèm header `Authorization` | `401 Unauthorized` |
| S2 | Gọi API kèm access token đã hết hạn | `401`, frontend tự động gọi `/refresh` (verify qua Network tab trình duyệt, không cần user thao tác gì) |
| S3 | Gọi API kèm access token đã bị blacklist (sau logout) | `401`, message "Token đã bị thu hồi..." |
| S4 | User A thử xem/hủy booking của User B qua PNR đoán được | `403` (sau fix A6) |
| S5 | User A thử tạo payment link / xem trạng thái thanh toán cho booking của User B | `403` (sau fix A4) |
| S6 | Gửi payload SQL injection vào field text (vd `contactEmail`, `fullName`) | Không có lỗi SQL, dữ liệu được lưu/escape an toàn (JPA parameterized query mặc định đã an toàn — test để xác nhận không có raw query nào) |
| S7 | Gửi payload XSS vào `fullName`, `cancelReason` | Dữ liệu lưu nguyên văn (backend không cần escape vì JSON response không phải HTML) nhưng **frontend phải escape khi render** — verify React tự escape (không dùng `dangerouslySetInnerHTML` ở đâu hiển thị dữ liệu user-generated) |
| S8 | CORS: gọi API từ origin khác `http://localhost:3000`/domain production đã khai báo | Bị browser chặn (kiểm tra qua DevTools Console, lỗi CORS) — verify SAU khi fix A3 rằng 2 origin hợp lệ (`localhost:3000`, domain production) vẫn gọi được bình thường |
| S9 | Đặt 2 booking cùng lúc trên cùng 1 ghế (concurrent) | Chỉ 1 thành công, cái còn lại `409 SEAT_UNAVAILABLE` — xem Module 4.2 |
| S10 | Rate limit login theo IP | Sau 5 lần trong 1 phút → `429` (Module 1.2) |

---

## 9. Checklist tổng hợp trước khi coi là "đã test xong toàn bộ"

- [ ] 1.1–1.5 Auth: đăng ký, đăng nhập, refresh, reuse detection, logout, logout-all, quên/đặt lại mật khẩu
- [ ] 2.1–2.3 Flight management: CRUD sân bay/chuyến bay, mọi nhánh state machine, DELAYED gửi email, CANCELLED tự hủy booking + hoàn tiền + giải phóng ghế + gửi email
- [ ] 3.1–3.2 Search: một chiều với đủ filter, cache đúng theo `page`/`size`, khứ hồi
- [ ] 4.1–4.5 Booking: xem sơ đồ ghế, tạo booking (đủ edge case trùng khách/hết ghế/race condition ghế), xem chi tiết (IDOR), "Vé của tôi", hủy vé (đủ 4 case bảng hoàn tiền)
- [ ] 5.1–5.4 Payment: tạo link, IPN (thành công/duplicate/sai amount/sai chữ ký), trang return, reconciliation scheduler
- [ ] 6.1–6.4 Email: đủ 8 loại template hiển thị đúng, 3 scheduler (checkin/expiry/retry) chạy đúng
- [ ] 7.1–7.6 Admin: KPI, chart, quản lý booking/airline/flight, export Excel, phân quyền đúng cho cả 2 chiều (admin vào được, user thường bị chặn)
- [ ] 8. Security: đủ 10 kịch bản S1–S10
- [ ] Toàn bộ UI frontend chạy qua Chrome DevTools Network tab, xác nhận **0 request nào lỗi 404 do sai path** (soát lại theo danh sách bug D1–D3, E1–E6 trong file rà soát)
- [ ] Test responsive cơ bản: mở mọi trang chính ở viewport mobile (375px) và desktop (1440px), không bị vỡ layout

> Sau khi checklist này pass 100%, xem lại `RA-SOAT-TOAN-DIEN-2026-07-29.md` để đảm bảo không còn mục nào ở mức 🔴/🟠 chưa fix — đó mới là điều kiện đủ để coi dự án sẵn sàng demo/deploy.