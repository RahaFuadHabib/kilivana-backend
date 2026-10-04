# Kilivana — Marketplace Implementation Plan

## 1. Overview

Kilivana is an agricultural commodity marketplace. It connects **buyers**, **suppliers**, **farmers**, and **inspectors** so that crop produce and processed goods can be listed, ordered, paid for, inspected for quality, and delivered end-to-end with verifiable trust.

The backend is a **modular monolith**: one deployable Spring Boot application, organised into independent feature modules that communicate through the domain layer. This gives the simplicity of a single codebase with the discipline of bounded contexts.

- **Stack:** Java 17, Spring Boot 3.3.x, Spring Security + JWT, Spring Data JPA, Flyway, PostgreSQL, Redis (cache), Testcontainers, JUnit 5 + AssertJ + Mockito.
- **Package root:** `com.kilivana`
- **Docs / spec:** this file.

## 2. Roles

| Role      | What they do                                                              |
|-----------|---------------------------------------------------------------------------|
| `BUYER`   | Purchase packaged/processed **product** listings from suppliers.          |
| `SUPPLIER`| Purchase **crop** listings from farmers; sell their own product listings. |
| `FARMER`  | List and sell **crop** produce; request site-visit certification.         |
| `INSPECTOR`| Schedule and perform on-site **quality inspections** and **site visits**; issue **audit reports**; award badges. |
| `ADMIN`   | Manage user accounts (suspend/activate) and contact messages. Provisioned via the inner admin signup (bootstrap key). |

Role rules are enforced at the service layer:
- Buyers may only order `PRODUCT` listings; suppliers only `CROP` listings; nobody may order their own listing.
- Listing creation is type-checked: a `FARMER` can only create `CROP` listings, a `SUPPLIER` only `PRODUCT` listings.

## 3. Module breakdown

