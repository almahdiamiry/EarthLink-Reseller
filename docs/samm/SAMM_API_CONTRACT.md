# SAMM API Canonical Contract Specification

**Document Version:** 1.0.0  
**Target Backend:** SAMM 5.1.15  
**OpenAPI Specification:** OpenAPI 3.1.0 (`docs/samm/openapi.json`)  
**Base Path:** `/api/v1`  
**Provider Identification:** `ALAMIRY` (behind SAS Seam)  
**Status:** Canonical Authority for SAMM REST Integration  

---

## 1. System Identity & Versioning

* **Software System:** SAMM (Subscriber Access Management & Monitoring)
* **Backend Version:** `5.1.15`
* **Specification Version:** OpenAPI `3.1.0`
* **Canonical API Root:** `/api/v1`
* **Integration Boundary:** All communication from the Android application occurs strictly over HTTPS REST via `/api/v1`. There is **zero** direct MikroTik RouterOS, SSH, RADIUS, or FreeRADIUS socket interaction from the client app.

---

## 2. Base URL Rules & URL Normalization

1. **Format & Scheme:**
   - Must use `https://` in production release builds.
   - Cleartext `http://` is strictly prohibited in release builds (fail-closed security constraint).
   - Local IP/port (e.g. `http://192.168.1.50:8000`) is permitted only in `debug` builds for local lab testing.
2. **Trailing Slash Normalization:**
   - Client configurations may provide URLs with or without trailing slashes (e.g., `https://samm.example.com` or `https://samm.example.com/`).
   - The HTTP client base URL must be normalized by trimming any trailing slash, ensuring that appending `/api/v1/...` results in valid, non-duplicated path separators.
3. **Prefix Discipline:**
   - If a user inputs `https://samm.example.com/api/v1`, the client normalizer must detect the `/api/v1` suffix and avoid producing double paths like `/api/v1/api/v1/...`.
   - The canonical base address configuration should store the host origin (`https://samm.example.com`), and client Retrofit/OkHttp endpoints specify `/api/v1/...`.

---

## 3. Authentication & Scope Model

### 3.1 Bearer Token
* Authentication uses static API Bearer tokens created in the SAMM Web Admin UI:
  ```text
  System -> API (Superadmin only)
  ```
* Header format on every request:
  ```http
  Authorization: Bearer samm_<token_secret>
  ```
* Secret tokens are shown **once** upon creation; the backend stores only the SHA-256 hash.
* If a token is revoked or expired, the backend immediately rejects subsequent requests with HTTP `401 Unauthorized`.
* **Credential Secrecy Rule:** Real tokens, passwords, or hashes must **never** be checked into version control, embedded in unit tests, or emitted into device logcat (`Timber`/`Log`).

### 3.2 Required Scopes
SAMM evaluates per-resource scopes on each token. A `write` scope automatically implies the matching `read` scope.
For complete Earthlink Reseller app parity, the provisioned token must hold at least:
1. `customers:read`: Query subscriber directory and inspect customer records (`GET /customers`, `GET /customers/{id}`).
2. `customers:write`: Create new subscribers, renew accounts, suspend, activate, assign plans, and update passwords.
3. `plans:read`: Query available service catalog (`GET /plans`, `GET /plans/{id}`).
4. *(Optional / Recommended)* `coa:write`: If session disconnect (`POST /customers/{id}/disconnect`) is invoked directly. *(Note: regular customer suspension automatically schedules CoA without needing client-side disconnect).*

### 3.3 Token Introspection / Smoke Test (`GET /api/v1/me`)
Used to verify connectivity and validate permissions upon saving provider settings:
* **Endpoint:** `GET /api/v1/me`
* **Response Schema (`MeResponse`):**
  ```json
  {
    "token_id": 4,
    "name": "reseller-app-pos1",
    "scopes": [
      "customers:read",
      "customers:write",
      "plans:read"
    ],
    "rate_limit_per_min": 120,
    "server_version": "5.1.15"
  }
  ```
* **Required Properties:** `token_id` (Int), `name` (String), `scopes` (List<String>), `rate_limit_per_min` (Int), `server_version` (String).

---

## 4. Customer Endpoints & DTO Specifications

### 4.1 Critical Identifier Discipline: Integer Customer ID
* **SAMM Identity Model:** Unlike EarthLink which frequently uses alphanumeric user IDs (`userId`) and internal indices (`userIndex`), SAMM strictly uses an **integer primary key** (`customer_id: Int`) across all path parameters (`/customers/{customer_id}`).
* **Username Field:** `username: String` is the unique RADIUS login credential.
* **Seam Implication:** The SAS adapter must map or correlate the local account model with SAMM's integer `customer_id` and unique `username`.

