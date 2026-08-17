# Architectural Decision Records (ADRs)

> **Governance Notice**: This document is a technical decision registry and historical context layer. It is subordinate to the Frozen Authority Bundle in `docs/authority/`.
> Each ADR is classified into one of:
> - `CURRENT TECHNICAL DECISION`: Active and enforced in production.
> - `FROZEN-TARGET COMPATIBLE`: Aligned with the frozen architecture bundle.
> - `SUPERSEDED BY FROZEN AUTHORITY`: Overridden by `docs/authority/` frozen artifacts.
> - `HISTORICAL RATIONALE`: Retained for background context and historical evolution.

---
## ADR-001: Model-View-ViewModel (MVVM) Architecture
* **Status:** Accepted
* **Context:** We need a robust architecture to separate business logic from UI rendering, ensuring the app is testable, maintainable, and scalable.
* **Decision:** Adopt the MVVM architecture pattern. ViewModels will hold UI state and handle interactions, while Composables will only render state and emit events.
* **Consequences:** Clear separation of concerns. Easier testing of business logic. UI components remain stateless where possible.
* **Alternatives considered:** MVC (tight coupling, hard to test), MVI (more boilerplate, steeper learning curve).
* **Related roadmap items:** None.

## ADR-002: Repository Pattern as Data Access and Orchestration Boundary
* **Status:** Accepted
* **Context:** Data originates from multiple sources: Live API, Room Database, and local mock data (Demo Mode). ViewModels should not need to know where data comes from.
* **Decision:** Implement the Repository Pattern. All ViewModels must request data through repository interfaces. Room Database is the single local source of truth for runtime local business state. Repositories are the only application-facing boundary for accessing and mutating that state.
* **Consequences:** ViewModels are decoupled from data sources. Easy to implement offline support and Demo Mode. Requires strict adherence to interface boundaries.
* **Alternatives considered:** Direct API calls from ViewModels (rejected due to tight coupling and inability to support Demo Mode easily).
* **Related roadmap items:** CQ-002.

## ADR-003: Jetpack Compose for UI
* **Status:** Accepted
* **Context:** We need a modern, declarative way to build Android UIs that is easy to maintain and performant.
* **Decision:** Use Jetpack Compose exclusively for all UI development.
* **Consequences:** Faster UI development, easier state management. Requires learning a new paradigm compared to XML layouts.
* **Alternatives considered:** XML-based layouts (legacy, verbose, harder to build dynamic UIs).
* **Related roadmap items:** UI-001, UI-002.

## ADR-004: Room Database for Local Storage
* **Status:** Accepted
* **Context:** The app requires offline capabilities, caching for performance, and a robust way to queue actions when offline.
* **Decision:** Use Room Database for all local relational data storage (accounts, ledgers, import batches, outbox).
* **Consequences:** Type-safe SQL queries, built-in migration support, and reactive streams (Flow) support.
* **Alternatives considered:** SQLiteOpenHelper (too much boilerplate, no compile-time checks), Realm (heavyweight, custom threading model).
* **Related roadmap items:** DB-001, DB-002, DB-003, SEC-003.

## ADR-005: Offline-First Synchronization (Outbox Pattern)
* **Status:** Accepted
* **Context:** Users may have intermittent network connectivity. App actions (like ledger updates) must not be lost if the network fails.
* **Decision:** Implement the Outbox Pattern. Local writes are committed to the local database along with a `sync_outbox` entry in the same transaction. A background sync engine processes the outbox.
* **Consequences:** High reliability. No data loss on network failure. Adds complexity to the data layer and requires conflict resolution strategies.
* **Alternatives considered:** Direct API calls with immediate failure (poor UX on bad networks).
* **Related roadmap items:** FEAT-002.

