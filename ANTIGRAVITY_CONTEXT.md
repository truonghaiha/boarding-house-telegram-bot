# ANTIGRAVITY CONTEXT - BOARDING HOUSE TELEGRAM BOT

## 1. PROJECT OVERVIEW

**Tên project:**
Xây dựng hệ thống Telegram Bot hỗ trợ quản lý nhà trọ

**English:**
Boarding House Management Telegram Bot

**Mục tiêu:**
Xây dựng hệ thống quản lý nhà trọ thông qua Telegram Bot, phục vụ bài tập lớn môn Lập trình hướng đối tượng Java/OOP (PTIT).

Project gồm **5 thành viên**.

Thời gian thực hiện ngắn, vì vậy ưu tiên:
- Thực tế
- Dễ demo
- Dễ giải thích khi bảo vệ
- Thể hiện rõ tư duy OOP & Design Patterns
- Không overengineering
- Không mở rộng phạm vi ngoài yêu cầu nếu chưa được yêu cầu.

**Phạm vi hiện tại:**
- 1 nhà trọ duy nhất.
- Có nhiều LANDLORD (N-LANDLORD) cùng quản lý một nhà trọ.
- Không có entity `BoardingHouse` ở thời điểm hiện tại.
- Tất cả LANDLORD dùng chung dữ liệu phòng, người thuê, hợp đồng, điện nước, hóa đơn, thanh toán, bảo trì.
- `landlord_id` KHÔNG được tự ý thêm vào các business entity (`Invoice`, `Payment`, `Room`, `Contract`, `MeterReading`).

---

## 2. TECHNOLOGY STACK

- **Language:** Java (target/release 17, tương thích Java 17 - Java 26)
- **Framework:** Spring Boot 3.3.4
- **Build Tool:** Maven
- **Data Access:** Spring Data JPA, Hibernate ORM 6.5.3
- **Database:** SQLite JDBC 3.46.0 (`boarding_house.db`)
- **Integration:** Telegram Bot API (Telegram Long Polling via `telegrambots-springboot-longpolling-starter`)
- **Testing:** JUnit 5 (Jupiter), Spring Boot Test
- **Logging:** SLF4J / Logback
- **Interface:** Telegram UI (Inline Keyboard / Reply Keyboard / Command Handlers), không sử dụng Web Frontend.

**Kiến trúc tổng thể:**
```
Telegram Client
      ↓
BoardingHouseTelegramBot / BotMessageSender
      ↓
CommandHandlers / State (ConversationStateService)
      ↓
Service Layer (UserService, RoomService, ContractService, InvoiceService, PaymentService, MaintenanceService, NotificationService, AuditLogService)
      ↓
Repository Layer (JpaRepository / Custom HQL Queries)
      ↓
Hibernate JPA Entity Manager
      ↓
SQLite Database (boarding_house.db)
```

---

## 3. PROJECT PACKAGE STRUCTURE

Source code thực tế trong `src/main/java/com/boardinghousebot`:

```
com.boardinghousebot
├── BoardingHouseBotApplication.java
├── config
│   ├── DatabaseConfig.java
│   ├── DatabaseMigrationService.java
│   └── TelegramBotConfig.java
├── bot
│   ├── BoardingHouseTelegramBot.java
│   ├── command
│   │   ├── BotMessageSender.java
│   │   └── CommandHandler.java
│   └── handler
│       ├── AdminCommandHandler.java
│       ├── ContractCommandHandler.java
│       ├── HelpCommandHandler.java
│       ├── InvoiceCommandHandler.java
│       ├── MaintenanceCommandHandler.java
│       ├── MenuCommandHandler.java
│       ├── MeterCommandHandler.java
│       ├── NotificationCommandHandler.java
│       ├── PaymentCommandHandler.java
│       ├── RoomCommandHandler.java
│       ├── StartCommandHandler.java
│       └── UserCommandHandler.java
├── domain
│   ├── audit
│   │   ├── AuditAction.java
│   │   └── AuditLog.java
│   ├── contract
│   │   ├── Contract.java
│   │   └── ContractStatus.java
│   ├── invoice
│   │   ├── Invoice.java
│   │   ├── InvoiceStatus.java
│   │   └── Payment.java
│   ├── maintenance
│   │   ├── MaintenanceRequest.java
│   │   └── MaintenanceStatus.java
│   ├── meter
│   │   ├── MeterReading.java
│   │   └── UtilityType.java
│   ├── notification
│   │   ├── Notification.java
│   │   └── NotificationType.java
│   ├── payment
│   │   └── PaymentMethod.java
│   ├── room
│   │   ├── Room.java
│   │   └── RoomStatus.java
│   └── user
│       ├── Role.java
│       └── User.java
├── repository
│   ├── AuditLogRepository.java
│   ├── ContractRepository.java
│   ├── InvoiceRepository.java
│   ├── MaintenanceRequestRepository.java
│   ├── MeterReadingRepository.java
│   ├── NotificationRepository.java
│   ├── PaymentRepository.java
│   ├── RoomRepository.java
│   └── UserRepository.java
├── service
│   ├── AuditLogService.java
│   ├── AuthorizationService.java
│   ├── ContractService.java
│   ├── InvoiceService.java
│   ├── MaintenanceService.java
│   ├── MeterReadingService.java
│   ├── NotificationService.java
│   ├── PaymentService.java
│   ├── RoomService.java
│   ├── TelegramNotificationService.java
│   └── UserService.java
├── strategy
│   ├── ElectricityPricingStrategy.java
│   ├── MeterPricingStrategy.java
│   ├── UtilityPricingStrategy.java
│   └── WaterPricingStrategy.java
├── state
│   ├── ConversationStateService.java
│   └── UserConversationState.java
├── observer
│   ├── ContractCreatedEvent.java
│   ├── InvoiceCreatedEvent.java
│   ├── MaintenanceCreatedEvent.java
│   ├── MaintenanceStatusChangedEvent.java
│   ├── MemberRoomChangedEvent.java
│   ├── MeterReadingCreatedEvent.java
│   ├── NotificationObserver.java
│   ├── PaymentRecordedEvent.java
│   └── RoleChangedEvent.java
├── scheduler
│   ├── ContractReminderScheduler.java
│   └── InvoiceReminderScheduler.java
├── exception
│   ├── BusinessException.java
│   └── GlobalExceptionHandler.java
└── util
    └── TelegramKeyboardUtil.java
```

---

## 4. ROLES

Enum `Role` hiện tại:
- `ADMIN`
- `LANDLORD`
- `MEMBER`

### **ADMIN:**
- Quản lý User (xem danh sách, tra cứu thông tin).
- Cấp quyền LANDLORD (Promote MEMBER $\rightarrow$ LANDLORD).
- Thu hồi quyền LANDLORD (Revoke LANDLORD $\rightarrow$ MEMBER).
- Xem Nhật ký hệ thống (Audit Log).
- Xem thống kê và các chức năng dành riêng cho Quản trị viên.
- Không trực tiếp quản lý nghiệp vụ phòng trọ (không sửa phòng/hợp đồng/hóa đơn).
- Không nhận notification nghiệp vụ bảo trì / phòng / hóa đơn của LANDLORD hoặc MEMBER.

### **LANDLORD:**
- Quản lý phòng trọ (Tạo, sửa, xem danh sách phòng còn chỗ/đang ở, xóa phòng trống).
- Quản lý người thuê (Assign member vào phòng, xóa member khỏi phòng, chuyển phòng cho member).
- Quản lý hợp đồng (Tạo hợp đồng mới, gia hạn, kết thúc hợp đồng).
- Quản lý điện nước (Ghi chỉ số điện/nước hàng tháng, tính tiền tự động).
- Tạo hóa đơn (Tạo hóa đơn tháng cho từng phòng).
- Ghi nhận thanh toán hóa đơn (Thanh toán một phần / Thanh toán toàn bộ).
- Quản lý bảo trì (Xem danh sách báo sự cố, cập nhật trạng thái sự cố PENDING $\rightarrow$ IN_PROGRESS $\rightarrow$ RESOLVED).
- Xem thông báo, thống kê doanh thu/tỷ lệ lấp đầy, thông tin tài khoản.

