# Alamiry / SAMM Provider Integration — Final Implementation Plan

> **Execution mode:** This document is the implementation authority for the agent.
>
> **Recommended workflow:** Use `subagent-driven-development` or `executing-plans` if available. Execute tasks in order. Each task has an explicit gate. Do not skip evidence collection, tests, or stop conditions.
>
> **Important:** Do not reproduce real passwords, API tokens, SSH keys, or other secrets in source files, test fixtures, logs, commits, screenshots, or documentation.

---

# 0. Goal

Add **Alamiry/SAMM** as a second supported ISP provider behind a **minimal SAS seam**, while preserving the application's existing product meaning:

- one local subscriber/account model;
- one local financial ledger;
- one existing durable external-operation mechanism;
- one existing recovery path;
- one Firebase/local sync model;
- existing EarthLink-specific functionality where SAMM has no proven equivalent;
- SAMM access only through its REST API;
- no direct MikroTik, FreeRADIUS, RADIUS, SSH, or RouterOS integration from the Android app.

The supported providers are:

```text
EARTHLINK
ALAMIRY   -> SAMM backend
```

`SAS` is only the internal naming boundary for provider-dependent subscriber operations. It does **not** mean "support every FreeRADIUS or every ISP backend."

---

# 1. Target Architecture

The intended request flow is:

```text
UI / ViewModel
      |
      v
provider-aware operation
      |
      v
SasGatewayRouter
      |
      +-------------------+
      |                   |
      v                   v
EarthLink adapter      SAMM adapter
      |                   |
      v                   v
EarthLink API        SAMM REST API
                          |
                          v
                       verified
                        result
```

For durable operations:

```text
User intent
   |
   v
PendingExternalOperation
   |
   | provider captured HERE
   v
dispatch
   |
   +--> EarthLink
   |
   +--> SAMM
   |
   v
provider-specific verification
   |
   v
existing local materialization / ledger rules
```

The router must never choose a provider from:

- username;
- current connectivity;
- "the other provider worked";
- API error;
- timeout;
- missing SAMM configuration;
- availability of a fallback.

Provider selection is explicit and durable.

---

# 2. Product Invariants

These are hard invariants. The agent must not violate them to make the code easier.

## 2.1 One local account model

Do not create:

```text
SammAccount
EarthlinkAccount
ProviderAccount
```

Use the existing `LocalAccount` entity.

Add only:

```text
operationProvider
```

as provider-routing metadata.

---

## 2.2 One local financial ledger

Do not create:

```text
SammLedger
ExternalLedger
ProviderLedger
```

The existing local ledger remains the application's financial accounting boundary.

SAMM invoices must not silently become a second copy of the app's local reseller ledger.

For SAMM operations triggered by this app, **do not generate SAMM invoices unless a future product decision explicitly requires it**.

Therefore, when SAMM exposes:

```text
generate_invoice
```

the initial integration should pass:

```text
false
```

for app-driven customer creation, renewal, and plan changes unless the current product flow proves a business reason to do otherwise.

---

## 2.3 One durable pending-operation mechanism

Do not create:

```text
SammOutbox
SammQueue
SammRecoveryState
ProviderReconciliationEngine
```

Reuse `PendingExternalOperation`.

Add:

```text
operationProvider
```

to the pending operation so the provider cannot change during recovery.

---

## 2.4 No cross-provider fallback

This must remain impossible:

```text
ALAMIRY operation
   -> SAMM timeout
   -> try EarthLink
```

or:

```text
EARTHLINK operation
   -> EarthLink unavailable
   -> try SAMM
```

Failure is failure/inconclusive. It is not permission to change provider.

---

## 2.5 Provider change is not migration

Changing:

```text
LocalAccount.operationProvider
```

means:

> future provider-dependent operations for this local account target the selected provider.

It does **not** mean:

- copy the subscriber;
- clone credentials;
- migrate provider state;
- import historical transactions;
- merge external accounts;
- reconcile two providers.

Changing provider must preserve the local account ID and local history.

Pending operations already created must retain their original provider even if the `LocalAccount.operationProvider` later changes.

---

# 3. Current SAMM Contract Baseline

The actual uploaded OpenAPI from the local SAMM server is the contract source for implementation.

Verified baseline:

```text
OpenAPI: 3.1.0
API title: SAMM API
API version: 1.0
SAMM server version: 5.1.15
Base API path: /api/v1
Authentication: HTTP Bearer token
Credential creation: System -> API
Credential introspection: GET /me
```

The token security scheme is explicitly a bearer token beginning with `samm_`. The secret is shown once and only its SHA-256 hash is stored server-side.

The current installation also documents:

```text
customers:read
customers:write
plans:read
plans:write
billing:read
billing:write
sessions:read
coa:write
...
```

The initial Android integration must request only the scopes actually required by the implemented feature.

For the initial core flow, target:

```text
customers:read
customers:write
plans:read        (only if plan selection requires SAMM plan listing)
```

Do not request:

```text
billing:write
routers:write
services:write
diagnostics:*
logs:*
webhooks:*
```

unless a future task explicitly adds those capabilities.

---

# 4. Proven SAMM Customer Operations

The current OpenAPI exposes:

```text
GET    /customers
POST   /customers

GET    /customers/{customer_id}
PATCH  /customers/{customer_id}
DELETE /customers/{customer_id}

POST   /customers/{customer_id}/suspend
POST   /customers/{customer_id}/activate
POST   /customers/{customer_id}/renew
POST   /customers/{customer_id}/reset-limit
POST   /customers/{customer_id}/assign-plan

GET    /customers/{customer_id}/usage
GET    /customers/{customer_id}/timeline
GET    /customers/{customer_id}/why-blocked
```

The create request requires:

```text
username
password
firstname
lastname
plan_id
```

and supports additional profile, expiry, and account fields.

Password changes are part of:

```text
PATCH /customers/{customer_id}
```

using the `password` field.

Renewal is:

```text
POST /customers/{customer_id}/renew
```

and returns:

```text
id
username
status
new_expiration
invoice_id
```

with invoice generation configurable.

Plan changes are:

```text
POST /customers/{customer_id}/assign-plan
```

with options including plan selection, expiration behavior, state reset, invoice generation, reactivation, live speed application, and notification behavior.

---

# 5. Critical Queue Semantics

The SAMM OpenAPI states that some mutations are queued and applied by `samm-radius`, while other customer mutations are immediate.

Current documented examples of queued operations include:

```text
reset-limit
card enable/disable
card/group changes
```

The customer operations used by the initial app integration:

```text
create customer
suspend
activate
renew
assign plan
update customer
```

are documented as immediate.

The command tracker is:

```text
GET /commands
```

and is scoped to the calling token.

Its response contains:

```text
id
action
target
applied
created_at
applied_at
```

The queue response itself does **not** guarantee a `command_id` field.

Therefore:

### Hard rule

Never write code that assumes:

```text
queued response -> commandId
```

unless the exact runtime response proves that field exists.

If a future queued SAMM capability is added, correlation must use the exact contract and observable command semantics, not guessed identifiers.

---

# 6. Operation Parity Matrix

This matrix controls the shared seam.

| Product operation | EarthLink | SAMM | Initial decision |
|---|---|---|---|
| Search subscriber | YES | YES | COMMON |
| Read subscriber | YES | YES | COMMON |
| Create/activation | YES | YES (`POST /customers`) | COMMON |
| Suspend | YES | YES | COMMON |
| Activate/reactivate | YES | YES | COMMON |
| Renewal | YES | YES (`/renew`) | COMMON |
| Extension | YES | YES if semantically equivalent to `/renew` | COMMON only after proof |
| Change plan/account type | YES | YES (`/assign-plan`) | COMMON if current semantics map cleanly |
| Change PPP password | YES | YES (`PATCH /customers/{id}`) | COMMON |
| Plan/package listing | YES | YES (`GET /plans`) | COMMON only if current UI requires it and semantics match |
| Test-user creation | YES | No proven equivalent | EARTHLINK-ONLY |
| Reseller balance | YES | No | EARTHLINK-ONLY |
| Account cost/prepaid | YES | No proven equivalent | EARTHLINK-ONLY |
| Deposit refill | YES | No direct equivalent | EARTHLINK-ONLY |
| Refill | YES | No direct equivalent | **UNSUPPORTED on SAMM** |
| EarthLink statement | YES | No | EARTHLINK-ONLY |
| SAMM billing | N/A | YES | OUT OF SCOPE |
| Router/MikroTik operations | N/A | YES | OUT OF SCOPE |
| RADIUS diagnostics | N/A | YES | OUT OF SCOPE |
| Webhooks | N/A | YES | OUT OF SCOPE |

### Refill is explicitly unsupported

Do **not** map:

```text
EarthLink deposit refill
```

to:

```text
SAMM invoice
SAMM payment
SAMM billing
```

because these are not proven to represent the same product operation.

For an ALAMIRY/SAMM account:

```text
REFILL -> Unsupported provider operation
```

