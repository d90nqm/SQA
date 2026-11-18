# Chạy DentalCareX trên Windows

Mã nguồn: https://github.com/son023/ThietKeVaTrienKhaiHeThongNhaKhoaThongMinhTichHopChatBotAI

Đã tải nhánh `develop`, commit `7a88ce5`, bằng shallow clone (chỉ lịch sử gần nhất).

Đã kiểm tra ngày 28/10/2026: build frontend và toàn bộ 14 image Java thành công; 17 container của phần quản lý đang chạy. Eureka báo 13 ứng dụng client ở trạng thái `UP` (registry không tự đăng ký). Đăng nhập admin qua gateway trả HTTP 200 và token; đăng nhập trên trình duyệt cũng thành công. Các API đọc users, patients, doctors, medical-services, appointments, invoices đều trả HTTP 200, lần lượt có 25, 10, 5, 20, 16 và 16 bản ghi. Chưa kiểm thử toàn bộ luồng tạo/sửa nghiệp vụ.

## Cần những gì?

| Thành phần | Vai trò | Máy hiện tại |
| --- | --- | --- |
| Git | Tải và cập nhật mã nguồn | Đã có 2.45.1 |
| Docker Desktop chạy Linux containers/WSL2, Docker Compose | Chạy backend và các cơ sở dữ liệu | Đã có, engine 29.2.1 |
| Node.js và npm | Chạy frontend React 18/Vite 6 | Đã có Node 22.14.0, npm 10.9.2 |
| Java 17 | Chạy/biên dịch backend ngoài Docker hoặc trong IDE | Đã có; Docker tự cung cấp JDK/JRE 17 |
| Python 3 | Chỉ cần khi tạo lại `compose.local.json` bằng tiện ích đi kèm | Đã có Python 3.14 |
| Internet | Tải npm packages, image Docker và phụ thuộc Maven ở lần đầu | Cần khi chưa có cache |

Không cần cài PostgreSQL, Redis, Axon Server hoặc Gradle toàn hệ thống; Docker cung cấp chúng. Mỗi dịch vụ Java có cấu hình Gradle riêng, repo không có một lệnh Gradle ở thư mục gốc để build toàn bộ.

Docker hiện được cấp khoảng 7.6 GiB RAM. Cấu hình cục bộ giới hạn mỗi dịch vụ Spring ở 512 MiB (Java heap 256 MiB), Axon ở 768 MiB. Đây là cấu hình phát triển để chạy thử; có thể tăng giới hạn nếu chạy tải lớn. Lần build đầu tải nhiều image/phụ thuộc và có thể mất vài phút đến lâu hơn tùy mạng.

## Bật lại dự án

1. Mở Docker Desktop và đợi engine sẵn sàng.
2. Mở PowerShell tại thư mục `dental-app`.
3. Ở lần đầu, tạo cặp khóa JWT trong tệp `.env` bằng Java 17. Có thể sao chép `.env.example` thành `.env` trước để cấu hình thêm tích hợp.

```powershell
java .\scripts\GenerateLocalJwtKeys.java
```

4. Chạy:

```powershell
powershell -ExecutionPolicy Bypass -File .\start-local.ps1
```

Script khởi động backend trong Docker và frontend trong nền. Đợi dịch vụ Java khởi động rồi mở http://localhost:3000 (dùng `localhost` để khớp CORS).

Sau khi thay đổi mã Java, build lại bằng:

```powershell
powershell -ExecutionPolicy Bypass -File .\start-local.ps1 -Build
```

Frontend đã cài thư viện bằng `npm install`. Nếu muốn tự chạy và xem log trong terminal:

```powershell
cd fe
npm.cmd run dev -- --host 127.0.0.1 --strictPort
```

Chỉ chạy một phiên frontend trên cổng 3000.

## Đăng nhập cục bộ

- Số điện thoại quản trị: `0900000000`
- Mật khẩu cục bộ: `LocalAdmin@2026`

Mật khẩu này chỉ được đặt cho tài khoản admin trong database Docker mới của dự án. Dữ liệu mẫu gốc có 25 tài khoản; các tài khoản khác giữ mật khẩu trong seed gốc. SQL gốc vẫn ở `scripts/user-service.sql`. Dữ liệu lịch hẹn mẫu chủ yếu thuộc năm 2025 nên màn hình lọc theo ngày hiện tại có thể trống.

## Địa chỉ và kiểm tra

