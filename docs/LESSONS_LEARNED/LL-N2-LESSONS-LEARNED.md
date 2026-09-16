## N2 Lessons Learned

> **Status & Authority Boundary**: This document is a historical record of verified forensic lessons and observed findings from the N2 milestone. It is **NOT** an authoritative specification and must never override the current literal source code or active project authorities (`Target Product Contract v0.6`, `AGENTS.md`).

---

### A. Technical Lessons & Observed Facts

#### 1. Multi-Device Identity Fragmentation
* **Observed Fact**: Offline and distributed operations on separate devices materialized distinct `LocalAccount` entities (each with a local UUID) for the same physical ISP subscriber.
* **Lesson Learned**: In local-first distributed architectures, a local primary key (`LocalAccount.id`) cannot serve as the single source of domain identity. Domain matching and reconciliation must account for multi-container reality without forcing premature physical merges.

#### 2. Inbound Ledger Wedge
* **Observed Fact**: When incoming remote account synchronizations collided on `sourceExternalId`, legacy logic dropped or quarantined the entire account. Subsequent incoming ledger rows referencing `accountId REFERENCES local_accounts(id)` failed due to foreign key violations.
* **Lesson Learned**: Never drop an authentic incoming container that carries dependent transactional lineage. Persist the incoming container in an unindexed or relaxed state with an explicit audit trail (`IDENTITY_COLLISION_RECORDED`), allowing dependent financial rows to land safely.

#### 3. Strategy D (`sourceExternalId` Constraint Relaxation)
* **Observed Fact**: Migration 17$\to$18 removed the `UNIQUE(sourceExternalId)` index constraint on `local_accounts`.
* **Lesson Learned**: Unique constraints on external or third-party identifiers in local databases assume single-writer synchronization that does not hold during distributed offline imports. Removing the uniqueness constraint avoids synthetic ID fabrication and prevents sync aborts.

#### 4. uTower Ambiguity Handling
* **Observed Fact**: Multiple local account candidates shared identical normalized usernames or phone numbers.
* **Lesson Learned**: When automated matching cannot establish a unique link with 100% confidence, the matcher must return `SubscriberMatchResult.Ambiguous`. Fabricating synthetic "ghost" accounts or arbitrarily selecting the first candidate silently corrupts financial history.

#### 5. B2 Ambiguity Policy
* **Observed Fact**: Batch imports encountering ambiguous subscriber records were prone to halting or partial corruption.
* **Lesson Learned**: Adopt the B2 policy: quarantine the ambiguous source record, skip dependent financial transactions for that specific row, emit an audit warning, and allow the remainder of the batch to proceed.

#### 6. Identity Bridge Pattern
* **Observed Fact**: Accounts bound to the EarthLink ISP gateway require an association to an external subscriber index.
* **Lesson Learned**: Do not re-key local account primary keys (`LocalAccount.id`) or reparent immutable ledger rows across accounts. Use an identity bridge pointer (`ispUserIndex`) on the existing container to associate external identity while keeping local financial lineage physically intact.

---

### B. Evidence Integrity Failures

During the N2 forensic reviews, multiple narrative summaries by prior agents fabricated code structures that directly contradicted the literal Git working tree:

1. **Inaccurate Signature Claims**: Prior reviews relied on alternative or inaccurate signature claims for `bindIspIdentity`. The literal, verified working tree signature in `Repositories.kt` is:
   ```kotlin
   suspend fun bindIspIdentity(
       accountId: String,
       userIndex: Int,
       ispSubscriberId: String?
   ): LocalAccount
   ```
2. **Fabricated DAO Update Mechanics**: Narrative claims asserted that the account was updated via `accountDao.updateIspIdentity(...)` or `accountDao.upsert(...)`. The literal source implementation executes:
   ```kotlin
   val existing = accountDao.getByIdOneShot(accountId) ?: ...
   val updated = existing.copy(ispUserIndex = userIndex, ...)
   accountDao.update(updated)
   OutboxManager.upsertWithOutbox(
       outboxDao = outboxDao,
       entityType = "local_accounts",
       entityId = updated.id,
       payload = adapter.toJson(updated)
   )
   ```
3. **Manufactured UUID Rewriting Claims**: Summaries asserted that inbound account collisions were resolved by re-minting UUIDs via `collidingAccount.copy(id = UUID.randomUUID().toString())`. Literal source inspection proved no UUID re-minting was ever implemented; the authentic remote UUID is preserved.
4. **False Positive Reconciliation Claims**: Reviews asserted that an unhandled `IllegalStateException` inside `bindIspIdentity` was incorrectly classified as requiring `RECONCILIATION_REQUIRED`. Literal execution path tracing proved the financial operation and ledger write had already completed and committed, audit logging had completed, and the exception was safely caught and logged without financial drift.

---

### C. Role-Based Evidence Framework

