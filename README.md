# Boarding House Management Telegram Bot

## Overview
Boarding House Management Telegram Bot is a Java Spring Boot monolithic application designed to facilitate boarding house management tasks through a Telegram Bot interface.

## Technologies
- Java 17 / 21 (Runtime 26.0.2 tested)
- Spring Boot 3.3.4
- Spring Data JPA
- Hibernate 6 (with SQLite Dialect)
- SQLite Database
- Telegram Bot API Client (7.11.0)
- Maven
- Lombok
- JUnit 5 & SLF4J / Logback

## Project Structure
```text
com.boardinghousebot

├── config
│
├── bot
│   ├── handler
│   │   ├── StartHandler
│   │   ├── HelpHandler
│   │   ├── MenuHandler
│   │   ├── RoomCommandHandler
│   │   └── TenantCommandHandler
│   ├── command
│   │   ├── CommandHandler
│   │   └── BotMessageSender
│   └── BoardingHouseTelegramBot
│
├── domain
│   ├── user
│   │   ├── User
│   │   └── Role
│   ├── room
│   │   ├── Room
│   │   └── RoomStatus
│   ├── audit
│   │   ├── AuditLog
│   │   └── AuditAction
│   ├── contract, meter, invoice, payment, maintenance, notification
│
├── repository
│   ├── UserRepository
│   ├── RoomRepository
│   └── AuditLogRepository
│
├── service
│   ├── UserService
│   ├── RoomService
│   ├── AuthorizationService
│   └── AuditLogService
│
├── state
│   ├── UserConversationState
│   └── ConversationStateService
│
├── strategy, state, observer, scheduler, exception, util
```

## Core Features
1. **User & Role Management (Phase 2)**:
   - 3 System Roles: `ADMIN`, `LANDLORD`, `MEMBER`.
   - Automatic registration upon `/start` (default `MEMBER`).
   - Admin promotion/revocation (`/promote`, `/revoke`) with ADMIN role immutability.
   - Service-layer authorization and Audit Logging.
2. **Room & Tenant Management (Phase 3)**:
   - **Room Entity & Status**: Rooms with `roomNumber`, `monthlyRent` (BigDecimal), `maxOccupants`, `status` (`AVAILABLE`/`OCCUPIED`), and `description`.
   - **Landlord Operations**: Create room (multi-step wizard with `/cancel`), Edit room, Delete empty room, List rooms with capacity badges, Filter by status (`AVAILABLE` / `OCCUPIED`).
   - **Tenant Assignment**: Assign MEMBER to room (`/assign <id> <roomNumber>`), Remove member (`/remove_tenant <id>`), Move member to another room (`/move <id> <roomNumber>`).
   - **Validation & Business Rules**: Unique room numbers, rent > 0, maxOccupants > 0, occupancy capacity checks, rejection of deleting occupied rooms, rejection of assigning non-MEMBER users or already-assigned members.
   - **Member "Phòng của tôi" View**: Members view their own room details and co-tenants list safely.

## Requirements
- JDK 17 or Java 21+
- Apache Maven 3.8+

## Configuration
Configure environment variables or create a `.env` file (see `.env.example`):
- `TELEGRAM_BOT_USERNAME`: Username of your Telegram Bot
- `TELEGRAM_BOT_TOKEN`: Secret Token from @BotFather
- `TELEGRAM_ADMIN_ID`: Telegram User ID of the administrator

## Run Project
To start the application:
```bash
mvn spring-boot:run
```

To execute unit & integration tests:
```bash
mvn clean test
```

## Current Development Phase
Current Phase: Phase 3 — Room & Tenant Management