| Dịch vụ | Địa chỉ |
| --- | --- |
| Giao diện | http://localhost:3000 |
| API Gateway | http://localhost:8080 |
| Eureka registry | http://localhost:8761 |
| Axon dashboard | http://localhost:8024 |
| PostgreSQL từ máy Windows | `localhost:15432`, user `postgres`, password `password` |
| Redis | `localhost:6379` |

Các database có tên theo dịch vụ, ví dụ `user-service`, `patient-service`, `doctor-service`. Trong mạng Docker, PostgreSQL vẫn dùng `postgres:5432`; cổng 15432 chỉ dành cho công cụ trên máy Windows vì cổng 5432 bị Windows từ chối.

```powershell
docker compose -f compose.local.json ps
docker compose -f compose.local.json logs --tail 100 user-service
docker compose -f compose.local.json logs --tail 100 gateway
```

Frontend ghi log vào `frontend-local.log` và `frontend-local.err.log`. Nhật ký build ban đầu ở `build-local.log`.

## Dừng dự án

```powershell
powershell -ExecutionPolicy Bypass -File .\stop-local.ps1
```

Lệnh dừng giữ nguyên database trong Docker volumes của project `dental-local`.

## Chatbot và tích hợp bên ngoài

Để cấu hình Gemini, sao chép `.env.example` thành `.env` và điền khóa của bạn vào `GEMINI_API_KEY`. Docker Compose truyền khóa và model vào chatbot. Có thể tự kiểm tra lại bằng `python scripts/check-gemini.py` (lệnh gọi API thật). Chatbot vẫn đang tắt trong `compose.local.json` hiện tại.

Theo lựa chọn hiện tại, `compose.local.json` chỉ chạy phần quản lý nha khoa. Chatbot, Neo4j, MongoDB, ngrok và pgAdmin không được khởi động. Khi cần bật tích hợp, thiết lập thông tin PayOS, SMTP, Gemini và ngrok qua các biến trong .env.example.

Để chạy chatbot đầy đủ sau này cần:

- Hai thư mục mô hình do tác giả huấn luyện: `service/chatbot-service/app/models/intent_model` và `service/chatbot-service/app/models/ner_model_final`. Các thư mục này bị bỏ qua trong Git và không có trong bản tải.
- Gemini API key của bạn, tự điền vào cấu hình cục bộ.
- Python 3.13 theo Dockerfile chatbot, cùng PyTorch CPU và các thư viện trong `requirements.txt` (Docker sẽ cài).
- Neo4j, MongoDB và dữ liệu tri thức; repo có `kb_v4_final_train.json` cùng script `build_knowledge_base.py` nhưng vẫn cần kiểm tra quy trình nạp dữ liệu khi bật chatbot.
- Internet để tải thêm mô hình embedding `keepitreal/vietnamese-sbert` nếu chưa có cache.

Thanh toán trực tuyến cần tài khoản PayOS và bộ client ID/API key/checksum key riêng, cùng webhook URL khi tích hợp. Gửi email cần SMTP riêng. Các chức năng này chưa cấu hình trong bản chạy cục bộ.

## Các điều chỉnh để chạy cục bộ

- `compose.local.json`: cấu hình độc lập, project và volumes riêng `dental-local`, cổng chỉ bind `127.0.0.1`, giới hạn bộ nhớ.
- `Dockerfile.local`: dùng cache Gradle chung và giới hạn số worker/bộ nhớ build; Dockerfile gốc được giữ lại.
- Healthcheck Eureka dùng trang `/` vì dịch vụ gốc không khai báo Spring Boot Actuator.
- `scripts/init-all-databases.sh` dùng xuống dòng LF để chạy trong Linux.
- `scripts/local-admin.sql` tạo mật khẩu admin cục bộ khi khởi tạo volume mới.
- Vite không tự bật cửa sổ trình duyệt.

Nếu chuyển thư mục hoặc cần tạo lại cấu hình sau khi pull, chạy `prepare-local.ps1` rồi build lại. Script tạo cấu hình từ `docker-compose.yml` gốc và loại bỏ các tích hợp bên ngoài như trên.

```powershell
git pull --ff-only
powershell -ExecutionPolicy Bypass -File .\prepare-local.ps1
powershell -ExecutionPolicy Bypass -File .\start-local.ps1 -Build
```

Nếu cần toàn bộ lịch sử Git: `git fetch --unshallow`.
