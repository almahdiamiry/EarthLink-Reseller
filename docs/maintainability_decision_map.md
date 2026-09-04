# Final Maintainability Decision Map for EarthLink Reseller V1

## 1. Architectural Context and Core Separation

This decision map establishes the maintainability baseline and feature extensibility evaluation for the EarthLink Reseller V1 repository on current HEAD (`0dee706`).

```text
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                EARTHLINK RESELLER V1                                   │
├──────────────────────────────────────────┬─────────────────────────────────────────────┤
│         PROTECTED / FROZEN CORE          │              EXTENSION SURFACE              │
├──────────────────────────────────────────┼─────────────────────────────────────────────┤
│ • Additive ledger math (INV-01, INV-02)  │ • Subscriber profile metadata               │
│ • Single-writer dispatch (INV-03)        │ • Read-only gateway telemetry & queries     │
│ • Canonical materialization (INV-04)     │ • Customer detail cards & sections          │
│ • 4-tuple statement recovery (INV-05)    │ • Dashboard list filters & chips            │
│ • Atomic Room restore & lineage (INV-06) │ • External device telemetry (MikroTik/UBNT) │
│ • Durable outbox & recovery (INV-08)     │ • Non-financial UI actions & helpers        │
│ • uTower import engine (INV-09)          │ • Display-only string sanitizers            │
├──────────────────────────────────────────┴─────────────────────────────────────────────┤
│ GOAL: Keep the frozen core stable and safe from incidental churn, while ensuring the   │
│       extension surface is discoverable, isolated, and inexpensive to change.          │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### Preliminary Priority Notice

All priorities recorded in this decision map represent preliminary architectural judgment. They reflect technical leverage and risk under current repository evidence, not an implementation schedule. When implementation planning occurs later, task ordering and sequencing may adapt based on specific product requirements, dependency trees, and risk boundaries.

Priority Tiers:
* **P1**: High-leverage future work (materially reduces future feature cost)
* **P2**: Meaningful consolidation (eliminates duplicated configuration/logic)
* **P3**: Low-risk cleanup (removes proven unreachable legacy code)
* **P4**: Accepted / deferred debt (intentional design or compatibility parameters)
* **CLOSED**: Historical items already resolved on current HEAD
* **REJECTED**: Disproven false positives that must remain separate
* **SUPERSEDED**: Historical cosmetic items with no runtime consequence

---

## 2. Extension Surface Stress-Test: Ubiquiti and MikroTik Telemetry

To evaluate whether ordinary user-facing features can be introduced through narrow seams without reopening the frozen core, we examine a realistic non-financial capability: displaying wireless CPE device telemetry (signal strength, model, MAC, IP, uptime, connection state) on the subscriber detail screen.

This analysis is strictly a maintainability stress-test of the extension surface. It is not an implementation plan or a feature design proposal.

### 2.1 Identity Analysis and the Device-Management Identity Gap

* **Observed in Current Code:**
  [`Models.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/model/Models.kt) lines 194 through 196 exposes:
  * `currentIP`: Live data-plane session IP assigned to the active PPPoE or Radius subscriber session. Sourced from gateway responses; transient lifecycle.
  * `currentMAC`: Data-plane MAC address learned by the NAS or BRAS for the active session.
  * `accountMAC`: Static profile MAC address provisioned on the Earthlink user account.
* **Identity / Access Contract Gap:**
  The application must not assume that `currentIP` is a device management IP or that `currentMAC` is the managed CPE identity. In fixed-wireless ISP deployments, `currentIP` is typically an internal client IP behind carrier-grade NAT (CGNAT) or tower subnetting. `currentMAC` may be the subscriber's indoor Wi-Fi router WAN port rather than the outdoor wireless CPE radio. A managed CPE radio frequently has a separate management IP or controller UUID that is not present in existing application data models.

### 2.2 Official Vendor Documentation Findings