---

### 4.2 Customer Listing & Search (`GET /api/v1/customers`)
Used for subscriber directory search, synchronization, and local disappearance reconciliation.

* **Path:** `GET /api/v1/customers`
* **Query Parameters:**
  | Parameter | Type | Required | Default | Description |
  |---|---|---|---|---|
  | `search` | String | No | null | Substring search across username, name, mobile |
  | `username` | String | No | null | Exact username lookup |
  | `status` | String | No | null | Filter by status: `active`, `suspended`, or `expired` |
  | `plan_id` | Int | No | null | Filter by plan ID |
  | `auto_renew` | Boolean | No | null | Filter by auto-renew flag |
  | `expiring_before` | String (ISO-8601) | No | null | Expiry before timestamp |
  | `expiring_after` | String (ISO-8601) | No | null | Expiry after timestamp |
  | `assigned_admin_id`| Int | No | null | Scope to specific admin ID |
  | `assigned_admin` | String | No | null | Scope to admin username |
  | `limit` | Int | No | 100 | Page size (1..1000) |
  | `offset` | Int | No | 0 | Page offset (>= 0) |

* **Response Schema (`ListResponse[CustomerListItem]`):**
  ```json
  {
    "total": 42,
    "items": [
      {
        "id": 105,
        "username": "user_baghdad_01",
        "firstname": "Ahmed",
        "lastname": "Ali",
        "mobile": "07701234567",
        "email": null,
        "status": "active",
        "plan_id": 12,
        "plan_name": "Standard 10M",
        "auto_renew": false,
        "created_at": "2026-08-01T10:00:00Z",
        "expiration_date": "2026-10-01T10:00:00Z",
        "assigned_admin_id": 1,
        "assigned_admin": "superadmin",
        "disabled": false
      }
    ]
  }
  ```

---

### 4.3 Customer Detail Read (`GET /api/v1/customers/{customer_id}`)
* **Path:** `GET /api/v1/customers/{customer_id}`
* **Response Schema (`CustomerResponse`):**
  ```json
  {
    "id": 105,
    "username": "user_baghdad_01",
    "firstname": "Ahmed",
    "lastname": "Ali",
    "mobile": "07701234567",
    "email": null,
    "address": "Baghdad, Karrada",
    "notes": null,
    "status": "active",
    "created_at": "2026-08-01T10:00:00Z",
    "expiration_date": "2026-10-01T10:00:00Z",
    "uptime_budget": null,
    "uptime_used": 142000,
    "original_plan_id": 12,
    "upload_used": 5368709120,
    "download_used": 42949672960,
    "auto_renew": false,
    "assigned_admin_id": 1,
    "disabled": false,
    "mac_address": null,
    "sticky_mac": null,
    "framed_ip_address": null,
    "ipoe_circuit_id": null
  }
  ```
* **Password Note:** `CustomerResponse` **never** contains the subscriber's password. In SAMM, passwords are strictly write-only.

---

### 4.4 Customer Creation (`POST /api/v1/customers`)
Corresponds to the **`ACTIVATION`** pending operation.

* **Path:** `POST /api/v1/customers`
* **HTTP Success:** `201 Created`
* **Request Schema (`CustomerCreate`):**
  ```json
  {
    "username": "user_baghdad_02",
    "password": "SecretPassword123",
    "firstname": "Mustafa",
    "lastname": "Kamal",
    "plan_id": 12,
    "generate_invoice": false,
    "mobile": "07801234567",
    "address": "Mansour",
    "email": null,
    "auto_renew": false
  }
  ```
* **Required Fields:** `username`, `password`, `firstname`, `lastname`, `plan_id`.
* **Invoicing Constraint:** The app must explicitly set `generate_invoice = false`. SAMM defaults to `true`, but the reseller app manages local ledger debt independently without generating internal SAMM subscriber cash invoices.

---

### 4.5 Customer Renewal (`POST /api/v1/customers/{customer_id}/renew`)
Corresponds to the **`RENEWAL`** pending operation.

* **Path:** `POST /api/v1/customers/{customer_id}/renew?generate_invoice=false`
* **HTTP Success:** `200 OK`
* **Query Parameter:** `generate_invoice=false` (Mandatory).
* **Request Body:** None.
* **Response Schema (`CustomerRenewResponse`):**
  ```json
  {
    "id": 105,
    "username": "user_baghdad_01",
    "status": "active",
    "new_expiration": "2026-11-01T10:00:00Z",
    "invoice_id": null
  }
  ```