## ADR-006: Hybrid Demo Mode
* **Status:** Accepted
* **Context:** Users and sales teams need to safely demonstrate the app without mutating live production data, but the app should feel completely real.
* **Decision:** Implement a Hybrid Demo Mode handled exclusively in the Repository layer. Safe reference data (packages, prices) is read from the live API. All write operations (creating users, changing passwords) are simulated locally.
* **Consequences:** The UI does not need to know if it's in Demo Mode. Safe demonstrations. Requires careful maintenance of the simulation layer to match API behavior.
* **Alternatives considered:** A completely separate mock backend (high maintenance), mock UI flags (leaks demo logic into ViewModels/UI).
* **Related roadmap items:** DEMO-001, DEMO-002.

## ADR-007: Manual Dependency Injection
* **Status:** Deprecated (Migrated)
* **Context:** The project initially needed a quick way to share instances (Network, Database, Repositories) across the app without complex setup.
* **Decision:** Used a global singleton (`EarthlinkApp.instance`) for manual dependency injection initially. This has now been migrated to use `AppViewModelProvider.Factory` for ViewModel instantiation via constructor injection.
* **Consequences:** Improved testability and decoupled ViewModels from the application context.
* **Alternatives considered:** Dagger/Hilt (initially considered, but `ViewModelProvider.Factory` was sufficient and lightweight).
* **Related roadmap items:** CQ-001, ARCH-001 (Completed).

## ADR-008: State Management with StateFlow
* **Status:** Accepted
* **Context:** ViewModels need a reactive, lifecycle-aware way to expose state to Jetpack Compose.
* **Decision:** Use `StateFlow` and `MutableStateFlow` in ViewModels, collected using `collectAsStateWithLifecycle()` in Compose.
* **Consequences:** Safe collection of flows that automatically stops when the UI is in the background, saving resources.
* **Alternatives considered:** LiveData (legacy, tied to Android lifecycle closely, lacks advanced operators).
* **Related roadmap items:** None.

## ADR-009: Centralized Error Handling Strategy
* **Status:** Accepted
* **Context:** API and database errors need to be caught and translated into user-friendly messages without crashing the app.
* **Decision:** API errors are caught in the Repository layer and translated into meaningful application errors or `Result` wrappers before being passed to the ViewModel.
* **Consequences:** ViewModels don't deal with HTTP codes or raw exceptions. Consistent error states in the UI.
* **Alternatives considered:** Let ViewModels catch exceptions (leads to duplicated error handling logic).
* **Related roadmap items:** None.

## ADR-010: Security-First Credential Storage
* **Status:** Accepted (Proposed)
* **Context:** The app handles sensitive ISP credentials, API tokens, and customer passwords.
* **Decision:** Never store passwords in plain text. Use `EncryptedSharedPreferences` for API tokens. Use `FLAG_SECURE` on screens displaying passwords.
* **Consequences:** Higher security compliance. Prevents casual credential theft from device backups or screenshots.
* **Alternatives considered:** Standard SharedPreferences (insecure).
* **Related roadmap items:** SEC-001, SEC-002, SEC-004.

## ADR-011: Compose Navigation Strategy
* **Status:** Accepted (Proposed)
* **Context:** The app needs to navigate between different Compose screens.
* **Decision:** Use Jetpack Navigation Compose with a standardized `NavHost`.
* **Consequences:** Type-safe routing, deep link support, standardized backstack management.
* **Alternatives considered:** Custom state-based routing (hard to manage backstack and deep links).
* **Related roadmap items:** NAV-001.

## ADR-012: Documentation Hierarchy & Authority [SUPERSEDED BY FROZEN AUTHORITY]
> **Status**: SUPERSEDED. The single source of truth for product and architecture is `docs/authority/`. Current code determines implementation state; executable tests determine verification state.
* **Status:** Accepted
* **Context:**
The project is developed collaboratively by humans and AI assistants over multiple sessions. Context can easily be lost between sessions if project documentation is not maintained.
* **Decision:**
The project documentation becomes part of the architecture.

The following documents are considered authoritative:

- PRODUCTION_INVARIANTS.md
- AI_DEVELOPMENT_GUIDE.md
- PROJECT_ROADMAP.md
- ARCHITECTURE.md
- DESIGN_DECISIONS.md
- CHANGELOG.md
- CONTRIBUTING.md
Every development session begins by reading these documents.
If implementation conflicts with PRODUCTION_INVARIANTS.md,
the implementation is considered non-compliant and must be fixed.

For non-invariant documentation conflicts, production source code is treated
as the current implementation truth until the documentation is updated.
* **Consequences:**
AI assistants maintain long-term consistency.
Project knowledge is preserved.
New contributors can quickly understand the project.
* **Alternatives considered:**
Relying on conversation history or external notes (rejected).
* **Related roadmap items:**
None.

## ADR-013: Incremental Development Policy [FROZEN-TARGET COMPATIBLE]
> **Status**: FROZEN-TARGET COMPATIBLE. Tasks are executed one at a time per active phase plan.

* **Status:** Accepted

* **Context:**
Large AI-generated commits frequently introduce regressions and make code review difficult.

* **Decision:**
Development follows an incremental workflow.

Only one roadmap task may be implemented per iteration.

Each implementation must:

- update PROJECT_ROADMAP.md
- update CHANGELOG.md
- be reviewed before continuing

* **Consequences:**
Lower regression risk.
Smaller code changes.
Easier reviews.
Higher code quality.

* **Alternatives considered:**
Large multi-feature implementations (rejected).

* **Related roadmap items:**
All roadmap tasks.

## ADR-014: AI-Assisted Development Workflow [SUPERSEDED BY FROZEN AUTHORITY]
> **Status**: SUPERSEDED. Replaced by the frozen workflow in AGENTS.md (authority → current artifact → implementation plan → executable evidence).

* **Status:** Accepted

* **Context:**
The project is primarily developed with assistance from AI coding agents. Consistent behavior between sessions is required.

* **Decision:**
Every AI development session follows the mandatory workflow:

1. Read PRODUCTION_INVARIANTS.md
2. Read AI_DEVELOPMENT_GUIDE.md
3. Read ARCHITECTURE.md
4. Read PROJECT_ROADMAP.md
5. Read DESIGN_DECISIONS.md
6. Read CONTRIBUTING.md
7. Read CHANGELOG.md
8. Select the highest-priority eligible task
9. Verify dependencies
10. Implement only one task
11. Update PROJECT_ROADMAP.md
12. Update CHANGELOG.md
13. Stop and wait for user approval

No AI assistant may skip this workflow.

* **Consequences:**
Predictable development.
Consistent architecture.
No duplicated work.
Reduced regressions.

* **Alternatives considered:**
Ad-hoc development sessions (rejected).

* **Related roadmap items:**
None.

## ADR-015: Failed Design Attempts
* **Status:** Documented
* **Context:** Attempts to resolve complex layout issues sometimes fail due to structural misunderstandings of existing code.
* **Decision:** Failed attempts, including the reasoning for their failure and the steps taken to revert them, will be documented here to prevent repeating the same mistakes.
* **Failed Attempt 1: IME_INSETS_SHOW_ANIMATION Timeout (2026-07-05)**
    * **Problem:** Periodic ANR/timeout during keyboard show animation.
    * **Attempted Fix:** Refactored `DashboardScreen` from `Column` to `LazyColumn` to simplify hierarchy.
    * **Result:** Failed. Broke composition context (`ColumnScope` vs `LazyListScope`), causing widespread compilation errors. Reverted changes.
    * **Lesson:** Avoid structural rewrites to resolve layout-specific animation issues without thorough analysis of scope dependencies.

## ADR-016: Deferred Application Initialization for Cold-Start Optimization

* **Status:** Accepted