The app must surface a clear unsupported state.

It must not silently switch to EarthLink.

---

# 7. File Map

## Create

```text
app/src/main/java/com/example/core/network/SasGateway.kt

app/src/main/java/com/example/core/network/SasGatewayRouter.kt

app/src/main/java/com/example/core/network/samm/SammApiService.kt

app/src/main/java/com/example/core/network/samm/SammDtos.kt

app/src/main/java/com/example/core/network/samm/SammNetworkClient.kt

app/src/main/java/com/example/core/network/samm/SammGatewayImpl.kt
```

---

## Modify

```text
app/src/main/java/com/example/core/model/Models.kt

app/src/main/java/com/example/core/database/AppDatabase.kt

app/src/main/java/com/example/core/security/PreferenceManager.kt

app/src/main/java/com/example/domain/repository/Interfaces.kt

app/src/main/java/com/example/data/repository/Repositories.kt

app/src/main/java/com/example/EarthlinkApp.kt

app/src/main/java/com/example/ui/viewmodels/AppViewModelProvider.kt

app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt

app/src/main/java/com/example/ui/screens/SettingsScreen.kt

app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt

app/src/main/java/com/example/ui/screens/LocalAccountsScreen.kt
```

Create a dedicated settings composable only if consistent with the current UI structure:

```text
app/src/main/java/com/example/ui/screens/SammSettingsSection.kt
```

---

## Documentation artifacts

```text
docs/samm/openapi.json
docs/samm/SAMM_API_CONTRACT.md
docs/samm/PROVIDER_OPERATION_MATRIX.md
docs/samm/LAB_SMOKE_TEST_RESULTS.md
docs/samm/INTEGRATION_EVIDENCE.md
```

---

# Task 0 — Repository Baseline and Provider Call-Graph Audit

## Purpose

Before changing code, establish the current repository reality.

No implementation begins until this task is complete.

---

## Step 0.1 — Capture baseline

Record:

```bash
git status --short
git branch --show-current
git rev-parse HEAD
```

Run the actual current test baseline:

```bash
./gradlew :app:testDebugUnitTest
```

If instrumentation is available:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Record:

```text
test count
failed tests
skipped tests
build variant
commit SHA
working tree state
```

Do not hard-code historical test counts.

---

## Step 0.2 — Enumerate every `EarthlinkGateway` consumer

Search for:

```text
EarthlinkGateway
earthlinkGateway
gateway:
```

Specifically inspect:

```text
EarthlinkApp.kt
AppViewModelProvider.kt
EarthlinkSearchViewModel.kt
AuthViewModel.kt
DashboardViewModel.kt
StatementViewModel.kt
SyncWorker.kt
Repositories.kt
Interfaces.kt
```

Produce a table:

| Caller | Operation | Provider-specific? | Target seam |
|---|---|---|---|

---

## Step 0.3 — Enumerate every pending-operation producer

Search:

```text
PendingExternalOperation(
createPendingOperation
operationType =
resolvePendingOperation
```

Record every producer for:

```text
ACTIVATION
RENEWAL
REFILL
EXTEND
TEST_USER
```

Do not assume the known examples are exhaustive.

---

## Step 0.4 — Enumerate every recovery entry point

Search for:

```text
recoverColdStartOrphanedOperations
sweepAndResolvePendingOperations
verifyAndResolvePendingOperation
resolvePendingOperationSerialized
```

Also search:

```text
SyncWorker
WorkManager
onCreate
startup recovery
```

Important current path to verify:

```text
EarthlinkApp startup recovery
SyncWorker recovery/sweep
```

Both must become provider-aware.

---

## Step 0.5 — Audit LocalAccount serialization

Trace `LocalAccount` through:

```text
Moshi adapters
JSON payloads
Firebase
restore
backup
outbox
sync
import/export
copy/update
```

Answer explicitly:

```text
Is operationProvider synchronized?
If yes, how is it protected from stale remote overwrite?
If no, how is local provider state preserved across restore/device use?
```

The agent must not leave this ambiguous.

Target behavior:

- provider identity may be synchronized as business routing metadata if current sync architecture treats `LocalAccount` as shared account state;
- SAMM credentials/base URL/token must never be synchronized to Firebase.

---

## Step 0.6 — Audit credential lifecycle

Inspect:

```text
PreferenceManager.clearCredentials()
IspCredentialOwner
login/logout
restore/session transitions
account switching
```

Determine exactly when the SAMM endpoint/token must be invalidated.

---

## Step 0.7 — Produce the operation matrix

Create:

```text
docs/samm/PROVIDER_OPERATION_MATRIX.md
```

It must contain every user-facing ISP operation currently present in the application.

### Gate

Do not start SAMM adapter implementation until:

- all EarthLink gateway consumers are known;
- all pending-operation producers are known;
- all recovery entry points are known;
- LocalAccount serialization behavior is known;
- credential lifecycle is known;
- every current ISP operation has a parity classification.

---

# Task 1 — SAMM API Contract Discovery from the Actual Installation

## Purpose

Replace assumptions with evidence.

The uploaded OpenAPI is the current source for implementation:

```text
Server: SAMM 5.1.15
API base: /api/v1
```

Store a repository copy as:

```text
docs/samm/openapi.json
```

Do not store tokens.

---

## Step 1.1 — Save OpenAPI

Use the exact production/local artifact already retrieved.

Validate:

```bash
python3 -m json.tool docs/samm/openapi.json >/dev/null
```

or on Windows:

```powershell
Get-Content docs\samm\openapi.json -Raw | ConvertFrom-Json | Out-Null
```

---

## Step 1.2 — Confirm authentication

Do not implement:

```text
POST /auth/token
```

unless the live OpenAPI later proves it.

The current OpenAPI proves:

```text
GET /me
Authorization: Bearer samm_...
```

Use a test token created from:

```text
System -> API
```

Never put the actual token into repository documentation.

---

## Step 1.3 — Verify `/me`

Runtime test:

```bash
curl -s \
  -H "Authorization: Bearer $SAMM_TOKEN" \
  "$SAMM_BASE/api/v1/me"
```

Record:

```text
token_id
name
scopes
rate_limit_per_min
server_version
```

The token response model explicitly includes `server_version`.

---

## Step 1.4 — Verify customer listing

Use:

```text
GET /customers
```

Test:

```text
username
search
status
plan_id
limit
offset
```

Record exact response shape.

Current OpenAPI defines:

```text
{
  total,
  items
}
```

and the customer list includes:

```text
id
username
firstname
lastname
mobile
email
status
plan_id
plan_name
auto_renew
created_at
expiration_date
...
```



---

## Step 1.5 — Verify customer read

Use:

```text
GET /customers/{customer_id}
```

Record:

```text
id
username
firstname
lastname
mobile
status
expiration_date
plan identity
usage fields
```

---

## Step 1.6 — Verify customer creation in an isolated test account

Use the exact `CustomerCreate` schema.

Required fields are:

```text
username
password
firstname
lastname
plan_id
```

For the Android integration, explicitly test:

```text
generate_invoice = false
```

unless a later product decision requires SAMM-side billing.

Do not use a real customer's account for first validation.

---

## Step 1.7 — Verify suspend/activate

Test:

```text
POST /customers/{id}/suspend
POST /customers/{id}/activate
```

Record:

```text
HTTP status
response body
status transition
session behavior
```

Current SAMM documentation states suspension drops live sessions via CoA.

---

## Step 1.8 — Verify renewal

Test:

```text
POST /customers/{id}/renew?generate_invoice=false
```

Record:

```text
old expiration
new expiration
status
invoice_id
```

Confirm whether `new_expiration` is enough to verify successful renewal.

---

## Step 1.9 — Verify plan assignment

Inspect exact `AssignPlanRequest` fields from OpenAPI before coding.

Test only the minimal request required for current app semantics.

Do not blindly enable:

```text
reset_state
reactivate
apply_speed_live
notify_customer
```

unless the current EarthLink product behavior proves the same intent.

---

## Step 1.10 — Verify password update

Use:

```text
PATCH /customers/{id}
```

with:

```json
{
  "password": "..."
}
```

Do not attempt to read the password back.

Do not log it.

Do not persist it in pending operations.

---

## Step 1.11 — Verify `GET /commands`

Only required for queued capabilities.

Record the exact shape of:

```text
CommandItem
```

Current schema:

```text
id
action
target
applied
created_at
applied_at
```



Do not invent:

```text
status
command_id
failed_reason
```

unless the runtime contract proves them.

---

## Step 1.12 — Verify errors

Use exact contract paths.

Test at least:

```text
401
403
404
409
422
429
```

Record:

```text
HTTP code
response body shape
Retry-After
X-RateLimit-Limit
X-RateLimit-Remaining
```

The current OpenAPI explicitly documents these error classes and rate-limit headers.

---

## Step 1.13 — Write the contract

Create:

```text
docs/samm/SAMM_API_CONTRACT.md
```

Required sections:

```text
Server/version
Base URL rules
Authentication
Required scopes
Customer endpoints
Plan endpoints
Mutation semantics
Queue semantics
Error map
Rate limiting
Security constraints
```

For each supported operation include:

| Operation | Method | Path | Request | Response | Immediate/Queued | Verification |
|---|---|---|---|---|---|---|

### Gate

No SAMM DTO/service implementation until this document is complete.

---

# Task 2 — Minimal SAS Contract and Error/Result Seam

## Purpose

Create the smallest provider seam that represents **real shared product capabilities**.

Do not make the new interface a copy of `EarthlinkGateway`.

---

## Step 2.1 — Reinspect the existing error model

Inspect:

```text
ApiResult.kt
EarthlinkGatewayException
EarthlinkTransportException
EarthlinkInconclusiveException
EarthlinkBusinessException
EarthlinkAuthException
```

Do not automatically create a second parallel exception hierarchy.

Decision order:

1. reuse existing neutral result/error infrastructure if it is genuinely reusable;
2. minimally generalize EarthLink-specific types if that avoids duplication and does not destabilize unrelated code;
3. introduce a small SAS-specific hierarchy only where necessary.

The final design must not leave:

```text
Earthlink exception hierarchy
+
SAS exception hierarchy
+
another result hierarchy
```

all representing the same failure classes.

---

## Step 2.2 — Define the shared subscriber contract

The shared seam must cover only operations that both providers truly support.

Initial target:

```text
searchSubscribers
getSubscriber
createSubscriber
suspendSubscriber
activateSubscriber
renewSubscriber
changePlan        [only after parity proof]
changePassword
```

Potential plan-listing support:

```text
listPlans
```

must be added only if current UI flow requires it and both providers can provide semantically equivalent data.

Do not force:

```text
balance
prepaid
deposit
statement
test-user
billing
```

into the common interface.

---

## Step 2.3 — Use typed operation results

Do not reduce all provider mutations to:

```text
Boolean
```

because SAMM and EarthLink may have different semantic result information.

At minimum distinguish:

```text
Applied
Rejected
Inconclusive
Unsupported
```

If the exact provider contract requires queued handling, allow:

```text
Queued(reference)
```

but only when an observable queue reference actually exists.

A result model might conceptually be:

```text
Applied(data)
Queued(data)
Rejected(error)
Inconclusive(reason)
Unsupported(reason)
```

Do not require every provider to manufacture values that do not exist.

---

## Step 2.4 — Operation-Aware Provider Verification (No Separate Subsystem, No Weak Generic Oracle)

### Architectural Principle
**No separate `SasOperationVerifier` subsystem + No weak generic `verifySubscriberState()` + Provider-specific operation-aware verification.**

Do not build an over-engineered reconciliation engine, but **never** fall back to a weak, generic `verifySubscriberState(id): SasSubscriberView?` as a one-size-fits-all oracle. Different operations have fundamentally distinct verification oracles.

Place operation-aware verification directly on `SasGateway`:

```kotlin
enum class SasVerificationOutcome {
    VERIFIED_SUCCESS,
    VERIFIED_FAILURE,
    INCONCLUSIVE
}

interface SasGateway {
    // ... core operations ...

    /**
     * Operation-aware verification for recovery sweeps.
     * Evaluates provider state against operation intent without blind redispatch.
     */
    suspend fun verifyOperationOutcome(
        operationType: String,
        targetIdentifier: String,
        baselineEvidence: String?
    ): SasVerificationOutcome
}
```

### Operation-Specific Oracles

1. **SAMM Activation Verification (`operationType = "ACTIVATION"`):**
   - Query exact username: `GET /customers?username=<exact username>`
   - **Crucial:** Use the dedicated `username=<exact username>` parameter for exact lookup. Do **not** use `search=<username>` for exact activation verification to avoid prefix/substring collisions.
   - **Oracle:** A customer with the exact username exists and matches expected parameters -> `VERIFIED_SUCCESS`. Not found -> `VERIFIED_FAILURE`. Network error -> `INCONCLUSIVE`.

2. **SAMM Renewal Verification (`operationType = "RENEWAL"`):**
   - Read customer: `GET /customers/{id}`
   - **Oracle:** `customer.expiration_date` is strictly advanced beyond `baselineEvidence` (the pre-operation expiration timestamp) -> `VERIFIED_SUCCESS`. Expiration unchanged -> `VERIFIED_FAILURE`. Network error -> `INCONCLUSIVE`.

3. **SAMM Suspend / Activate Verification (`operationType = "TOGGLE_ACTIVE"`):**
   - Read customer: `GET /customers/{id}`
   - **Oracle:** `customer.status` equals the expected target status (`active` or `suspended`) -> `VERIFIED_SUCCESS`. Different status -> `VERIFIED_FAILURE`.

4. **SAMM Plan Change Verification (`operationType = "CHANGE_PLAN"`):**
   - Read customer: `GET /customers/{id}`
   - **Oracle:** `customer.plan_id` equals the expected target plan ID -> `VERIFIED_SUCCESS`.

5. **SAMM Password Change:**
   - **Oracle:** No safe non-secret read-back exists. Storing plaintext passwords in pending operations is strictly forbidden.
   - If response is lost -> strictly `INCONCLUSIVE`. Never blindly auto-retry password changes from hidden stored credentials; require manual user action.

unless the provider later exposes a safe non-secret confirmation mechanism.

---

## Step 2.5 — Tests

Create:

```text
SasGatewayContractTest.kt
```

and any minimal result/error tests.

Test:

```text
retryable transport
auth failure
not found
business validation
rate limit
unsupported capability
inconclusive outcome
```

Each test must have:

```text
Claim
Seam
Independent Oracle
```

---

# Task 3 — Provider Identity in LocalAccount and PendingExternalOperation

## Files

```text
core/model/Models.kt
core/database/AppDatabase.kt
```

Tests:

```text
app/src/androidTest/java/.../MigrationProviderTest.kt
```

---

## Step 3.1 — Recheck actual Room version

Do not trust this plan's historic version number blindly.

Inspect:

```text
AppDatabase.VERSION
```

and current migrations.

If the current version is still `18`, use:

```text
18 -> 19
```

with **one migration** that adds both fields.

Do not create unnecessary:

```text
18 -> 19
19 -> 20
```

steps.

---

## Step 3.2 — Add provider field to LocalAccount

Use a central provider value.

Target:

```text
operationProvider: String = "EARTHLINK"
```

with explicit Room default:

```text
EARTHLINK
```

Do not scatter raw strings through ViewModels.

Use a centralized constant/enum mapping that still persists as a stable string.

---

## Step 3.3 — Add provider field to PendingExternalOperation

Target:

```text
operationProvider: String = "EARTHLINK"
```

with SQLite default:

```text
EARTHLINK
```

---

## Step 3.4 — Migration

If current version is 18:

```sql
ALTER TABLE local_accounts
ADD COLUMN operationProvider TEXT NOT NULL DEFAULT 'EARTHLINK';
```

and:

```sql
ALTER TABLE pending_external_operations
ADD COLUMN operationProvider TEXT NOT NULL DEFAULT 'EARTHLINK';
```

inside the same migration:

```text
MIGRATION_18_19
```

Update Room schema assets.

---

## Step 3.5 — Migration test placement

This test must be an **Android instrumentation test** because it verifies actual Room/SQLite migration behavior.

Use:

```text
app/src/androidTest/...
```

not:

```text
app/src/test/...
```

Do not label a test "Robolectric" while running it with `AndroidJUnit4`.

---

## Step 3.6 — Verify actual schema

The test must:

1. create a real v18 database;
2. insert rows using the actual v18 schema;
3. run the real migration;
4. validate schema;
5. verify original data remains;
6. verify both new fields are `EARTHLINK`;
7. open with the Room database implementation if practical.

Do not assume Kotlin constructor defaults create SQLite defaults.

---

## Step 3.7 — Commit gate

Run:

```bash
./gradlew :app:connectedDebugAndroidTest
```

and targeted migration tests.

Only proceed when migration and schema validation pass.

---

# Task 4 — SAMM Credential and Configuration Lifecycle

## Files

```text
core/security/PreferenceManager.kt
```

Potential UI:

```text
ui/screens/SammSettingsSection.kt
```

---

## Step 4.1 — Credential model

Use:

```text
EncryptedSharedPreferences
```

already present in the application.

Store:

```text
SAMM base URL
SAMM token
```

Never store in:

```text
Room
Firebase
LocalAccount
PendingExternalOperation
audit record
logs
```

---

## Step 4.2 — Define ownership

Because the app can operate with different operators and different SAMM deployments, determine whether the credentials are:

```text
device-global
```

or:

```text
operator-scoped
```

Target behavior for this integration:

```text
operator-scoped SAMM configuration
```

using the existing credential-owner isolation pattern where applicable.

The endpoint and token must switch together.

Do not allow:

```text
operator A token
+
operator B SAMM host
```

through stale preferences.

---

## Step 4.3 — Add lifecycle methods

Add methods equivalent to:

```text
saveSammBaseUrl()
getSammBaseUrl()

saveSammToken()
getSammToken()

clearSammCredentials()
```

If the current PreferenceManager already has a unified logout/clear mechanism, integrate into it.

---

## Step 4.4 — Token UI handling

The settings screen must not display the real token by default.

Preferred behavior:

```text
SAMM token: ●●●●●●●●
Status: configured
```

Editing may replace the token.

Saving without changing it must preserve the existing secret without copying it through UI text fields unnecessarily.

---

## Step 4.5 — Configuration invalidation

Do not create a one-time:

```text
lazy SAMM gateway
```

that permanently captures:

```text
baseUrl + token
```

from the first app startup.

After Settings changes:

```text
old client invalid
new configuration active
```

The connection test and all subsequent operations must use the newly saved configuration.

---

## Step 4.6 — `/me` connection test

The connection test must call:

```text
GET /me
```

not:

```text
searchSubscribers("")
```

Success means:

```text
HTTP 200
valid MeResponse
```

Display useful non-secret information:

```text
server version
token scopes
rate limit
```

Do not expose the token value.

---

## Step 4.7 — Security tests

Test:

```text
token not present in logs
token not present in exceptions shown to UI
token not serialized into Firebase
token not present in pending operation payload
logout clears SAMM credentials according to ownership rules
changing operator identity does not reuse another operator's SAMM token
```

---

# Task 5 — SAMM DTOs, Retrofit Service, and Network Client

## Files

```text
core/network/samm/SammDtos.kt
core/network/samm/SammApiService.kt
core/network/samm/SammNetworkClient.kt
```

---

## Step 5.1 — DTOs from actual OpenAPI only

Do not invent:

```text
id -> String
```

if the contract says:

```text
id -> integer
```

Current SAMM customer IDs are integers.

Use exact request/response fields from the OpenAPI.

### Customer ID Boundary Contract

- **SAMM DTOs:** Customer ID is typed as `Int`:
  ```kotlin
  @Json(name = "id") val id: Int
  ```
- **Domain Normalization (`SasSubscriberView`):** Normalized to `String`:
  ```kotlin
  subscriberId = dto.id.toString()
  ```
- **Outgoing SAMM Path Parameter Conversion:** When formatting a SAMM path parameter requiring integer `customer_id` (e.g. `/customers/{id}/renew`), convert safely:
  ```kotlin
  val customerId = subscriberId.toIntOrNull()
      ?: throw SasBusinessException(
          statusCode = null,
          errorMessage = "Invalid SAMM customer ID integer: '$subscriberId'"
      )
  ```
  *(Never allow an unhandled `NumberFormatException` to escape to ViewModels).*
- **`LocalAccount.ispSubscriberId`:** Remains `String?` in Room. Do NOT change it to `Int`, as EarthLink relies on string-based subscriber identifiers and cross-provider schema unity is required.

Do not reuse guessed fields such as:

```text
full_name
expire_at
plan
```

when the actual schema says:

```text
firstname
lastname
expiration_date
plan_id
```

unless an explicit DTO mapping is introduced.

---

## Step 5.2 — Core DTOs

At minimum define exact models for:

```text
MeResponse

CustomerCreate
CustomerListItem
CustomerResponse
CustomerUpdate
CustomerRenewResponse

AssignPlanRequest
AssignPlanResponse

PlanResponse

ListResponse<T>

HTTPValidationError
ValidationError

CommandItem
```

Only add more when the implemented operation needs them.

---

## Step 5.3 — Retrofit paths

Use exact current paths:

```text
GET  /me

GET  /customers
GET  /customers/{customer_id}

POST /customers

PATCH /customers/{customer_id}

POST /customers/{customer_id}/suspend
POST /customers/{customer_id}/activate
POST /customers/{customer_id}/renew
POST /customers/{customer_id}/assign-plan

GET /plans

GET /commands
```

Do not implement unproven endpoints.

---

## Step 5.4 — Authentication interceptor

Add:

```text
Authorization: Bearer <token>
Accept: application/json
```

Do not hard-code:

```text
Bearer
```

into stored credentials.

The PreferenceManager stores only the token value.

---

## Step 5.5 — Redirect/TLS and Cleartext Policy Resolution

Support:

```text
http://
https://
```

### Cleartext HTTP and Network Security Policy Resolution

The application's release security baseline enforces:

```xml
<!-- app/src/main/res/xml/network_security_config.xml -->
<base-config cleartextTrafficPermitted="false">
```

Android Network Security Config is designed for domain names (FQDN) and loopback addresses; `<domain-config>` does **not** provide a dynamic runtime whitelist for arbitrary private IP addresses (`192.168.x.x`).

**Strict Policy Rules:**
1. **Release Builds:** **HTTPS required.** Cleartext HTTP is strictly prohibited in release builds, even if the user enters an IP address. Do not create an illusion of dynamic IP whitelisting. Production deployments must use HTTPS (`https://hostname` or `https://<ip>` with valid TLS).
2. **Debug / Lab Builds:** Cleartext HTTP (`http://192.168.x.x`) is permitted **exclusively** through a debug-only network security resource:
   ```xml
   <!-- app/src/debug/res/xml/network_security_config.xml -->
   <?xml version="1.0" encoding="utf-8"?>
   <network-security-config>
       <base-config cleartextTrafficPermitted="true">
           <trust-anchors>
               <certificates src="system" />
           </trust-anchors>
       </base-config>
   </network-security-config>
   ```
3. **Verification Matrix:**
   - `http://192.168.x.x` -> Supported in debug/lab builds only; rejected with clear UI diagnostic in release builds.
   - `https://192.168.x.x` -> Supported in release and debug (subject to standard TLS certificate validation).
   - `https://hostname` -> Supported in release and debug (standard production pattern).

Hard rules:

```text
no TLS certificate bypass
no trust-all certificates
no hostname verifier override
no automatic HTTPS -> HTTP downgrade
no dynamic cleartext whitelist for arbitrary IPs in release
```

---

## Step 5.6 — Logging

Debug logging must redact:

```text
Authorization
Bearer tokens
password
API secrets
```

Never log full request bodies when they contain passwords.

Release builds must not emit body-level API logging.

---

## Step 5.7 — HTTP error mapping

Map:

```text
401 -> auth failure
403 -> scope/authorization failure
404 -> not found
409 -> conflict
422 -> validation/business failure
429 -> rate-limited/retryable according to policy
5xx -> transport/server failure
network timeout -> transport/inconclusive depending mutation state
```

Honor:

```text
Retry-After
```

when the retry layer actually retries.

Do not make a mutation endlessly retry automatically.

---

## Step 5.8 — Dual Testing Harness: Unit Error Mapping + MockWebServer Wire Contract

Both test suites are required with distinct responsibilities:

1. **Unit Error Mapping Suite (`SammGatewayErrorMappingTest`):**
   - Uses a **Fake `SammApiService`** returning synthetic Retrofit `Response.success`/`Response.error`.
   - Fast, JVM-only verification of business logic and HTTP code mapping (401, 403, 404, 409, 422, 429, 5xx -> `SasGatewayException`).

2. **Wire Protocol Contract Suite (`SammWireContractTest`):**
   - Uses **`MockWebServer`** to verify real HTTP serialization and client pipeline behavior that fake services cannot catch:
     - Exact URL path prefix (`/api/v1/` placement, ensuring no `/api/v1/api/v1` or missing `/api/v1`)
     - Correct HTTP methods (GET, POST, PATCH)
     - Mandatory header injection: `Authorization: Bearer <token>` and `Accept: application/json`
     - Correct query parameter encoding (e.g. `search=<query>`, `limit`, `offset`)
     - Path parameter formatting (e.g. integer `/customers/123/renew`)
     - Request body JSON serialization (e.g. `CustomerCreate`, `ChangePassword`)
   - **Dependency Rule:** Add `testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")` to `app/build.gradle.kts`, strictly matching the project's pinned OkHttp version `4.12.0` from `libs.versions.toml` to prevent binary incompatibility.

---

# Task 6 — `SammGatewayImpl`

## Files

```text
core/network/samm/SammGatewayImpl.kt
```

---

## Step 6.1 — Read operations first

Implement and test:

```text
search
get customer
list plans if needed
/me connection test
```

Do not start with mutations.

---

## Step 6.2 — Search semantics

Current SAMM search supports:

```text
search
username
status
plan_id
...
```

Prefer exact username lookup when the current operation asks for one username.

Do not depend on `search=""` as a connectivity test.

---

## Step 6.3 — Normalize to one subscriber view

Map SAMM customer data into the minimal shared subscriber view.

Example conceptual fields:

```text
externalId
username
displayName
status
planId / planName
expiresAt
```

Do not pollute the shared model with:

```text
SAMM-only diagnostics
MikroTik metadata
billing internals
router inventory
```

unless a current application feature genuinely needs them.

### ID Type Coercion Boundary