* **Required Fields:** `id` (Int), `username` (String), `status` (String), `new_expiration` (String ISO-8601), `invoice_id` (Int?).

---

### 4.6 Status Transitions: Suspend & Activate
Immediate account status toggles.

* **Suspend:**
  * **Path:** `POST /api/v1/customers/{customer_id}/suspend`
  * **HTTP Success:** `200 OK` -> `CustomerResponse` with `status: "suspended"`.
  * **CoA Semantics:** SAMM automatically sends a RADIUS CoA disconnect to drop any active session on the NAS.
* **Activate:**
  * **Path:** `POST /api/v1/customers/{customer_id}/activate`
  * **HTTP Success:** `200 OK` -> `CustomerResponse` with `status: "active"`.

---

### 4.7 Plan Assignment (`POST /api/v1/customers/{customer_id}/assign-plan`)
Corresponds to changing a subscriber's service package.

* **Path:** `POST /api/v1/customers/{customer_id}/assign-plan`
* **HTTP Success:** `200 OK`
* **Request Schema (`AssignPlanRequest`):**
  ```json
  {
    "plan_id": 15,
    "when": "now",
    "generate_invoice": false,
    "invoice_mode": "none",
    "extend_expiration": false,
    "reset_state": false,
    "reactivate": false,
    "apply_speed_live": false,
    "notify_customer": false,
    "keep_expiration": false
  }
  ```
* **Required Fields:** `plan_id` (Int).
* **Minimal Scope Rule:** The reseller app defaults `when = "now"`, `generate_invoice = false`, and `invoice_mode = "none"`.
* **Response Schema (`AssignPlanResponse`):**
  ```json
  {
    "id": 105,
    "username": "user_baghdad_01",
    "plan_id": 15,
    "invoice_id": null
  }
  ```

---

### 4.8 Customer Update & Password Change (`PATCH /api/v1/customers/{customer_id}`)
* **Path:** `PATCH /api/v1/customers/{customer_id}`
* **HTTP Success:** `200 OK` -> `CustomerResponse`
* **Request Schema (`CustomerUpdate`):**
  All fields are optional:
  ```json
  {
    "password": "NewSecretPassword456",
    "firstname": "Ahmed",
    "lastname": "Ali",
    "mobile": "07709999999"
  }
  ```
* **Password Change Usage:** Dispatched with payload `{"password": "new_password"}`.
* **Name Update Usage:** Dispatched with payload `{"firstname": "new_first", "lastname": "new_last"}`.

---

## 5. Plan Endpoints & Catalog Specifications

### 5.1 Plan Listing (`GET /api/v1/plans`)
* **Path:** `GET /api/v1/plans`
* **Query Parameters:**
  * `enabled_only`: Boolean (Default: `false`). The app queries `enabled_only=true` to display only active sellable plans.
* **Response Schema:** JSON array of `PlanResponse` objects (`List<PlanResponse>`):
  ```json
  [
    {
      "id": 12,
      "name": "Standard 10M",
      "price": 35000.0,
      "enabled": true,
      "upload_speed": "5M",
      "download_speed": "10M",
      "duration_value": 30,
      "duration_unit": "days",
      "duration_type": "fixed",
      "daily_traffic_limit": null,
      "daily_limit_type": "combined",
      "max_transfer": null,
      "max_transfer_type": "combined",
      "max_sessions": 1,
      "pool_name": "dhcp_pool1",
      "auto_renew": false,
      "created_at": "2026-01-01T00:00:00Z"
    }
  ]
  ```
* **Required Fields:** `id` (Int), `name` (String), `created_at` (String).

### 5.2 Plan Read (`GET /api/v1/plans/{plan_id}`)
* **Path:** `GET /api/v1/plans/{plan_id}`
* **Response Schema:** Single `PlanResponse` object.

---

## 6. Mutation Semantics & Queue Architecture

### 6.1 Immediate vs. Queued Execution

| Mutation Type | SAMM Mechanism | Endpoint Examples | Behavior |
|---|---|---|---|
| **Direct Entity Mutations** | **Immediate** | `POST /customers`<br>`POST /customers/{id}/renew`<br>`POST /customers/{id}/suspend`<br>`POST /customers/{id}/activate`<br>`POST /customers/{id}/assign-plan`<br>`PATCH /customers/{id}` | Applied synchronously within the HTTP transaction against PostgreSQL. Returns immediately with updated entity. Associated network disconnects (CoA) are queued automatically by SAMM backend. |
| **Accounting / Counter Resets** | **Queued (`samm-radius`)** | `POST /customers/{id}/reset-limit`<br>`POST /cards/groups/{id}/disable` | Applied asynchronously by background `samm-radius` service within one tick (~a few seconds). Endpoint returns `{"queued": true}`. |