### **MEMBER:**
- Xem phòng của mình (xem số phòng, tiền phòng, danh sách bạn cùng phòng).
- Xem hợp đồng của mình (xem thời hạn, tiền cọc, ngày bắt đầu/kết thúc).
- Xem chỉ số điện nước (xem lịch sử điện nước đã ghi).
- Xem hóa đơn của mình (xem tổng hóa đơn, số tiền đã trả, còn nợ, hạn thanh toán).
- Báo sự cố / Yêu cầu bảo trì (tạo báo cáo hỏng hóc thiết bị).
- Xem thông báo cá nhân.
- Xem thông tin tài khoản.

**Identity & Authentication:**
- Không sử dụng Username/Password truyền thống.
- **Telegram ID** (`Long`) là định danh chính của User trong hệ thống.
- Khi gửi lệnh `/start`:
  - Nếu Telegram ID chưa tồn tại $\rightarrow$ Tự động đăng ký User mới với vai trò `MEMBER`.
  - Nếu Telegram ID khớp với `TELEGRAM_ADMIN_ID` cấu hình trong `.env` $\rightarrow$ Đăng ký / Khởi tạo với vai trò `ADMIN`.
  - ADMIN có thể promote/revoke LANDLORD thông qua UI Admin Panel.

---

## 5. ENVIRONMENT CONFIGURATION

`application.properties`:
```properties
spring.application.name=boarding-house-bot
spring.config.import=optional:file:.env[.properties]

spring.datasource.url=jdbc:sqlite:boarding_house.db
spring.datasource.driver-class-name=org.sqlite.JDBC
spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect

spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
spring.jpa.properties.hibernate.format_sql=true

telegram.bot.username=${TELEGRAM_BOT_USERNAME:}
telegram.bot.token=${TELEGRAM_BOT_TOKEN:}
telegram.admin-id=${TELEGRAM_ADMIN_ID:}
```

File `.env` (Đặt ở thư mục gốc của project, chứa cấu hình bí mật):
```env
TELEGRAM_BOT_USERNAME=your_bot_username
TELEGRAM_BOT_TOKEN = SECRET, không ghi giá trị
TELEGRAM_ADMIN_ID=your_telegram_id
```

> [!CAUTION]
> Tuyệt đối KHÔNG commit token thật của Telegram Bot hoặc secret API keys vào mã nguồn hay bất kỳ context file nào.

---

## 6. DATABASE

Database file chính:
`boarding_house.db` (SQLite)

**Quy tắc bất biến đối với Database:**
- KHÔNG xóa file database (`boarding_house.db`).
- KHÔNG recreate database / reset database.
- KHÔNG drop toàn bộ tables hoặc xóa dữ liệu test hiện tại.
- Mọi thay đổi schema (migration) phải bảo toàn dữ liệu, bảo đảm tính **idempotent**, không làm mất dữ liệu SQLite hiện có.
- Hệ thống có `DatabaseMigrationService` tự động kiểm tra và thực hiện migration schema (như cập nhật `CHECK` constraint cho bảng `notifications`) một cách an toàn khi ứng dụng khởi động.

---

## 7. COMPLETED PHASES

### **Phase 1 - Project Foundation**
- Khởi tạo Spring Boot 3.3.4, Maven build pipeline, SQLite database connection.
- Cấu hình JPA/Hibernate dialect cho SQLite.
- Health Check REST Controller: `GET /api/health` $\rightarrow$ `{"status":"UP","application":"boarding-house-bot"}`.
- Global exception handling và logging foundation.

