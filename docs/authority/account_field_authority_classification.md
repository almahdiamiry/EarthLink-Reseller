# LocalAccount Field Authority Classification

This document provides the authoritative multi-authority domain classification for all fields in `LocalAccount` per Phase 5 Task P5-01, `Target Product Contract v0.6`, `G1-G8 Consolidated Architecture Summary`, and `Final Independent Adjudication Memo`.

---

## Authority Domain Taxonomy

Every field of `LocalAccount` belongs to exactly one authority domain:

| Domain | Authority / Mutation Path | Conflict Resolution Rule |
| :--- | :--- | :--- |
| **ISP / Server-Owned** | ISP Cloud / Network API responses (`earthlink_username`, `status`, `expirationDate`, `profileName`) | Server wins; immutable locally except via authoritative ISP sync/response |
| **Reseller / Local-Owned** | Reseller UI & Local Mutations (`displayName`, `phone1`, `phone2`, `address`, `currentPriceIqd`) | Local/Reseller writes via Canonical Outbox; LWW or interactive merge |
| **Reminder-Note / LWW** | Reseller Notes & Reminders (`reminderNote`, `notes`, `reminderDate`) | Last-Write-Wins based on explicit mutation timestamp |
| **Operational Credential / Session** | Active Firebase Auth Session & Token (`firebaseUid`, `userToken`) | Scoped strictly to active auth session; zero cross-session leakage |
| **Derived Financial** | Computed from Opening Baseline + Canonical Ledger (`debtIqd`, `advanceIqd`) | Derived dynamically via `BalanceCalculator.reconstructCurrentPosition()`; NEVER directly overwritten |
| **Legacy / History-Only** | Snapshot baseline & uTower import preservation (`openingDebtIqd`, `openingAdvanceIqd`, `openingLoanIqd`, `loanIqd`, `isLegacy`, `isSnapshotHistory`, `stateSource`, `stateConfidence`) | Immutable historical baseline; preserved permanently across ISP deletions |

---

## Full Field Inventory

| Field Name | Type | Authority Domain | Semantics & Invariants |
| :--- | :--- | :--- | :--- |
| `id` | `String` | **Identity** | Primary Key; UUID / deterministic provenance ID (`INV-01`, `INV-05`) |
| `displayName` | `String` | **Reseller / Local-Owned** | Reseller-facing account label |
| `earthlinkUsername` | `String?` | **ISP / Server-Owned** | Authoritative ISP username |
| `phone1` | `String?` | **Reseller / Local-Owned** | Primary contact phone |
| `phone2` | `String?` | **Reseller / Local-Owned** | Secondary contact phone |
| `address` | `String?` | **Reseller / Local-Owned** | Physical location / address |
| `debtIqd` | `Double` | **Derived Financial** | Current outstanding debt; derived from baseline + ledger |
| `advanceIqd` | `Double` | **Derived Financial** | Current prepayment balance; derived from baseline + ledger |
| `currentPriceIqd` | `Double` | **Reseller / Local-Owned** | Subscription package price |
| `openingDebtIqd` | `Double` | **Legacy / History-Only** | Snapshot baseline opening debt (`INV-01`, `INV-06`) |
| `openingAdvanceIqd`| `Double` | **Legacy / History-Only** | Snapshot baseline opening advance |
| `openingLoanIqd` | `Double` | **Legacy / History-Only** | Snapshot baseline opening loan |
| `loanIqd` | `Double` | **Legacy / History-Only** | Historical uTower loan compatibility data (never a second financial authority) |
| `isLegacy` | `Boolean` | **Legacy / History-Only** | History-only/deactivated flag; protects account from physical deletion |
| `isSnapshotHistory`| `Boolean` | **Legacy / History-Only** | Marks row as immutable historical snapshot artifact |
| `stateSource` | `String?` | **Legacy / History-Only** | Baseline origin (e.g. `UTOWER_IMPORT`, `SNAPSHOT_BASELINE`) |
| `stateConfidence` | `String?` | **Legacy / History-Only** | Baseline trust classification (e.g. `VERIFIED`, `HEURISTIC`) |
| `sourceExternalId` | `String?` | **Legacy / History-Only** | Provenance external source key |
| `sourceBatchId` | `String?` | **Legacy / History-Only** | Import batch provenance reference |
| `createdAt` | `Long` | **Reseller / Local-Owned** | Local record creation timestamp |
| `updatedAt` | `Long` | **Reseller / Local-Owned** | Local record update timestamp (NOT remote version) |
| `remoteVersion` | `Long?` | **ISP / Server-Owned** | Authoritative Firestore server timestamp (`INV-06`) |