* **MikroTik RouterOS:**
  * **API mechanisms and transport:** Official MikroTik RouterOS documentation confirms RouterOS v7 provides a REST API accessible over HTTPS (`www-ssl` service, TCP port 443) or HTTP (`www` service, TCP port 80). RouterOS v6 and v7 also support the proprietary RouterOS API on TCP port 8728 (plaintext) or TCP port 8729 (TLS-encrypted).
  * **Station telemetry endpoint:** Connected wireless client telemetry is queried via `POST /rest/interface/wireless/registration-table/print` (legacy wireless drivers) or `POST /rest/interface/wifi/registration-table/print` (modern WiFiWave2 and 802.11ax drivers).
  * **Telemetry fields:** Returns `signal-strength` (in dBm), `signal-to-noise`, `tx-rate`, `rx-rate`, `uptime`, `mac-address`, `interface`, and transmitted/received packet counts.
  * **Authentication:** HTTP Basic authentication over TLS (`Authorization: Basic <base64>`).
* **Ubiquiti (UBNT):**
  * **Platform distinction:** Ubiquiti maintains distinct platforms. UniFi targets enterprise and campus SDN Wi-Fi managed via UniFi Network Application. For fixed wireless broadband (WISP), Ubiquiti uses **airMAX** (LiteBeam, PowerBeam, Rocket) and **UISP** (formerly UNMS).
  * **UISP NMS REST API:** In WISP deployments, individual radios connect to a centralized UISP controller. The official UISP REST API documentation is exposed locally on every UISP console at `https://<uisp-hostname>/nms/api-docs/`. Telemetry endpoints include `/nms/api/v2.1/devices/{id}/detail` and `/nms/api/v2.1/devices/{id}/statistics`.
  * **airMAX standalone CPE status:** Direct airOS devices expose an internal status endpoint (`/status.cgi`) requiring session cookies or HTTP Basic authentication.
  * **Telemetry fields:** Station signal strength (composite and per-chain dBm), remote CCQ, noise floor, transmit/receive airtime capacity, frequency, channel width, distance, and link uptime.
  * **Authentication:** UISP utilizes dedicated API tokens passed via the `x-auth-token` HTTP request header.

### 2.3 Reachability Dimensions and Access Models

Direct IP-based device telemetry is technically feasible when the stored address is actually a reachable device-management address and the vendor management service, routing, firewall, authentication, and transport requirements permit access.

| Dimension | Result | Technical Explanation |
| :--- | :--- | :--- |
| Dedicated management IP possible? | Yes, technically possible | ISPs frequently assign dedicated management IPs to customer CPE radios |
| Does current app prove `currentIP` is management IP? | Unproven / Gap | `currentIP` represents subscriber data-plane session IP, not verified as management IP |
| Can Android reach it directly? | Environment-dependent | Direct access is feasible only when the stored address is a reachable management address and routing permits it (e.g. via corporate VPN); unroutable across public cellular without an intermediary |
| Does vendor management service need to be enabled? | Yes | `www-ssl` / REST API on MikroTik, airOS web service on Ubiquiti |
| Does routing/firewall permit it? | Environment-dependent | Management access must not be blocked by intermediate firewalls or client isolation |
| Is direct vendor API technically possible? | Yes, verified per vendor | Official RouterOS REST API and airOS status endpoints exist |
| Is NMS/controller architecture possible? | Yes, verified per vendor | Official Ubiquiti UISP REST API exists; MikroTik TR-069 / controller exists |
| Current repository device identity established? | Identity / Contract Gap | Current models do not store controller device UUIDs or verify CPE wireless MAC mapping |

---

## 3. The Reconciled Maintainability Decision Map