### **Phase 2 - User & Role Management**
- Phân quyền 3 tầng (`ADMIN`, `LANDLORD`, `MEMBER`).
- Quản lý người dùng, tự động khởi tạo theo Telegram ID.
- Promote/Revoke quyền LANDLORD bởi ADMIN.
- Audit Log tự động ghi lại toàn bộ các thao tác quan trọng (`AuditLogService`, `AuditAction`).
- Hệ thống Menu theo Role (`/start`, `/help`, `/menu`).

### **Phase 3 - Room & Tenant Management**
- Quản lý Phòng trọ: Mã phòng (`roomNumber`), Tiền phòng (`monthlyRent`), Sức chứa tối đa (`maxOccupants`), Trạng thái (`AVAILABLE`/`OCCUPIED`), Mô tả.
- Xếp người thuê vào phòng (`assignMemberToRoom`), xóa khỏi phòng (`removeMemberFromRoom`).
- Nghiệp vụ **Chuyển phòng** (`moveMember`): tự động kết thúc Hợp đồng ACTIVE ở phòng cũ, tạo Hợp đồng mới ở phòng mới, chuyển MEMBER sang phòng mới và cập nhật trạng thái 2 phòng.
- Màn hình thông tin phòng cho MEMBER và co-tenant (bạn cùng phòng).
- Wizard/State Pattern cho luồng nhập liệu Telegram (`ConversationStateService`, lệnh `/cancel`).

### **Phase 4 - Contract Management**
- Hợp đồng thuê nhà (`Contract`, `ContractStatus`): ngày bắt đầu, ngày kết thúc, tiền phòng, tiền cọc, trạng thái (`ACTIVE`, `ENDED`, `CANCELLED`).
- Ràng buộc nghiệp vụ:
  - Một MEMBER chỉ có tối đa 1 hợp đồng ACTIVE tại một thời điểm.
  - Một phòng trọ chỉ có tối đa 1 hợp đồng ACTIVE tại một thời điểm.
  - Ngày kết thúc phải lớn hơn ngày bắt đầu.
  - Hợp đồng `ENDED` là dữ liệu lịch sử (Read-Only).

### **Phase 5 - Meter Reading (Electricity & Water)**
- Ghi chỉ số Điện/Nước (`MeterReading`, `UtilityType`: `ELECTRICITY`, `WATER`).
- Áp dụng **Strategy Pattern**: `ElectricityPricingStrategy` (điện) và `WaterPricingStrategy` (nước).
- Tính sản lượng tiêu thụ (`currentReading - previousReading`) và tổng tiền (`consumption * unitPrice`).
- Ràng buộc Unique: Mỗi phòng chỉ ghi tối đa 1 chỉ số điện và 1 chỉ số nước cho mỗi tháng/năm (`uk_meter_room_period_type`).

### **Phase 6 - Invoice & Payment Management**
- Tạo Hóa đơn hàng tháng (`Invoice`, `InvoiceStatus`: `UNPAID`, `PARTIALLY_PAID`, `PAID`, `OVERDUE`).
- Hạn thanh toán mặc định: Ngày 15 của tháng hóa đơn.
- Tự động đánh giá trạng thái Quá hạn (`OVERDUE`) nếu quá hạn thanh toán mà chưa trả đủ.
- Ghi nhận thanh toán (`Payment`, `PaymentMethod`: `CASH`, `BANK_TRANSFER`, `E_WALLET`).
- Hỗ trợ thanh toán một phần (`PARTIALLY_PAID`) và thanh toán đủ (`PAID`), tính số tiền còn lại (`remainingAmount`).
- Backend Payment/Invoice được giữ nguyên vẹn 100%.

