# Account and authentication contract

Public registration (`POST /api/v1/auth/signup` or `/register`) requires email,
password, fullName, phoneNumber, username, region and role. Public roles remain
BUYER, FARMER, SUPPLIER and INSPECTOR. Additional required fields are farmName
for FARMER, businessName for SUPPLIER, and employeeCode for INSPECTOR.

```json
{
  "email": "farmer@example.com",
  "password": "secretPass1",
  "fullName": "Jane Farmer",
  "phoneNumber": "0712345678",
  "username": "jane_farmer",
  "region": "Kiambu",
  "role": "FARMER",
  "farmName": "Green Farm"
}
```

Username is trimmed and lowercased with Locale.ROOT, has length 3-30, and matches
exactly `[a-z][a-z0-9_]{2,29}`: the first character is a lowercase ASCII letter;
remaining characters are lowercase ASCII letters, digits or underscore.
It is globally unique across roles, including staff.
Valid examples: `amara`, `amara_osei`, `farmer123`. Invalid examples: `A`,
`123farmer`, `farmer-name`, `farmer name`, `abcdefghijklmnopqrstuvwxyz12345`.
Username can be changed through the owner profile endpoint; it is never used
to look up a login account. Region is trimmed, nonblank, at most 200 characters,
and common to all roles. No county allowlist or location guessing is applied.

Phones use libphonenumber 9.0.40 with default region KE and full Kenyan validity
checking. National, national-without-trunk, 254 and +254 representations of a
valid Kenyan number normalize to E.164 (for example +254712345678). Spaces,
hyphens and parentheses are accepted. Foreign numbers, alphabetic input and
extensions are rejected. Canonical phones are globally unique, including staff.

`POST /api/v1/auth/login` accepts only this request contract:

```json
{"identifier":"farmer@example.com","password":"secretPass1"}
```

```json
{"identifier":"0712345678","password":"secretPass1"}
```

Identifiers containing @ use trimmed, case-insensitive email resolution. Other
identifiers must normalize to a valid Kenyan phone and use exact canonical phone
resolution. Both paths use the existing email-based AuthenticationManager for
password and account-status checks. Unknown accounts, invalid phone identifiers,
username identifiers and wrong passwords receive 401. A legacy email-only JSON
login payload receives 400 because identifier is required. Consumers must change
their login payload from email to identifier.

AuthResponse adds username, region and canonical phoneNumber. Private profile
responses add username and region; phoneNumber is canonical. Existing token,
role, verification and role-profile fields remain. Historical unknown regions
are null (omitted by the configured JSON null policy). Public seller responses
keep their contact-data privacy contract, and Farmer farmLocation reflects region.

`PUT /api/v1/users/me` remains a partial update despite the PUT method. Omitted or
null fields preserve values; supplied blank values for editable required fields
are rejected. Username and phone changes normalize exactly as signup and check
uniqueness excluding the current account. Email is not editable. Region, fullName
and supported role-profile fields are trimmed. Either Driver vehicle field can
be updated independently. Validation or uniqueness failures roll back the entire
update. Invalid data returns 400 and uniqueness conflicts return 409; database
unique constraints also protect against concurrent writers.

## Farmer compatibility

New Farmer signup uses common region instead of farmLocation. farmName is still
required. A deprecated farmLocation may accompany signup only if it matches the
trimmed region. A farmLocation-only signup is rejected because region is required.
Profile updates retain farmLocation as a Farmer-only region alias: it can be
supplied alone, or together with an equal region. Conflicting values are rejected.
New signup and profile updates keep the retained farmer_profiles.farm_location
column synchronized with region. Responses retain farmLocation for compatibility.

## V11 deployment and backfill

V1-V10 are unchanged. V11 is a Java Flyway migration in
`src/main/java/db/migration/V11__account_identity.java`, so backfill uses the same
pinned libphonenumber metadata as the application. It:

1. Validates every existing phone and detects collisions after normalization.
   Invalid or colliding values abort the migration with affected user IDs.
   Resolve the actual phone ownership in V10 data and retry; no account is merged,
   deleted, assigned a fake phone, or chosen arbitrarily.
2. Adds username and region. Existing usernames become `u_` plus the full unsigned
   user UUID encoded in lowercase base 36. This is injective and uses at most 27
   characters (two-character prefix plus at most 25 base-36 digits), so no UUID
   truncation or collision is introduced and every generated name obeys the rule.
3. Copies trimmed nonblank Farmer farmLocation into common region. Other legacy
   accounts retain null region until their owner supplies one. No location is
   fabricated. All new public signups require region.
4. Stores canonical phones and adds global unique constraints for username and
   phone, a lowercase ASCII username check, an E.164 Kenyan phone shape check,
   and a trimmed nonblank check for non-null regions.

PostgreSQL executes V11 transactionally; failure leaves V10 data intact. Historical
farm_location values and credentials are retained. Administrator bootstrap keeps
its existing DTO and gets a generated canonical username; its phone goes through
the same normalization and global uniqueness checks. It is exempt from the public
region requirement.

JWT claims, refresh token hashing/rotation/reuse detection, logout, password reset,
public-role restrictions, Inspector PENDING signup and Admin verification remain
unchanged. Username and phone updates do not change the email JWT identity.