- **Ingress (SAMM API -> Domain):** In `SammSubscriberDto`, `id` is an `Int`. Normalize it into `SasSubscriberView.subscriberId` as `id.toString()`, so domain and UI code work uniformly with `String` subscriber IDs.
- **Egress (Domain -> SAMM API):** When calling SAMM endpoints parameterized by `{customer_id}` (e.g. `/customers/{customer_id}/renew`), parse via `subscriberId.toIntOrNull() ?: throw SasBusinessException(statusCode = null, errorMessage = "Invalid SAMM customer ID integer: $subscriberId")`.

---

## Step 6.4 — Create subscriber / activation

Map current app activation intent to:

```text
POST /customers
```

Use the exact `CustomerCreate` body.

Important:

```text
password is required for SAMM creation
```

but:

```text
password must not be persisted in PendingExternalOperation
```

The pending payload can contain:

```text
username
firstname
lastname
mobile if needed
plan_id
localAccountId if already known
amount
provider
```

but not:

```text
password
```

---

## Step 6.5 — SAMM activation accounting rule

If `POST /customers` succeeds:

```text
provider mutation success
       +
existing local financial materialization
```

Do not additionally create a SAMM invoice unless explicitly required.

---

## Step 6.6 — Suspend

Map:

```text
disable/toggle off
```

to:

```text
POST /customers/{id}/suspend
```

Remember that SAMM documents live session disconnection via CoA.

---

## Step 6.7 — Activate

Map:

```text
enable/toggle on
```

to:

```text
POST /customers/{id}/activate
```

Do not emulate this through a generic PATCH if the dedicated endpoint is the proven product operation.

---

## Step 6.8 — Renew/extend

Use:

```text
POST /customers/{id}/renew
```

For app-driven renewal:

```text
generate_invoice=false
```

unless later product rules explicitly change this.

Record the returned:

```text
new_expiration
```

as provider evidence.

---

## Step 6.9 — Change plan

Use:

```text
POST /customers/{id}/assign-plan
```

Only map fields that correspond to the app's current operation intent.

Do not automatically set:

```text
generate_invoice=true
```

because the app has its own local financial ledger.

---

## Step 6.10 — Password

Use:

```text
PATCH /customers/{id}
```

with:

```text
password
```

Do not persist the password.

Do not include it in operation payloads.

Do not include it in exception messages.

---

## Step 6.11 — Unsupported refill

If caller requests:

```text
REFILL
```

for ALAMIRY:

```text
return Unsupported
```

Do not call:

```text
/invoices/{id}/pay
```

Do not create a fake mapping.

---

## Step 6.12 — Unsupported EarthLink-only actions

Do not fake:

```text
balance
prepaid
deposit
statement
test user
```

inside SAMM.

The provider router must expose the limitation explicitly.

---

# Task 7 — EarthLink SAS Adapter

## Purpose

Avoid forcing the full existing EarthLink gateway to implement a massive shared interface.

Create a thin adapter:

```text
EarthlinkSasGatewayAdapter
```

that implements only common proven operations.

It delegates to the existing:

```text
EarthlinkGateway
```

---

## Step 7.1 — Common operations only

Delegate only:

```text
search
read
create/activation if currently supported
suspend
activate
renew/extend
change password
change plan if proven equivalent
```

Do not move EarthLink-only operations into the SAS abstraction.

---

## Step 7.2 — Preserve EarthLink-only paths

These remain direct EarthLink dependencies where current product behavior requires them:

```text
login
balance
prepaid
deposit
statement
test-user
other EarthLink-specific portal actions
```

---

## Step 7.3 — Unsupported common operations

If EarthLink cannot support a common operation with equivalent semantics:

```text
Unsupported
```

Do not fabricate success.

---

## Step 7.4 — Regression gate

Run the full current unit suite after adapter wiring.

No EarthLink behavior should change simply because the adapter was introduced.

---

# Task 8 — SasGatewayRouter

## Purpose

Centralize provider selection.

---

## Step 8.1 — Provider identity

Use stable values:

```text
EARTHLINK
ALAMIRY
```

No arbitrary strings from UI code.

Centralize provider constants.

Reject:

```text
UNKNOWN
blank
null-equivalent values
typos
```

where possible.

---

## Step 8.2 — Routing rule

Conceptually:

```text
EARTHLINK -> EarthlinkSasGatewayAdapter
ALAMIRY   -> SammGatewayImpl
```

If ALAMIRY is selected but SAMM is not configured:

```text
throw explicit configuration/provider exception
```

Do not return EarthLink.

---

## Step 8.3 — Runtime configuration

The router must use the **current SAMM configuration**, not a stale startup snapshot.

A cached client is acceptable only if:

```text
config changes invalidate it
```

---

## Step 8.4 — Tests

Required:

```text
EARTHLINK -> EarthLink
ALAMIRY -> SAMM
ALAMIRY without config -> explicit failure
unknown provider -> explicit failure
no fallback under transport error
no fallback under 404
no fallback under auth error
```

---

# Task 9 — Durable Operations and Recovery

## Files

```text
domain/repository/Interfaces.kt
data/repository/Repositories.kt
```

---

## Step 9.1 — Provider capture before dispatch

Every durable operation must write:

```text
operationProvider
```

when the pending intent is created.

This is especially important for activation because a `LocalAccount` may not exist yet.

Required sequence:

```text
provider selected
      |
      v
build durable intent
      |
      v
persist PendingExternalOperation(operationProvider = selected)
      |
      v
dispatch external request
```

Never:

```text
dispatch
  |
  v
create LocalAccount
  |
  v
infer provider later
```

---

## Step 9.2 — Activation without existing LocalAccount

For a new subscriber activation:

```text
provider selected in UI
plan selected
pending operation created
operationProvider persisted
SAMM create called
response returns external customer ID
LocalAccount created/updated with same provider
local accounting materialized
```

If the SAMM response is lost:

```text
do not immediately create a second subscriber
```

First verify:

```text
GET /customers?username=<exact username>
```

and correlate to the pending intent.

---

## Step 9.3 — Renewal/extension verification

SAMM:

```text
GET /customers/{id}
```

can provide:

```text
expiration_date
```

and therefore can verify an expected renewal outcome.

The pending operation should contain enough non-secret intent data to evaluate the result, such as:

```text
externalId if known
username
previous expiration when known
operation type
expected provider
expected plan when applicable
```

Do not store password.

---

## Step 9.4 — Change-plan verification

Verify:

```text
customer.plan_id == expected target plan
```

before any retry after an ambiguous outcome.

---

## Step 9.5 — Suspend/activate verification

Use the SAMM customer state:

```text
status
```

to verify the intended state where a recovery policy exists.

---

## Step 9.6 — Password ambiguity

A password mutation is different.

Never add a password to:

```text
PendingExternalOperation
database
logs
analytics
Firebase
backup
```

If the provider response is lost:

```text
outcome = inconclusive
```

Do not invent an automatic safe retry mechanism without an observable non-secret verification path.

---

## Step 9.7 — Repository responsibility

`LocalLedgerRepositoryImpl` remains responsible for:

```text
durable operation state
materialization
local accounting
operation lifecycle
```

It must not contain:

```text
SAMM URL handling
Retrofit calls
SAMM JSON parsing
EarthLink REST implementation
```

Provider-specific verification belongs behind the provider boundary.

This is a critical correction to any implementation that simply changes:

```text
EarthlinkGateway -> SasGateway
```

while leaving provider-specific API logic embedded inside the repository.

---

## Step 9.8 — Recovery routing

For every pending operation:

```text
router/provider lookup
      |
      v
operationProvider
      |
      v
correct provider adapter + verifier
```

Never:

```text
repository -> "default EarthLink gateway"
```

---

## Step 9.9 — Existing recovery entry points

Update all currently known recovery paths, including:

```text
EarthlinkApp startup
SyncWorker
```

and any additional call site found during Task 0.

---

## Step 9.10 — Pending provider immutability

If:

```text
LocalAccount.operationProvider
```

changes after a pending operation was created:

```text
PendingExternalOperation.operationProvider
```

must remain unchanged.

Test explicitly.

---

# Task 10 — Application and ViewModel Wiring

## Files

```text
EarthlinkApp.kt
AppViewModelProvider.kt
EarthlinkSearchViewModel.kt
SyncWorker.kt
```

---

## Step 10.1 — EarthlinkApp

Expose a provider-aware runtime access path.

Do not permanently construct SAMM from startup settings.

Use one of:

```text
reconfigurable provider holder
```

or:

```text
factory that rebuilds the SAMM adapter when settings change
```

Target invariant:

```text
Save new SAMM host/token
      |
      v
current runtime provider uses new configuration
```

---

## Step 10.2 — Recovery wiring

Update:

```text
recoverColdStartOrphanedOperations(...)
sweepAndResolvePendingOperations(...)
```

to use provider-aware routing.

---

## Step 10.3 — AppViewModelProvider

Current application consumers must be classified.

Keep EarthLink direct for:

```text
AuthViewModel
DashboardViewModel
StatementViewModel
```