### 6.2 Command Queue Tracker (`GET /api/v1/commands`)
When queued mutations are executed, the caller's token commands are tracked in the command audit log.
* **Path:** `GET /api/v1/commands`
* **Query Parameters:**
  * `applied`: Boolean (optional: filter by `true` or `false`)
  * `limit`: Int (1..500, default: 50)
  * `offset`: Int (default: 0)
* **Response Schema (`ListResponse[CommandItem]`):**
  ```json
  {
    "total": 1,
    "items": [
      {
        "id": 901,
        "action": "reset_limit",
        "target": "user_baghdad_01",
        "applied": true,
        "created_at": "2026-10-06T03:00:00Z",
        "applied_at": "2026-10-06T03:00:02Z"
      }
    ]
  }
  ```
* **Exact Schema Guarantee:** The fields are strictly `id` (Int), `action` (String), `target` (String?), `applied` (Boolean), `created_at` (String), `applied_at` (String?).
* **Constraint:** Do not invent non-existent fields such as `status`, `command_id`, or `failed_reason`.

---

## 7. Error Semantics & Status Mapping

SAMM uses standard REST HTTP status codes and structured JSON bodies:

| HTTP Status | Semantic Meaning | SAMM Payload / Headers | SAS Seam Translation |
|---|---|---|---|
| **401 Unauthorized** | Missing, invalid, expired, or revoked API token. | `{"detail": "Not authenticated"}` | `EarthlinkAuthException` (or `SasAuthException`) — halts automatic dispatch, alerts user to check API token. |
| **403 Forbidden** | Token lacks required scope (e.g. attempted write with read-only token). | `{"detail": "Forbidden: missing scope customers:write"}` | `SasBusinessException` (fatal configuration error, non-retryable). |
| **404 Not Found** | Specified entity (`customer_id`, `plan_id`) does not exist. | `{"detail": "Customer not found"}` | `SasBusinessException` (entity missing). |
| **409 Conflict** | Duplicate resource (e.g., username already exists during creation). | `{"detail": "Username already exists"}` | `SasBusinessException` (deterministic business rejection; for activation, marks failed, allows username edit). |
| **422 Unprocessable** | Request schema or parameter validation failure. | `HTTPValidationError`: `{"detail": [{"loc": [...], "msg": "...", "type": "..."}]}` | `SasBusinessException` (malformed payload). |
| **429 Too Many Requests**| Rate limit exceeded for the token. | `{"detail": "Rate limit exceeded"}`<br>Headers: `Retry-After: <seconds>`, `X-RateLimit-Limit`, `X-RateLimit-Remaining` | `SasTransportException` / Transient — client must respect `Retry-After` seconds before retrying. |
| **500 / 502 / 503 / 504**| Server error or gateway unavailable. | HTML/JSON gateway error. | `EarthlinkInconclusiveException` (or `SasInconclusiveException`) — **must preserve pending operation** for cold-start recovery. |

### 7.1 Validation Error Schema (`HTTPValidationError`)
```json
{
  "detail": [
    {
      "loc": ["body", "plan_id"],
      "msg": "field required",
      "type": "value_error.missing"
    }
  ]
}
```

---

## 8. Rate Limiting Rules & Headers

* **Configuration:** Per-token rate limits are established at creation in the SAMM web console (default: 120 requests/minute).
* **Header Telemetry:**
  ```http
  X-RateLimit-Limit: 120
  X-RateLimit-Remaining: 114
  ```
* **Exceeded State (HTTP 429):**
  When requests exceed the quota, SAMM returns HTTP 429 with:
  ```http
  HTTP/1.1 429 Too Many Requests
  Retry-After: 15
  Content-Type: application/json

  {"detail": "Rate limit exceeded. Try again in 15 seconds."}
  ```
* **Client Behavior:**
  1. The OkHttp interceptor or retry loop parses the `Retry-After` header value (in integer seconds).
  2. If present and <= 60 seconds, the client pauses or schedules backoff.
  3. Never tight-loop on HTTP 429.

---

## 9. Security & Operational Constraints

1. **Zero Credential Logging:**
   - Tokens (`samm_...`) and user passwords must **never** be logged in plain text, whether in production logs, debug output, crash reports, or exceptions.
   - HttpLoggingInterceptor log level must be configured to omit headers or sanitize `Authorization`.
2. **Cleartext Transport Prohibition:**
   - Android Network Security Configuration must reject cleartext HTTP for remote SAMM endpoints in production builds.