### **Phase 7 - Maintenance, Notification & Scheduler**
- Quản lý Sự cố / Bảo trì (`MaintenanceRequest`, `MaintenanceStatus`: `PENDING`, `IN_PROGRESS`, `RESOLVED`).
- Áp dụng **State Pattern** cho vòng đời sự cố.
- Áp dụng **Observer Pattern** (Spring `ApplicationEventPublisher` & `NotificationObserver`) cho tất cả các sự kiện hệ thống.
- Hệ thống Thông báo 2 kênh (DB Notification + Telegram Message).
- Scheduler tự động: `InvoiceReminderScheduler` (nhắc đóng tiền hóa đơn & quá hạn), `ContractReminderScheduler` (nhắc hợp đồng sắp hết hạn).

---

## 8. NOTIFICATION SYSTEM

**Flow kiến trúc:**
```
PaymentService / RoomService / ContractService / MeterReadingService / MaintenanceService
      ↓
Spring ApplicationEvent (PaymentRecordedEvent, MemberRoomChangedEvent, ContractCreatedEvent, etc.)
      ↓
NotificationObserver
      ├──→ Save DB Notification (NotificationRepository)
      └──→ Send Telegram Message (TelegramNotificationService)
```

**Danh sách đầy đủ các `NotificationType` (12 loại):**
1. `MAINTENANCE_CREATED` ("Yêu cầu bảo trì mới", "🔧")
2. `MAINTENANCE_STATUS_CHANGED` ("Cập nhật bảo trì", "🛠")
3. `ROLE_CHANGED` ("Thay đổi vai trò tài khoản", "👤")
4. `ROOM_CHANGED` ("Cập nhật thông tin phòng", "🏠")
5. `INVOICE_CREATED` ("Hóa đơn mới", "💰")
6. `INVOICE_DUE_REMINDER` ("Nhắc thanh toán hóa đơn", "⏰")
7. `INVOICE_OVERDUE` ("Hóa đơn quá hạn", "🚨")
8. `CONTRACT_EXPIRING` ("Hợp đồng sắp hết hạn", "📄")
9. `CONTRACT_CREATED` ("Hợp đồng mới", "📝")
10. `ELECTRICITY_CREATED` ("Chỉ số điện mới", "⚡")
11. `WATER_CREATED` ("Chỉ số nước mới", "💧")
12. `PAYMENT_RECORDED` ("Thanh toán hóa đơn", "💳")

**Quy tắc phân phối Notification:**
- **Maintenance (MEMBER tạo):** Gửi cho tất cả LANDLORD.
- **Maintenance (LANDLORD đổi status):** Gửi cho MEMBER báo sự cố.
- **Room / Contract / Meter / Invoice / Payment:** Chỉ gửi cho các **MEMBER** thuộc phòng của hóa đơn/hợp đồng đó.
- **Chống gửi nhầm:** KHÔNG gửi notification nghiệp vụ cho ADMIN, LANDLORD (trừ maintenance mới), hoặc MEMBER phòng khác.
- **Chống duplicate per-member:** Kiểm tra trùng lặp theo bộ `(recipientTelegramId, referenceType, referenceId, type)` để đảm bảo mỗi thành viên trong phòng đều nhận thông báo và không ai bị duplicate khi reprocess event.

---

## 9. IMPORTANT JPA / LAZY LOADING RULES

- Tránh triệt để `LazyInitializationException` bằng cách sử dụng `JOIN FETCH` tại repository boundary hoặc thực hiện query trong cùng context transaction.
- KHÔNG tự ý chuyển tất cả các quan hệ `@ManyToOne` / `@OneToMany` từ `LAZY` sang `EAGER`.
- Tránh vòng lặp vô hạn `StackOverflowError` trong `toString()`, `equals()`, `hashCode()` giữa các entity có quan hệ hai chiều (`User` $\leftrightarrow$ `Room`, `Invoice` $\leftrightarrow$ `Payment`, v.v.).

---

## 10. TELEGRAM UI RULES

