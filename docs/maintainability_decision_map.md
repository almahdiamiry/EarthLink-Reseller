# Final Maintainability Decision Map for EarthLink Reseller V1

## 1. Architectural Context and Core Separation

This decision map establishes the maintainability baseline and feature extensibility evaluation for the EarthLink Reseller V1 repository on current HEAD (`1a8c8c9fea6fee57c8865c13aede0f079046a2c3`).

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

### Canonical Classifications

Every candidate in this repository has been evaluated against repository evidence and classified into one canonical status:
* **CLOSED / HISTORICAL**: Completed, verified, and committed on HEAD.
* **ACCEPTED YELLOW**: Formally accepted technical debt per `AGENTS.md` §5; functioning reliably without current defects.
* **INTENTIONAL**: Deliberate architectural design; not debt.
* **REJECTED / FALSE POSITIVE**: Disproven finding; modifying would introduce regressions or corrupt domain invariants.
* **SUPERSEDED**: Cosmetic finding with no operational benefit; churn prohibited.
* **V2 RESEARCH / POC FIRST**: Future product feature requiring field research and out-of-band transport infrastructure.

---

## 2. Extension Surface Boundary: CPE Device Telemetry (MikroTik / Ubiquiti)

An architectural stress test evaluated whether ordinary non-financial capabilities (displaying wireless CPE radio signal strength, model, MAC, IP, and uptime) can be integrated through narrow presentation seams without reopening the frozen core.

The test confirmed:
* **Seam Viability:** Telemetry can be integrated via an isolated ViewModel provider seam and transient UI state without modifying Room entity schemas, outbox synchronization, or ledger invariants.
* **V2 Product Boundary:** Direct implementation in V1 is rejected because fundamental infrastructure and identity prerequisites remain unresolved:
  1. *Management Identity Gap:* Stored `currentIP` is a carrier-grade NAT (CGNAT) data-plane session IP, not a verified CPE management address.
  2. *MAC Ambiguity:* Stored `currentMAC` / `accountMAC` often reflects indoor Wi-Fi router WAN ports rather than outdoor CPE wireless radios.
  3. *Network Reachability:* Android handsets on public cellular networks cannot directly reach private `10.x.x.x` / `172.16.x.x` management subnets without corporate VPN or cloud controller intermediaries.
  4. *Authentication & Binding:* Reseller credential models, vendor service enablement (RouterOS REST, airOS, UISP tokens), and subscriber-to-device binding contracts belong to future product design.
* **Classification:** `V2 RESEARCH / POC FIRST` (strictly outside V1 scope).

---

## 3. The Reconciled Maintainability Decision Map

