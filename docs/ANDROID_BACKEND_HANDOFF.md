# Android backend handoff

> **PORTING REFERENCE — NOT A MERGE-COMPATIBLE MIGRATION SET.**
> This source uses Java **V11__account_identity** and SQL **V12__farm_produce_category_seed.sql**.
> Another known integration line already uses different V11/V12 migrations.
> **Port the logic into versions appropriate to YOUR backend history. Do not copy these version numbers blindly. Do not use Flyway repair to force compatibility.**

## 1. Purpose

This branch provides reference/source functionality for migrating Android-required behavior into the shared e-commerce/admin backend. It starts at commit `627ed1e37835946ed321a9be0481ee62714a0f2e`, with the audited product/category/image/error changes and regression tests. It is not a request to merge its database history unchanged.

All endpoint paths below use the `/api/v1` prefix. Protected requests use an access token through Bearer authentication. Never include real credentials or tokens in handoff files or logs.

## 2. Auth contract

- `POST /auth/signup` (`/auth/register` alias): required `email`, `password` (8–72 characters), `fullName` (at most 100), `phoneNumber`, `username`, `region`, and `role`.
- Public roles: BUYER, FARMER, SUPPLIER, INSPECTOR. FARMER requires nonblank `farmName`; SUPPLIER requires nonblank `businessName`; INSPECTOR requires nonblank `employeeCode` (at most 100). DRIVER/ADMIN are not public signup roles. Inspectors start ACTIVE with PENDING verification; Inspector privileges require VERIFIED.
- Username is trimmed/lowercased, globally unique, and matches `[a-z][a-z0-9_]{2,29}`. Region is trimmed, nonblank, at most 200 characters, and required for every public role. Legacy migrated accounts may have null region until updated.
- Phone accepts valid Kenyan numbers, normalizes to E.164 `+254...`, rejects extensions/non-Kenyan numbers, and is globally unique after normalization. Email is trimmed/lowercased and checked for uniqueness.
- Deprecated Farmer `farmLocation` must match region on signup when supplied. On profile update it is a Farmer-only compatibility alias; if both values are supplied they must agree.
- `POST /auth/login`: JSON `identifier` and `password`. Identifier accepts email or Kenyan phone. **Username is NOT a login identifier.** Invalid credentials/inactive users are rejected.
- `POST /auth/refresh`: JSON `refreshToken`. Valid refresh rotates it, revokes the presented token, and retains its family. Refresh tokens are stored as hashes. Revoked, expired, unknown, or inactive-user tokens are rejected; source includes family-revocation logic.
- `POST /auth/logout`: JSON `refreshToken`; a recognized token revokes all refresh tokens belonging to that user. Logout does not immediately invalidate already-issued stateless access JWTs; they expire normally.
- Auth responses expose `userId`, `email`, `username`, `region`, `phoneNumber`, `fullName`, `role`, `verificationStatus`, `accessToken`, `refreshToken`, and `refreshTokenExpiresAt`. `GET /auth/me` returns the current user profile. Preserve identity, role/verification, and session fields when porting.
- Default access lifetime is 15 minutes; refresh lifetime is 14 days, configurable. Auth token responses use Cache-Control no-store.

See [account/auth contract](account-auth-contract.md) for detailed profile and migration behavior. Verify refresh-family revocation persists under the receiving backend's actual transaction boundaries; existing reuse coverage is a service unit test.

## 3. Farmer product contract

| Endpoint | Behavior |
|---|---|
| POST /products | Owner authenticated as FARMER/SUPPLIER; creates product. FARMER becomes sellerType FARMER, sector FARM_PRODUCE. Initial status ACTIVE. |
| GET /products/mine | Authenticated owner's products, **including hidden and other non-ACTIVE products**. |
| GET /products/{productId} | ACTIVE is publicly visible; other statuses require owner, ADMIN, or verified Inspector. Unauthorized visibility returns 404. |
| PUT /products/{productId} | Owner-only partial update; omitted/null values preserve current values. |
| DELETE /products/{productId} | Owner-only **unlist**, sets HIDDEN, returns 200 with no body. Does not physically delete product, images, or order history. |
| PATCH /products/{productId}/status | ADMIN-only moderation; supports ACTIVE/HIDDEN, not Farmer relisting. |
| GET /products/{productId}/images | Image metadata under the same product visibility rules. |