| ID | Area | Current State | Concrete Evidence | Maintainability Impact | Extensibility Impact | Frozen-Core Impact | Future-Feature Friction | Preliminary Priority | Decision | Possible Implementation Direction |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **MNT-01** | Dead code | Completely removed from codebase | Deleted in commit `e022487` (Observed in git log) | Eliminates confusion regarding active customer screen | Positive | None | None | **CLOSED** | **CLOSED / RESOLVED** | None. Settled in git history. |
| **MNT-02** | Synchronization | Completely removed from codebase | Deleted in commit `1763e3d` (Observed in git log) | Eliminates orphaned locking delegate | Neutral | None | None | **CLOSED** | **CLOSED / RESOLVED** | None. Settled in git history. |
| **MNT-03** | Build config | Cleaned and deduplicated | Verified in commit `1763e3d` and [`build.gradle.kts:144-195`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/build.gradle.kts) (Observed now) | Prevents dependency confusion and warning noise | Neutral | None | None | **CLOSED** | **CLOSED / RESOLVED** | None. Settled in git history. |
| **MNT-04** | Dead code | Legacy, unreachable code | [`MoneyParser.kt:9-29, 68-72, 184-222, 227-230`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/ledger/MoneyParser.kt) (Observed now: 0 static callers in main or test) | Low. Deprecated threshold methods could mislead future financial code | Neutral | Low | None | **P3** | **DELETE CANDIDATE** | Remove unreachable deprecated methods and `MoneyValue` sealed interface without touching active whole-IQD parsers. |
| **MNT-05** | Repository seam | Properly delegated through repository interface | Refactored in commit `0dee706` to delegate to `UtowerImportRepository.importUtowerTgz` (Observed in git log) | Enforces single entry point for uTower archive extraction | Positive | Protected / must not touch | None | **CLOSED** | **CLOSED / RESOLVED** | None. Settled in git history. |
| **MNT-06** | ViewModel API | Unused compatibility parameter | [`LocalAccountsViewModel.kt:39`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/viewmodels/LocalAccountsViewModel.kt); passed by `AppViewModelProvider.kt:50` and 2 test harnesses (Observed now) | Negligible. Marked with `@Suppress("unused")` | Neutral | None | None | **P4** | **ACCEPTED YELLOW** | Retain as documented compatibility parameter to prevent constructor churn in tests. |
| **MNT-07** | Duplication | Duplicated configuration array | [`UtowerImporter.kt:121-137`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/sync/UtowerImporter.kt) and [`Repositories.kt:2464-2480`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/data/repository/Repositories.kt) share identical 15 patterns (Observed now) | Low. Date parsing patches must be synchronized across both files | Low friction | Low | Low | **P2** | **CONSOLIDATE CANDIDATE** | Share the 15 date pattern strings through a common internal definition within the uTower ingestion package. |
| **MNT-08** | Duplication | Duplicated presentation text sanitization | [`DashboardScreen.kt:98-104`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/DashboardScreen.kt) and [`SharedComponents.kt:128-134, 240-246`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/SharedComponents.kt) duplicate loops and regexes (Observed now) | Low to medium. Presentation text sanitization duplicated across cards | Low friction | None | Low | **P2** | **CONSOLIDATE CANDIDATE** | Consolidate numeral mapping and expiration string cleaning into a single presentation helper function. |
| **MNT-09** | Execution ownership | Resolved via ViewModel boundary seam | Refactored in `EarthlinkSearchViewModel.kt` and `UserDetailScreenV2.kt`; verified by `EarthlinkSearchViewModelSeamTest.kt` | UI-level backend coroutines eliminated; zero boundary leaks | Positive | Protected / preserved | None | **CLOSED** | **CLOSED / RESOLVED** | Complete. Durable ViewModel execution boundary established for payment, debt, refill, and metadata updates. Behavior-preservation gate closed with exception `BPE-MNT09-01`. |
| **MNT-10** | Presentation | Disproven false positive | [`UserDetailScreenV2.kt:69-158`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt) pairs operations with positive debt; [`LocalAccountDetailScreen.kt:332`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/LocalAccountDetailScreen.kt) renders raw negative ledger rows (Observed now) | High risk of financial presentation corruption if merged | Strongly impedes | None | High | **REJECTED** | **FALSE POSITIVE** | Preserve existing intentional separation between customer statement grouping and raw ledger audit feeds. |
| **MNT-11** | File size | Monolithic source files | [`Repositories.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/data/repository/Repositories.kt) (3,388 lines), [`UserDetailScreenV2.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt) (3,174 lines). Historical verification record: compiles without errors and passes regression test suites in baseline evidence (`evidence/`) | Low. Navigability requires symbol search, but code remains stable in production history | Medium friction | Protected / must not touch | Medium | **P4** | **ACCEPTED YELLOW** | Retain files intact per `AGENTS.md` section 5. Do not refactor or split solely for line count. |
| **MNT-12** | Database | 16 sequential Room migrations | [`AppDatabase.kt:145-310`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/database/AppDatabase.kt). Historical verification record: migration chain executed in under 15ms during cold start kill-point certification (`evidence/`) | Low. Historical benchmarks prove migration chain executes under 15ms | Neutral | Protected / must not touch | None | **P4** | **ACCEPTED YELLOW** | Retain migration chain intact per `AGENTS.md` section 5. |
| **MNT-13** | Database | Double currency representation | SQLite `REAL` columns in Room entity tables; guarded by whole-IQD validators (Observed now) | Low. Legacy float/double columns in SQLite are safely guarded at runtime by domain math | Neutral | Protected / must not touch | None | **P4** | **ACCEPTED YELLOW** | Retain existing schema representation per `AGENTS.md` section 5. Guard with domain validation. |
| **MNT-14** | API hierarchy | Two-tier ledger recording methods | [`Interfaces.kt:72-99`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/domain/repository/Interfaces.kt) separates low-level inserts from workflows (Observed now) | None. Layering is deliberate for test harnesses and atomic workflows | Neutral | Protected / must not touch | None | **P4** | **INTENTIONAL** | Retain two-tier API structure. Document distinct roles in interface documentation. |
| **MNT-15** | Legacy API | Documented maintenance-only methods | [`Interfaces.kt:96, 118, 126`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/domain/repository/Interfaces.kt); documented with KDoc warnings in commit `e41223a` (Observed now) | Low. Methods are isolated and marked with `@Deprecated` or KDoc warnings | Neutral | Protected / must not touch | None | **P4** | **ACCEPTED / DEFERRED** | Retain documented maintenance helpers for backward compatibility with Phase 3 test harnesses. |
| **MNT-16** | Legacy fields | Read-only historical semantic fields | Retained in `LocalAccount` and Room entities for historical snapshot compatibility (Observed now) | Low. Fields are read-only and provide historical context for uTower | Neutral | Protected / must not touch | None | **P4** | **ACCEPTED YELLOW** | Retain as read-only historical context per `AGENTS.md` section 5. |
| **MNT-17** | Packaging | Non-standard package location | `PdfStatementGenerator` and `ExpiryNotificationManager` reside in `com.example.core.sync` (Observed now) | Negligible. Placement causes zero runtime defects or architectural leakage | Neutral | None | None | **SUPERSEDED** | **SUPERSEDED** | Do not move classes solely for cosmetic package purity. |
| **MNT-18** | Security | Centralized encryption handling | [`PreferenceManager.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/security/PreferenceManager.kt) manages AES and SQLCipher passphrase initialization. Repository structural evidence: stable implementation | High sensitivity. Centralized encryption functions reliably in production | Neutral | Protected / must not touch | None | **P4** | **INTENTIONAL** | Retain security implementation intact. Avoid modifications that could jeopardize database unlocking. |

---

## 4. Key Findings

### 4.1 Highest-Leverage Finding: MNT-09 (UI/Backend Execution Ownership on RED-Adjacent Paths)

* **Historical Problem:**
  In [`UserDetailScreenV2.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt), UI composables directly called backend subsystems exposed as public properties by [`EarthlinkSearchViewModel`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt). Beyond read queries (`gateway.getBalance()`, `gateway.getAccountCost(acctIdx)`), the Compose UI directly owned coroutines for financial mutations (`localLedgerRepository.recordAccountPayment(...)`, `localLedgerRepository.recordAccountDebt(...)`), executed uncoordinated local dual-writes (`repo.saveAccount(...)`), triggered cloud outbox synchronization (`syncRepo.requestSync(...)`), and hosted the local renewal callback lambda in `refillUser`. This coupled persistent writes to transient composable lifecycles.