| Module         | Responsibility                                                        |
|----------------|------------------------------------------------------------------------|
| `common`       | `BaseEntity`, shared exceptions, `DomainEvent` types, async + event dispatch, cache configuration (`@EnableCaching`, Redis). |
| `security`     | JWT access/refresh tokens, request context filter, `CurrentUser`/`AuthenticatedUser`, password hashing, opt-in rate limiting. |
| `auth`         | Signup, login, refresh, logout (voids all of a user's refresh tokens). |
| `admin`        | Inner domain for provisioning `ADMIN` accounts via a bootstrap key + dashboard/reports/monitoring API. |
| `users`        | Users, profiles (buyer/supplier/farmer/inspector/driver), role/status management, driver availability. |
| `addresses`    | Buyer/seller delivery and pickup addresses with optional coordinates. |
| `products`     | Crop/input listings (`sellerType` FARMER/SUPPLIER), sectors, categories, images, stock reservation, moderation, admin browse. |
| `orders`       | Multi-seller cart → customer order split into per-seller sub-orders; per-sub-order subtotal/delivery fee/total; state machine; cancelling/rejecting auto-releases reserved stock and refunds verified payments; publishes `OrderStatusChangedEvent`. |
| `payments`     | Payment lifecycle + account ledger (inbound/outbound); marks the parent customer order PAID when all sub-orders are covered. |
| `paymentgateway` | Gateway abstraction (`PaymentGateway`), M-PESA stub, idempotent webhook handler (pessimistic lock on `externalId`). |
| `logistics`    | Delivery job lifecycle + auditable tracking-event trail; admin driver assignment + driver acceptance. |
| `inspection`   | Inspections (configurable checklists), site-visit requests, audit reports, visit photos (media storage), next-inspection scheduling. |
| `badges`       | Badges and user badges; badge auto-awarding (e.g. a passed inspection awards `QUALITY_HARVEST`).
| `notifications`| Event-driven notifications (order, payment, logistics, disputes); unread/read state, bulk read. |
| `disputes`     | Raise, escalate, and resolve disputes against orders with admin involvement. |
| `messages`     | Public contact-message intake; admin inbox (open/resolve).              |
| `media`        | Media storage abstraction (`MediaStorage`, local filesystem impl).      |
| `audit`        | Controller interceptor records administrative + business actions; query endpoint. |

Each module owns its `domain`, `repository`, `service`, and `api` (DTOs + controller). Cross-module access goes through services and repositories only.

## 4. State machines

`PLACED ─> CONFIRMED ─> READY_FOR_PICKUP ─> IN_PROGRESS ─> DELIVERED ─> COMPLETED`

```
PLACED           : buyer may CANCELLED;         seller may CONFIRMED or REJECTED
CONFIRMED        : seller may READY_FOR_PICKUP or IN_PROGRESS; buyer may CANCELLED
READY_FOR_PICKUP : seller may IN_PROGRESS (handed to driver); buyer may CANCELLED
IN_PROGRESS      : seller may DELIVERED;        buyer may CANCELLED
DELIVERED        : buyer may COMPLETED
COMPLETED / CANCELLED / REJECTED : terminal
```

- **Seller** may move to `CONFIRMED`, `READY_FOR_PICKUP`, `IN_PROGRESS`, `DELIVERED`, `REJECTED`.
- **Buyer** may move to `CANCELLED`, `COMPLETED`.
- Moving to `CANCELLED` or `REJECTED` releases the reserved quantity back to the listing **and** auto-refunds every `VERIFIED` payment on the order (`Payment.refund() → REFUNDED`).
- Each sub-order carries `subtotal`, `delivery_fee` (server-computed) and `total = subtotal + delivery_fee`.
- A `CustomerOrder` (one checkout) is split into one sub-order per seller; it becomes `PAID` once every sub-order is covered by a verified payment.

### 4.2 Delivery

```
PENDING_ACCEPTANCE ─> ACCEPTED ─> AT_PICKUP ─> PICKED_UP ─> IN_TRANSIT ─> DELIVERED  (records deliveredAt)
                      └> CANCELLED      └> FAILED   └> FAILED   └> FAILED
```

Drivers see `PENDING_ACCEPTANCE` jobs (`/api/v1/logistics/jobs/available`) and accept or decline; an admin may pre-assign a driver via `POST /api/v1/logistics/jobs/{id}/assign`. `AT_PICKUP`/`PICKED_UP`/`IN_TRANSIT` also accept `FAILED`. Terminal states (`DELIVERED`, `FAILED`, `CANCELLED`) cannot transition further. Logistics events are auditable through `tracking_events`.

### 4.3 Inspection

```
SCHEDULED ─> IN_PROGRESS ─> PASSED  (records rating 1-5, findings, performedAt)
                         └> FAILED
```

A `PASSED` inspection automatically awards the owner the `QUALITY_HARVEST` badge and publishes `InspectionCompletedEvent`.

### 4.4 Payment

```
            ┌─> VERIFIED ─> REFUNDED
PENDING ────┤
            └─> FAILED
```

`markVerified()` (PENDING→VERIFIED), `markFailed()` (PENDING→FAILED), `refund()` (VERIFIED→REFUNDED). Sellers manage inbound payments (verify/fail/refund); buyers track outbound payments. Gateway confirmation arrives via the payment webhook and publishes `PaymentStatusChangedEvent`.

### 4.5 Site visit

```
REQUESTED ─> SCHEDULED ─> COMPLETED
          └> REJECTED
```

Farmers request visits on their own listings; inspectors schedule/complete; inspectors or the requesting farmer may reject.

## 5. Trust & fulfillment: badges

Seeded badges:

| Code                 | Description                                        |
|----------------------|----------------------------------------------------|
| `QUALITY_HARVEST`    | Listing passed a site inspection (auto-awarded).   |
| `TRUSTED_SUPPLIER`   | Recognised supplier.                               |
| `CERTIFIED_FARM`     | Certified farm.                                    |
| `RELIABLE_BUYER`     | Trusted buyer.                                     |
| `COMMUNITY_PARTNER`  | Community partner.                                 |

`BadgeService.awardBadge` is idempotent per user-badge pair (duplicate awards are a no-op / conflict). Verified Inspectors award badges; `QUALITY_HARVEST` is granted automatically on a passed inspection.

## 6. Security

- `POST /api/v1/auth/signup` and `/api/v1/auth/register` are aliases. Public roles: `BUYER`, `FARMER`, `SUPPLIER`, `INSPECTOR`; `DRIVER` and `ADMIN` are rejected.
- Inspector signup requires a nonblank `employeeCode` (maximum 100 characters, trimmed, no uniqueness or format restriction). The user and Inspector profile are created atomically as `ACTIVE` / `PENDING`. Other signup roles retain their existing verification defaults.
- Signup, login and refresh return authoritative `verificationStatus` (`UNVERIFIED`, `PENDING`, `VERIFIED`) alongside the existing token fields. Pending Inspectors can log in; Android can also read `/api/v1/auth/me` or `/api/v1/users/me` to check current verification.
- Admin approval: `PATCH /api/v1/users/{userId}/verification`, Admin bearer token, JSON `{"verificationStatus":"VERIFIED"}`. Returns the updated `UserProfileResponse`; non-Admins receive `403 FORBIDDEN`. No additional approval endpoint is needed.
- Inspector privileges require both Inspector role and VERIFIED state loaded from the database on each authenticated request. Approval/revocation takes effect on subsequent requests even with existing tokens. This covers inspections, site visits, audit reports, inspection photos, badge awards and inactive product/image access. Existing ownership and Farmer/Supplier permissions remain.
- Unauthorized Inspector operations return the standard `403 FORBIDDEN` error; inactive products/images retain the existing `404` concealment behavior. Public catalog and authenticated nonprivileged capabilities remain available.
- `POST /api/v1/admins/signup` — public; the *inner domain* endpoint that provisions `ADMIN` accounts. It requires the `X-Admin-Bootstrap-Key` header matching `kilivana.security.admin.bootstrap-key` (env `ADMIN_BOOTSTRAP_KEY`, no default). Not configured ⇒ endpoint returns 403, so admin provisioning is disabled out of the box. Reuses `AuthService.registerAndAuthenticate` and issues the standard token pair.
- `POST /api/v1/contact-messages` and `POST /api/v1/webhooks/**` — public by design.
- `GET /api/v1/products/**`, `GET /api/v1/categories/**`, `GET /api/v1/badges/**`, `GET /actuator/health/**` — public read endpoints.
- `POST /api/v1/auth/login`, `/refresh`, `/logout`; logout revokes **all** refresh tokens belonging to the user.
- Access tokens are signed JWTs (HS256), 15 min, issued by `kilivana-backend`. The signing secret **must** be supplied via `JWT_SECRET` (`kilivana.security.jwt.secret`); `JwtService` refuses to start with a blank secret.
- Mutating endpoints resolve the actor from the token via `CurrentUser.required()` and enforce role/ownership rules.
- Suspended users are rejected at the JWT filter (`RuntimeException` → cleared context → 401).
- Optional per-IP rate limiting (`kilivana.security.rate-limit.enabled`, 30 req/min) is gated off by default.

## 7. Data model & migrations

`flyway` + `ddl-auto: validate` keep entities and schema in lock-step.

- `V1__initial_schema.sql` — users, profiles, listings, orders/order_items, payments, refresh tokens.
- `V2__trust_and_fulfillment.sql` — badges, user_badges, inspections, deliveries + badge seed data.
- `V3__admin_role.sql` — extends the `users.role` check constraint to include `ADMIN`.
- `V4__communications_and_evidence.sql` — `contact_messages`, `site_visit_requests`, `audit_reports`, `visit_photos`, `delivery_logs` + supporting indexes.
- `V5__payments_gateway.sql` — adds `external_id` (unique) and `gateway_reference` columns to `payments` for gateway correlation.
- `V6__products_and_multi_seller_orders.sql` — renames `listings → products` (FKs rewritten), DRIVER role + verification status, `categories`/`product_images`/`saved_products`/`driver_profiles`/`addresses`/`password_reset_tokens`, multi-seller `customer_orders` + `cart_items`, inspection `checklists`/`questions`.
- `V7__logistics_renames.sql` — renames `deliveries → logistics_jobs` and `delivery_logs → tracking_events`, normalising legacy statuses into the new lifecycle.
- `V8__communications_disputes_and_audit.sql` — `notifications`, `disputes`, `audit_logs` tables + supporting columns/indexes.
- `V9__delivery_fees_and_pickup_ready.sql` — adds `subtotal`/`delivery_fee`/`total_amount` per sub-order and `READY_FOR_PICKUP` order-status support.
- `V10__refresh_token_family_id.sql` — adds `refresh_tokens.family_id` (backfilled, indexed) for refresh-token family tracking.

Every entity extends `BaseEntity` (`id` UUID, optimistic-lock `version`, `createdAt`/`updatedAt`).

## 8. REST API surface

Controllers under `/api/v1/**` (113 endpoints; full spec via Swagger when `OPENAPI_ENABLED`/`SWAGGER_ENABLED` are on):

```
# auth, users & addresses
POST   /api/v1/auth/signup | /register | /login | /refresh | /logout | /forgot-password | /reset-password
GET    /api/v1/auth/me
POST   /api/v1/admins/signup                          (X-Admin-Bootstrap-Key header)
GET    /api/v1/users/me | /users/{userId};  PUT /users/me
PATCH  /api/v1/users/{userId}/status                  (admin: SUSPENDED/ACTIVE)
PATCH  /api/v1/users/{userId}/verification            (admin sets UNVERIFIED/PENDING/VERIFIED)
GET/POST /api/v1/addresses;  PUT/DELETE /api/v1/addresses/{addressId}

# marketplace
GET/POST /api/v1/products;  GET /products/mine | /saved | /{productId};  PUT/DELETE /products/{productId}
PATCH   /api/v1/products/{productId}/status           (moderation)
POST/DELETE /api/v1/products/{productId}/save;  POST/GET /products/{productId}/images
GET/POST /api/v1/categories
GET    /api/v1/sellers/{sellerId}/products | /profile
POST   /api/v1/cart;  GET /cart/items;  PATCH/DELETE /cart/items/{cartItemId}
POST   /api/v1/cart/checkout;  POST /api/v1/checkout/validate
POST   /api/v1/orders;  GET /orders/mine | /{orderId};  POST /orders/{orderId}/cancel | /confirm-receipt
PATCH  /api/v1/orders/{orderId}/status

# payments
POST   /api/v1/payments                       (idempotency-key, MPESA)
GET    /api/v1/payments/inbound | /outbound | /{paymentId}
POST   /api/v1/payments/verify;  POST /payments/{id}/fail | /refund
POST   /api/v1/webhooks/payments             (public gateway webhook)

# logistics
GET    /api/v1/logistics/jobs/available | /mine | /order/{orderId} | /{jobId}/tracking
POST   /api/v1/logistics/jobs;  POST /jobs/{jobId}/assign | /decline | /proof-of-delivery | /tracking
PATCH  /api/v1/logistics/jobs/{jobId}/status

# trust & evidence
GET/POST /api/v1/inspections;  GET /inspections/checklists | /{inspectionId}
POST   /api/v1/inspections/{inspectionId}/photos;  GET /inspections/{inspectionId}/photos
POST   /api/v1/inspections/{inspectionId}/result
PATCH  /api/v1/inspections/{inspectionId}/status
GET    /api/v1/photos/{photoId};  GET /photos/{photoId}/content
GET/POST /api/v1/site-visits;  GET /site-visits/{visitId}
POST   /api/v1/site-visits/{visitId}/schedule | /complete | /reject
POST   /api/v1/audit-reports/inspection/{inspectionId};  GET /audit-reports/{reportId} | /inspection/{inspectionId}
GET/POST /api/v1/badges/...  (catalog, award by inspector, user badges)

# notifications, disputes, ops
GET    /api/v1/notifications | /unread-count | /{notificationId};  POST .../{id}/read | /read-all;  DELETE .../{id}
GET/POST /api/v1/disputes;  GET /disputes/mine | /{disputeId};  POST /{disputeId}/escalate | /resolve
POST   /api/v1/contact-messages                 (public intake)
GET    /api/v1/contact-messages | /{messageId};  PATCH /{messageId}/resolve   (admin)
GET    /api/v1/admin/dashboard | /users | /products | /orders | /payments | /logistics/jobs | /disputes | /reports | /audit-logs
GET    /actuator/health | /info | /metrics | /prometheus
```

JSON payloads use request records with Jakarta Bean Validation (`@NotNull`, `@NotBlank`, `@DecimalMin`, `@Email`, `@Future`, etc.). Errors use a flat envelope: `code`, `message`, `status`, `path`, `requestId`, `timestamp` (`409 BUSINESS_CONFLICT`, `403 FORBIDDEN`, `404 NOT_FOUND`, `429 RATE_LIMITED`).

## 9. Caching, events & observability

- **Redis**: available for distributed state (`spring.cache.type: redis`, rate-limiting backend) and used by the dev/test setup alongside Postgres. Application-level `@Cacheable` usage is intentionally minimal today.
- **Domain events**: `DomainEvent` types plus an async `ApplicationEventMulticaster` (`kilivanaEventExecutor`). Published: `OrderStatusChangedEvent`, `PaymentStatusChangedEvent`, `InspectionCompletedEvent`, `ContactMessageReceivedEvent`, `LogisticsJobStatusChangedEvent`; logistics also records `TrackingEvent`s. Notifications are driven off these lifecycle changes; `DomainEventLogger` records every emission.
- **Observability**: Spring Boot Actuator with Prometheus registry — `/actuator/health`, `/info`, `/metrics`, `/prometheus`.

## 10. Testing strategy

- **Integration tests** (Testcontainers Postgres + Redis, Spring Boot): end-to-end flows for authentication, marketplace, trust & fulfillment, and the fulfillment extensions (contact messages, site visits, audit reports, delivery logs, payment webhook).
  - `IntegrationTestSupport` boots one Postgres and one Redis container per JVM; each test truncates the Postgres tables and flushes Redis. **`badges` is seeded by Flyway and never truncated.**
- **Pure domain unit tests:** `Listing`, `Delivery`, `Inspection`, `Payment` — state-transition rules, guards, invariants.
- **Service unit tests (Mockito):** `OrderService` — role authorisation, create + reserve behaviour, cancel/reject releasing stock, and auto-refund of verified payments.
- Environment requirement for Testcontainers on this machine: a running Colima Docker engine via the docker socket, with `DOCKER_API_VERSION=1.44` and `-Dapi.version=1.44` (engine API min 1.40, testcontainers default 1.32 fails) and `TESTCONTAINERS_RYUK_DISABLED=true`.

## 11. Open / deliberate simplifications

- The M-PESA adapter is a stub (`MpesaStubPaymentGateway`) that returns `SUCCESS` synchronously; `PaymentGateway` lets real providers (M-PESA, Stripe) slot in behind the webhook endpoint.
- Media storage is a local-filesystem implementation of `MediaStorage`; object storage (S3) would be a drop-in replacement.
- Rate limiting defaults to a Redis-backed fixed-window counter (`RATE_LIMIT_BACKEND=redis`, `matchIfMissing`); set `RATE_LIMIT_BACKEND=memory` for a single-instance in-memory counter.
- Multi-seller carts are implemented; the public catalog browse (`GET /products`) returns an unpaged list (clients paginate client-side), while admin list endpoints use Spring Data pagination.
- Email is delivered only once `MAIL_HOST`/`MAIL_USERNAME`/`MAIL_PASSWORD` are configured (dev runs disable the mail health check via `MANAGEMENT_HEALTH_MAIL_ENABLED=false`).
## Canonical account identity (V11)

See [account/auth contract](account-auth-contract.md) for required username and region, Kenyan phone normalization and uniqueness, identifier-based email/phone login, profile update semantics, and the V10-to-V11 migration/backfill.