where the operation is explicitly EarthLink-specific.

Use provider-aware routing for:

```text
EarthlinkSearchViewModel
```

where subscriber operations can target either provider.

---

## Step 10.4 — EarthlinkSearchViewModel

Audit each function individually.

Expected classification:

```text
search/read          -> router
create activation    -> router
renew/extend         -> router
toggle active        -> router
change plan          -> router if parity proven
password             -> router
refill               -> EarthLink-only / unsupported on SAMM
balance              -> EarthLink-only
cost/prepaid         -> EarthLink-only
test user            -> EarthLink-only unless parity later proven
```

Do not replace every `gateway` reference with `router`.

That would collapse provider-specific functionality into the wrong abstraction.

---

## Step 10.5 — Preserve operation intent

Before launching asynchronous work, capture:

```text
account ID
provider
operation type
external subscriber ID
```

from the correct state snapshot.

Do not allow a user or coroutine race to change the provider target after the durable intent has been created.

---

# Task 11 — UI: SAMM Settings and Provider Selection

## Files

```text
ui/screens/SettingsScreen.kt
ui/screens/UserDetailScreenV2.kt
ui/screens/SammSettingsSection.kt
```

---

## Step 11.1 — SAMM settings

Expose:

```text
SAMM base URL
SAMM API token
Test connection
Save
```

Do not expose:

```text
username/password SAMM login
```

because the current API contract uses manually created bearer tokens.

---

## Step 11.2 — Base URL rules

Allow:

```text
IP address
hostname
HTTP
HTTPS
```

subject to the Android network-security policy.

Normalize:

```text
trailing slash
/api/v1 placement
```

so the Retrofit base URL is unambiguous.

Do not produce:

```text
/api/v1/api/v1
```

from user input.

Prefer accepting:

```text
http://10.0.0.10
```

or:

```text
https://samm.example.com
```

and internally append the API path consistently.

---

## Step 11.3 — Test connection

Test:

```text
GET /me
```

Result states:

```text
Connected
Invalid token
Insufficient configuration
TLS/network failure
Server unavailable
Unexpected response
```

Do not expose stack traces or tokens to the user.

---

## Step 11.4 — Provider selector

Display:

```text
Provider: EARTHLINK
Provider: ALAMIRY
```

The UI may display a friendlier label:

```text
SAMM
```

while the persisted provider key remains the agreed stable internal value.

---

## Step 11.5 — New account creation

The provider must be selected **before** activation begins.

Required flow:

```text
Choose provider
Choose plan
Enter account data
Create pending operation
Dispatch
```

The provider must not be inferred after subscriber creation.

---

## Step 11.6 — Existing account provider change

Allow only through an intentional edit flow.

Display warning:

```text
Changing provider changes where future ISP operations are sent.
This does not migrate the external subscriber.
Local history remains unchanged.
Pending operations keep their original provider.
```

Block provider change if there is an active pending operation for that account unless the current product semantics explicitly permit it.

---

## Step 11.7 — Reopen/restart verification

After changing provider:

```text
save
close
reopen
```

must restore the same provider.

---

# Task 11A — Batch Provider Assignment

## Purpose

Provide a small account-management action for changing the provider assignment of multiple local accounts at once.

This is **not** a batch ISP API engine and does not perform external account migration.

It only updates:

```text
LocalAccount.operationProvider
```

for explicitly selected local accounts.

---

## Scope

Support:

```text
EARTHLINK
ALAMIRY
```

as target providers.

The action must never:

- call SAMM APIs;
- call EarthLink APIs;
- clone or migrate external subscribers;
- modify local financial history;
- modify `PendingExternalOperation.operationProvider`;
- create a second batch/outbox/reconciliation system.

---

## UI Placement and Screen Integration

Put the action in the existing account-list/account-management UI (`LocalAccountsScreen.kt`), not inside SAMM connection settings.

Settings remain responsible for:

```text
SAMM base URL
SAMM API token
connection test
```

Account management is responsible for:

```text
provider assignment
```

---

## Selection Model and Memory Safety

The account list should support:

```text
single account selection
multi-selection
Select All Visible
Select All Results (query-driven)
```

### Memory-Safe "Select All Results" & Filter Predicate Reuse
> **Critical Rule:** The batch operation must reuse the **exact same search/filter predicate** as the active account-list query.
>
> When `Select All Results` is chosen with active search/filter criteria, resolve matching account IDs via a lightweight projection query:
> ```sql
> SELECT id FROM local_accounts WHERE ... [current filter predicate]
> ```
> This keeps memory consumption minimal (returning only strings) and executes efficiently even on large account datasets.
>
> **Mandatory Guarantee:** The batch cannot affect accounts outside the current filtered result set.

Useful filters:

```text
Provider:
  All
  EarthLink
  SAMM
```

This allows common operations such as:

```text
Filter: EarthLink
Select All Results
Target: SAMM
Apply
```

---

## Batch Action Flow & TOCTOU Protection

```text
Account list (LocalAccountsScreen)
   |
   v
Select accounts (or Select All Results IDs)
   |
   v
Choose target provider
   |
   v
Preview Dialog (Informational only — estimates based on t0 read state)
   |
   v
Confirm / Apply
   |
   v
Room @Transaction (MANDATORY AUTHORITATIVE RE-CHECK INSIDE TRANSACTION)
   |-- 1. Re-query current active in-flight accounts from pending_external_operations
   |-- 2. Query accounts already set to targetProvider
   |-- 3. Filter candidate list to strictly eligible accounts
   |-- 4. Bulk UPDATE eligible accounts
   |-- 5. Return BatchProviderResult breakdown
   v
UI displays interpretable result
```

No network request is performed by the batch operation.

---

## Preview Before Confirmation (Informational Only)

> **Architectural Guarantee:** The preview is strictly **informational and non-authoritative**. It calculates provisional counts based on UI read state at time $t_0$.
>
> On **Apply**, the authoritative active-pending check and the provider assignment must happen together inside **ONE Room transaction**.
> The transaction must re-read active pending operations directly from the database and must **never** rely on the earlier preview numbers.

Before committing, show:

```text
Confirm provider change

Selected accounts: N

EarthLink -> SAMM: X
SAMM -> EarthLink: Y
Already assigned to target: Z
Skipped because of active in-flight operation: P
```

Also state:

```text
Local account IDs will not change.
Local financial history will not change.
Pending operations keep their original provider.
This does not migrate the external subscriber.
```

---

## Active Pending Operations (TOCTOU Invariant Protection)

Active in-flight operations in the repository encompass all non-terminal states:

```sql
status NOT IN ('COMPLETED', 'FAILED')
```

*(This covers `PENDING`, `DISPATCHING`, and `RESOLVING` as proven by `Repositories.kt`).*

### Classic TOCTOU Guard
A preview shows the state at time $t_0$. Between preview display and the user clicking "Apply" ($t_1$), a new pending operation may have been dispatched.

**Therefore, the exclusion of active accounts MUST be re-evaluated inside the exact same Room transaction that performs the update.**

Do not modify:

```text
PendingExternalOperation.operationProvider
```

even when its parent `LocalAccount.operationProvider` is changed.

---

## Persistence, Room Transaction, and Interpretable Result

Apply all eligible provider changes in **one Room transaction**.

### Typed Result Model
```kotlin
data class BatchProviderResult(
    val totalSelected: Int,
    val updated: Int,
    val alreadyTarget: Int,
    val skippedDueToPending: Int
)
```

### DAO & Repository Implementation

In `LocalAccountDao`:

```kotlin
@Query("""
    SELECT DISTINCT accountId 
    FROM pending_external_operations 
    WHERE status NOT IN ('COMPLETED', 'FAILED') 
      AND accountId IN (:accountIds)
""")
suspend fun getAccountIdsWithActiveInFlightOperations(accountIds: List<String>): List<String>

@Query("SELECT id FROM local_accounts WHERE id IN (:accountIds) AND operationProvider = :targetProvider")
suspend fun getAccountIdsAlreadyOnProvider(accountIds: List<String>, targetProvider: String): List<String>

@Query("""
    UPDATE local_accounts 
    SET operationProvider = :targetProvider, updatedAt = :now 
    WHERE id IN (:eligibleAccountIds)
""")
suspend fun updateOperationProviderForAccounts(
    eligibleAccountIds: List<String>, 
    targetProvider: String, 
    now: Long = System.currentTimeMillis()
)
```

In `LocalAccountRepositoryImpl`:

```kotlin
@Transaction
suspend fun batchSetProvider(candidateAccountIds: List<String>, targetProvider: String): BatchProviderResult {
    // 1. RE-CHECK active in-flight operations inside the transaction (TOCTOU safety)
    val activeInFlightIds = localAccountDao.getAccountIdsWithActiveInFlightOperations(candidateAccountIds).toSet()
    
    // 2. Identify accounts already on target provider (avoid redundant updates)
    val alreadyOnTargetIds = localAccountDao.getAccountIdsAlreadyOnProvider(candidateAccountIds, targetProvider).toSet()
    
    // 3. Determine strictly eligible accounts
    val eligibleIds = candidateAccountIds.filterNot { it in activeInFlightIds || it in alreadyOnTargetIds }
    
    // 4. Atomic update
    if (eligibleIds.isNotEmpty()) {
        localAccountDao.updateOperationProviderForAccounts(eligibleIds, targetProvider)
    }
    
    // 5. Return verifiable, interpretable metrics for the UI
    return BatchProviderResult(
        totalSelected = candidateAccountIds.size,
        updated = eligibleIds.size,
        alreadyTarget = alreadyOnTargetIds.size,
        skippedDueToPending = activeInFlightIds.size
    )
}
```

On success:

```text
all eligible LocalAccount rows
    -> target operationProvider
UI displays: "Updated: 472, Already SAMM: 18, Skipped: 10"
```

On transaction failure:

```text
Atomic SQLite rollback -> 0 rows updated
UI displays: "Batch update failed: [error details]"
```


---

## No External Side Effects

The batch action is local metadata management only.

It must not:

```text
POST /customers
POST /customers/{id}/renew
POST /customers/{id}/assign-plan
POST EarthLink mutation
```

or perform any other provider API call.

After the batch completes, the next provider-dependent operation for each account uses the newly assigned provider through `SasGatewayRouter`.

---

## Required Tests

### Batch Selection

- [ ] Select one account.
- [ ] Select multiple accounts.
- [ ] Select all visible accounts.
- [ ] Select all filtered results across pagination.
- [ ] Filtered batch isolation test: Proves that executing a batch update with an active filter strictly modifies only matching accounts and leaves all accounts outside the filtered result set untouched.
- [ ] Empty selection cannot execute the action.

### Provider Assignment

- [ ] EarthLink accounts can be assigned to SAMM.
- [ ] SAMM accounts can be assigned to EarthLink.
- [ ] Accounts already using the target provider are treated as no-op.
- [ ] Account IDs remain unchanged.
- [ ] Local financial history remains unchanged.

### Pending-Operation Safety

- [ ] Account with active pending operation is skipped.
- [ ] Existing `PendingExternalOperation.operationProvider` remains unchanged.
- [ ] Recovery still uses the pending operation's original provider.

### Transactionality

- [ ] Eligible accounts are updated in one Room transaction.
- [ ] Simulated transaction failure does not leave a partially updated batch.

### Routing Consequence

After the batch:

```text
LocalAccount.operationProvider = SAMM
```

must cause the next provider-dependent operation to route through:

```text
SasGatewayRouter -> SAMM
```

and similarly for EarthLink.

### No External Side Effects

- [ ] Batch assignment performs zero SAMM API requests.
- [ ] Batch assignment performs zero EarthLink API requests.

---

# Task 12 — Adversarial Integration Test Kit

Create tests that fail when the actual production wiring is wrong.

Do not settle for tests proving that classes implement interfaces.

---

## 12.1 Provider routing

Test:

```text
same account + EARTHLINK -> EarthLink adapter
same account + ALAMIRY -> SAMM adapter
```

---

## 12.2 No fallback

Force:

```text
SAMM timeout
SAMM 401
SAMM 404
SAMM 500
SAMM not configured
```

Assert:

```text
EarthLink call count == 0
```

---

## 12.3 New-account provider capture

Test:

```text
select ALAMIRY
create activation intent
verify pending.operationProvider == ALAMIRY
```

Do this before a `LocalAccount` exists.

---

## 12.4 Pending provider immutability

Test:

```text
pending provider = ALAMIRY
account provider later changed to EARTHLINK
recovery still uses ALAMIRY
```

---

## 12.5 Account identity preservation

Test:

```text
account id before provider change == account id after provider change
```

No history loss.

---

## 12.6 Persistence

Test:

```text
write ALAMIRY
restart database/app
read ALAMIRY
```

---

## 12.7 Serialization/sync

Test:

```text
LocalAccount -> serialized payload -> restored account
```

Confirm:

```text
operationProvider preserved
credentials absent
```

If the sync system intentionally excludes provider state, test that behavior explicitly instead.

---

## 12.8 Credential isolation

Test:

```text
operator A -> SAMM A
logout/switch
operator B -> SAMM B
```

Confirm no token leakage.

---

## 12.9 Runtime configuration refresh

Test:

```text
configure SAMM A
call /me -> A

change config to SAMM B
call /me -> B
```

This catches stale `lazy` client bugs.

---

## 12.10 Unsupported refill

Test:

```text
ALAMIRY + REFILL
```

must produce:

```text
Unsupported
```

and make:

```text
0 SAMM mutation calls
0 EarthLink fallback calls
```

---

## 12.11 SAMM exact API behavior

Use MockWebServer to prove:

```text
GET /api/v1/customers
POST /api/v1/customers
GET /api/v1/customers/{id}
POST /api/v1/customers/{id}/renew
POST /api/v1/customers/{id}/suspend
POST /api/v1/customers/{id}/activate
PATCH /api/v1/customers/{id}
POST /api/v1/customers/{id}/assign-plan
GET /api/v1/me
```

are actually generated correctly.

---

# Task 13 — Test-Quality / Anti-Slop Gate

Every new test must answer three questions:

## Claim

What production behavior is being proved?

Example:

```text
ALAMIRY operations never fall back to EarthLink.
```

## Seam

What real boundary is exercised?

Examples:

```text
router
repository
ViewModel
MockWebServer
Room migration
EncryptedSharedPreferences
```

## Independent Oracle

What observation proves the claim?

Examples:

```text
adapter call count
HTTP request path
database column value
serialized JSON
provider ID
response value
```

Reject tests that only prove:

```text
class implements interface
method exists
constructor accepts object
```

unless a structural property itself is the intended requirement.

---

# Task 14 — Real SAMM Lab E2E Verification

## Purpose

Prove the integration against the actual SAMM 5.1.15 installation.

Do this only on controlled test subscribers.

---

## Step 14.1 — Configure

In the app:

```text
SAMM URL
SAMM token
Test connection
```

Expected:

```text
/me -> 200
```

---

## Step 14.2 — Read

Use a dedicated lab subscriber.

Verify:

```text
search
read
status
plan
expiration
```

against SAMM portal/API.

---

## Step 14.3 — Create activation

Create a disposable test subscriber.

Capture:

```text
before count
request
HTTP response
returned customer ID
after count
username
plan
status
```

Confirm:

```text
no duplicate subscriber
```

---

## Step 14.4 — Renewal

Capture:

```text
before expiration
POST renew
returned new_expiration
GET customer after mutation
```

Verify:

```text
new expiration is correct
local ledger materialization is correct
no duplicate SAMM invoice was created when invoice generation is disabled
```

---

## Step 14.5 — Suspend/activate

Verify both:

```text
SAMM status
```

and, where practical:

```text
live-session behavior
```

---

## Step 14.6 — Password change

Use a disposable test account.

Verify using a safe authentication check.

Do not print the password into logs or documentation.

---

## Step 14.7 — Change plan

If included in the first implementation, verify:

```text
plan before
assign-plan
plan after
```

and confirm the app did not create an unexpected SAMM invoice.

---

## Step 14.8 — Recovery / lost ACK simulation

Where safely possible, simulate:

```text
request succeeds
response is lost
```

Then verify:

```text
activation -> existing customer discovered
renewal -> expiration reflects change
plan change -> target plan reflected
```

Confirm the app does not blindly duplicate the mutation.

For password mutation, verify that the integration returns an explicitly inconclusive/manual-retry outcome instead of storing the password.

---

# Task 15 — Final Regression and Release Evidence

Run all relevant suites.

## 15.1 JVM

```bash
./gradlew :app:testDebugUnitTest
```

---

## 15.2 Instrumentation

```bash
./gradlew :app:connectedDebugAndroidTest
```

---

## 15.3 Build

```bash
./gradlew :app:assembleDebug
```

and the release-equivalent task used by the repository if applicable.

---

## 15.4 Production source scan

Search for accidental leakage:

```text
samm_
Authorization
Bearer
password
token
```

Check:

```text
logs
exceptions
Firebase payloads
Room entities
pending operation payloads
analytics
debug output
```

---

## 15.5 Final evidence document

Create:

```text
docs/samm/INTEGRATION_EVIDENCE.md
```

Include:

```text
repository commit
SAMM version
OpenAPI version
implemented operations
unsupported operations
scope set
test counts
unit result
instrumentation result
lab result
known limitations
```

Do not include:

```text
real token
real password
SSH credentials
secret configuration
subscriber passwords
```

---

# 16. Exact Scope of Initial Implementation

## Implement now

```text
SAMM connection settings
SAMM bearer authentication
/me connection test

search subscriber
read subscriber

create subscriber / activation
suspend
activate
renew / extension if proven equivalent
change plan if proven equivalent
change password

provider routing
provider persistence
pending provider persistence
provider-aware recovery

EarthLink regression preservation
```