| ID | Domain / Area | Current State | Concrete Evidence | Frozen-Core Impact | Final Classification | Final Action / Rationale |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **MNT-01** | Dead code | Completely removed | Deleted in commit `e022487` | None | **CLOSED / HISTORICAL** | Settled in git history. |
| **MNT-02** | Concurrency | Completely removed | Deleted in commit `1763e3d` | None | **CLOSED / HISTORICAL** | Settled in git history. |
| **MNT-03** | Build config | Cleaned and deduplicated | Verified in commit `1763e3d` and `build.gradle.kts` | None | **CLOSED / HISTORICAL** | Settled in git history. |
| **MNT-04** | Dead code | Completely removed | Deleted unreachable legacy parser methods and `MoneyValue` sealed interface in commit `1a8c8c9` | Protected / preserved | **CLOSED / HISTORICAL** | Completed in commit `1a8c8c9`. 4 unit tests added; full suite 579/579 green. |
| **MNT-05** | Repository seam | Properly delegated | Delegated to `UtowerImportRepository.importUtowerTgz` in commit `0dee706` | Protected / must not touch | **CLOSED / HISTORICAL** | Settled in git history. |
| **MNT-06** | ViewModel API | Unused compatibility parameter | `LocalAccountsViewModel.kt:39`; passed by `AppViewModelProvider.kt:50` and test harnesses | None | **ACCEPTED YELLOW** | Retain with `@Suppress("unused")` to prevent constructor churn in tests per `AGENTS.md` §5. |
| **MNT-07** | Ingestion | Consolidated uTower date patterns | Consolidated verbatim 15 Baghdad date patterns into internal `UtowerDateParser` in commit `ce30a17` | Protected / preserved | **CLOSED / HISTORICAL** | Completed in commit `ce30a17`. 6 unit tests added; full suite 575/575 green. |
| **MNT-08** | Presentation | Consolidated numeral normalization and date sanitization | Extracted `sanitizeGatewayDateString` in `SharedComponents.kt` and reused in `DashboardScreen.kt` in commit `137944c` | None | **CLOSED / HISTORICAL** | Completed in commit `137944c`. 5 unit tests added; full suite 569/569 green. |
| **MNT-09** | Execution ownership | Resolved via ViewModel boundary seam | Refactored in `EarthlinkSearchViewModel.kt` and `UserDetailScreenV2.kt` (commits `eea94ff`, `427dfa2`, `e7b1066`) | Protected / preserved | **CLOSED / HISTORICAL** | Completed on HEAD. Seam verified with 11 characterization tests; gate closed with `BPE-MNT09-01`. |
| **MNT-10** | Presentation | Disproven false positive | `UserDetailScreenV2.kt` pairs operations with positive debt; `LocalAccountDetailScreen.kt` renders raw negative ledger rows | None | **REJECTED / FALSE POSITIVE** | Disproven. Preserve intentional separation between customer statements and raw ledger audit feeds. |
| **MNT-11** | File size | Monolithic source files | `Repositories.kt` (~3,388 lines), `UserDetailScreenV2.kt` (~3,043 lines). Compiles cleanly, passes all regression tests | Protected / must not touch | **ACCEPTED YELLOW** | Accepted V1 debt per `AGENTS.md` §5. Code is stable; splitting solely for line count is prohibited. |
| **MNT-12** | Database | 16 sequential Room migrations | `AppDatabase.kt:145-310`. Migration chain executes in under 15ms during cold boot kill-point tests | Protected / must not touch | **ACCEPTED YELLOW** | Accepted V1 debt per `AGENTS.md` §5. Squashing carries extreme schema regression risk. |
| **MNT-13** | Database | Double currency representation | SQLite `REAL` columns in Room entity tables; guarded by whole-IQD validators | Protected / must not touch | **ACCEPTED YELLOW** | Accepted V1 debt per `AGENTS.md` §5. Guarded with domain validation. |
| **MNT-14** | API hierarchy | Two-tier ledger recording methods | `Interfaces.kt:72-99` separates low-level inserts from workflows | Protected / must not touch | **INTENTIONAL** | Deliberate architectural layering separating low-level bulk/test inserts from validated domain workflows. |
| **MNT-15** | Legacy API | Documented maintenance-only methods | `Interfaces.kt:96, 118, 126` marked with `@Deprecated` or KDoc warnings | Protected / must not touch | **ACCEPTED / KEEP** | Retain documented maintenance helpers for backward compatibility with Phase 3 test harnesses. |
| **MNT-16** | Legacy fields | Read-only historical semantic fields | `loanIqd`, `isLegacy` in `LocalAccount` and Room entities | Protected / must not touch | **ACCEPTED YELLOW** | Accepted V1 debt per `AGENTS.md` §5. Read-only historical context for uTower. |
| **MNT-17** | Packaging | Non-standard package location | `PdfStatementGenerator` and `ExpiryNotificationManager` reside in `com.example.core.sync` | None | **SUPERSEDED** | Cosmetic packaging. Causes zero runtime defects; moving classes creates unnecessary churn. |
| **MNT-18** | Security | Centralized encryption handling | `PreferenceManager.kt` manages AES and SQLCipher passphrase initialization | Protected / must not touch | **INTENTIONAL** | Stable SQLCipher passphrase handling; modifications risk startup failure. |
| **CPE-01** | Device Telemetry | Live CPE wireless telemetry (MikroTik / Ubiquiti) | Architectural stress test passed; physical reachability, device identity, credentials, and binding unresolved | Protected / must not touch | **V2 RESEARCH / POC FIRST** | Keep outside V1. Implement only after field research validates carrier transport and device identity. |

---

## 4. Completed Maintenance Summary

All justified V1 maintainability items have been implemented, verified, and committed:

* **MNT-09 (UI/Backend Execution Ownership):**
  Transferred backend execution ownership for payment, debt, refill renewal, and metadata persistence from `UserDetailScreenV2.kt` into `EarthlinkSearchViewModel.kt` under `viewModelScope(Dispatchers.IO)`. Repository properties were made private. Behavior-preservation gate verified with 11 permanent seam characterization tests and authorized exception `BPE-MNT09-01` (fail-closed local Room persistence before remote gateway dispatch). Documented in commit `e7b1066` and [`docs/LESSONS_LEARNED/LL-DURABLE-PRECONDITION-PRESERVATION.md`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/docs/LESSONS_LEARNED/LL-DURABLE-PRECONDITION-PRESERVATION.md).