* **Context:**
The application class `EarthlinkApp` previously performed manual fallback Firebase initialization and WorkManager background sync scheduling synchronously inside `onCreate()`. This introduced noticeable blockage on the main/UI thread, adding latency to the cold-start process and risking potential App Not Responding (ANR) flags during startup under constraint conditions.

* **Decision:**
Move all non-essential and heavy initialization processes (manual Firebase manual/fallback initialization, database collections setup, and WorkManager background sync scheduling) into a background coroutine context using `CoroutineScope(Dispatchers.Default).launch`.

* **Consequences:**
- Significant reduction in main-thread startup latency (cold-start optimization).
- Better thread safety and responsive initial user experience.
- Graceful handling of manual initialization errors without crashing the main application thread.

* **Alternatives considered:**
- Jetpack App Startup library (rejected as too complex for custom manual Firebase fallback configurations).

* **Related roadmap items:**
- AUDIT-N02

## ADR-017: Pure Moshi KSP Code Generation (Removal of Reflection Adapter)

* **Status:** Accepted

* **Context:**
The application had dual-model configurations, utilizing both Kotlin symbol-processing annotation (`@JsonClass(generateAdapter = true)`) and the reflection-based `KotlinJsonAdapterFactory()`. The reflection library is much larger, slower, and consumes extra heap during parse/lookup loops.

* **Decision:**
Completely remove `KotlinJsonAdapterFactory()` from all `Moshi.Builder` declarations (across `EarthlinkNetwork.kt` and `Repositories.kt`), and enforce the usage of code-generated adapters. To make this possible, we fully annotated all domain, request, and local database models with `@JsonClass(generateAdapter = true)`.

* **Consequences:**
- 0% reflection overhead for JSON serialization and parsing.
- Smaller binary/APK footprint.
- Faster, more reliable, and compile-time checked model serialization.

* **Alternatives considered:**
- Retaining reflection for complex nested items (rejected; annotation-based KSP code-generation perfectly compiles all current models).

* **Related roadmap items:**
- AUDIT-N03

## ADR-018: Build Namespace and Domain Alignment

* **Status:** Accepted

* **Context:**
The Android application previously used a generic `namespace = "com.example"` configuration in `app/build.gradle.kts`. This led to generated `BuildConfig` and resource `R` classes being published under a default package, contradicting the production-grade application domain (`com.alamiry.earthlinkreseller`).

* **Decision:**
Update the Android namespace in `app/build.gradle.kts` to `"com.alamiry.earthlinkreseller"`. To prevent compilation failures, we updated all 16 Jetpack Compose UI screen files, `SyncRepositoryImpl.kt`, and `EarthlinkApp.kt` to import and reference the `R` and `BuildConfig` classes from this new correct domain path, while leaving source directory structural layouts unaffected.

* **Consequences:**
- Generates `BuildConfig` and `R` classes in the correct application domain.
- Clean separation between source packages and Gradle namespaces.
- Prepares the binary cleanly for release obfuscation.

* **Related roadmap items:**
- AUDIT-N04

## ADR-019: Explicit Secure Transport Policies via Network Security Configuration

* **Status:** Accepted

* **Context:**
While the app targeting Android API 36 blocks cleartext traffic by default, it lacked an explicit `network_security_config.xml` mapping. Declaring an explicit configuration is high-standard industry practice to strictly define security constraints and guarantee secure communication in all build types.

* **Decision:**
Create `/app/src/main/res/xml/network_security_config.xml` mandating `cleartextTrafficPermitted="false"`, and wire it into the `AndroidManifest.xml` via the `android:networkSecurityConfig` attribute.

* **Consequences:**
- Guarantees that cleartext (HTTP) traffic is strictly blocked globally, preventing potential misconfiguration downgrades.
- Protects customer/reseller credential data against man-in-the-middle exploits.

* **Related roadmap items:**
- AUDIT-N05




