# Changelog

All notable changes to the EarthLink Reseller V1 project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> ℹ️ **Historical Archive:** For granular, workstream-by-workstream forensic changelogs covering pre-release development and the complete remediation era (v1.0.0 – v1.109.0), see [`docs/historical/CHANGELOG_V1_REMEDIATION_ARCHIVE.md`](docs/historical/CHANGELOG_V1_REMEDIATION_ARCHIVE.md).

---

## [Unreleased]

### Maintenance
- Consolidated root tracking documentation (`ISSUE_LOG.md`, `FUTURE_WORK.md`, `PROJECT_ROADMAP.md`, `CHANGELOG.md`) aligning repository topology with `AGENTS.md` and active post-V1 maintenance rules.

### Fixed & Hardened (Safety Workstreams WS1–WS9)
- **WS1 (XC-01, API-02)**: Statement correlation timezone aligned to `Asia/Baghdad` and deserialization mapped to API v0.7.0 keys (`deposit`, `withdrawal`, `balance`, `date`).
- **WS2 (API-03)**: Gateway activation HTTP 200 responses with missing `userIndex` mapped to `EarthlinkInconclusiveException` (`INCONCLUSIVE`) to preserve recovery path.
- **WS3 (FIN-02)**: Successive compound corrections compute delta against effective position (root + prior corrections) with idempotency return on zero-delta.
- **WS4 (OUT-01, OUT-05, FIN-03)**: Per-account active pending operation deduplication, strict caller abort on claim failure, and 250 IQD multiple boundary validation.
- **WS5 (CONC-04, CONC-05)**: Immediate `INCONCLUSIVE` exit for operations already in `RESOLVING` state; serialized cold-start recovery lock.
- **WS6 (ID-01)**: Local account provenance guard preventing live ISP gateway requests for unlinked/local-only accounts.
- **WS7 (ID-02, ID-03, ID-07)**: Conflicting username guard in phone matching (Stage 3) and collision quarantine for ambiguous names/phones during uTower imports.
- **WS8 (CONC-01)**: Downward pull sync wrapped in `DataOperationCoordinator.withOperation(DataOperationMode.REMOTE_APPLY)` for mutual exclusion.
- **WS9 (API-06)**: Double-checked token refresh locking on `tokenLock` with deterministic coroutine gate verification.

### Added
- 25 permanent regression tests across 9 workstream suites, elevating the verified test baseline to 607/607 passing (100% green).

---

## [1.109.0] - 2026-08-22 — V1 Production Certified Baseline

**Final Independently Certified Baseline: `6d91dbd` | Test Corpus: 535/535 Green**

### Added & Certified
- **Zero-Trust Release Gating & Machine Certification (G8 / Phase 03)**:
  - Validated all 79 adversarial probe handlers against production invariant contracts.
  - Certified fail-closed execution, cryptographic release APK signing integrity (`earthlink_reseller_release.jks`), and ProGuard/R8 minification rules (`AppBuildConfig`).
- **G1 Crash & Process-Restart Durability**:
  - Single-writer hardware claim (`claimDispatch`) preventing duplicate external mutation dispatch.
  - 4-tuple correlation `(userID, operation, amount, timestamp ±90s)` against gateway statement upon app restart.
  - Automatic startup recovery sweep wired into `EarthlinkApp.onCreate` and background sync.
- **Financial History Immutability & Preservation**:
  - Direct physical row deletions from `local_ledger_entries` eliminated; `ON DELETE CASCADE` removed in Room migration 14.
  - Additive correction-by-difference (`correctsEntryId`) for ledger adjustments and reversals.
  - ISP subscriber deactivations/disappearances decouple into monotonic `isHistoryOnlySubscriber = true`, permanently preserving debt and payment histories.
- **G4 Lineage Linearization & Sync Safety**:
  - Generation advancement (`g4_local_generation`) on full dataset replacement/clear.
  - Same-transaction generation validation rejecting stale remote sync results.
  - Per-item SQLite outbox processing with poison-pill isolation and zero terminal dead-letter drops.
- **Deterministic Import & Identity (G5)**:
  - Deterministic fallback coordinate derivation for uTower archive imports (`import_${batchId}_${transactionsRead}`).
  - Proven two-device offline transaction convergence and idempotency under arrival-order variations.

---

## [1.0.0] - Initial Release Baseline
- Core reseller subscriber management, PPPoE credentials tool, and multi-package pricing.
- Offline-first Room database with SQLCipher AES-256 encryption at rest.
- Localized Arabic & English Material 3 interface.
