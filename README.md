# MAIN — Hệ thống bãi đỗ xe thông minh

Triển khai SRS 2.3 trong `IoT_System (2).md`: Spring Boot 3.5.16 / Java 21, Next.js 16.3.8 / React 19.3, PostgreSQL, giao thức REST cho một ESP32 và ba vị trí S1–S3.

## Chạy trên Windows, không cần Docker

Cần Java 21, Node.js 24 và Python 3.10+ để chạy simulator. Maven Wrapper có trong `backend/`.

```powershell
cd D:\IoT_WebSystem
.\scripts\init-env.ps1
.\scripts\start-local.ps1
```

Mở **http://localhost:3000**. Tài khoản quản trị có username từ `ADMIN_USERNAME`; mật khẩu ngẫu nhiên nằm trong **`.env` → `ADMIN_PASSWORD`**. Script không in bí mật ra log. ADMIN chỉ được tạo lần đầu khi DB chưa có ADMIN; sửa `.env` không tự đổi mật khẩu tài khoản đã tồn tại.

Chế độ local dùng PostgreSQL thật từ `embedded-postgres`, lưu bền vững tại `.runtime/postgres`, cổng 5440. Backend 8080 và web 3000 bind loopback; đây là chế độ phát triển trên máy. Log tại `.runtime/backend.log`, `.runtime/frontend.log`. `.tools/` là cache build, không phải mã nguồn bàn giao.

Sau khi đã build, khởi động bằng `.\scripts\start-local.ps1 -SkipBuild`. Dừng bằng `.\scripts\stop-local.ps1`. Giữ nguyên dữ liệu DB; không xóa thư mục khi tiến trình còn chạy.

Để ESP32 kết nối cùng mạng Wi-Fi, chạy `.\scripts\start-local.ps1 -SkipBuild -Lan` sau khi dừng phiên chạy cũ. Web/API nghe trên cổng 3000 qua LAN; backend vẫn bind loopback. Cấu hình IP máy tính trong `firmware/parking_config.h`. Nếu dùng thanh toán, đặt `PUBLIC_URL=http://<IP-LAN>:3000` trong `.env` trước khi khởi động để đường dẫn quay lại web đúng máy. Windows Firewall cần cho phép TCP 3000 từ mạng LAN tin cậy. Có thể dùng Compose bên dưới thay thế.

## Chạy LAN bằng Docker Compose

1. Khởi động Docker daemon. Chạy `scripts/init-env.ps1`, hoặc chép `.env.example` thành `.env` và thay toàn bộ placeholder.
2. Đặt `PUBLIC_URL=http://<IP-LAN-máy-host>:3000`. Không dùng localhost trên ESP32.
3. `docker compose up --build -d`.
4. Mở `http://<IP-LAN-máy-host>:3000`. Web và API dùng chung origin. DBeaver trên máy host kết nối database Docker bằng host `localhost`, port `5440`, database `parking`, username/password trong `.env`. Cổng database chỉ bind loopback, không mở ra mạng LAN.
5. `docker compose logs -f backend` để kiểm tra khởi động. `docker compose down` giữ volume dữ liệu.

Mặc định không tin `X-Forwarded-For`; rate limit dùng địa chỉ peer. Nếu cần rate limit riêng từng IP qua proxy, cấu hình `TRUSTED_PROXY` đúng địa chỉ proxy tin cậy; proxy ghi đè header, không nối dữ liệu do client cung cấp. Không đặt tùy tiện để tin mọi proxy.

## Luồng demo đầy đủ

1. Đăng ký một USER ở `/register`, đăng nhập và kiểm tra trạng thái chưa có thẻ.
2. ADMIN đăng nhập → **Quản lý thẻ** → **Cấp thẻ mới**, chọn USER và UID thử nghiệm hợp lệ. Thẻ mới chưa có quyền vào.
3. Mở chi tiết thẻ, chọn gói 30/90/365 ngày. Giá mặc định là giá demo.
4. Với `PAYMENT_MODE=mock`, trang đơn ghi rõ **THANH TOÁN MÔ PHỎNG**. ADMIN chọn kịch bản thành công/callback lặp/sai tiền/trả muộn. USER không có quyền gọi route giả lập.
5. Sau xác minh mô phỏng thành công, đơn PAID, card_renewals có đúng một kết quả, hạn thẻ được cập nhật.
6. Chạy simulator bằng hướng dẫn dưới đây; đặt cả ba slot FREE rồi quét IN/OUT. Quan sát dashboard, lịch sử và nhật ký quét.
7. Thử `offline on`, trả tiền lặp, khóa thẻ khi đang trong bãi và hiệu chỉnh phiên có lý do.