## ADR-020: Implicit Scaling of Small Values in IQD Parsing (`parseIqdAmount`)
* **Status:** Accepted
* **Context:**
The UI is optimized for rapid numerical input by assuming thousands by default (e.g., the user types "40" to mean "40,000" IQD). However, when importing files or reading JSON payloads, the values might be ambiguous between absolute IQD values and "units" (scaled down by 1000).
* **Decision:**
The function `parseIqdAmount` deliberately multiplies any numerical value less than `10,000` by `1000`. This is considered correct and intentional behavior that matches the UI logic, allowing the system to seamlessly interpret small values as units and large values as absolute amounts, minimizing input friction for users and standardizing mixed-format imports.
* **Consequences:**
- User input is significantly faster.
- Small genuine amounts (e.g., 500 IQD) cannot be accurately processed without being scaled up (500 becomes 500,000), but this is acceptable given the typical domain values (internet subscriptions in Iraq).
* **Alternatives considered:**
- Adding strict `_unit` vs `_iqd` suffix checking in all parsed keys (rejected: disrupts the intentional UX pattern and existing data mappings).

## ADR-021: Local Backup Database Passphrase Inclusion
* **Status:** Accepted
* **Context:**
The application uses SQLCipher to encrypt the local database. When creating a `utower_data.zip` backup, the encryption passphrase is included in plaintext within a `backup_info.json` file alongside the encrypted database.
* **Decision:**
This is an intentional trade-off prioritizing **Availability and User Data Recovery over Confidentiality**. Since this app acts primarily as a local accounting ledger rather than a banking application or centralized wallet, the primary threat is the user accidentally losing access to their data, not an adversary extracting local ledger data. Highly sensitive ISP admin credentials or fund passwords are not compromised by this ledger backup.
* **Consequences:**
- Users can reliably restore their backups across different devices or after factory resets without memorizing a complex decryption key.
- A physical attacker or malicious app with file access can extract the backup and decrypt the ledger. This risk is accepted by the domain threat model.
* **Alternatives considered:**
- Forcing the user to create/remember a custom backup PIN or binding the backup to a specific Firebase UID without passphrase exposure (rejected: increases the risk of permanent data loss if the user forgets the PIN or loses the Firebase account).

## ADR-023: Global Data Maintenance Barrier & Resilient Network API Success Resolution [FROZEN-TARGET COMPATIBLE]
> **Status**: FROZEN-TARGET COMPATIBLE.
* **Status:** Accepted
* **Context:**
Mass data mutations (such as exact backup snapshot restore, uTower batch database imports, or user sign-out and table clearing) previously risked concurrent race conditions with background sync workers (`SyncWorker`) or incoming Firestore realtime listener events. Additionally, network response handling for Earthlink reseller endpoints frequently returned success status messages in Arabic ("تم التجديد بنجاح") or localized English without object payloads, which required explicit classification.
* **Decision:**
1. Implement `DataMaintenanceLock`: a global coroutine mutex and atomic barrier that wraps high-impact maintenance operations (`restoreBackupZip`, `importFromFile`, `importFromPreview`, `signOut`). The sync engine checks `DataMaintenanceLock.isLocked()` and pauses background sync operations while maintenance is active.
2. Expand `safeApiCall` in `Repositories.kt` to recognize comprehensive Arabic and English success confirmation keywords across all reseller operations before evaluating null payloads.
3. Introduce `parseSubscriptionPriceIqd` in `MoneyParser.kt` to seamlessly support fractional thousands input in Jetpack Compose UI fields (e.g., "22.5" -> 22,500 IQD).
* **Consequences:**
- Eliminates table-wiping and race condition risks between local restores and cloud sync.
- Guarantees zero false failures on localized Earthlink API response messages.
- Provides smooth decimal currency entry in subscriber renewal flows.
* **Related roadmap items:**
- CHAT6-HARDENING

