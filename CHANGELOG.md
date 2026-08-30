# Changelog

All notable changes to the EarthLink Reseller V1 project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

> ℹ️ **Historical Archive:** For granular, workstream-by-workstream forensic changelogs covering pre-release development and the complete remediation era (v1.0.0 – v1.109.0), see [`docs/historical/CHANGELOG_V1_REMEDIATION_ARCHIVE.md`](docs/historical/CHANGELOG_V1_REMEDIATION_ARCHIVE.md).

---

## [Unreleased]

### Maintenance
- Consolidated root tracking documentation (`ISSUE_LOG.md`, `FUTURE_WORK.md`, `PROJECT_ROADMAP.md`, `CHANGELOG.md`) aligning repository topology with `AGENTS.md` and active post-V1 maintenance rules.

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