- Menu Telegram hiển thị các nút thao tác dạng Keyboard tùy theo vai trò `ADMIN`, `LANDLORD`, `MEMBER`.
- Menu LANDLORD hiện tại ẩn nút `💳 Thanh toán` trực tiếp trên Main Menu, nhưng Backend Payment Service vẫn duy trì đầy đủ.
- Màn hình Quản lý Phòng có dòng hướng dẫn chuẩn: `Vui lòng chọn thao tác bên dưới:` ngay dưới tiêu đề.
- Các tin nhắn thông báo Telegram sử dụng icon chuyên biệt ở đầu tiêu đề và định dạng chuẩn (không thụt lùi dòng thừa).

---

## 11. DESIGN PATTERNS APPLIED

Hệ thống thể hiện các mẫu thiết kế OOP chính sau:
1. **Command Pattern:** Các CommandHandler (`StartCommandHandler`, `RoomCommandHandler`, `InvoiceCommandHandler`, v.v.) đóng gói các yêu cầu từ Telegram Update.
2. **State Pattern:**
   - Quản lý trạng thái đoạn chat của người dùng (`UserConversationState`, `ConversationStateService`).
   - Quản lý vòng đời bảo trì (`MaintenanceStatus`: `PENDING` $\rightarrow$ `IN_PROGRESS` $\rightarrow$ `RESOLVED`).
3. **Strategy Pattern:** Tính tiền điện nước linh hoạt theo loại dịch vụ (`UtilityPricingStrategy`, `ElectricityPricingStrategy`, `WaterPricingStrategy`).
4. **Observer Pattern:** Phát tin và xử lý sự kiện bất đồng bộ/đồng bộ trong dự án (`ApplicationEventPublisher`, `PaymentRecordedEvent`, `NotificationObserver`).
5. **Repository Pattern & Service Layer:** Tách biệt Data Access Layer và Business Logic Layer.

---

## 12. OOP REQUIREMENTS

Khi bổ sung hoặc chỉnh sửa mã nguồn:
- Đảm bảo tính đóng gói (Encapsulation), trừu tượng (Abstraction), đa hình (Polymorphism) và kế thừa hợp lý.
- Tuân thủ nguyên lý SOLID, không tạo God Class.
- Đặt Validation đúng vị trí (Service Layer / Entity Domain), không đưa business calculation phức tạp vào Telegram Handlers.

---

## 13. TESTING & VERIFICATION

**Lệnh chạy kiểm thử chuẩn:**
```bash
mvn clean test
```
Hoặc khi không muốn xóa `target`:
```bash
mvn compile test-compile test
```

**Kết quả chạy kiểm thử gần nhất (20/09/2026):**
- **Tests run:** 191
- **Failures:** 0
- **Errors:** 0
- **Skipped:** 0
- **Build Status:** `BUILD SUCCESS`

---

## 14. CURRENT DATABASE / MIGRATION STATUS

- File database: `boarding_house.db` đang chứa dữ liệu thực tế.
- Service migration: `DatabaseMigrationService` chạy khi ứng dụng khởi động để nâng cấp schema bảng `notifications` hỗ trợ 12 loại `NotificationType` mà không gây mất dữ liệu hay lỗi SQLite constraint.
- Tính chất: Migration bảo đảm tính **Idempotent** (chỉ thực hiện khi schema thực sự chưa cập nhật, tự động bỏ qua nếu đã đúng).

---

## 15. KNOWN BUGS & LESSONS LEARNED

1. **Spring Boot .env Import:** Cần có `spring.config.import=optional:file:.env[.properties]` trong `application.properties` để Spring tự động load biến môi trường từ `.env`.
2. **SQLite Check Constraint:** Direct `ALTER TABLE` trong SQLite không sửa được `CHECK` constraint của Column; phải dùng chiến thuật tạm bảng (`notifications_new`), copy dữ liệu, drop bảng cũ và đổi tên bảng mới.
3. **Lazy Loading outside Transaction:** Khi xử lý Spring Event Listener, nếu truy cập các quan hệ `LAZY` (ví dụ `payment.getInvoice().getRoom()`), listener cần có `@Transactional(readOnly = true)` hoặc mở Hibernate Session để tránh `LazyInitializationException`.
4. **Per-Member Notification Dedup:** Trong phòng có nhiều MEMBER, duplicate check phải kiểm tra theo `(recipientTelegramId, referenceType, referenceId, type)` thay vì chỉ dựa vào `referenceId` toàn cục để không làm bỏ sót các MEMBER khác.