## ADR-024: Firebase Identity Continuity and Full vs Partial Snapshot Contracts
* **Status:** Accepted
* **Context:**
When anonymous Firebase users authenticate via Google or Email sign-in, creating a new user session previously changed their UID, severing access to their Firestore cloud data and causing data loss. Furthermore, remote Firestore document deserialization needed an explicit contract to distinguish full baseline account snapshots from partial field updates.
* **Decision:**
1. In `SyncRepositoryImpl.kt`, use `currentUser.linkWithCredential()` when transitioning an active anonymous session to Google or Email authentication. This preserves the existing UID and retains access to `/users/{uid}/` subcollections.
2. Define a strict snapshot validation contract in `RemoteEntityValidator.kt`: documents flagged with `isFullSnapshot = true` must contain complete financial and identity fields (`debtIqd`, `displayName`), failing closed as `Malformed` if missing. Partial updates (`isFullSnapshot = false` or omitted) safely fall back to existing local attributes.
* **Consequences:**
- Guarantees seamless identity and cloud storage continuity when users link Google accounts.
- Prevents corrupt partial sync payloads from zeroing or corrupting baseline snapshot data.
* **Related roadmap items:**
- CHAT8-4-P0-1, CHAT8-4-P1-1

## ADR-025: uTower Historical Data as Immutable Source History
* **Status:** Accepted (Permanent)
* **Context:** Imported uTower historical statements and raw ledger entries represent immutable historical audit records from external ISP billing engines.
* **Decision:** Treat all historical import rows as immutable source records. Historical entries are never rewritten, re-applied, or modified during runtime calculations.
* **Consequences:** Eliminates financial discrepancies and guarantees audit log fidelity.

## ADR-026: Database Snapshot as Authoritative Migration Baseline
* **Status:** Accepted (Permanent)
* **Context:** Full restore and migration flows must reliably establish baseline state without performing heuristic reconstruction.
* **Decision:** Snapshot state restored from backup represents the absolute authoritative starting baseline. Runtime financial calculations must not re-apply historical entries that are already factored into baseline snapshot balances.
* **Consequences:** Guarantees idempotency during backup/restore and clean database migrations.

## ADR-027: Remote Version Derived Exclusively from Server-Side Timestamps
* **Status:** Accepted (Permanent)
* **Context:** Local device clocks are subject to skew, tampering, or drift, causing inaccurate conflict resolution when comparing local timestamps (`createdAt`, `occurredAt`) against Firestore timestamps.
* **Decision:** The remote version domain is strictly derived from authoritative Firestore server timestamps (`updatedAt`, `deletedAt`). Local device timestamps are never used as substitutes for server versions during conflict resolution.
* **Consequences:** Resolves all clock-skew conflicts deterministically across multi-device synchronizations.

## ADR-028: Canonical Mutation Exclusivity through DataOperationCoordinator [FROZEN-TARGET COMPATIBLE]
> **Status**: FROZEN-TARGET COMPATIBLE. Governs mutation exclusivity.
* **Status:** Accepted (Permanent)
* **Context:** Direct uncoordinated database writes or parallel operations (e.g. backup during import, or restore during realtime sync) lead to race conditions, table locking, or corrupted states.
* **Decision:** Every synchronized mutation or high-impact maintenance action MUST execute exclusively through `DataOperationCoordinator` under an explicit `DataOperationMode`.
* **Consequences:** Guarantees transactional isolation, thread safety, and zero concurrent state clobbering.

## ADR-029: Snapshot Restoration Protocol [SUPERSEDED BY FROZEN AUTHORITY]
> **Status**: SUPERSEDED BY FROZEN AUTHORITY. Governed by G3 Restore specifications in `docs/authority/`.
* **Status:** Accepted (Permanent)
* **Context:** Restoring backups via heuristic reconstruction risks partial state drift and ghost records.
* **Decision:** Database restore executes as an exact database snapshot restoration, replacing tables atomically within a single transaction and invalidating transient deduplication caches via `remoteSyncCoordinator.clearCache()`.
* **Consequences:** 100% deterministic restore behavior across devices.

