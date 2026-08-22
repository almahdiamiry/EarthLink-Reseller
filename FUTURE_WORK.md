# Future Work

This document tracks valid future engineering improvements that remain legitimate and useful after the V1 certification baseline.

When an item is completed:
1. Remove it from this document.
2. Record completion in `CHANGELOG.md`.
3. Record any lasting lessons in `docs/LESSONS_LEARNED/`.

---

## Valid Future Work

### FW-01 — ViewModel Architectural Thinning
* **Description:** Decompose `EarthlinkSearchViewModel` and eliminate residual in-memory `inflightAccountLocks` by moving orchestration entirely into repository and use-case layers.
* **Why Useful:** Improves presentation-layer testability and architectural separation of concerns.
* **Constraint:** SQLite single-claim authority (`claimDispatch`) already provides durable hardware-level execution safety; this is an internal presentation-layer cleanup.

### FW-02 — API DTO Typing & Modernization
* **Description:** Replace residual untyped JSON and generic `Map` response parsing in non-financial API call sites with strongly typed Kotlin data classes.
* **Why Useful:** Increases compile-time type safety and code clarity across secondary network interactions.
* **Constraint:** Applies only to non-financial endpoints; all financial and mutation endpoints are already typed and contract-verified.

### FW-03 — Database Schema Integer Modernization
* **Description:** Migrate monetary columns (`amountIqd`, `debtIqd`, `loanIqd`, `advanceIqd`) in SQLite Room entities and queries from `Double` to integer `Long` cents/fils.
* **Why Useful:** Eliminates floating-point types in the database schema.
* **Constraint:** Deferred to post-launch database maintenance. Runtime financial accuracy is currently guaranteed by boundary validation enforcing whole 250-IQD integer denominations.

### FW-04 — Room Schema Baseline Consolidation
* **Description:** Squash incremental Room migrations (`MIGRATION_1_2` through `MIGRATION_16_17`) into a unified baseline schema.
* **Why Useful:** Simplifies future schema maintenance and reduces migration overhead for post-V1 database upgrades.
* **Constraint:** Post-launch maintenance. Current 16 incremental migrations are non-destructive and fully verified.

### FW-05 — Large File Modularization
* **Description:** Decompose oversized Kotlin source files (`Repositories.kt`, `UserDetailScreenV2.kt`, `UtowerImporter.kt`, `SettingsScreen.kt`, `SyncRepositoryImpl.kt`) into smaller, single-responsibility files and packages.
* **Why Useful:** Improves maintainability, IDE responsiveness, and compilation locality.
* **Constraint:** Pure refactoring with zero runtime behavior changes.

---

## Conditional Work

### COND-01 — Demo Code Removal
* **Description:** Remove `demoMode` code paths across the codebase.
* **Condition:** Execute only if a future release policy explicitly requires zero demo code in the release artifact binary. Otherwise, demo mode remains isolated behind developer flags.