---

# 17. Explicitly Not Implemented

Do not expand this task into:

```text
SAMM reseller balance
SAMM deposit
SAMM refill
SAMM billing synchronization
SAMM invoice management
SAMM webhooks
SAMM router management
MikroTik configuration
RADIUS management
FreeRADIUS management
network diagnostics
router scripts
bulk operations
undo
reverse transactions
provider migration engine
dual-ledger accounting
generic reconciliation framework
generic multi-ISP framework
```

SAMM has APIs for many of those features, but their existence does not make them part of this product integration.

---

# 18. Critical Edge Cases the Agent Must Handle

## 18.1 SAMM not configured

```text
ALAMIRY selected
SAMM host/token missing
```

Result:

```text
clear configuration error
```

not:

```text
EarthLink fallback
```

---

## 18.2 Invalid token

```text
401
```

Result:

```text
auth/config error
```

Do not retry indefinitely.

---

## 18.3 Insufficient scope

```text
403
```

Result:

```text
permission error
```

Do not retry against EarthLink.

---

## 18.4 Existing customer not found

```text
404
```

Result:

```text
provider-specific not-found
```

Do not reinterpret it as:

```text
try other provider
```

---

## 18.5 Duplicate create

```text
409
```

Treat as:

```text
possible existing external state
```

For activation, verify by exact username before deciding whether a retry/create is safe.

---

## 18.6 Validation failure

```text
422
```

Display a business-validation error.

Do not classify as transport failure.

---

## 18.7 Rate limit

```text
429
```

Use:

```text
Retry-After
```

where appropriate.

Do not aggressively retry financial mutations.

---

## 18.8 Server error after mutation submission

If the operation may already have reached SAMM:

```text
do not blindly replay
```

First use provider-specific verification when safe.

---

## 18.9 Local account provider changes while pending

Pending operation keeps:

```text
original provider
```

---

## 18.10 New activation with no LocalAccount

Provider is persisted in:

```text
PendingExternalOperation
```

before external dispatch.

---

# 19. Required Review Before Any "Refactor" Expansion

The agent must stop and reconsider if implementation starts introducing:

```text
GenericProviderManager
ProviderRegistry
ExternalReconciliationEngine
SyncStateMachine
SecondOutbox
SecondLedger
ProviderIdentityRegistry
RuntimeGovernanceRegistry
```

Those are not required for this integration.

The intended architecture remains:

```text
minimal seam
+
provider router
+
EarthLink adapter
+
SAMM adapter
+
provider-aware recovery
```

---

# 20. Final Definition of Done

All of the following must be true.

## Provider model

- [ ] ALAMIRY/SAMM exists as a selectable provider.
- [ ] Provider identity persists across app restart.
- [ ] Existing accounts migrate to `EARTHLINK`.
- [ ] New activations capture provider before external dispatch.
- [ ] Pending operations preserve provider identity.
- [ ] Provider changes preserve local account ID and history.
- [ ] Provider changes do not mutate existing pending-operation provider.

## Routing

- [ ] EARTHLINK routes only to EarthLink adapter.
- [ ] ALAMIRY routes only to SAMM adapter.
- [ ] Unknown provider fails explicitly.
- [ ] Missing SAMM configuration fails explicitly.
- [ ] No cross-provider fallback exists.
- [ ] No provider is inferred from username or availability.

## SAMM API

- [ ] Actual SAMM OpenAPI is stored in project docs.
- [ ] Server version is recorded as discovered at implementation time.
- [ ] Bearer token authentication is implemented.
- [ ] `/me` is the connection test.
- [ ] Exact SAMM paths are used.
- [ ] Exact SAMM DTO fields are used.
- [ ] Error mapping is typed.
- [ ] Rate-limit behavior is understood.
- [ ] HTTP/HTTPS behavior is safe.
- [ ] No TLS bypass exists.
- [ ] No secret is logged.

## Core operations

- [ ] Search works.
- [ ] Read works.
- [ ] Activation/create works where parity is proven.
- [ ] Suspend works.
- [ ] Activate works.
- [ ] Renewal works.
- [ ] Extension works only if semantically proven equivalent.
- [ ] Plan change works only if semantically proven equivalent.
- [ ] Password change works.
- [ ] Refill is explicitly unsupported on SAMM.
- [ ] EarthLink-only operations remain EarthLink-specific.

## Financial semantics

- [ ] Local ledger remains the only app ledger.
- [ ] No second SAMM ledger is created.
- [ ] SAMM invoices are not generated by default for app-driven financial operations.
- [ ] Local accounting is not duplicated into SAMM billing.
- [ ] Refill does not silently map to invoice/payment.

## Reliability

- [ ] Durable provider intent is stored before dispatch.
- [ ] Lost responses do not automatically cause unsafe duplicate mutations.
- [ ] Activation recovery can identify an already-created SAMM customer.
- [ ] Renewal recovery can verify resulting expiration.
- [ ] Plan-change recovery can verify resulting plan.
- [ ] Suspend/activate recovery can verify resulting status.
- [ ] Password outcomes remain safe and do not store secrets.
- [ ] Recovery is provider-aware.
- [ ] `EarthlinkApp` recovery is provider-aware.
- [ ] `SyncWorker` recovery is provider-aware.

## Data and sync

- [ ] Room migration tested against actual schema.
- [ ] Existing rows preserved.
- [ ] `operationProvider` persists correctly.
- [ ] LocalAccount serialization audited.
- [ ] Sync behavior explicitly documented.
- [ ] SAMM credentials are never synced.
- [ ] Pending payloads contain no passwords.

## UI

- [ ] SAMM base URL can be configured.
- [ ] SAMM token can be configured securely.
- [ ] Connection test uses `/me`.
- [ ] Current provider is visible.
- [ ] Provider can be intentionally changed.
- [ ] New-account flow selects provider before dispatch.
- [ ] Configuration changes invalidate stale runtime client state.

## Batch provider assignment

- [ ] Multi-account provider assignment is available from account management (`LocalAccountsScreen`).
- [ ] Select All Results works with active filters/search.
- [ ] Preview shows exact affected, unchanged, and skipped counts.
- [ ] Accounts with active pending operations are safely excluded.
- [ ] Eligible changes are committed atomically in one Room transaction.
- [ ] No external API calls occur during assignment.
- [ ] No new database table or batch engine is introduced.
- [ ] Provider changes take effect through the existing `SasGatewayRouter`.

## Testing

- [ ] Baseline test result recorded before modifications.
- [ ] Unit tests pass.
- [ ] Instrumentation tests pass.
- [ ] Migration test passes.
- [ ] Credential/security tests pass.
- [ ] MockWebServer contract tests pass.
- [ ] Provider isolation tests pass.
- [ ] No-fallback tests pass.
- [ ] New-account provider-capture test passes.
- [ ] Recovery provider-routing test passes.
- [ ] Runtime configuration refresh test passes.
- [ ] Unsupported-refill test passes.
- [ ] Real SAMM lab smoke test passes for implemented operations.

## Documentation

- [ ] `docs/samm/openapi.json`
- [ ] `docs/samm/SAMM_API_CONTRACT.md`
- [ ] `docs/samm/PROVIDER_OPERATION_MATRIX.md`
- [ ] `docs/samm/LAB_SMOKE_TEST_RESULTS.md`
- [ ] `docs/samm/INTEGRATION_EVIDENCE.md`

all exist and contain no secrets.

---

# 21. Execution Order

The agent should execute strictly in this order:

```text
Task 0
  |
  v
Task 1
  |
  v
Task 2
  |
  v
Task 3
  |
  v
Task 4
  |
  v
Task 5
  |
  v
Task 6
  |
  v
Task 7
  |
  v
Task 8
  |
  v
Task 9
  |
  v
Task 10
  |
  v
Task 11
  |
  v
Task 11A
  |
  v
Task 12
  |
  v
Task 13
  |
  v
Task 14
  |
  v
Task 15
```

Do not skip directly from:

```text
API docs
```

to:

```text
UI integration
```

Do not skip recovery testing because the happy path works.

Do not call the integration complete because the project compiles.

---

# 22. Final Architectural Rule

The implementation is correct only when the runtime relationship is:

```text
User Intent
     |
     v
Provider selected
     |
     v
Durable provider identity captured
     |
     v
SasGatewayRouter
     |
     +------------------+
     |                  |
     v                  v
EarthLink adapter     SAMM adapter
     |                  |
     v                  v
Provider API         SAMM REST
     |                  |
     +---------+--------+
               |
               v
      provider-specific
        verified outcome
               |
               v
      existing local
       materialization
               |
               v
      existing local
       financial model
```

Never:

```text
UI -> provider-specific HTTP
```

Never:

```text
repository -> direct SAMM REST
```

Never:

```text
timeout -> other provider
```

Never:

```text
SAMM billing -> local ledger
```

Never:

```text
password -> durable operation payload
```

Never:

```text
provider change -> external migration
```

The goal is a **small, explicit provider seam**, not a new framework.