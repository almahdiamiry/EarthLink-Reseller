# Production Invariants (Permanent Contract)

This document specifies the non-negotiable architectural and runtime invariants for the Earthlink Reseller App.
Every code change, refactor, and synchronization enhancement MUST uphold these invariants at all times.
All invariants are subordinate to the Frozen Implementation Authority Bundle in `docs/authority/`.

---

## State Ownership Invariants

- **INV-01 (Four Distinct State Tiers):** Application state is partitioned into four non-overlapping tiers: `Historical` (immutable source archives), `Snapshot` (authoritative baseline state), `Runtime` (derived application/presentation state computed from authoritative local state), and `Remote` (cloud Firestore mirrors). No tier may assume the role or responsibilities of another. Runtime state is never an independent source of truth.
- **INV-02 (Historical Source Immutability):** Imported uTower raw historical records and committed historical ledger records are immutable after successful import commit. ImportBatch lifecycle metadata may transition only through its explicitly defined state machine (e.g. `in_progress` -> `completed` / `failed` / `rolled_back`), while its committed historical source payload remains immutable.
- **INV-03 (Single Source of Truth):** Room Database is the single local source of truth for runtime business state. Composables, ViewModels, and background tasks must observe local Room state and never maintain isolated parallel databases.
- **INV-04 (Zero Double-Application):** Runtime calculations, balance derivations, and history rebuilds must never re-apply historical snapshot records on top of existing baseline snapshots.

---

## Synchronization & Versioning Invariants

- **INV-05 (One State, One Authority):** Every synchronized business entity must have one authoritative semantic definition, one version domain, one mutation policy, and one synchronization path.
- **INV-06 (One Authoritative Remote Version Domain):** The authoritative remote version MUST originate from trusted Firestore server-side timestamps. For normal documents this is `updatedAt`; for deletion/tombstone state this is `deletedAt`. These server timestamps MUST be persisted locally as the comparable remote version and MUST be compared only against the same remote-version domain. Local timestamps (`createdAt`, `occurredAt`, or device-clock `updatedAt`) MUST NEVER be used as substitutes for server versions.
- **INV-07 (Composite Cursor Advancement):** Sync pagination and realtime resume cursors MUST use composite keys `(serverTimestamp, documentId)`. A cursor may advance only past an event that has a valid ordered cursor position and has been successfully applied, deterministically resolved, or quarantined without losing its recoverable position. A malformed event with no valid server version/cursor position MUST NOT be allowed to silently disappear behind a later cursor.
- **INV-08 (Realtime Echo Isolation):** Snapshot listeners must evaluate `metadata.hasPendingWrites()` on a per-document basis (`dc.document.metadata.hasPendingWrites()`). A local pending write on entity A must NEVER suppress or delay unrelated remote events for entity B within the same snapshot.
- **INV-09 (Query Membership != Business Deletion):** A Firestore `DocumentChange.Type.REMOVED` indicates query boundary exit, NOT business deletion. Business deletion MUST be explicitly signaled by a `deletedAt` tombstone timestamp or explicit deletion contract.
- **INV-10 (Deterministic Convergence):** Multiple devices with differing states MUST converge to an identical fixed-point state after repeated synchronization passes. Once converged, subsequent sync passes MUST produce ZERO additional Firestore writes.

---

## Mutation & Coordinator Invariants

- **INV-11 (Deterministic Restore & Mutation Channel):** Every runtime code path capable of mutating synchronized business state or performing destructive database operations MUST execute within an atomic, serialized mutation boundary (coordinated via `DataOperationCoordinator` as an implementation mechanism). In accordance with G3 Restore specifications, database snapshot restoration restores the exact snapshot baseline deterministically without heuristic reconstruction, synthetic invoice generation, or uncoordinated historical recomputation.
- **INV-12 (No Outbox Loops on Remote Apply):** Applying incoming remote events (`REMOTE_APPLY`) or recalculating balances from cloud sync MUST NOT generate local Outbox records (`sync_outbox`), preventing echo/ping-pong synchronization loops.
- **INV-13 (Outbox Durability & Operation Mutual Exclusion):** High-impact data operations (`RESTORE`, `IMPORT`, `BACKUP`, `ROLLBACK`, `CLEAR_DATA`) are mutually exclusive and must execute under strict maintenance isolation. Outbox mutations are durable and retryable until terminal success or explicit user cancellation; user mutations MUST NOT be discarded into an unrecoverable `DEAD_LETTER` blackhole. BACKUP MUST NOT mutate business state, create Outbox records, advance sync cursors, or alter synchronization metadata.

---

## Security & Reliability Invariants

- **INV-14 (Fail-Closed Encryption & Key Recovery):** If an existing encrypted SQLCipher database is detected on disk but the database key material cannot be decrypted/recovered, the system MUST fail closed (halt access safely). It MUST NEVER generate a new blank encryption key over an existing encrypted database.
- **INV-15 (Fail-Closed Release Signing):** In `release` builds, missing or invalid production signing credentials MUST cause an immediate build failure. Fallback to debug signing, placeholder keys, or unsigned release artifacts is strictly prohibited.
- **INV-16 (Immutable Certification Evidence):** Certification test suites (`FinalTestMatrixCertificationTest`, `ProductionCertificationPipelineTest`, `ProductionExecutableInvariantsTest`, `DeepCrossLayerInvariantsTest`) and baseline regression suites are frozen contracts. Tests must never be weakened, skipped, or rewritten to conform to buggy production behavior.

---

## Verification & Governance Compliance Invariants

> **Invariant Scope Distinction**:
> - **INV-01 through INV-16**: The 16 canonical runtime and business invariants governing application state, synchronization, mutations, and security. Evaluated and enforced by `contract/invariant_contract.yaml` and `scripts/verify_invariant_contract.py`.
> - **INV-17**: Meta / governance invariant governing machine-verified compliance, bounded test execution, and non-recursive governance closure across phase transitions.

- **INV-17 (Fail-Closed Verification & Bounded Execution):** Every phase closure MUST be verified against `contract/phase_requirements.yaml` through machine evidence matrix (`ALL BLOCKING ROWS PASS`). Narrative reports without machine proof are ignored. Verification tasks MUST execute through bounded runners (`run_verified_command.py`) enforcing strict timeouts, process-tree cleanup, NO-SOURCE detection, and Meta-Gate adversarial fixtures (`GOV-01..08`). All new findings MUST be incorporated directly into existing manifests and registries under the Non-Recursion Rule without introducing uncoordinated governance layers.