---

## 16. DO NOT DO THESE THINGS

TUYỆT ĐỐI KHÔNG:
1. Reset, xóa hoặc recreate database `boarding_house.db`.
2. Xóa dữ liệu test hiện tại trong database.
3. Thay đổi Telegram Bot Token hoặc commit token thật lên git/doc.
4. Xóa Backend Payment / Invoice service khi chỉ được yêu cầu sửa UI Telegram Menu.
5. Tự ý thêm field `landlord_id` vào các entity nghiệp vụ chung (`Invoice`, `Payment`, `Room`, `Contract`).
6. Tự ý đổi tất cả các quan hệ JPA sang `EAGER`.
7. Tuyên bố "Test pass" nếu chưa có kết quả chạy Maven thực tế.

---

## 17. CURRENT DATA / MANUAL TEST INFORMATION

- **ADMIN Telegram ID:** Config trong `.env` (`TELEGRAM_ADMIN_ID`).
- **N-LANDLORD Model:** Các LANDLORD có tài khoản được cấp quyền bởi ADMIN và cùng quản lý các phòng trong hệ thống.
- **MEMBER Accounts:** Các tài khoản Telegram ID tự động đăng ký vai trò `MEMBER` khi khởi chạy lệnh `/start`.

---

## 18. HOW FUTURE AGENT MUST WORK

Mỗi khi nhận task mới trong dự án này:
1. **Bước 1:** Đọc file `ANTIGRAVITY_CONTEXT.md` này để nắm toàn bộ bức tranh kiến trúc và quy tắc dự án.
2. **Bước 2:** Đọc mã nguồn thực tế tại các file liên quan trước khi chỉnh sửa.
3. **Bước 3:** Không tự ý thay đổi architecture, database schema hoặc logic cốt lõi nếu không có yêu cầu.
4. **Bước 4:** Thực hiện thay đổi tối thiểu, chính xác theo yêu cầu.
5. **Bước 5:** Thực thi lệnh `mvn clean test` hoặc `mvn compile test-compile test` để xác minh 100% test suite passing.
6. **Bước 6:** Cập nhật thông tin công việc mới hoàn thành vào mục `# CURRENT STATUS` dưới đây.

---

# CURRENT STATUS

**Ngày cập nhật:** 21/09/2026

**Phase hiện tại:**
- **Phase 1 đến Phase 7:** Đã hoàn thành 100%.
- **Payment Notification Feature (COMPLETED):**
  - Thực thi thành công `PaymentRecordedEvent` xử lý hoàn toàn theo cơ chế `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`.
  - Đảm bảo DB Notification và Telegram message CHỈ được khởi tạo/gửi đi khi giao dịch thanh toán đã `COMMIT` thành công.
  - Pre-cache đầy đủ thông tin scalar và danh sách recipient Telegram IDs trong scope transaction ban đầu để tránh lỗi `LazyInitializationException` và session closed.
  - Hỗ trợ đầy đủ N-LANDLORD, gửi thông báo độc lập cho tất cả MEMBER trong phòng, ngăn ngừa duplicate per-recipient/payment/type.
  - Giữ nguyên vẹn 100% backend payment/invoice logic và dữ liệu SQLite `boarding_house.db`.

**Test Suite Metrics (Chạy gần nhất):**
- **Total Tests:** 191
- **Failures:** 0
- **Errors:** 0
- **Skipped:** 0
- **Build Result:** `BUILD SUCCESS`

**Công việc chuẩn bị tiếp theo:**
- Kiểm thử tổng thể các kịch bản Telegram Bot UI.
- Rà soát log và tối ưu trải nghiệm người dùng Telegram.
- Chuẩn bị tài liệu báo cáo và slide bảo vệ bài tập lớn OOP.