Create fields: `title` (nonblank, at most 150), `description` (nonblank, at most 1000), `unit` (nonblank, at most 30), `unitPrice` (at least 0.01), `availableQuantity` (at least zero), optional `categoryId`, optional `lowStockThreshold` (at least zero), optional `minimumOrderQuantity` (at least 0.01, defaults to one).

Response uses **name**, although requests use **title**. Preserve `productId`, `sellerId`, seller information, sector/status, category ID/name, name/description/unit, price, available/reserved/sold quantities, thresholds/minimum quantity, lowStock, images, and createdAt.

Statuses: ACTIVE, INACTIVE, HIDDEN, PENDING_APPROVAL. Updating an ACTIVE product to zero available quantity changes it to INACTIVE. Reservation exhaustion also makes it INACTIVE; releasing stock can reactivate INACTIVE. A quantity edit does not automatically relist HIDDEN or reactivate every non-ACTIVE state. Preserve these rules rather than assuming DELETE means removal from /mine.

## 4. Category contract

- Public `GET /categories?sector=FARM_PRODUCE` lists active FARM_PRODUCE categories, sorted by name. Without sector it lists active categories across sectors.
- Product category remains optional at backend creation for legacy compatibility. Android's current crop editor requires a category selection.
- A supplied categoryId must exist (404 if missing), be active (409 if inactive), and match product sector (409 if mismatched). Farmers cannot select AGRI_INPUTS categories; Suppliers cannot select FARM_PRODUCE categories.
- Edit: omitted or null categoryId preserves the existing category. Other omitted/null edit fields also preserve values, including lowStockThreshold. This request cannot clear category/threshold through null.
- Seed names: **Cereals & Grains; Pulses & Legumes; Vegetables; Fruits; Roots & Tubers; Nuts & Oilseeds; Herbs & Spices; Coffee & Tea**.
- Seed logic preserves normalized equivalent existing names/UUIDs and references, reactivates an inactive match, and inserts missing categories with fixed UUIDs. Ambiguous duplicates or conflicting fixed UUIDs abort the transaction. Consequently, do not assume all seeded names always have the fixed UUID in an existing database.

## 5. Product image contract

- `POST /products/{productId}/images/upload`: multipart/form-data with part **file**; authenticated owner only. Returns 200 with `imageId`, `fileName`, `contentType`, `sizeBytes`, `url`, `sortOrder`.
- `GET /products/{productId}/images/{imageId}/content`: raw bytes with validated media content type, no-store, and nosniff. ACTIVE products are public; non-ACTIVE products are accessible only to owner/ADMIN/verified Inspector. The image must belong to the requested product and have an uploaded-content URL.
- Accepted content types: image/jpeg, image/png, image/gif, image/webp. Storage validates content signatures and declared type, rejects empty/oversized/unsupported files, and creates server-generated filenames.
- Default storage limit: **5,242,880 bytes (5 MiB)**. Multipart defaults: 5MB file and 6MB request. Configure storage and servlet limits coherently.
- Maximum **10 images per product**, including legacy metadata registrations. Product locking protects concurrent uploads; rollback cleanup removes newly stored files after database rollback.
- Legacy `POST /products/{productId}/images` registers JSON metadata/URL; it does not upload bytes. Content endpoint serves uploaded images, not arbitrary externally registered URLs.
- Database persists image metadata/storage key; bytes persist separately through MediaStorage (currently local filesystem). Use a durable, writable media root, preserve storage keys, and back up metadata and bytes together. A database-only deployment cannot reproduce existing image content.
- Android consumes returned relative content URLs; resolve them against the API server origin without accidentally duplicating /api/v1.