* **Implementation Resolution:**
  Execution ownership of payment, debt, refill renewal, and metadata persistence moved into `EarthlinkSearchViewModel` running within `viewModelScope(Dispatchers.IO)`. Repository and gateway properties in `EarthlinkSearchViewModel` were made private, eliminating backend references and coroutine launches from `UserDetailScreenV2.kt`.
* **Behavior-Preservation Gate and Exception `BPE-MNT09-01`:**
  The BEFORE-vs-AFTER behavior-preservation gate formally closed with 11 permanent characterization unit tests in [`EarthlinkSearchViewModelSeamTest.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/test/java/com/example/ui/viewmodels/EarthlinkSearchViewModelSeamTest.kt) and 562/562 unit tests passing repository-wide.
  *Authorized Exception `BPE-MNT09-01` (Fail-Closed Metadata Dual-Write):* Under unexpected local Room database write failure, the refactored `updateUserDisplayName` and `changeAccountType` methods halt before the remote gateway call, preventing split-brain state where the remote ISP is updated while local persistence is broken. All standard success, remote-failure, and financial ledger paths remain strictly preserved.

### 4.2 Secondary Consolidation Findings: MNT-07 and MNT-08

* **MNT-07 (uTower date pattern duplication):**
  [`UtowerImporter.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/sync/UtowerImporter.kt) lines 121 through 137 and [`Repositories.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/data/repository/Repositories.kt) lines 2464 through 2480 define an identical list of 15 pattern strings in Asia/Baghdad timezone. Updating date formats requires modifying both files. Consolidating into a single shared definition within the ingestion package eliminates drift.
* **MNT-08 (Presentation numeral and regex sanitization duplication):**
  [`DashboardScreen.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/DashboardScreen.kt) lines 98 through 104 and [`SharedComponents.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/SharedComponents.kt) lines 128 through 134 and 240 through 246 contain identical loops mapping Eastern Arabic and Persian digits to ASCII, along with identical date regex routines. This belongs strictly to presentation text sanitization for gateway dates, not domain financial parsing.

### 4.3 Low-Risk Cleanup Candidate: MNT-04 (MoneyParser Unreachable Code)

* **Current Reality:**
  [`MoneyParser.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/ledger/MoneyParser.kt) lines 9 through 29 define `sealed interface MoneyValue`, lines 68 through 72 define `@Deprecated fun parseIqdAmount`, lines 184 through 222 define `fun parseUiInput`, and lines 227 through 230 define `@Deprecated fun normalizeUiInputToIqd`.