## ADR-030: Fail-Closed Security Policy for Cryptography and Signing
* **Status:** Accepted (Permanent)
* **Context:** Cryptographic failures (missing keystore, corrupted database passphrases, or unconfigured release signing) must never fall back to insecure defaults or silent blank keys.
* **Decision:** The application fails closed:
1. Missing release signing credentials halts `assembleRelease` immediately.
2. Unrecoverable SQLCipher keys on existing databases halts database initialization safely rather than overwriting with a new key.
3. Android Keystore failures in production reject operation execution immediately.
* **Consequences:** Uncompromising security posture against data leakage or silent corruption.

## ADR-031: Immutable Certification Evidence Contract
* **Status:** Accepted (Permanent)
* **Context:** Weakening or altering verification test assertions to match buggy production code creates false confidence.
* **Decision:** All certification test suites (`FinalTestMatrixCertificationTest`, `ProductionCertificationPipelineTest`, etc.) are frozen immutable contracts. Production code must adapt to satisfy the tests, never the inverse.
* **Consequences:** Machine-enforced truth and reliable production readiness guarantees.

## ADR-032: Anonymous Firebase Sync Policy
* **Status:** Accepted (Permanent)
* **Context:** Anonymous Firebase sessions require unambiguous cloud sync and authentication transition rules.
* **Decision:**
1. When an anonymous user successfully links with permanent credentials, the existing Firebase UID is preserved and cloud ownership remains in the same /users/{uid}/ namespace.
2. If linking is not possible and the application must transition to a different permanent UID, all listeners and sync state for the old UID MUST be fully detached before the new UID becomes active. Cloud data ownership MUST follow the explicitly defined recovery/reconciliation policy; it MUST NOT be assumed to transfer automatically.
* **Consequences:** Eliminates cross-account data leaks and ensures continuous data ownership.





## ADR-031: Verification Contract Reconciliation & Loop-Prevention Protocol
* **Status:** Accepted (Permanent)
* **Context:** The project transitioned to consolidated phase requirements (`contract/phase_requirements.yaml`), yet verification contracts (`contract/test_environment_matrix.yaml`) retained references to 114 obsolete or unconsolidated test entries, causing false verification blockers unrelated to active code.
* **Decision:**
  1. **Contract Reconciliation over Test Deletion:** When test structures or requirements consolidate, verification contracts must be updated through formal reconciliation (mapping active suites, classifying obsolete suites, preserving future phase suites) rather than deleting tests or weakening gates.
  2. **Lesson Learned - Verification Contract Drift:** The test environment matrix retained obsolete/misaligned legacy entries after the project transitioned to consolidated phase requirements, causing a false verification blocker unrelated to the current Phase 1/2 implementation.
  3. **Regression Protection Enforcement:** Critical certification suites across all active and future phases must be explicitly locked and checked for presence in both matrix and disk to prevent silent drift.
* **Consequences:** Eliminates verification loops, ensures fail-closed compliance, and guarantees zero-false-positive gate execution.
* **Related roadmap items:** EARTHLINK-ROOT-CAUSE-PHASE-2.

## Database Restore Protocol Semantics

- **Context:** Restoring older database backups risks artificial sync storms or overwriting newer cloud records if timestamps or outbox queues are not managed strictly.
- **Decision:** The following Restore Protocol rules are strictly enforced during `BackupManager.kt` restore operations:
  1. **Timestamp Preservation**: Restored entities must preserve their original timestamps (`updatedAt`, `createdAt`). `restoreBackupZip` must NOT artificially bump `updatedAt` to `System.currentTimeMillis()`.
  2. **Outbox Queueing**: Restoring a backup must NOT queue new `upsert` entries for merged records. Only offline outbox state captured *within* the backup file is restored verbatim.
  3. **Tombstone Respect**: The restore process must respect local pending delete tombstones in the live database's `sync_outbox`. Deleted entities must not be resurrected by an older backup.
  4. **Timestamp Conflict Evaluation**: When merging, backup records are inserted/upserted ONLY if the local record does not exist or the backup record is strictly newer than the local record.