## 6. Seller order contract

Android Farmer Orders uses authenticated **GET /orders/mine**. Backend returns all orders where actor is buyer OR seller, without a status filter. Response includes `orderId`, `sellerId`, `buyerId`, status, totals/currency, timestamps, and items containing `productId`, productTitle, quantity, unitPrice, lineTotal.

Android filters sellerId to the current Farmer, optionally selects orders containing the productId, then counts **distinct order IDs**. It does not count item rows, quantity, or only successful orders.

**All statuses are included:** PLACED, CONFIRMED, READY_FOR_PICKUP, IN_PROGRESS, DELIVERED, COMPLETED, CANCELLED, REJECTED. This is proven by the absence of a status predicate in backend myOrders/repository and Android cropOrders, plus Android `CropManagementTest.orderCountIsDistinctSellerOrdersAcrossAllStatusesNotQuantity`, which explicitly includes CANCELLED and COMPLETED and excludes other sellers/products. The Android source/test was inspected separately; it is not copied into this backend branch.

There is no dedicated per-product order-count endpoint in this source. Preserve order/item IDs and unfiltered seller history. Android treats unavailable order data as unknown, not zero.

## 7. My Crops regression

GET /products/mine previously could fail with Category LazyInitializationException when product DTO mapping read category.name after the repository persistence context closed. Open-in-view is false.

ProductRepository now fetches **owner and category** for findByOwnerId, findByStatusAndOwnerId, and findWithOwnerById. Keep this fetch requirement or map DTOs inside an appropriate transaction in the receiving backend. Do not rely on open-in-view to hide the regression.

ProductCategoryIntegrationTest exercises category assignment followed by /mine with image metadata and verifies categoryName, ownership, sector, and image fields.

## 8. Error contract

Unexpected exceptions return sanitized 500 INTERNAL_ERROR with a generic message; internal exception text/stack traces stay server-side. ApiError includes code, message, status, path, requestId, timestamp, fieldErrors.

RequestContextFilter validates/preserves supplied X-Request-Id or generates one, stores it in request context/MDC, and returns it as a header. GlobalExceptionHandler logs the full unexpected exception with the **same requestId** returned in the error body/header. Preserve correlation through the receiving backend's filter/handler stack and avoid logging credentials/tokens.

## 9. Database / Flyway warning

**Do not merge/copy migration version numbers blindly and do not use Flyway repair to force compatibility.**

This reference line contains V1–V10 SQL, Java `src/main/java/db/migration/V11__account_identity.java` (explicit checksum 1102), and `src/main/resources/db/migration/V12__farm_produce_category_seed.sql`.

Known other line: `origin/feature/google-auth-and-deploy-hardening` at cached commit 9379853 uses V11__one_live_payment_per_order.sql and V12__payment_verification_attribution.sql (plus V13–V16). **Both V11 and V12 conflict.**

The source machine's persistent database has the category seed applied as V12, corroborated by a historical application log. This handoff does not change that database/history and is not a fresh verification of its history table.

Receiving developer MUST port account schema/backfill/constraints and category seed logic into migrations appropriate to HIS backend history. Review existing phones, username constraints, categories, IDs and references first; V11 fails on invalid/colliding normalized phones. Preserve already-applied histories and plan deployment per environment. V12 is intentionally retained unchanged here for inspection.

README's inherited V1–V11 count predates the uncommitted seed; this handoff is authoritative for this branch's additional V12.

## 10. Infrastructure

