# Kilivana Backend

For Android backend porting, start with [the handoff contract and migration warnings](docs/ANDROID_BACKEND_HANDOFF.md).

**Kilivana** is an agricultural e-commerce and logistics platform connecting **farmers, buyers, suppliers, inspectors, drivers, and admins** through a single REST API. Think of it as a full marketplace backend: farmers and suppliers list produce, buyers order it, inspectors verify quality, drivers deliver it, and admins oversee everything.

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [What's Inside (Modules)](#whats-inside-modules)
3. [Authentication & Token Flow](#authentication--token-flow)
4. [API Endpoints Reference](#api-endpoints-reference)
5. [Architecture & Design](#architecture--design)
6. [Database & Migrations](#database--migrations)
7. [Running Tests](#running-tests)
8. [Client Applications](#client-applications)
9. [Environment Variables](#environment-variables)
10. [Project Structure](#project-structure)

---

## Quick Start

### Prerequisites

| Tool | Version | Why |
|---|---|---|
| Java | 17+ (works on 17–21) | Runtime |
| PostgreSQL | 16 | Primary database |
| Redis | 6+ | Caching + rate limiting |
| Docker | Any recent | Integration tests (Testcontainers) |

### 1. Set environment variables

```bash
export DB_URL=jdbc:postgresql://localhost:5432/kilivana
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
export JWT_SECRET=change-me-to-a-long-random-secret-min-32-bytes
export ADMIN_BOOTSTRAP_KEY=some-long-bootstrap-key
```

### 2. Run

```bash
./mvnw spring-boot:run
```

Flyway automatically applies all 11 migrations (`V1`–`V11`) on startup. The API is now live at `http://localhost:8080/api/v1`.

### 3. Create the first admin

```bash
curl -X POST http://localhost:8080/api/v1/admins/signup \
  -H "X-Admin-Bootstrap-Key: $ADMIN_BOOTSTRAP_KEY" \
  -H "Content-Type: application/json" \
  -d '{"identifier":"admin@example.com","password":"changeit123","fullName":"Admin","phoneNumber":"+254700000000"}'
```

### 4. Log in

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"identifier":"admin@example.com","password":"changeit123"}'
```

You'll get back an `accessToken` (lasts 15 minutes) and a `refreshToken` (lasts 14 days). Use the access token in all subsequent requests:

```
Authorization: Bearer <accessToken>
```

### 5. Enable interactive API docs (Swagger UI)

```bash
export OPENAPI_ENABLED=true
export SWAGGER_ENABLED=true
export OPENAPI_PUBLIC=true   # optional: expose without ADMIN role
```

Then visit `http://localhost:8080/swagger-ui.html`.

---

## What's Inside (Modules)

The project is a **modular monolith**: each feature area is a self-contained module under `com.kilivana.<module>`, and each module owns its own `domain` (entities), `repository` (data access), `service` (business logic), and `api` (REST controllers + DTOs).

| Module | What it does |
|---|---|
| `auth` | Signup, login, token refresh, logout, forgot/reset password |
| `users` | User profiles per role (buyer/supplier/farmer/inspector/driver), account status, verification |
| `addresses` | Delivery and pickup addresses with optional GPS coordinates |
| `products` | Product catalog, categories, images, seller type (FARMER/SUPPLIER), stock management, moderation |
| `orders` | Shopping cart, multi-seller checkout, order state machine, stock reservation, delivery fee calculation |
| `payments` | Payment lifecycle (create → verify → refund), idempotency keys |
| `paymentgateway` | Payment provider abstraction (M-PESA stub), idempotent webhook processing |
| `logistics` | Delivery jobs, driver assignment/acceptance, status tracking, GPS events, proof of delivery |
| `inspection` | Quality inspections with configurable checklists, site visits, audit reports, photos |
| `badges` | Trust badges (e.g., "Quality Harvest"), auto-awarded on passed inspections |
| `notifications` | Event-driven notifications triggered by order/payment/logistics/inspection events |
| `disputes` | Raise, escalate, and resolve disputes against orders |
| `messages` | Public contact form intake and admin inbox |
| `audit` | Interceptor-based audit logging of admin and business actions |
| `admin` | Admin account provisioning (bootstrap key) + dashboard/reports/monitoring APIs |
| `media` | File storage abstraction (local filesystem implementation) |
| `security` | JWT (HS256), rate limiting (Redis or in-memory), CORS, Spring Security filters |
| `common` | Shared infrastructure: error handling, domain events, base entity, async config |

---

## Authentication & Token Flow

Kilivana uses **JWT (HS256)** with a two-token system:

| Token | Lifetime | Purpose |
|---|---|---|
| **Access token** | 15 minutes | Sent as `Authorization: Bearer <token>` on every API request |
| **Refresh token** | 14 days | Exchanged for a new access token when the old one expires |

See [the account/auth contract](docs/account-auth-contract.md) for signup fields, identity rules, profile updates, compatibility and V11 deployment details. Login accepts `identifier` (email or Kenyan phone), never username.

### The four core auth schemas

These are the exact JSON shapes the Android/iOS clients need (also in `auth-schemas-json.txt` and `auth-schemas-for-android.txt`):

#### 1. Login Request — `POST /api/v1/auth/login`

```json
{
  "identifier": "user@example.com",
  "password": "password123"
}
```

#### 2. Login Response (AuthResponse)

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "username": "jane_farmer",
  "region": "Kiambu",
  "phoneNumber": "+254712345678",
  "fullName": "Jane Farmer",
  "role": "FARMER",
  "verificationStatus": "UNVERIFIED",
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshTokenExpiresAt": "2026-10-12T10:00:00Z"
}
```

`role` is one of: `BUYER`, `SUPPLIER`, `FARMER`, `INSPECTOR`, `DRIVER`, `ADMIN`.

#### 3. Refresh Token Request — `POST /api/v1/auth/refresh`

```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

Returns a new `AuthResponse` with a fresh access token **and** a rotated refresh token (the old one is revoked).

#### 4. Logout Request — `POST /api/v1/auth/logout`

```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

Revokes the entire refresh token family. Returns `200 OK` with no body.

### Token lifecycle

```
Client                          Server
  |                               |
  |-- POST /auth/login ---------->|  (email or phone identifier + password)
  |<- accessToken + refreshToken -|
  |                               |
  |-- GET /products ------------->|  (Bearer accessToken)
  |<- 200 OK ---------------------|
  |                               |
  |-- GET /products ------------->|  (accessToken expired, 401)
  |<- 401 Unauthorized -----------|
  |                               |
  |-- POST /auth/refresh -------->|  (refreshToken)
  |<- new accessToken + new ------|  (old refreshToken revoked)
  |   refreshToken                |
  |                               |
  |-- POST /auth/logout --------->|  (refreshToken)
  |<- 200 OK ---------------------|  (entire token family revoked)
```

### Additional auth endpoints

| Endpoint | Description |
|---|---|
| `POST /api/v1/auth/signup` | Public registration (buyer/supplier/farmer/inspector) |
| `POST /api/v1/auth/forgot-password` | Request a password reset email |
| `POST /api/v1/auth/reset-password` | Reset password with token from email |
| `GET /api/v1/auth/me` | Get current user's profile (requires auth) |
| `POST /api/v1/admins/signup` | Create admin (requires `X-Admin-Bootstrap-Key` header) |

---

## API Endpoints Reference

All endpoints are prefixed with `/api/v1`. All request/response bodies are JSON.

### Auth (`/auth`)

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/auth/signup` | Public | Register a new user |
| POST | `/auth/login` | Public | Log in, get tokens |
| POST | `/auth/refresh` | Public | Rotate tokens |
| POST | `/auth/logout` | Public | Revoke tokens |
| POST | `/auth/forgot-password` | Public | Request password reset |
| POST | `/auth/reset-password` | Public | Reset password with token |
| GET | `/auth/me` | Bearer | Current user profile |

### Users (`/users`)

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/users/me` | Bearer | Get own profile |
| PATCH | `/users/me` | Bearer | Update own profile |
| GET | `/users/sellers/{id}` | Public | Public seller profile |
| PATCH | `/users/{id}/status` | Admin | Suspend/activate user |
| PATCH | `/users/{id}/verification` | Admin | Verify/reject user |

### Products (`/products`)

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/products` | Public | Browse active products |
| GET | `/products/{id}` | Public | Product detail |
| POST | `/products` | Supplier/Farmer | Create product |
| PATCH | `/products/{id}` | Owner | Update product |
| POST | `/products/{id}/images` | Owner | Add product image |
| GET | `/products/categories` | Public | List categories |
| POST | `/products/categories` | Admin | Create category |
| PATCH | `/products/{id}/moderate` | Admin | Moderate product |

### Orders (`/orders`)

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/orders/cart` | Buyer | View cart |
| POST | `/orders/cart` | Buyer | Add to cart |
| PATCH | `/orders/cart/{id}` | Buyer | Update cart item |
| POST | `/orders/checkout` | Buyer | Place order (multi-seller) |
| GET | `/orders` | Bearer | List own orders |
| GET | `/orders/{id}` | Bearer | Order detail |
| PATCH | `/orders/{id}/status` | Seller/Admin | Update order status |
| POST | `/orders/{id}/cancel` | Buyer | Cancel order |

### Payments (`/payments`)

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/payments` | Buyer | Create payment (idempotent) |
| POST | `/payments/verify` | Buyer | Verify payment |
| POST | `/payments/webhook` | Public | Gateway webhook (idempotent) |

### Logistics (`/logistics`)

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/logistics/jobs/available` | Driver | Available delivery jobs |
| POST | `/logistics/jobs/{id}/accept` | Driver | Accept a job |
| PATCH | `/logistics/jobs/{id}/status` | Driver/Admin | Update job status |
| POST | `/logistics/jobs/{id}/proof` | Driver | Submit proof of delivery |
| GET | `/logistics/jobs/{id}/tracking` | Bearer | Tracking events |
| POST | `/logistics/jobs/{id}/tracking` | Driver | Add tracking event |

### Inspections (`/inspections`)

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/inspections` | Farmer/Supplier | Request inspection |
| GET | `/inspections` | Bearer | List inspections |
| PATCH | `/inspections/{id}/status` | Inspector | Update status |
| POST | `/inspections/{id}/result` | Inspector | Submit result |
| POST | `/inspections/{id}/site-visits` | Inspector | Schedule site visit |
| POST | `/inspections/{id}/audit-reports` | Inspector | Create audit report |

### Other

| Method | Path | Auth | Description |
|---|---|---|---|
| GET | `/badges` | Public | List all badges |
| GET | `/notifications` | Bearer | List notifications |
| POST | `/messages` | Public | Submit contact message |
| GET | `/admin/dashboard` | Admin | Dashboard stats |
| GET | `/admin/reports` | Admin | Reports data |

---

## Architecture & Design

### Modular monolith

```
src/main/java/com/kilivana/
├── auth/           ← signup, login, refresh, logout, password reset
│   ├── api/        ← controllers + request/response DTOs
│   ├── service/    ← business logic
│   └── ...
├── users/          ← profiles, roles, status
├── products/       ← catalog, categories, stock
├── orders/         ← cart, checkout, state machine
├── payments/       ← payment lifecycle
├── logistics/      ← delivery jobs, tracking
├── inspection/     ← quality checks, site visits
├── badges/         ← trust badges
├── notifications/  ← event-driven alerts
├── disputes/       ← order disputes
├── messages/       ← contact form
├── audit/          ← audit logging
├── admin/          ← admin dashboard
├── security/       ← JWT, rate limiting, CORS
└── common/         ← shared infrastructure
```

### State machines (enforced server-side)

**Order:** `PLACED → CONFIRMED → READY_FOR_PICKUP → IN_PROGRESS → DELIVERED → COMPLETED`
Also: `CANCELLED`, `REJECTED` (with automatic stock release + refund)

**Logistics Job:** `PENDING_ACCEPTANCE → ACCEPTED → AT_PICKUP → PICKED_UP → IN_TRANSIT → DELIVERED`
Also: `FAILED`, `CANCELLED`

**Inspection:** `SCHEDULED → IN_PROGRESS → PASSED` or `FAILED`

**Payment:** `PENDING → VERIFIED` or `FAILED`; `VERIFIED → REFUNDED`

**Site Visit:** `REQUESTED → SCHEDULED → COMPLETED` or `REJECTED`

### Security model

- **JWT (HS256)** — access tokens (15 min) + refresh tokens (14 days)
- **Refresh token rotation** — every refresh issues a new token pair; the old refresh token is revoked
- **Token family tracking** — all refresh tokens in a family are revoked if reuse is detected
- **RBAC** — enforced in the service layer on every write/read path (not just UI hiding)
- **Rate limiting** — Redis-backed or in-memory; stricter limits on login (5/min) and forgot-password (3/min)
- **Admin bootstrap key** — required to create the first admin account
- **CORS** — configurable allowed origins; rejects wildcard with credentials

### Multi-seller cart

One checkout creates a single `CustomerOrder` that is automatically split into per-seller `Order` sub-orders. Each sub-order gets its own:
- Stock reservation
- Payment
- Logistics job
- Delivery fee calculation

### Idempotency

- **Payments** — idempotent per `idempotencyKey` (safe to retry)
- **Webhooks** — idempotent per `externalId` under a pessimistic row lock (safe for duplicate gateway callbacks)

### Domain events

Order, payment, logistics, inspection, and contact events are published asynchronously. The `NotificationEventListener` picks them up and creates notifications for the relevant users.

---

## Database & Migrations

PostgreSQL schema is managed by **Flyway** (11 migrations, applied automatically on startup):

| Migration | What it creates |
|---|---|
| V1 | Core tables: users, profiles, orders, payments, refresh tokens |
| V2 | Trust/fulfillment: badges, inspections, deliveries, tracking |
| V3 | Adds `ADMIN` role |
| V4 | Communications: contact messages, site visits, audit reports, photos |
| V5 | Payment gateway columns (external_id, gateway_reference) |
| V6 | Products rename, categories, multi-seller orders, cart, driver profiles |
| V7 | Logistics renames, GPS coordinates, proof of delivery |
| V8 | Notifications, disputes, audit logs |
| V9 | Delivery fees, order totals |
| V10 | Refresh token family ID (for rotation tracking) |
| V11 | Canonical username, common region, normalized unique Kenyan phones (Java migration) |

---

## Running Tests

The project has **80 tests** across 18 test suites:

### Unit tests (no Docker needed)

```bash
./mvnw test -Dtest='!*IntegrationTest'
```

These cover domain logic (state machines), security (JWT, rate limiting, CORS), services (orders, auth, password reset), and media storage.

### Integration tests (require Docker)

```bash
# With Docker Desktop (standard socket):
./mvnw test

# With Colima (macOS):
export DOCKER_HOST=unix:///Users/mac/.colima/default/docker.sock
./mvnw test
```

Integration tests use **Testcontainers** to spin up real PostgreSQL 16 and Redis 7 instances, then run full end-to-end flows:
- Marketplace flow (supplier orders → farmer verifies payment)
- Auth flow (signup → refresh → logout with token rotation)
- Admin flow (bootstrap signup → dashboard → user management)
- Trust & fulfillment flow (order → logistics → inspection → badges → delivery)
- Extended fulfillment (contact messages, site visits, audit reports, webhooks)

> **Colima users:** Set `DOCKER_HOST` to the Colima socket path. Ryuk (Testcontainers' cleanup reaper) is disabled automatically in the surefire config because Colima's VM doesn't support bind-mounting the Docker socket.

### Test results

```
Tests run: 80, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

---

## Client Applications

The backend serves three client apps under `client/`:

| Client | Audience | Stack | Location |
|---|---|---|---|
| **Main App** | Farmers, Buyers, Suppliers, Inspectors | React Native (Expo) | `client/mobile/` |
| **Driver App** | Drivers | React Native (Expo) | `client/driver/` |
| **Admin Portal** | Administrators | React (Vite) | `client/admin/` |

All three communicate with the backend at `/api/v1`. See `client/README.md` for setup and API URL configuration.

---

## Environment Variables

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/kilivana` | PostgreSQL JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / `postgres` | Database credentials |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis for caching + rate limiting |
| `JWT_SECRET` | *(empty — required in prod)* | HS256 signing secret (min 32 bytes) |
| `JWT_ACCESS_TOKEN_MINUTES` | `15` | Access token TTL |
| `JWT_REFRESH_TOKEN_DAYS` | `14` | Refresh token TTL |
| `ADMIN_BOOTSTRAP_KEY` | *(empty — admin signup disabled)* | Enables `POST /admins/signup` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Allowed web origins |
| `RATE_LIMIT_ENABLED` | `true` | Enable rate limiting |
| `MEDIA_ROOT` | `./uploads` | Local file storage root |
| `PASSWORD_RESET_URL` | `http://localhost:3000/reset-password` | Link in reset emails |
| `OPENAPI_ENABLED` / `SWAGGER_ENABLED` | `false` | Expose API docs |
| `DOCKER_HOST` | *(auto-detected)* | Docker socket path (for tests) |

Full configuration: `src/main/resources/application.yml`.

---

## Project Structure

```
KILIVANA/
├── src/
│   ├── main/
│   │   ├── java/com/kilivana/
│   │   │   ├── auth/           ← Authentication module
│   │   │   ├── users/          ← User profiles & roles
│   │   │   ├── products/       ← Product catalog
│   │   │   ├── orders/         ← Cart, checkout, order state machine
│   │   │   ├── payments/       ← Payment lifecycle
│   │   │   ├── paymentgateway/ ← Payment provider abstraction
│   │   │   ├── logistics/      ← Delivery jobs & tracking
│   │   │   ├── inspection/     ← Quality inspections
│   │   │   ├── badges/         ← Trust badges
│   │   │   ├── notifications/  ← Event-driven notifications
│   │   │   ├── disputes/       ← Order disputes
│   │   │   ├── messages/       ← Contact form
│   │   │   ├── audit/          ← Audit logging
│   │   │   ├── admin/          ← Admin dashboard
│   │   │   ├── media/          ← File storage
│   │   │   ├── security/       ← JWT, rate limiting, CORS
│   │   │   └── common/         ← Shared infrastructure
│   │   └── resources/
│   │       ├── application.yml ← Main configuration
│   │       └── db/migration/   ← Flyway migrations (V1–V11)
│   └── test/java/com/kilivana/ ← 80 tests (unit + integration)
├── client/
│   ├── mobile/                 ← React Native (Expo) main app
│   ├── driver/                 ← React Native (Expo) driver app
│   └── admin/                  ← React (Vite) admin portal
├── docs/
│   ├── implementation.md       ← Detailed implementation reference
│   ├── spec/spec.json          ← OpenAPI specification
│   └── index.html              ← Swagger UI
├── scripts/
│   └── run-dev.sh              ← One-command dev runner
├── auth-schemas-json.txt       ← Auth schemas for Android (JSON)
├── auth-schemas-for-android.txt ← Auth schemas for Android (detailed)
├── pom.xml                     ← Maven build
└── mvnw / mvnw.cmd             ← Maven wrapper
```

---

## Exposing the API Publicly (ngrok)

```bash
export CORS_ALLOWED_ORIGINS="https://your-app.ngrok-free.dev"
./mvnw spring-boot:run
# in a second terminal:
ngrok http 8080
```

Point mobile/driver apps at the tunnel via `extra.apiUrl` in `app.json` and the admin portal via `VITE_API_URL`.

---

## Further Reading

- **Implementation details:** `docs/implementation.md`
- **OpenAPI spec:** `docs/spec/spec.json`
- **Auth schemas for Android:** `auth-schemas-for-android.txt` and `auth-schemas-json.txt`
- **Client setup:** `client/README.md`