- **Consequences:** Protects newer cloud changes, eliminates artificial upsert storms, and prevents zombie resurrection of deleted records.

## Concurrency Strategy
- **Database Consistency (Room)**: Always use `withTransaction {}` for multi-step database operations to guarantee atomicity and thread-safe rollback on error.
- **In-Memory Cache & Suspending State**: Always use `kotlinx.coroutines.sync.Mutex` (`mutex.withLock {}`) when protecting asynchronous caches, state flows, or suspending logic.
- **No Thread Blocking in Coroutines**: NEVER use `synchronized(this)` inside a `suspend` function. Thread-blocking synchronization primitives block the underlying coroutine dispatcher worker, causing thread starvation.

## Backup Security & Portability Architecture
- **Archive Format**: `.zip` archive containing the SQLite/SQLCipher database (`earthlink_secure.db`), WAL/SHM sidecars, and `backup_info.json`.
- **Passphrase Encryption**: `AES/GCM/NoPadding` (256-bit key) with random 12-byte IV and random 16-byte PBKDF2 salt (10,000 iterations). Backups are encrypted using device and user seeds (`Firebase UID`, `Device ID`, fallback seeds).
- **Restore Candidate Resolution**: During restore, `BackupManager` executes deterministic candidate verification across extracted passphrase, current passphrase, fallback passphrase, firebaseUid, and legacy unencrypted candidate (gated with `LEGACY_UNENCRYPTED_BACKUP_RESTORE` audit log).
- **Pre-Restore Safety**: Automatically creates a full pre-restore snapshot (`pre_restore_backup_*.zip`). If generation fails, restore halts unless `force = true`. Wrapped in `DataOperationCoordinator.withOperation`.

## Firestore Security Rules & Multi-Tenant Verification
- **Architecture**: Enforces complete multi-tenant cryptographic and identity-based isolation through Firebase Authentication. All Firestore entities are strictly scoped inside the user's private tenant path: `/users/{userId}/{collectionName}/{documentId}`.
- **Rules (`firestore.rules`)**: Uses helper functions `isAuthenticated()` and `isOwner(userId)` to ensure `request.auth.uid == userId`. Collections explicitly aligned include `local_accounts`, `local_ledger_entries`, `import_batches`, and `audit_logs`.
- **Implementation Alignment**: `SyncRepositoryImpl.kt` queries `firestore.collection("users").document(uid).collection(collName)`, matching the security rules exactly.

## Explicit Timestamp Semantics
- **Timestamp Definitions**:
  - `createdAt`: Immutable system timestamp when record was created.
  - `businessUpdatedAt` (`updatedAt`): Mutable domain timestamp set ONLY by user/business actions.
  - `occurredAt`: Immutable transaction execution timestamp.
  - `remoteUpdatedAt` / `serverTimestamp`: Authoritative timestamp assigned by Firestore.
  - `derivedCalculatedAt`: Internal execution timestamp for local recalculations (MUST NOT mutate `businessUpdatedAt`).
  - `syncCursor` (`last_sync_timestamp`): High-water mark representing the latest server-confirmed state.
- **Conflict Resolution Rules**:
  - *Zero Artificial Timestamps on Remote Apply*: Incoming entities MUST NOT artificially bump `updatedAt` to `System.currentTimeMillis()`.
  - *Explicit Timestamp Comparison*: `local_accounts` use `updatedAt`. `local_ledger_entries` use `createdAt` or `occurredAt`. Never compare `createdAt` vs `updatedAt` for mutable entities.
  - *Tie-Breaking Policy*: If local and remote timestamps are exactly equal, the remote state is authoritative and wins to guarantee convergence.