- Java 17+, Maven wrapper/build definition; account normalization depends on libphonenumber.
- PostgreSQL (project uses PostgreSQL 16); Redis (Redis 7) for configured cache/rate limiting. Supply DB_URL, DB_USERNAME, DB_PASSWORD and REDIS_HOST/REDIS_PORT through secure runtime configuration.
- Supply JWT_SECRET, JWT issuer/audience/lifetimes, and ADMIN_BOOTSTRAP_KEY securely. Do not reuse development defaults. Configure CORS, trusted proxy settings, rate limits, mail/reset URLs and enabled API docs for the deployment.
- MEDIA_ROOT must be persistent and writable; MEDIA_MAX_FILE_BYTES, MEDIA_MAX_FILE_SIZE, MEDIA_MAX_REQUEST_SIZE control storage/multipart limits.
- docker-compose.yml is the committed base version; it does not include this Windows machine's local 5433 override.
- Tests use IntegrationTestSupport's disposable PostgreSQL/Redis Testcontainers with dynamic connection properties, not the persistent local database. Never run this reference's Flyway against localhost:5433/kilivana.
- Tests require Docker. pom.xml disables Ryuk; track/remove only test-owned disposable containers after testing, never persistent containers. Test media uses a temporary directory.

## 11. Porting checklist

- [ ] Adapt migrations to receiving history, review legacy data, and validate on disposable databases without repair.
- [ ] Signup/profile changes preserve username, region, Kenyan phone normalization and uniqueness; verify role-specific fields and Inspector verification.
- [ ] Email/phone identifier login works; username login remains unsupported.
- [ ] Refresh rotates correctly; invalid/reused token rejection and family invalidation persist transactionally; logout prevents further refresh.
- [ ] Product create/mine/details/edit retain Android field names and numeric semantics.
- [ ] DELETE hides; /mine still includes hidden; unauthorized non-ACTIVE details/images remain invisible.
- [ ] Categories list/assignment enforce activity/sector, allow backend legacy null, and preserve omitted/null edits.
- [ ] All eight seed names are available without breaking existing category/product references.
- [ ] /mine with category and images succeeds with open-in-view disabled.
- [ ] Multipart upload/content work; size/types/ten-image limit, concurrency, ownership, rollback cleanup and durable storage hold.
- [ ] Seller orders retain all statuses and product IDs; count distinct seller order IDs, including cancelled/rejected.
- [ ] Unexpected errors correlate client reference/header and server stack trace without exposing internal details.
- [ ] Run included backend tests against disposable infrastructure and Android smoke/regression tests against the port.

## 12. Tests

| Included test | Coverage |
|---|---|
| ProductCategoryIntegrationTest | Seeded API, optional/matching category, active/sector/missing validation, partial/null edits, categorized My Crops with images. |
| CategorySeedMigrationIntegrationTest | Fresh/existing DB, repeat execution, identity/reference preservation, ambiguity and UUID-collision rollback. |
| ProductImageUploadIntegrationTest | HTTP upload/content, authorization/type checks, concurrent ten-image cap, hidden visibility, transaction/file rollback. |
| service/ProductImageServiceTest | Metadata/content mapping, limit, ownership, stream closure and cleanup failures/rollback. |
| common/GlobalExceptionHandlerTest | Sanitized unexpected error and matching logged/body/header reference with stack trace. |
| AccountIdentityIntegrationTest; AccountMigrationIntegrationTest; users/AccountIdentityTest | Signup/profile/login identity rules and migration/backfill/constraints. |
| AuthFlowIntegrationTest; security/AuthServiceTest; security/RefreshTokenSecurityTest | Auth flow, role validation, token rotation/reuse service logic. |
| MarketplaceFlowIntegrationTest; FulfillmentExtensionsIntegrationTest; service/OrderServiceTest; domain/ProductTest | Marketplace/order behavior, seller authorization/status changes and inventory/product rules. |
| media/LocalFileStorageTest; remaining security/Inspector tests | Media validation/storage and inherited authentication/authorization boundaries. |

LocalMyCropsDiagnosticTest is deliberately excluded. The portable My Crops regression uses disposable data. Test results from the handoff preparation are reported separately; historical target files are not part of the source package.