* **Evidence:**
  Ripgrep verified zero static call sites across `app/src/main` and `app/src/test`. Production code exclusively uses specialized whole-IQD parsers (`parseUiThousandsAmount`, `parseSubscriptionPriceIqd`, `parseRawIqd`, `parseUtowerAmount`). Deleting these deprecated methods is behavior-neutral and eliminates superseded threshold logic.

---

## 5. Preserved Negative Knowledge Register

To prevent future agents and maintainers from reopening settled investigations, the following findings are permanently recorded as closed, intentional, or false positives:

1. **`HistoryPresentationManager` unification is a false positive (MNT-10):**
   `HistoryPresentationManager` in [`UserDetailScreenV2.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt) formats customer-facing statements with paired charges and positive payment credits. [`LocalAccountDetailScreen.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/LocalAccountDetailScreen.kt) renders raw append-only ledger audit rows with negative numbers for debt. Merging them corrupts financial presentation semantics.
2. **Large source files are accepted technical debt (MNT-11):**
   [`Repositories.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/data/repository/Repositories.kt) (~3,388 lines) and [`UserDetailScreenV2.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt) (~3,174 lines) compile cleanly and run reliably in historical verification evidence (`evidence/`). Splitting them solely for line count violates [`AGENTS.md`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/AGENTS.md) section 5.