3. **No Password Persistence in Pending Operations:**
   - Pending operations stored in the local Room database (`pending_external_operations`) must never store raw user passwords in clear text.
4. **No Direct Network Sockets:**
   - The app shall not open MikroTik API (port 8728/8729), SSH (port 22), RADIUS (port 1812/1813), or PostgreSQL direct database connections.

---

## 10. Provider Parity & Supported Operations Table

This table maps every supported subscriber and catalog operation across the application, specifying whether it executes immediately or is queued, and the verification oracle.

| Operation | Method | Path | Request DTO | Response DTO | Mode | Verification Oracle / Recovery Strategy |
|---|---|---|---|---|---|---|
| **Smoke Test (`ME`)** | `GET` | `/api/v1/me` | None | `MeResponse` | Immediate | Response HTTP 200, checks server version `5.1.15` and required scopes. |
| **Search Directory** | `GET` | `/api/v1/customers` | Query params (`search`, `status`, `limit`, `offset`) | `ListResponse[CustomerListItem]` | Immediate | Total >= 0, returns matching customer records. |
| **Get Subscriber** | `GET` | `/api/v1/customers/{customer_id}` | None | `CustomerResponse` | Immediate | Returns customer detail (usage, expiry, status). |
| **Activation (`ACTIVATION`)** | `POST` | `/api/v1/customers` | `CustomerCreate` (`generate_invoice = false`) | `CustomerResponse` (201) | Immediate | Verified by HTTP 201 with `id > 0`. Recovery: Query `GET /customers?username={user}`; if exists, verified executed. |
| **Renewal (`RENEWAL`)** | `POST` | `/api/v1/customers/{customer_id}/renew?generate_invoice=false` | None | `CustomerRenewResponse` (200) | Immediate | Verified by HTTP 200 with `new_expiration > old_expiration`. Recovery: Read `GET /customers/{id}` to verify expiration advancement. |
| **Suspend** | `POST` | `/api/v1/customers/{customer_id}/suspend` | None | `CustomerResponse` (200) | Immediate | Verified by `status == "suspended"`. Non-financial. |
| **Activate** | `POST` | `/api/v1/customers/{customer_id}/activate` | None | `CustomerResponse` (200) | Immediate | Verified by `status == "active"`. Non-financial. |
| **Change Plan** | `POST` | `/api/v1/customers/{customer_id}/assign-plan` | `AssignPlanRequest` (`when = "now"`, `generate_invoice = false`) | `AssignPlanResponse` (200) | Immediate | Verified by response `plan_id == requested_plan_id`. Non-financial or handled by separate plan change policy. |
| **Change Password** | `PATCH` | `/api/v1/customers/{customer_id}` | `CustomerUpdate` (`password = "..."`) | `CustomerResponse` (200) | Immediate | HTTP 200 confirms RADIUS write. Password is write-only. |
| **Update Name** | `PATCH` | `/api/v1/customers/{customer_id}` | `CustomerUpdate` (`firstname`, `lastname`) | `CustomerResponse` (200) | Immediate | HTTP 200 confirms name change in response. |
| **List Plans** | `GET` | `/api/v1/plans?enabled_only=true` | None | `List<PlanResponse>` | Immediate | Returns enabled plans list with price and bandwidth limits. |
| **Get Plan** | `GET` | `/api/v1/plans/{plan_id}` | None | `PlanResponse` | Immediate | Returns authoritative plan details and cost. |
| **Inspect Commands** | `GET` | `/api/v1/commands` | Query params (`applied`, `limit`, `offset`) | `ListResponse[CommandItem]` | Immediate | Checks status of queued background tasks (`applied == true`). |

---

## 11. Strictly Unsupported SAMM Operations (Fail-Closed)

The following operations exist in the EarthLink provider but have **no equivalent** in SAMM. When invoked against the `ALAMIRY` provider, the router or adapter must immediately fail closed by throwing `UnsupportedProviderOperationException` (never silently no-op or route to billing):

1. **`REFILL` (Reseller Deposit Box Refill):**
   - SAMM does not have a reseller prepaid balance wallet. Refill is strictly unsupported.
2. **`TEST_USER` (EarthLink Temporary Account):**
   - SAMM does not feature disposable test user quotas.
3. **`EXTEND` (Grace Duration Extension):**
   - SAMM does not offer zero-cost grace extensions without modifying expiration dates or plan renewal.
4. **`SHOW_USER_PASSWORD` / `SHOW_ACCOUNT_PASSWORD`:**
   - SAMM stores subscriber passwords for RADIUS but does not expose them on read endpoints (`GET /customers/{id}`). Passwords are write-only.