## Simulator ESP32

Từ PowerShell, nạp biến môi trường cục bộ rồi chạy:

```powershell
foreach ($line in Get-Content .env) {
  if ($line -match '^([A-Z_][A-Z0-9_]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
  }
}
python firmware/simulator/simulator.py --url http://localhost:3000
```

Lệnh trong simulator:

```text
slot S1 FREE
slot S2 FREE
slot S3 FREE
scan IN 04A10B7C
slot S1 OCCUPIED
scan OUT 04A10B7C
slot S1 FREE
offline on
offline off
reboot
quit
```

UID phải được ADMIN gán trước. Simulator chỉ ghi nhận OPEN, không điều khiển thiết bị thật. Sketch `firmware/SPS_24_Doi_Cong.ino` dùng backend để xác thực thẻ và ghi phiên, giữ web chẩn đoán và OTA. Xem [firmware/README.md](firmware/README.md) để cấu hình, build và nạp ESP32. Không chạy simulator đồng thời với ESP32 sử dụng cùng `ESP32-01`.

## Kiểm thử và build

```powershell
cd backend
.\mvnw.cmd test
.\mvnw.cmd package
cd ..\frontend
npm ci
npm run typecheck
npm run build
# Khởi động ứng dụng local trước khi chạy E2E.
npx playwright install chromium
npm run test:e2e
cd ..
python -m unittest discover -s firmware/simulator -p 'test_*.py' -v
python scripts/check_contract.py
```

Integration tests dùng PostgreSQL thật trên cổng tạm, không dùng H2/mock repository để kiểm tra concurrency. E2E dùng thông tin ADMIN trong `.env`, tạo USER/thẻ/đơn thử nghiệm trong DB local; chỉ chạy trên dữ liệu demo. Không chạy E2E trên môi trường thu tiền thật. Kết quả đã chạy và phần chưa nghiệm thu nằm trong [docs/test-evidence.md](docs/test-evidence.md).

## Thanh toán thật

Xem [docs/payment-adapter.md](docs/payment-adapter.md). `PAYMENT_MODE=payos` yêu cầu credentials thật và không fallback sang mock. Profile `production` từ chối mock. Cần xác minh timestamp/timezone và dữ liệu tra soát trên chính kênh thanh toán được cấu hình.

Tunnel HTTPS chỉ chuyển về `http://127.0.0.1:8088` của Compose. Listener này chỉ chấp nhận `POST /api/v1/payments/webhooks/payos`; các đường khác trả 404. Không tunnel listener web 3000 hoặc database. Khi public web ngoài LAN, cấu hình TLS reverse proxy và `COOKIE_SECURE=true`.

## Cấu trúc và hợp đồng

- `backend/`: monolith theo module auth/users/cards/parking/devices/payments/audit; Controller → Service → JPA repository.
- `frontend/`: App Router, các trang USER/ADMIN, polling khi tab hiển thị, QR render từ payload.
- `firmware/`: simulator giao thức và lõi C++ độc lập phần cứng.
- `infra/`: reverse proxy, listener webhook riêng.
- `docs/openapi.yaml`: hợp đồng đầy đủ endpoint/request/response/auth; JSON syntax là một tập con hợp lệ của YAML.
- `docs/decisions.md`, `docs/erd.md`: quyết định và quan hệ dữ liệu.
- Flyway V1: 13 bảng và seed bãi/chỗ/gói/thiết bị; không chứa mật khẩu hay key thật.

Không sửa migration đã chạy. Script `generate_model.py` chỉ mô tả schema khởi tạo; thay đổi DB đã triển khai phải tạo migration mới. Giữ nguyên SRS khi chưa có quyết định nghiệp vụ mới.

## Trạng thái tích hợp

Web/backend/simulator có thể chạy mà không cần phần cứng. **AT-33 (thanh toán thật) và AT-34 (phần cứng thật) chưa được nghiệm thu** khi chưa có tài khoản payOS, dữ liệu kênh và linh kiện. Lõi C++ chưa phải firmware đã flash được cho một board xác định. Không dùng mô hình timer barrier để tuyên bố an toàn cho xe thật.