To prevent narrative drift, evidence must be evaluated by its **functional role** rather than an absolute hierarchical ladder:

```text
CURRENT LITERAL SOURCE
→ Establishes what the system currently implements and executes.

ACTIVE PROJECT AUTHORITY
→ Establishes what the system is authorized and required to do (contracts & invariants).

EXECUTED TEST SUITES
→ Establishes what behavior was empirically exercised and verified at runtime.

HISTORICAL CERTIFICATION RECORDS
→ Establishes what was verified at a specific prior milestone boundary.

AGENT SUMMARIES & NARRATIVES
→ Navigation and hypothesis generation only; carries zero evidentiary weight.
```

* **Core Rule**: Evidence sources have different evidentiary roles; a source can prove what exists without proving what is required, and an authority can establish a requirement without proving the implementation satisfies it.
* **Defect Separation**: When Current Source conflicts with Active Authority, it indicates an implementation defect or a requirement mismatch, not that source automatically overrules authority or vice versa.
* **Non-Self-Authentication**: Agent summaries are never self-authenticating evidence. Every claim must be verified against literal source or executed test output.

---

### D. Authority Discipline

1. **Owner $\ne$ Router**: `AGENTS.md` governs operational rules, verification procedures, and routing. `Target Product Contract v0.6` governs product requirements and domain truth. Neither duplicates mutable facts owned by the other.
2. **Closed Means Closed**: When an architectural milestone (such as G1–G8 or Task 3) is locked, it cannot be reopened for aesthetic refactoring or line-count reduction. Reopening requires a demonstrated repository defect, an invariant violation, or an authoritative contradiction.
3. **Contract Precedence**: No implementation change may relax an operational RED invariant simply to satisfy a test.

---

### E. Contradiction Register Discipline

When an agent summary or narrative review directly contradicts the literal source:
* Do not attempt to compromise, harmonize, or synthesize between the hallucination and the real code.
* Immediately quote the exact file path and line numbers from the literal Git working tree.
* Formally reject the fabricated statement.

---

### F. Current vs. Historical Evidence

* **Historical Evidence (`evidence/`, `docs/historical/`)**: Represents static point-in-time certification records (e.g. G8 certification, migration 16 verification). They document how the system reached a previous milestone and must not be treated as live executable code.
* **Current Working Tree**: Represents the live, mutable state of the application. Current behavior must always be evaluated against the active source files and the latest active migrations (e.g. Migration 17$\to$18), not superseded historical test scripts.

---

### G. Test Evidence Discipline

1. **Passing Tests $\ne$ Full Contract Safety**: A green test only proves the exact assertions executed. A test that asserts `assertTrue(result.isSuccess)` without checking database side-effects or outbox mutations does not prove data integrity.
2. **Explicit Financial Oracles**: Financial test assertions must never duplicate production formulas. Expected balances must be hard-coded literal arithmetic constants derived independently from opening vectors and known mutations:
   $$\text{Expected Position} = (\text{Opening Debt} - \text{Opening Advance} + \text{Opening Loan}) + \sum(\text{Runtime Mutations})$$
3. **Deterministic Concurrency**: Synchronization tests must use deterministic `CompletableDeferred` or mutex barriers, never arbitrary timing delays (`delay(100)`).
4. **Scope Accuracy**: Never claim "all tests passed" unless the complete repository verification gate was executed. Targeted runs must always be reported as "Focused verification passed."

---

### H. Layer Separation

A recurring source of confusion in distributed systems review is conflating distinct architectural layers:

* **Local Identity Binding $\ne$ Remote Synchronization**: `bindIspIdentity` is a local SQLite transaction binding a local account container to an ISP index. It operates independently of Firestore sync mechanics.
* **Document Transport Ordering $\ne$ Domain Identity Authority**: Firestore's `remoteVersion` establishes transport write ordering (last-write-wins for document snapshots). It does not confer authority to override physical ISP gateway subscriber mapping.
* **Deterministic Convergence $\ne$ Semantic Correctness**: Two devices converging to identical database states via LWW does not guarantee that the converged state reflects real-world subscriber ownership. Domain invariants must guard against illegal state convergence.
* **Precondition Validation $\ne$ Failure Requiring Recovery**: An in-memory or transactional rejection of an invalid state transition (such as index mismatch) is a normal defensive guard, not an operational failure requiring external data repair.

---

### I. Auditability Boundary Lesson

The targeted adjudication of `bindIspIdentity` in `refillSubscriber` established clear boundaries for audit requirements:

* **Operational Action Audited**: The user-initiated financial operation (`REFILL_USER`) is persistently logged in `audit_log` with subscriber ID, amount, and transaction metadata.
* **Ledger Persisted**: The financial transaction is immutably recorded in `local_ledger_entries`.
* **Binding Failure Contained**: When `bindIspIdentity` encounters an index conflict ($X \ne Y$), it throws an `IllegalStateException` inside a transactional block. The exception is caught and logged to system logcat (`Log.w`).
* **Zero Financial Drift**: Because the ledger correctly reflects the payment and debt resolution, and the ISP account was successfully refilled, the failure to update an optional index does not corrupt balances or orphan transactions. Creating synthetic recovery tickets or marking the account `RECONCILIATION_REQUIRED` would introduce false alarms for an already completed, verified transaction.

---

### J. Identity and Container Lessons

1. **Local Account as a Container**: A `LocalAccount` row is a local storage container for ledger history and metadata, not an infallible mirror of an ISP subscriber entity.
2. **Ledger Immutability**: Ledger rows must never be deleted, cascaded, or silently reparented across accounts. The additive history from baseline must remain untouched under all merge and conflict scenarios.
3. **No Blind Unification**: When two containers are discovered to belong to the same real-world user, they should be reconciled through identity pointers (`ispUserIndex`), not destructive row deletion or synthetic ledger transfers.

---

### K. Minimum-Change and Anti-Slop Discipline

1. **The Principle of Minimal Delta**: When resolving defects, change only the lines strictly necessary to satisfy the invariant. Do not bundle refactorings, rename variables, or rearrange architecture during a targeted fix.
2. **Respect Accepted Technical Debt**: Accepted YELLOW debt (e.g. large files like `Repositories.kt`, double-precision currency legacy fields) is stable and functional. Touching it without an explicit user requirement introduces gratuitous regression risk.
3. **Unslop Communication**: All technical documentation, plans, and review records must use concise, objective language. Avoid hyperbolic prose, self-congratulatory claims, filler phrases, and speculative narrative flourishes. Headings must use sentence case and text must focus strictly on technical mechanics.

---

### L. Preferred Forensic Workflow

For any future investigation, follow this verified five-step progression:

```text
1. RAW SOURCE RECONCILIATION (Git diff, exact lines, schema DDL)
         ↓
2. PATH TRACING (Actual runtime execution order from UI to DB)
         ↓
3. ADVERSARIAL PROBING (Edge cases, conflict points, crash boundaries)
         ↓
4. ADJUDICATION AGAINST CONTRACT (Invariant check, failure taxonomy)
         ↓
5. MINIMAL REMEDIATION OR ACCEPT AS-IS (Code change only if defect proven)
```

---

### M. N2 Final Status

* **Task 1 (Schema & Constraint Safety)**: VERIFIED (Migration 17$\to$18 relaxes `UNIQUE(sourceExternalId)` safely).
* **Task 2 (Remote Sync Collision Handling)**: VERIFIED (`RemoteSyncCoordinator` persists colliding accounts without dropping).
* **Task 3 (uTower Matching & Ambiguity Resolution)**: LOCKED (Ambiguity correctly quarantined via B2 policy).
* **Task 4 (Remote Sync Compatibility & ISP Mapping)**: VERIFIED (`ispSubscriberId` / `ispUserIndex` mapping and remote sync compatibility confirmed).
* **Task 5 (Account Resolution & Identity Bridge)**: CLOSED; `N2AccountResolutionTest` provided focused evidence for the implemented resolution and identity-binding behaviors.
* **Overall N2 Verification State**: All required N2 verification gates have completed according to their respective recorded test runs. *(Aggregate test count across all suites is omitted pending formal full-suite gate execution).*

---

### N. Backup Incident

During the forensic audit, an inquiry was made regarding `earthlink_backup_1789281798680.zip`.
* Verification confirmed that `earthlink_backup_1789281798680.zip` was not present in the workspace root directory.
* The backup incident is separate from the N2 source implementation. It was not used as evidence for N2 correctness and was not repaired during the N2 review.
* Per strict operational directives, no attempts were made to touch, restore, recreate, delete, or alter any backup artifacts during this pass.

---

### O. Future-Agent Rules

1. **Verify Before Asserting**: Never cite a function signature, DAO method, or architectural behavior without reading the literal lines from the current Git working tree.
2. **Never Reopen Closed Areas**: Do not modify or propose changes to G1–G8, Task 3, or closed N2 tasks unless presented with a reproducible test failure or authoritative contradiction.
3. **No Speculative Infrastructure**: Do not build staging tables, generic reconciliation daemons, or identity registries. Build only what the product contract explicitly mandates.
4. **Preserve Raw Git State**: Never execute destructive Git commands (`git checkout .`, `git reset --hard`, `git clean -fd`) or mutate uncommitted working tree state without explicit user instruction.
5. **Adhere to the Lean Planning Model**: Trivial fixes and documentation reviews require direct execution and verification. Only major architectural milestones warrant formal multi-stage planning documents.
6. **Lessons Learned Are Non-Authoritative**: The Lessons Learned document is a record of verified findings and retrospective rules. It is NOT an authority source and must never override current source or active project authority.