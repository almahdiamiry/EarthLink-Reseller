Viewed SharedComponents.kt:235-255

Viewed DashboardScreen.kt:70-120

Viewed SharedComponents.kt:230-285

Viewed SharedComponents.kt:60-85

Searched for "parseExpirationTimestamp"

Viewed GetRemainingTimeTest.kt:1-60

Viewed PROJECT\_ROADMAP.md:1-58



\# EarthLink Reseller V1 — Maintainability Matrix Master Work Plan



\---



\## 1. Current V1 Position



The repository is on stable checkpoint `eea94ff349216698fc70e6de8ae8787dc311bab4` with MNT-09 resolved. The test suite is fully passing (\*\*562 / 562 unit tests green, 0 failures, 0 errors\*\*).



\### What MNT-09 Established

1\. \*\*Durable UI/Backend Seam:\*\* Direct repository, gateway, and sync manipulation was removed from \[`UserDetailScreenV2.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt). All financial and operational calls are owned by \[`EarthlinkSearchViewModel.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt) within `viewModelScope(Dispatchers.IO)`.

2\. \*\*Behavior Preservation:\*\* 11 permanent characterization unit tests in \[`EarthlinkSearchViewModelSeamTest.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/test/java/com/example/ui/viewmodels/EarthlinkSearchViewModelSeamTest.kt) prove that observable business behavior is preserved across all 9 affected paths.

3\. \*\*Authorized Exception `BPE-MNT09-01`:\*\* Fail-closed ordering for subscriber display name and package type updates (local Room write precedes remote gateway dispatch, preventing split-brain remote mutations if local persistence fails).

4\. \*\*Frozen-Core Inviolability:\*\* Zero modifications were made to additive ledger math, dispatch claim queries, or Room schema.



\### What the CPE Telemetry Stress Test Established

1\. \*\*Architectural Viability:\*\* The test proved that a non-financial capability (querying external device status) can be integrated via a ViewModel provider seam and transient UI state without touching Room schema, the outbox, or ledger records.

2\. \*\*Passed Stress Test, Not a Production Feature:\*\* The spike successfully validated UI/ViewModel extensibility, but deliberately left unaddressed the physical network reachability, device-management identity resolution, carrier CGNAT routing, vendor authentication, and subscriber-to-device binding. These remain future product design problems, not V1 maintainability issues.



\---



\## 2. Matrix Classification



Every maintainability item from \[`docs/maintainability\_decision\_map.md`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/docs/maintainability\_decision\_map.md) is classified into exactly one canonical category:



| ID | Domain | Summary | Canonical Classification | Rationale |

| :--- | :--- | :--- | :--- | :--- |

| \*\*MNT-01\*\* | Dead code | `AccountDetailScreen.kt` deletion | \*\*E. CLOSED / HISTORICAL\*\* | Fully resolved in commit `e022487`. |

| \*\*MNT-02\*\* | Concurrency | Orphaned coordinator mutex cleanup | \*\*E. CLOSED / HISTORICAL\*\* | Fully resolved in commit `1763e3d`. |

| \*\*MNT-03\*\* | Build config | Duplicate dependencies in Gradle | \*\*E. CLOSED / HISTORICAL\*\* | Fully resolved in commit `1763e3d`. |

| \*\*MNT-04\*\* | Dead code | `MoneyParser.kt` unreachable legacy methods | \*\*B. DO NEXT — V1 MEDIUM VALUE\*\* | Proven 0 callers. Deleting deprecated non-validated methods prevents future misuse in financial code. |

| \*\*MNT-05\*\* | Repository | uTower import repository delegation seam | \*\*E. CLOSED / HISTORICAL\*\* | Fully resolved in commit `0dee706`. |

| \*\*MNT-06\*\* | ViewModel | Unused `appDatabase` parameter in `LocalAccountsViewModel` | \*\*C. ACCEPT / DEFER — V1\*\* | Documented compatibility parameter; suppressing warning avoids constructor churn across test harnesses. |

| \*\*MNT-07\*\* | Ingestion | Duplicate 15 uTower date pattern strings | \*\*B. DO NEXT — V1 MEDIUM VALUE\*\* | Exact duplication in `UtowerImporter.kt` and `Repositories.kt`. Consolidating prevents import vs preview drift. |

| \*\*MNT-08\*\* | Presentation | Duplicate numeral mapping and date regexes | \*\*A. DO NOW — V1 HIGH VALUE\*\* | Duplicated across `DashboardScreen.kt` and `SharedComponents.kt`. Zero frozen-core risk; improves display consistency. |

| \*\*MNT-09\*\* | Execution | UI/backend execution ownership on RED paths | \*\*E. CLOSED / HISTORICAL\*\* | Fully resolved on HEAD. Seam verified with 11 permanent tests; gate closed with `BPE-MNT09-01`. |

| \*\*MNT-10\*\* | Presentation | Merging customer history with raw ledger audit | \*\*F. REJECTED / FALSE POSITIVE\*\* | Disproven. Customer statements and raw audit feeds have fundamentally different signed debt semantics. |

| \*\*MNT-11\*\* | Architecture | Large source files (`Repositories.kt`, `UserDetailScreenV2.kt`) | \*\*C. ACCEPT / DEFER — V1\*\* | Accepted V1 debt (\[`AGENTS.md` §5](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/AGENTS.md#L96)). Code compiles cleanly and passes all tests. Splitting solely for line count is prohibited. |

| \*\*MNT-12\*\* | Database | 16 sequential Room migrations | \*\*C. ACCEPT / DEFER — V1\*\* | Accepted V1 debt (\[`AGENTS.md` §5](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/AGENTS.md#L96)). Chains execute in under 15ms. Consolidation carries extreme schema regression risk. |

| \*\*MNT-13\*\* | Database | Double currency representation (`REAL` columns) | \*\*C. ACCEPT / DEFER — V1\*\* | Accepted V1 debt (\[`AGENTS.md` §5](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/AGENTS.md#L96)). Safely guarded by runtime domain math and whole-IQD validators. |

| \*\*MNT-14\*\* | API | Two-tier ledger recording methods | \*\*C. ACCEPT / DEFER — V1\*\* | Intentional architectural layering separating low-level bulk inserts from validated business workflows. |

| \*\*MNT-15\*\* | Repository | Documented maintenance-only repository methods | \*\*C. ACCEPT / DEFER — V1\*\* | Retained and documented with KDocs for Phase 3 test harnesses. |

| \*\*MNT-16\*\* | Schema | Legacy semantic fields (`loanIqd`, `isLegacy`) | \*\*C. ACCEPT / DEFER — V1\*\* | Accepted V1 debt (\[`AGENTS.md` §5](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/AGENTS.md#L96)). Read-only historical compatibility context. |

| \*\*MNT-17\*\* | Packaging | Non-standard package location for PDF/notification | \*\*C. ACCEPT / DEFER — V1\*\* | Purely cosmetic packaging. Moving classes risks breaking imports with zero runtime or maintainability benefit. |

| \*\*MNT-18\*\* | Security | Centralized encryption handling in `PreferenceManager.kt` | \*\*C. ACCEPT / DEFER — V1\*\* | Intentional security architecture. Stable SQLCipher passphrase handling; modifications risk startup failure. |

| \*\*CPE-01\*\* | Device Telemetry | MikroTik / Ubiquiti live CPE telemetry integration | \*\*D. V2 PRODUCT / FEATURE DESIGN\*\* | Architectural stress test passed; physical reachability, device identity, and credentials belong to V2 product scope. |



\---



\## 3. V1 Work Queue



The V1 work queue contains only items with concrete code evidence, clear boundaries, and positive ROI without threatening the frozen core.



\### Prioritized Item Breakdown



| Field | MNT-08 | MNT-07 | MNT-04 |

| :--- | :--- | :--- | :--- |

| \*\*ID\*\* | MNT-08 | MNT-07 | MNT-04 |

| \*\*Decision\*\* | \*\*DO NOW — V1 HIGH VALUE\*\* | \*\*DO NEXT — V1 MEDIUM VALUE\*\* | \*\*DO NEXT — V1 MEDIUM VALUE\*\* |

| \*\*Problem\*\* | Identical Arabic/Persian numeral normalization and regex date parsing loops are copy-pasted across presentation files. | Identical list of 15 Baghdad timezone date format strings and parsing helpers are copy-pasted across ingestion modules. | Legacy unreachable parsing methods and unused `MoneyValue` sealed interface remain in financial parser file. |

| \*\*Evidence\*\* | \[`DashboardScreen.kt:98-104`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/DashboardScreen.kt#L98-L104) and \[`SharedComponents.kt:128-134, 240-246`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/SharedComponents.kt#L128-L134) contain identical digit-mapping loops and regex constants. | \[`UtowerImporter.kt:121-137`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/sync/UtowerImporter.kt#L121-L137) and \[`Repositories.kt:2464-2480`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/data/repository/Repositories.kt#L2464-L2480) share verbatim 15 date pattern strings and `parseDateString`/`parseBghDate` logic. | \[`MoneyParser.kt:9-29, 68-72, 184-222, 227-230`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/ledger/MoneyParser.kt#L9-L29). Ripgrep confirms zero callers across `app/src/main` and `app/src/test`. |

| \*\*Root Cause\*\* | Presentation cards independently implemented gateway expiration string cleaning rather than referencing a shared presentation utility. | `UtowerImportRepositoryImpl.processImportPreview` was added to `Repositories.kt` by copy-pasting the date parser from `UtowerImporter.kt`. | Phase 8 stabilized canonical whole-IQD parsers (`parseUiThousandsAmount`, `parseRawIqd`), but left deprecated pre-Phase 8 methods behind. |

| \*\*Risk if unchanged\*\* | Low. Inconsistent date handling if a new numeral variant or date edge case is fixed in one screen but missed in the other. | Medium. If an unrecognized date format is encountered in a reseller uTower backup, updating one file causes silent preview vs import drift. | Low. Future developers or agents could call deprecated methods that bypass whole-IQD and 250-IQD multiple validation. |

| \*\*Proposed intervention\*\* | Extract a private/internal presentation sanitizer `sanitizeGatewayDateString` in `SharedComponents.kt` and reuse it in `DashboardScreen.kt`. | Extract an `internal object UtowerDateParser` in `com.example.core.sync` containing the 15 patterns and `parseBghDate`; call from both files. | Delete `sealed interface MoneyValue`, `parseIqdAmount`, `parseUiInput`, and `normalizeUiInputToIqd` from `MoneyParser.kt`. |

| \*\*Frozen-core exposure\*\* | \*\*None\*\*. Confined strictly to `com.example.ui.screens`. | \*\*Low / Medium\*\*. Touches \[`UtowerImporter.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/sync/UtowerImporter.kt) (INV-09), but preserves identical regexes and Baghdad timezone. | \*\*Low / Medium\*\*. Edits \[`MoneyParser.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/ledger/MoneyParser.kt) (INV-01), but deletes only dead code with zero callers. |

| \*\*Verification depth\*\* | \*\*LOW\*\*. Targeted unit test (\[`GetRemainingTimeTest`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/test/java/com/example/ui/screens/GetRemainingTimeTest.kt)) and git diff inspection. | \*\*MEDIUM\*\*. Run \[`Phase2UtowerImportHardeningTest`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/test/java/com/example/Phase2UtowerImportHardeningTest.kt) and full import suites. | \*\*HIGH\*\*. Run \[`DataIntegrityReleaseGateTest`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/test/java/com/example/DataIntegrityReleaseGateTest.kt), financial invariants, and full unit test suite. |

| \*\*Dependencies\*\* | None. | None. | None. |

| \*\*Expected change surface\*\* | \~40 lines across 2 UI files. | \~35 lines across 2 ingestion files + 1 new internal helper object. | \~65 lines deleted in 1 file. |

| \*\*Why now?\*\* | Eliminates duplication in active presentation code with zero risk to business logic or financial integrity. | Eliminates preview/import discrepancy risk before freezing V1 ingestion code. | Sweeps deprecated traps from the financial package before cutting the final release build. |

| \*\*Why not now?\*\* | N/A | Can be deferred if uTower formats are considered permanently fixed. | Can be deferred if touching financial core files without a bug is deemed unacceptable. |



\---



\## 4. V1 Accepted Debt



Per \[`AGENTS.md` §5](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/AGENTS.md#L96), the following items are \*\*officially accepted V1 technical debt\*\*. They must not be refactored without an explicit user requirement:



1\. \*\*Large Source Files (MNT-11):\*\*  

&#x20;  \[`Repositories.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/data/repository/Repositories.kt) (3,388 lines) and \[`UserDetailScreenV2.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/ui/screens/UserDetailScreenV2.kt) (3,043 lines) compile cleanly and pass all regression tests. Splitting them solely for line count creates synthetic churn and risks regressing stable code.

2\. \*\*16 Sequential Room Migrations (MNT-12):\*\*  

&#x20;  The migration chain in \[`AppDatabase.kt`](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/app/src/main/java/com/example/core/database/AppDatabase.kt) executes in under 15ms during cold boot. Squashing migrations creates extreme risk of silent data loss or migration crashes for users upgrading from older database versions.

3\. \*\*Double Currency Representation (MNT-13):\*\*  

&#x20;  Legacy `REAL` columns in SQLite are safely guarded by runtime whole-IQD and 250-IQD multiple validation. Migrating SQLite columns to `INTEGER` would require a high-risk schema migration across all existing user databases for zero behavioral gain.

4\. \*\*Two-Tier Ledger Recording API (MNT-14):\*\*  

&#x20;  `Interfaces.kt` deliberately distinguishes low-level direct table operations (`addPayment`, `addDebt`) used by test fixtures and bulk importers from validated domain workflows (`recordAccountPayment`, `recordAccountDebt`). This separation is intentional.

5\. \*\*Documented Maintenance Methods (MNT-15):\*\*  

&#x20;  Repository methods marked with `@Deprecated` or KDoc maintenance warnings are required by Phase 3 test harnesses.

6\. \*\*Legacy Semantic Fields (MNT-16):\*\*  

&#x20;  Fields such as `loanIqd` and `isLegacy` are read-only historical context for uTower snapshots and do not participate in active balance materialization.

7\. \*\*Unused Compatibility Parameter (MNT-06):\*\*  

&#x20;  `appDatabase` parameter in `LocalAccountsViewModel` constructor is retained with `@Suppress("unused")` to prevent cascading constructor breakage across test harnesses.

8\. \*\*Cosmetic Package Placement (MNT-17):\*\*  

&#x20;  `PdfStatementGenerator` and `ExpiryNotificationManager` residing in `com.example.core.sync` is cosmetically imperfect but causes zero runtime defects or architectural leakage.

9\. \*\*Centralized Encryption Handling (MNT-18):\*\*  

&#x20;  `PreferenceManager.kt` manages SQLCipher passphrase initialization reliably. Tampering with it introduces critical database unlock failure risks.



\---



\## 5. V2 Boundary



The V2 boundary isolates product expansion candidates and architectural evolutions that must not be implemented in V1.



\### V2 Candidates



\#### 1. Real CPE Device Telemetry (MikroTik / Ubiquiti)

\* \*\*What It Is:\*\* Fetching and displaying live wireless link statistics (signal strength, CCQ, MCS rates, noise floor, link uptime) for subscriber CPE radios.

\* \*\*Why It Is Not V1 Work:\*\* V1 is an offline-first account and ledger management tool for EarthLink resellers. Adding live device network telemetry requires resolving out-of-band network connectivity that does not exist in the current application model.

\* \*\*What Was Already Validated:\*\* The CPE stress test proved that transient telemetry state can be displayed in `UserDetailScreenV2` via an isolated ViewModel provider seam without touching Room schema, outbox sync, or ledger invariants.

\* \*\*What Remains Unresolved:\*\*

&#x20; 1. \*Device Management Identity Gap:\* Current models store subscriber data-plane session IP (`currentIP`), which is usually behind carrier CGNAT. Outdoor CPE radios have separate private management IPs or controller UUIDs not stored in the database.

&#x20; 2. \*Network Reachability:\* Android handsets on public cellular networks cannot reach private 10.x.x.x / 172.16.x.x management subnets without an active corporate VPN tunnel or cloud controller intermediary.

&#x20; 3. \*Credential Management:\* Securely provisioning and storing reseller-wide default credentials and per-subscriber overrides.

\* \*\*Future Conceptual Direction (Product Intent Only):\*\*

&#x20; - CPE Mode configuration (Direct IP vs Controller API).

&#x20; - Management IP resolution hierarchy (gateway session IP vs dedicated custom management IP).

&#x20; - Default reseller credentials with optional subscriber override.

\* \*\*Required Prerequisite Research/POC:\*\* A standalone proof-of-concept testing HTTP/REST access over a real reseller management VPN or UISP controller API to validate transport latency and error handling under field conditions.



\#### 2. Full ViewModel / Screen Decomposition (Post-MNT-09 FW-01)

\* \*\*What It Is:\*\* Splitting `UserDetailScreenV2.kt` and `EarthlinkSearchViewModel.kt` into dedicated domain sub-screens (e.g. `AccountProfileScreen`, `FinancialActionScreen`, `DeviceDetailScreen`).

\* \*\*Why It Is Not V1 Work:\*\* MNT-09 successfully established the execution seam and eliminated UI-level backend coroutines. Further decomposition is a code-organization preference, not a safety requirement.

\* \*\*What Remains Unresolved:\*\* Defining sub-navigation routing contracts and shared UI state models.



\---



\## 6. Root-Cause Clusters



Superficial matrix findings collapse into four distinct root-cause clusters:



```text

&#x20;                               ROOT-CAUSE CLUSTERS

&#x20;                                        │

&#x20;   ┌────────────────────┬───────────────┴───────────────┬────────────────────┐

&#x20;   ▼                    ▼                               ▼                    ▼

\[Cluster 1]          \[Cluster 2]                     \[Cluster 3]          \[Cluster 4]

Copy-Paste Helpers   Post-Stabilization Dead Code   Accepted Debt        V2 Extension

(MNT-07, MNT-08)     (MNT-04, MNT-01..03)           (MNT-06, 11..18)     (CPE Telemetry)

```



\### Cluster 1: Copy-Paste Parsing \& Sanitization Helpers

\* \*\*Findings:\*\* MNT-07 (uTower date patterns), MNT-08 (numeral mapping \& date regexes).

\* \*\*Root Cause:\*\* Rapid feature implementation created independent copies of date and string parsing routines instead of shared singletons.

\* \*\*Relationship:\*\* These findings share an identical copy-paste code smell, but \*\*must NOT be combined into a single task\*\*. MNT-07 belongs to the uTower Ingestion tier (`com.example.core.sync`), while MNT-08 belongs to the Presentation tier (`com.example.ui.screens`). Combining them would violate the single-responsibility principle and mix presentation UI risks with ingestion logic.



\### Cluster 2: Post-Stabilization Deprecated Scaffolds

\* \*\*Findings:\*\* MNT-04 (MoneyParser dead code), historically MNT-01, MNT-02, MNT-03.

\* \*\*Root Cause:\*\* Architectural stabilization phases introduced canonical implementations, leaving deprecated transitional wrappers in place.



\### Cluster 3: Accepted Architecture \& Schema Compromises

\* \*\*Findings:\*\* MNT-06, MNT-11, MNT-12, MNT-13, MNT-14, MNT-15, MNT-16, MNT-17, MNT-18.

\* \*\*Root Cause:\*\* Deliberate engineering compromises where changing working code provides zero user value and incurs unacceptable regression risks.



\### Cluster 4: Future Product Extension Surface

\* \*\*Findings:\*\* CPE Device Telemetry.

\* \*\*Root Cause:\*\* Functional requirements that extend beyond local-first reseller ledger management into network device operations.



\---



\## 7. Hard Priority Test



To prevent cosmetic cleanup from outranking impactful work, candidates are scored across six qualitative dimensions:



| Candidate | Safety Leverage | Feature Friction Reduction | Change Cost | Regression Risk | Frozen-Core Exposure | Evidence Strength | Qualitative Priority Score | Maintainer Rationale |

| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |

| \*\*MNT-08\*\* | Low | Medium | Very Low (\~40 lines) | Very Low | \*\*None\*\* | High (exact code match) | \*\*8.5 / 10\*\* | Safe presentation consolidation. Zero frozen-core risk. Unifies display dates and numeral parsing across UI cards. |

| \*\*MNT-07\*\* | Medium | Low | Low (\~35 lines) | Low | \*\*Medium\*\* (`UtowerImporter.kt`) | High (exact code match) | \*\*6.5 / 10\*\* | Eliminates preview/import divergence risk for uTower formats, but touches a settled ingestion engine. |

| \*\*MNT-04\*\* | Medium | Low | Very Low (\~65 lines deleted) | Low | \*\*Medium\*\* (`MoneyParser.kt`) | High (proven 0 callers) | \*\*5.5 / 10\*\* | Cleaning dead code in financial files is good hygiene, but modifying `MoneyParser.kt` solely to delete unused methods violates the Minimum-Change Rule if code is already inert. |



\---



\## 8. Maintainability Stop Condition



Maintainability work on EarthLink Reseller V1 is \*\*sufficient and complete\*\* when:



1\. \*\*Execution Ownership Seam is Intact:\*\* All backend writes, financial mutations, and remote calls are owned by ViewModels; UI composables contain zero direct repository/gateway coroutines (proven by MNT-09).

2\. \*\*Frozen Core Remains Certified:\*\* All RED invariants (INV-01 through INV-10) pass 100% without modification or regression (562 / 562 tests passing).

3\. \*\*Active Technical Debt is Explicitly Cataloged:\*\* No unclassified "TODO" items remain in core packages; all accepted debt has documented rationale in `AGENTS.md` and `maintainability\_decision\_map.md`.

4\. \*\*Zero Open Product Defects:\*\* No data corruption, lost charges, duplicate claims, or balance materialization defects exist in production paths.

5\. \*\*V2 Extension Seam is De-risked:\*\* The architectural path for non-financial feature additions (such as device telemetry) is proven viable without reopening the frozen core.



> \*\*Rule:\*\* \*Success does not mean zero rows in the technical debt matrix. Success means every remaining item is either safely consolidated or officially accepted with zero operational risk.\*



\---



\## 9. Recommended Execution Order



If authorized to proceed with limited remaining work, execute strictly in this sequence:



```text

\[Current Baseline: 562/562 Green]

&#x20;              │

&#x20;              ▼

&#x20;  STEP 1: MNT-08 Consolidation (Presentation numeral/date helper)

&#x20;  ├── Seam: com.example.ui.screens

&#x20;  ├── Risk: Very Low (Zero frozen-core exposure)

&#x20;  └── Verify: GetRemainingTimeTest + UI inspection

&#x20;              │

&#x20;              ▼

&#x20;  STEP 2: MNT-07 Consolidation (uTower date pattern object)

&#x20;  ├── Seam: com.example.core.sync (UtowerDateParser)

&#x20;  ├── Risk: Low (Preserves exact regex list and timezone)

&#x20;  └── Verify: Phase2UtowerImportHardeningTest + full import suite

&#x20;              │

&#x20;              ▼

&#x20;  STEP 3: MNT-04 Dead Code Sweep (MoneyParser deprecated cleanup)

&#x20;  ├── Seam: com.example.core.ledger.MoneyParser

&#x20;  ├── Risk: Low (Deletions only; zero callers proven)

&#x20;  └── Verify: DataIntegrityReleaseGateTest + full unit test suite

&#x20;              │

&#x20;              ▼

\[FINAL STOP: Declare V1 Maintainability Frozen \& Move to V2/Product]

```



\---



\## 10. Risk / Evidence Notes



1\. \*\*MNT-04 5-Whys Challenge:\*\*  

&#x20;  - \*Is it actually unreachable?\* Yes, ripgrep confirmed 0 occurrences in `app/src/main` and `app/src/test`.

&#x20;  - \*Is there financial risk from removing it?\* No, active production code exclusively calls `parseUiThousandsAmount`, `parseSubscriptionPriceIqd`, and `parseRawIqd`.

&#x20;  - \*Could leaving it be safer than touching the file?\* Under a strict interpretation of the Minimum-Change Rule, leaving deprecated methods marked `@Deprecated` poses zero runtime hazard. Modifying `MoneyParser.kt` touches an INV-01 file. However, removing it permanently prevents future agents from adopting unvalidated `Double` parser methods.

2\. \*\*MNT-07 uTower Field Reality:\*\*  

&#x20;  uTower exports are historical backup archives from a legacy Windows software platform. New date formats are exceedingly rare in practice. The risk of preview vs import divergence is real in theory, but low in production frequency.

3\. \*\*CPE Telemetry Scope Shield:\*\*  

&#x20;  Any attempt to implement real MikroTik RouterOS or Ubiquiti UISP API calls in V1 must be rejected by the Scope Shield (\[`AGENTS.md` §7](file:///c:/Users/Almahdi-BOC/antigravity/Earthlink-Reseller-V1/AGENTS.md#L111)). It requires network infrastructure assumptions that do not exist in the current product.



\---



\## 11. Final Recommendation



Choose between two clear maintainer paths:



\### Option A: `V1 MAINTAINABILITY: READY FOR LIMITED REMAINING WORK`

Execute the 3 surgical, bounded consolidation items (MNT-08, MNT-07, and MNT-04) in the recommended sequence (\~140 total lines changed across 5 files), verify with targeted tests, and then seal V1 maintainability permanently.



\### Option B: `V1 MAINTAINABILITY: STOP AND MOVE TO PRODUCT WORK`

Declare V1 maintainability complete immediately on the current stable checkpoint (`eea94ff3`). MNT-09 resolved the only critical architectural risk. MNT-04, MNT-07, and MNT-08 are low-friction duplications that cause zero production defects. Move engineering effort directly to V2 feature planning.



\### Architectural Verdict

\*\*`V1 MAINTAINABILITY: READY FOR LIMITED REMAINING WORK`\*\*



\*Justification:\* Completing MNT-08 (presentation date/numeral sanitization) and MNT-07 (uTower date parsing consolidation) takes minimal effort (<80 lines total), carries near-zero risk, resolves all active P2 maintainability candidates in the decision map, and leaves the codebase in an unblemished, fully consolidated state for V1 release.