3. **Sequential Room migrations are accepted technical debt (MNT-12):**
   The 16 sequential Room migrations in [`AppDatabase.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/database/AppDatabase.kt) execute in under 15ms in historical verification evidence. Consolidating migrations creates high schema regression risks without operational benefit.
4. **Two-tier ledger API is intentional layering (MNT-14):**
   `addPayment` and `addDebt` in [`Interfaces.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/domain/repository/Interfaces.kt) are low-level table insertion primitives for tests and batch imports; `recordAccountPayment` and `recordAccountDebt` execute full business workflows with validation. Retaining both tiers is deliberate.
5. **`appDatabase` parameter in `LocalAccountsViewModel` is accepted (MNT-06):**
   Explicitly referenced in [`AppViewModelProvider.kt:50`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/AppViewModelProvider.kt) and test harnesses. Retaining it prevents constructor churn across test suites.
6. **Centralized security handling in `PreferenceManager.kt` is intentional (MNT-18):**
   Manages SQLCipher passphrase initialization. Modifications risk database startup failures.

---

## 6. Evidence and Verification Status

* **OBSERVED NOW (Current Turn):**
  * Clean git working tree with `utower_data_c.tgz` properly ignored.
  * Static ripgrep reference analysis confirming zero callers for deprecated symbols in [`MoneyParser.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/ledger/MoneyParser.kt).
  * Direct file inspection confirming duplicate 15 date pattern strings in [`UtowerImporter.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/sync/UtowerImporter.kt) and [`Repositories.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/data/repository/Repositories.kt).
  * Direct call-site tracing confirming direct repository and gateway usage in [`UserDetailScreenV2.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt).
* **KNOWN FROM PREVIOUS REPOSITORY EVIDENCE:**
  * Commits `e022487`, `1763e3d`, and `0dee706` recorded in git log as closing dead code, build bloat, and uTower seam bypasses.
  * Domain field authority recorded in [`account_field_authority_classification.md`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/docs/authority/account_field_authority_classification.md).
  * Accepted technical debt recorded in [`AGENTS.md`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/AGENTS.md) section 5.
* **VERIFIED FROM CURRENT OFFICIAL EXTERNAL DOCUMENTATION:**
  * Official MikroTik RouterOS v7 documentation: REST API mechanisms (`/rest/interface/wireless/registration-table/print`, `/rest/interface/wifi/registration-table/print`, `www-ssl` HTTPS port 443, HTTP Basic auth).
  * Official Ubiquiti documentation: UISP NMS REST API architecture (`/nms/api/v2.1/devices/{id}/statistics`, Swagger UI at `https://<uisp-hostname>/nms/api-docs/`, `x-auth-token` token auth, airMAX WISP vs UniFi platform distinction).
* **HISTORICAL VERIFICATION RECORDS:**
  * Certified unit test baseline: 535/535 tests passing recorded in baseline manifest.
  * 79 G8 adversarial verification records stored in `evidence/`.
  * Room migration sequence execution in under 15ms recorded in kill-point tests.
* **UNVERIFIED (Scope Boundaries):**
  * Full Gradle test execution (`./gradlew testDebugUnitTest`) was not run in this turn because zero production code was modified.
  * Direct device-management IP reachability and CPE wireless MAC mapping remain an unverified external assumption.