* **MNT-08 (Presentation Numeral Normalization & Date Sanitization):**
  Consolidated duplicated Eastern Arabic/Persian numeral conversion and gateway expiration date sanitization from `DashboardScreen.kt` and `SharedComponents.kt` into `SharedComponents.kt:sanitizeGatewayDateString`. Verified with 5 unit tests in `GetRemainingTimeTest.kt` (commit `137944c`).

* **MNT-07 (uTower Date Parser Consolidation):**
  Consolidated 15 identical Asia/Baghdad timezone date format strings and parsing routines from `UtowerImporter.kt` and `Repositories.kt` into internal object `UtowerDateParser`. Verified with 6 unit tests in `Phase2UtowerImportHardeningTest.kt` (commit `ce30a17`).

* **MNT-04 (Dead Financial Parser Symbols Removal):**
  Removed proven unreachable, deprecated methods (`parseIqdAmount`, `parseUiInput`, `normalizeUiInputToIqd`) and unused `MoneyValue` sealed interface from `MoneyParser.kt`. Active production code exclusively uses validated whole-IQD parsers. Verified with 4 unit tests in `MoneyParserTest.kt` and full suite (commit `1a8c8c9`).

---

## 5. Preserved Negative Knowledge Register

To prevent future agents and maintainers from reopening settled investigations, the following findings are permanently recorded as closed, intentional, or false positives:

1. **`HistoryPresentationManager` unification is a false positive (MNT-10):**
   `HistoryPresentationManager` in [`UserDetailScreenV2.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt) formats customer-facing statements with paired charges and positive payment credits. [`LocalAccountDetailScreen.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/LocalAccountDetailScreen.kt) renders raw append-only ledger audit rows with negative numbers for debt. Merging them corrupts financial presentation semantics.
2. **Large source files are accepted technical debt (MNT-11):**
   [`Repositories.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/data/repository/Repositories.kt) (~3,388 lines) and [`UserDetailScreenV2.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt) (~3,043 lines) compile cleanly and run reliably in historical verification evidence (`evidence/`). Splitting them solely for line count violates [`AGENTS.md`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/AGENTS.md) section 5.
3. **Sequential Room migrations are accepted technical debt (MNT-12):**
   The 16 sequential Room migrations in [`AppDatabase.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/database/AppDatabase.kt) execute in under 15ms in historical verification evidence. Consolidating migrations creates high schema regression risks without operational benefit.
4. **Double currency representation is accepted technical debt (MNT-13):**
   Legacy SQLite `REAL` columns in Room entities are safely guarded at runtime by domain whole-IQD and 250-IQD multiple validation. Migrating SQLite columns to `INTEGER` carries high schema regression risk without behavioral change.
5. **Two-tier ledger API is intentional layering (MNT-14):**
   `addPayment` and `addDebt` in [`Interfaces.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/domain/repository/Interfaces.kt) are low-level table insertion primitives for tests and batch imports; `recordAccountPayment` and `recordAccountDebt` execute full business workflows with validation. Retaining both tiers is deliberate.
6. **`appDatabase` parameter in `LocalAccountsViewModel` is accepted (MNT-06):**
   Explicitly referenced in [`AppViewModelProvider.kt:50`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/AppViewModelProvider.kt) and test harnesses. Retaining it prevents constructor churn across test suites.
7. **Documented maintenance methods are retained for test harnesses (MNT-15):**
   Repository methods marked with `@Deprecated` or KDoc maintenance warnings are required by Phase 3 test harnesses.
8. **Centralized security handling in `PreferenceManager.kt` is intentional (MNT-18):**
   Manages SQLCipher passphrase initialization. Modifications risk database startup failures.

---

## 6. Evidence and Verification Status

* **Current Baseline:**
  * Exact commit: `1a8c8c9fea6fee57c8865c13aede0f079046a2c3`
  * Git working tree: Clean
  * Test baseline: **579 / 579 unit tests passing** (0 failures, 0 errors)
  * Test growth: 535 certified baseline -> 562 (MNT-09 initial) -> 564 (MNT-09 regression fix) -> 569 (MNT-08) -> 575 (MNT-07) -> 579 (MNT-04)
* **Invariant Verification:**
  * Invariants INV-01 through INV-16 fully preserved.
  * Zero modifications to frozen-core additive ledger math, Room entity schemas, migrations, or single-writer dispatch claim queries.
* **Final Architectural Conclusion:**
  `V1 MAINTAINABILITY: COMPLETE — STOP MAINTENANCE CHURN`
  No further maintainability refactoring is justified for V1. The frozen core is protected, execution ownership is established, accidental duplications are consolidated, dead parser symbols are removed, accepted technical debt is explicitly documented, and future feature extensions (CPE telemetry) are bounded to V2.
