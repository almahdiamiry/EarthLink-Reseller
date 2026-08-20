# Earthlink Reseller V1 — G1 Financial Correctness & API Semantics Implementation Plan v2

> **Status:** FINAL IMPLEMENTATION PLAN — revised after second review against the FINAL APPROVED BASELINE, authority bundle, current repository baseline, API v0.7.0, POC, existing tests, owner decisions, and identified closure gaps.
>
> **Execution baseline:** `main` / `baee55fb1a53d83bfe1efc8ab849b780c6651a1b`
>
> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

## Goal

Close the FINAL APPROVED BASELINE on `main` / `baee55f` without redesigning the product, while making Earthlink financial mutations, lost-ACK recovery, API semantics, local financial materialization, and required sync/governance verification demonstrably safe.

This plan closes the approved **G1/API implementation scope** plus explicitly required cross-gate verification dependencies. It does **not** claim to implement independently open G3 Restore Merge or other independently open G3/G4/G5 implementation work unless explicitly listed below.

## Architecture

Keep the existing offline-first Room ledger, pending-operation resolver, Earthlink API adapter, outbox, and Firestore synchronization architecture.

Strengthen the existing mutation boundary rather than introducing:
- a distributed transaction/reconciliation engine;
- a generic synchronization state machine;
- a staging/publication database;
- an identity registry;
- a runtime governance registry.

External operation outcome remains an Earthlink/API concern. The local application durably records exact intent/evidence, keeps ambiguous outcomes unresolved, and only materializes the reseller ledger after deterministic or controlled manually verified success.

## Authority

Primary authority:
- `docs/authority/Target Product Contract v0.6.md`
- `docs/authority/G1-G8 Consolidated Architecture Summary.md`
- `docs/authority/Final Independent Adjudication Memo.md`
- FINAL APPROVED BASELINE — `docs/authority/EARTHLINK_RESELLER_V1_FINAL_APPROVED_FINDINGS_MATRIX.md`
- API v0.7.0 documentation and POC referenced by `AGENTS.md`

Historical remediation plans are evidence only and are not execution authority when their baseline is stale.

---

# Global Constraints

- Execution baseline is `baee55f` (`baee55fb1a53d83bfe1efc8ab849b780c6651a1b`); do not implement against `e404f75`.
- The stale `EARTHLINK_RESELLER_V1_REMEDIATION_PLAN_v6_FINAL_OWNER_DECISIONS.md` baseline must be explicitly superseded; it is not the implementation authority.
- `UNKNOWN / INCONCLUSIVE` MUST NOT become `FAILED` or `COMPLETED` without sufficient authoritative/external verification evidence.
- Current subscriber state alone MUST NOT prove a historical Earthlink mutation.
- Exact intended reseller charge MUST be persisted before external dispatch; recovery MUST never infer it from current package pricing.
- Every G1 ledger-producing operation MUST use the same financial safety path:
  - Activation / create using deposit
  - Renewal / Extension
  - Refill
- `newuserdeposit` and `newtestuser` must use endpoint-specific response types and preserve returned `userIndex` evidence where applicable.
- A stable local operation/transaction identity MUST be generated and durably persisted before external dispatch and reused through recovery/materialization.
- Authoritative financial verification must bypass presentation/cache data.
- `COMPLETED` is allowed only when external success is provable, exact operation identity and amount are known, account position/ledger/outbox are materialized, and the financial effects plus `COMPLETED` are atomically committed.
- An unresolved external mutation MUST NOT be blindly re-dispatched.
- A duplicate observation of an already-completed operation MUST return the existing idempotent successful outcome and MUST NOT create a second ledger/outbox effect.
- Do not invent statement-correlation rules. Only use fields/correlation relationships proven by the API documentation/POC.
- Manual verification is an explicit controlled resolution path. It is not a `"Mark Completed"` action and is not an autonomous reconciliation engine.
- Manual verification records externally observed evidence only; the normal verified-success resolver validates that evidence and enters the same atomic materialization path.
- Firebase is not a prerequisite for executing an ISP operation after the local pending intent is durably persisted.
- Explicit API business failure MUST NOT create a ledger row or `COMPLETED` state.
- Existing resolved findings remain closed unless implementation exposes a new contradiction.
- Do not create a 15→16 migration merely to repeat verification of the existing `MIGRATION_14_15`.
- Do not expand the scope into generic distributed-database, ERP, backup/restore, credential, money-representation, or governance redesign work.
- Preserve the permanent production invariants INV-01 through INV-17 and the frozen certification suites.
- Use TDD for each implementation unit: write the failing test, run it, implement the smallest change, run the focused test, then run the relevant regression set.
- Keep each commit reviewable and independently testable.
- Do not weaken, delete, bypass, or rewrite frozen certification tests merely to obtain a pass.

---

# G1 Financial Operation Boundary

The following operations are in scope for the same financial correctness protocol:

| Operation | External operation | Financial effect |
|---|---|---|
| Activation | Create/activate subscriber using the applicable Earthlink endpoint | Reseller charge + subscriber account position |
| Renewal / Extension | Extend/renew subscriber | Reseller charge + subscriber account position |
| Refill | Refill subscriber/package | Reseller charge + subscriber account position |

For **every** operation:

```text
exact intended reseller charge
        ↓
durable pending operation
        ↓
stable local operation identity
        ↓
external dispatch
        ↓
operation-specific result/evidence
        ↓
fresh authoritative verification if outcome is ambiguous
        ↓
atomic local materialization
        ├── account position
        ├── ledger
        ├── outbox
        └── pending = COMPLETED
```

The response-contract correction for `newuserdeposit` / `newtestuser` is endpoint-specific. It must not be incorrectly treated as the only G1 financial operation.

---

# Scope and Execution Order

1. Freeze the implementation baseline and establish the complete G1 mutation/API call graph.
2. Correct Earthlink mutation response contracts and preserve operation evidence.
3. Make pending operations financially complete before dispatch, including stable transaction identity.
4. Make transport/timeout/parse ambiguity UNKNOWN-safe and prohibit blind redispatch.
5. Remove state-only lost-ACK inference and implement controlled manual verification.
6. Enforce fresh authoritative verification and atomic local financial materialization.
7. Correct generic API null/error/value semantics and dashboard propagation.
8. Separate statement evidence from synthetic/local rows.
9. Close sync/concurrency/restart/replace verification gaps without redesign.
10. Verify `MIGRATION_14_15` and migration compatibility.
11. Correct governance paths and explicitly supersede the stale remediation baseline.
12. Run the final matrix coverage and certification gate.

No task should assume an unverified behavior from a previous task; each task has an explicit test gate.

---

# Task 1: Establish execution baseline and complete G1 mutation boundary

**Inspect:**
- `AGENTS.md`
- `app/src/main/java/com/example/core/model/Models.kt`
- `app/src/main/java/com/example/core/network/EarthlinkNetwork.kt`
- `app/src/main/java/com/example/core/network/ApiResult.kt`
- `app/src/main/java/com/example/core/sync/RemoteSyncCoordinator.kt`
- `app/src/main/java/com/example/core/sync/SyncWorker.kt`
- `app/src/main/java/com/example/core/sync/DataOperationCoordinator.kt`
- `app/src/main/java/com/example/core/ledger/BalanceCalculator.kt`
- `app/src/main/java/com/example/core/database/AppDatabase.kt`
- `app/src/main/java/com/example/data/repository/Repositories.kt`
- existing G1 tests under `app/src/test/java/com/example/`
- API v0.7.0 documentation and POC referenced by `AGENTS.md`

**Produces:** a written symbol/call-site map used by later tasks. Do not change behavior in this task.

- [ ] Confirm checkout is exactly `baee55fb1a53d83bfe1efc8ab849b780c6651a1b`.
- [ ] Confirm current test package and existing G1 tests before adding tests.
- [ ] Trace UI/repository initiation for **Activation**, **Renewal/Extension**, and **Refill** through external API and pending-operation persistence.
- [ ] Trace `newuserdeposit` and `newtestuser` specifically.
- [ ] Trace every production caller of `resolvePendingOperationVerifiedSuccess`, `resolvePendingOperationInconclusive`, and every terminal failure transition.
- [ ] Trace every balance/statement verification call used by recovery, distinguishing fresh network reads from cached presentation reads.
- [ ] Trace the exact Room transaction that materializes account position, ledger, outbox, and pending-operation status.
- [ ] Enumerate every production writer of the stable operation/ledger transaction identity.
- [ ] Enumerate every recovery path that could invoke an external financial mutation.
- [ ] Record the symbols/call graph in implementation notes; do not invent abstractions before this map exists.
- [ ] Run the existing focused G1 tests without modifying them to force a pass.

```bash
./gradlew :app:testDebugUnitTest   --tests com.example.Phase1G1PendingOperationDurabilityTest   --tests com.example.Phase1G1ProcessKillRecoveryTest   --tests com.example.Phase1UnknownOutcomeResolutionTest   --tests com.example.Phase1AtomicityAndLostAckTest   --tests com.example.Phase1DuplicateInitiationProtectionTest
```

Expected: baseline behavior is captured before implementation changes.

---

# Task 2: Correct `newuserdeposit` / `newtestuser` response contracts

**Modify/Test:**
- `app/src/main/java/com/example/core/network/EarthlinkNetwork.kt`
- `app/src/main/java/com/example/core/network/ApiResult.kt` only if required
- `app/src/main/java/com/example/core/model/Models.kt` only if evidence persistence belongs there
- `app/src/test/java/com/example/EarthlinkMutationResponseContractTest.kt`

- [ ] Add failing tests proving successful `newuserdeposit` numeric `value` decodes as numeric `userIndex`, not `true`.
- [ ] Add equivalent test for `newtestuser`.
- [ ] Add failing tests proving HTTP 200 + `isSuccessful=false` is business failure.
- [ ] Add failing tests for successful responses with missing/malformed required mutation payloads.
- [ ] Run the new tests and confirm failure against current Boolean models.
- [ ] Introduce endpoint-specific response DTO/result types while keeping common envelope transport fields generic.
- [ ] Change `createUserUsingDeposit` and `createTestUser` to return typed endpoint results.
- [ ] Make numeric `userIndex` explicit and reject malformed/missing successful payloads.
- [ ] Preserve returned `userIndex` as operation evidence where applicable.
- [ ] Verify explicit API business failure cannot enter ledger materialization.
- [ ] Run focused response-contract tests plus the existing G1 suite.
- [ ] Commit only the API contract change and tests.

Required contract:

```text
HTTP transport success + API success + valid payload
    -> typed endpoint success

HTTP 200 + API failure
    -> endpoint business failure

HTTP/parse/timeout ambiguity
    -> transport/inconclusive result
    -> caller applies UNKNOWN/PENDING semantics
```

---

# Task 3: Make every G1 pending operation financially complete before dispatch

**Modify/Test:**
- `app/src/main/java/com/example/core/model/Models.kt`
- canonical repository/service implementation creating `PendingExternalOperation`
- `app/src/main/java/com/example/core/database/AppDatabase.kt` only if required
- `app/src/test/java/com/example/PendingOperationFinancialIntentTest.kt`
- `app/src/test/java/com/example/Phase1G1PendingOperationDurabilityTest.kt`

- [ ] Add failing test for paid Activation proving exact subscriber charge is persisted before external dispatch.
- [ ] Add equivalent tests for Renewal/Extension and Refill.
- [ ] Add failing test proving recovery never substitutes current package pricing when persisted amount exists.
- [ ] Add failing test rejecting a paid ledger-producing pending operation when exact amount is absent; never silently substitute zero.
- [ ] Add failing test proving operation kind and account identity are persisted before dispatch.
- [ ] Generate the stable local operation/ledger transaction identity **before external dispatch**.
- [ ] Persist that identity in the durable pending operation.
- [ ] Reuse the same identity through success, ambiguity, restart, verification, materialization, and outbox upload.
- [ ] Add failing test proving recovery never generates a new financial identity for the same operation.
- [ ] Ensure returned `userIndex` is added to the same operation evidence after successful API response where applicable.
- [ ] Ensure Firebase availability is not required to create the local pending operation or dispatch the external ISP mutation after local durability is established.
- [ ] If schema change is required, verify schema version/history before modifying migrations.
- [ ] Do not create a migration for speculative cleanup.
- [ ] Run focused pending-operation, Firebase-independent execution, and migration regression tests.
- [ ] Commit as one independently reviewable financial-intent change.

Invariant:

```text
external dispatch begins
    only after
durable pending operation contains:
    account identity
    operation kind
    exact intended reseller charge
    stable local transaction identity
    known available operation evidence
```

---

# Task 4: Make transport/timeout/parse ambiguity UNKNOWN-safe and prohibit blind redispatch

**Modify/Test:**
- `app/src/main/java/com/example/core/network/ApiResult.kt`
- canonical pending-operation resolver/owner
- `app/src/main/java/com/example/core/model/Models.kt` only if needed
- `app/src/main/java/com/example/core/sync/SyncWorker.kt` only if recovery violates the rule
- `app/src/test/java/com/example/UnknownOutcomeStateMachineTest.kt`
- `app/src/test/java/com/example/Phase1DuplicateInitiationProtectionTest.kt`

- [ ] Add failing tests for not-sent, non-execution-proven, success-proven, and ambiguous-after-dispatch.
- [ ] Assert ambiguous-after-dispatch becomes `PENDING`/`INCONCLUSIVE`, never `FAILED`.
- [ ] Assert ambiguous-after-dispatch cannot become `COMPLETED` from a generic current snapshot.
- [ ] Assert unresolved operations cannot automatically invoke the external mutation again.
- [ ] Define the smallest explicit transition policy for the existing state model.
- [ ] If `RESOLVING` is retained, persist it only around an actual resolution attempt and make restart deterministic; otherwise do not maintain a dead state.
- [ ] Route timeout/IOException/parse ambiguity through unresolved handling.
- [ ] Ensure restart/sweep treats unresolved operations as verification obligations, not redispatch permission.
- [ ] Add failing duplicate-initiation test where the original operation is already unresolved.
- [ ] Add failing test where a second caller discovers the same operation is already `COMPLETED`.
- [ ] For an already-completed operation, return the existing idempotent successful outcome.
- [ ] Do not create a second ledger, account-position effect, or outbox obligation.
- [ ] Run focused state-machine, duplicate-dispatch, and idempotent-result tests.
- [ ] Commit the state-machine change.

Hard rule:

```text
INCONCLUSIVE != FAILED != COMPLETED
INCONCLUSIVE != automatic retry of the external mutation
```

---

# Task 5: Remove current-state-only success inference and implement controlled manual verification

**Modify/Test:**
- resolver implementation containing `resolvePendingOperationVerifiedSuccess`
- `app/src/main/java/com/example/core/sync/RemoteEntityValidator.kt` only if part of verification
- `app/src/main/java/com/example/core/network/EarthlinkNetwork.kt` for documented fresh lookups if needed
- `app/src/main/java/com/example/core/model/Models.kt` for durable verification evidence if needed
- `app/src/main/java/com/example/data/repository/Repositories.kt` if it owns orchestration
- existing pending-operation/status UI only if required
- `app/src/test/java/com/example/Phase1UnknownOutcomeResolutionTest.kt`
- `app/src/test/java/com/example/ManualVerificationResolutionTest.kt`

- [ ] Add failing test proving “subscriber currently active” alone does not resolve success.
- [ ] Add failing test proving plausible current balance/expiration alone does not resolve success.
- [ ] Add failing test proving saved `userIndex` is used for operation-specific lookup only where API contract documents it as evidence.
- [ ] Add failing test proving statement correlation is used only when deterministic fields are proven by API/POC.
- [ ] Remove the state-only success branch from production resolver code.
- [ ] Implement one controlled manual-verification action for unresolved operations.
- [ ] Display exact persisted amount and known operation evidence.
- [ ] Never recalculate amount from current package pricing.
- [ ] Require externally observed evidence before accepting manual verification as success.
- [ ] Persist verification evidence durably.
- [ ] Manual verification MUST NOT directly set `PendingOperation.status = COMPLETED`.
- [ ] There MUST be no `"Mark Completed"` action.
- [ ] Manual action records evidence; the normal verified-success resolver validates it and enters the same atomic materialization path as deterministic server verification.
- [ ] Manual verification MUST NOT invoke the original Earthlink mutation.
- [ ] Add negative test where sufficient evidence cannot be established; operation remains unresolved.
- [ ] Run unknown-outcome, manual-verification, and process-kill recovery tests.
- [ ] Commit resolver/manual-verification change.

Forbidden:

```text
active == success
currentBalance == expectedBalance == success
currentExpiration == expectedExpiration == success
responseLost + plausibleSnapshot == success
operator clicks "Mark Completed" == success
```

Allowed:

```text
documented operation-specific server evidence
+
exact persisted intent
+
controlled externally observed verification evidence
+
normal verified-success resolver
```

---

# Task 6: Enforce fresh authoritative verification and atomic financial materialization

**Modify/Test:**
- `app/src/main/java/com/example/core/network/EarthlinkNetwork.kt` for explicit fresh verification if needed
- resolver/repository implementation containing `resolvePendingOperationVerifiedSuccess`
- `app/src/main/java/com/example/core/ledger/BalanceCalculator.kt` only if authority is crossed incorrectly
- `app/src/main/java/com/example/core/database/AppDatabase.kt` only if transaction DAO access requires it
- `app/src/main/java/com/example/core/sync/OutboxManager.kt` if outbox creation is outside the required transaction
- `app/src/test/java/com/example/FinancialVerificationFreshnessTest.kt`
- `app/src/test/java/com/example/Phase1AtomicityAndLostAckTest.kt`
- `app/src/test/java/com/example/CompletedStateMaterializationInvariantTest.kt`

- [ ] Add failing test proving authoritative verification bypasses the 15-second presentation cache.
- [ ] Add failing test proving fresh server read is requested even when cached balance exists.
- [ ] Follow documented API propagation/retry behavior; never substitute presentation cache as proof.
- [ ] Add failing transaction tests for ledger, account-position, and outbox failures.
- [ ] Add failing test for missing local financial target.
- [ ] Add failing idempotency test proving repeated resolution of the same stable identity cannot create a second ledger transaction.
- [ ] Implement one Room transaction around account position, ledger, outbox, and pending status.
- [ ] Make `COMPLETED` the final state write in that transaction.
- [ ] Ensure all financial writes use the stable identity established before dispatch.
- [ ] Ensure exceptions roll back the transaction and leave the operation recoverable.
- [ ] Re-run atomicity, lost-ACK, financial-history, and duplicate-materialization tests.
- [ ] Commit the transaction-boundary change.

Required invariant:

```text
COMPLETED
    =>
    external success provable
    AND exact operation identity known
    AND exact intended amount known
    AND account position materialized
    AND ledger exists
    AND outbox exists
    AND all are one committed Room transaction
```

---

# Task 7: Correct generic API null/error/value semantics

**Modify/Test:**
- `app/src/main/java/com/example/core/network/ApiResult.kt`
- `app/src/main/java/com/example/core/network/EarthlinkNetwork.kt`
- affected callers of `getAccountCost()`, `getTestUsersCount()`, `getActiveTestUsersCount()`, password reveal
- `app/src/main/java/com/example/ui/screens/DashboardScreen.kt`
- `app/src/main/java/com/example/ui/screens/DashboardStatusScreen.kt`
- `app/src/main/java/com/example/ui/screens/PasswordToolsScreen.kt`
- `app/src/test/java/com/example/ApiErrorSemanticsTest.kt`
- `app/src/test/java/com/example/DashboardErrorPropagationTest.kt`

- [ ] Add failing tests for `isSuccessful=true` with null payload.
- [ ] Add failing tests for `isSuccessful=false` with HTTP 200.
- [ ] Prove `getAccountCost()` failure is not `0.0`.
- [ ] Prove count failures are not `0`.
- [ ] Prove password-reveal failure is not indistinguishable from a legitimate empty password.
- [ ] Replace fallback values with explicit result states compatible with existing architecture.
- [ ] Update callers to branch on success/unavailable/failure explicitly.
- [ ] Ensure dashboard fallback scans occur only when primary value is actually unavailable, not when true value is zero.
- [ ] Ensure dashboard counters recompute from Room after failed and successful sync/retry paths.
- [ ] Run focused API/UI propagation tests.
- [ ] Commit API semantics/UI propagation change.

---

# Task 8: Separate statement evidence from synthetic/local rows

**Modify/Test:**
- `app/src/main/java/com/example/ui/screens/StatementScreen.kt`
- statement model in `app/src/main/java/com/example/core/model/Models.kt` if provenance is absent
- statement assembly path in `app/src/main/java/com/example/data/repository/Repositories.kt` if it mixes remote and synthetic rows
- `app/src/test/java/com/example/StatementEvidenceProvenanceTest.kt`

- [ ] Add failing test proving server statement and synthetic/local rows have distinct provenance.
- [ ] Add failing test proving deterministic correlation ignores synthetic rows.
- [ ] Add explicit provenance using the smallest model change consistent with project conventions.
- [ ] Update statement UI without changing financial meaning/history.
- [ ] Run statement and G1 correlation tests.
- [ ] Commit provenance change.

---

# Task 9: Close sync/remote-version/concurrency/restart/replace verification gaps without redesign

**Modify only if a verification test exposes a real defect:**
- `app/src/main/java/com/example/core/sync/RemoteSyncCoordinator.kt`
- `app/src/main/java/com/example/core/sync/SyncRepositoryImpl.kt`
- `app/src/main/java/com/example/core/sync/SyncWorker.kt`
- `app/src/main/java/com/example/core/sync/DataOperationCoordinator.kt`
- `app/src/main/java/com/example/data/repository/Repositories.kt`

**Tests:**
- `Workstream10_5MonotonicRemoteVersionTest.kt`
- `Workstream13G1RealRestartCertificationTest.kt`
- `Workstream15CoordinatorTransportConcurrencyTest.kt`
- `Workstream9CDatasetReplacementTest.kt`
- `RemoteVersionWriterInventoryTest.kt`
- `ConcurrentLocalRemoteMutationTest.kt`

- [ ] Enumerate every writer of `remote_version:*` and record the DAO boundary.
- [ ] Add source/static test detecting raw writers outside the monotonic DAO boundary.
- [ ] Test lower, equal, and higher remote versions.
- [ ] Confirm equality is idempotent/no-op.
- [ ] Add genuine concurrent same-entity local/remote mutation test.
- [ ] Run local-first scheduling and assert canonical final invariant.
- [ ] Run remote-first scheduling and assert the same invariant.
- [ ] Exercise actual `SyncWorker.doWork()` for restart/recovery.
- [ ] Exercise replace/import against stale remote entities, generation state, tombstones, and cursor state.
- [ ] Verify concurrent processing cannot duplicate financial ledger effects.
- [ ] Do not alter coordinator locking architecture unless a test exposes a new contradiction.
- [ ] Run Phase 2/3 and Workstream 10.5/13/15/9C suites.
- [ ] Commit only if a real code defect is exposed; otherwise record verification closure.

Goal: **proof, not redesign.**

---

# Task 10: Verify `MIGRATION_14_15` and migration compatibility

- [ ] Confirm current schema version and existing `MIGRATION_14_15`.
- [ ] Load `earthlink_backup.zip` through the existing migration harness.
- [ ] Assert `correctsEntryId` is present and preserved.
- [ ] Assert existing ledger/account/history data remains intact.
- [ ] Assert migration is non-destructive.
- [ ] Assert compatibility with pending-operation/financial-materialization changes.
- [ ] Do not create `15→16` solely to repeat verification.
- [ ] Run migration regression suite.
- [ ] Commit only if an actual migration defect is fixed.

---

# Task 11: Governance and stale-plan cleanup

**Modify/Verify:**
- `AGENTS.md`
- `EARTHLINK_RESELLER_V1_REMEDIATION_PLAN_v6_FINAL_OWNER_DECISIONS.md` only to mark explicitly superseded if policy requires
- final plan under `docs/superpowers/plans/`
- `contract/phase_requirements.yaml`
- `contract/invariant_contract.yaml`
- `scripts/verify_invariant_contract.py`
- `scripts/run_verified_command.py`
- `scripts/scan_forbidden_patterns.py`

- [ ] Verify every API/POC path referenced by `AGENTS.md` resolves to an existing repository file.
- [ ] Replace stale `DOC/...` references with actual canonical `docs/...` paths.
- [ ] Add governance check failing when mandatory API/POC references point to nonexistent files.
- [ ] Add/retain explicit supersession statement for old remediation baseline `e404f75`.
- [ ] Do not rewrite historical content as though authored against `baee55f`.
- [ ] Ensure this plan names `baee55f` as execution baseline.
- [ ] Verify `AGENTS.md` remains the required agent entry point without conflicting navigation rules.
- [ ] Run governance/meta-gate verification.
- [ ] Commit governance separately from financial behavior changes.

---

# Task 12: Final matrix coverage and certification gate

- [ ] Build requirement-to-test map for every G1-A through G1-M and API-01 through API-05.
- [ ] Include explicit coverage for Activation, Renewal/Extension, and Refill.
- [ ] Confirm G1-D and G1-F converge on one production resolver policy.
- [ ] Confirm exact amount persisted before dispatch and never recomputed during recovery.
- [ ] Confirm stable identity persisted before dispatch and reused through recovery/materialization.
- [ ] Confirm `userIndex` preserved where contractually applicable.
- [ ] Confirm no current-state-only proof reaches `COMPLETED`.
- [ ] Confirm no unsupported statement correlation exists.
- [ ] Confirm manual verification records external evidence and cannot dispatch original mutation.
- [ ] Confirm manual verification cannot directly mark `COMPLETED`.
- [ ] Confirm explicit API business failure cannot produce ledger/`COMPLETED`.
- [ ] Confirm Firebase is not prerequisite for local G1 execution after local durability.
- [ ] Confirm fresh verification bypasses presentation/cache.
- [ ] Confirm atomic transaction contains account position + ledger + outbox + `COMPLETED`.
- [ ] Confirm repeated resolution of same identity is idempotent.
- [ ] Confirm missing financial target cannot produce `COMPLETED`.
- [ ] Confirm unresolved operations cannot blindly redispatch after restart.
- [ ] Confirm API failures never masquerade as zero/empty valid values.
- [ ] Confirm sync PARTIAL-VERIFICATION items are proven or explicitly non-blocking without speculative redesign.
- [ ] Confirm `MIGRATION_14_15` verified without unnecessary `15→16`.
- [ ] Confirm stale `e404f75` plan superseded.
- [ ] Confirm `AGENTS.md` canonical paths resolve.
- [ ] Confirm frozen certification tests remain intact.
- [ ] Run repository-provided bounded verification commands through existing runners.

Final gate:

```text
ALL BLOCKING ROWS PASS
```

- [ ] Run full app-module Gradle test suite.
- [ ] Run certification/meta-gate suite.
- [ ] Run forbidden-pattern/governance scans.
- [ ] Review final diff for scope expansion, weakened tests, speculative architecture, or deferred work.
- [ ] Commit final implementation in small logical commits.

---

# Matrix-to-Task Coverage

| Matrix requirement | Primary closure task |
|---|---|
| Owner Policy 0.1 operation-specific evidence | Tasks 2, 3, 5 |
| Owner Policy 0.2 no state-only proof | Task 5 |
| Owner Policy 0.3 unresolved behavior | Task 4 |
| Owner Policy 0.4 manual verification | Task 5 |
| G1-A | Task 4 |
| G1-B | Task 2 |
| G1-C | Task 3 |
| G1-D | Task 5 |
| G1-E | Task 6 |
| G1-F | Task 5 |
| G1-G | Tasks 2–3 |
| G1-H | Task 6 |
| G1-I | Tasks 5, 8 |
| G1-J | Task 4 |
| G1-K | Task 6 |
| G1-L | Task 2 |
| G1-M | Task 6 |
| API-01 | Task 7 |
| API-02 | Task 7 |
| API-03 | Task 7 |
| API-04 | Task 7 |
| API-05 | Task 7 |
| API-06 / API-07 | Task 7, P2 cleanup only where schema is stable |
| UI-01 | Task 7 |
| DATA-01 | Task 7 |
| DATA-02 | Task 7 |
| UI-02 | Task 8 |
| RC-09c | Task 9 |
| SYNC-04 | Task 9 |
| SYNC-06 | Task 9 |
| WS13 | Task 9 |
| WS15 | Task 9 |
| IMP-04 | Task 9 |
| GOV-01 | Task 11 |
| GOV-02 | Task 11 |
| PERF-01 | Deferred/P2; do not block G1 |
| API-08 | Deferred verification; do not classify without evidence |
| MIG-01 | Task 10 |
| DEF-01–06 | Explicitly excluded from immediate implementation |
| Resolved historical findings | Protected by Task 12 regression gate |
| RCA-01–12 | Covered by corresponding findings |
| P0-01–P0-16 | Tasks 2–7 |
| P1-01–P1-09 | Tasks 7–11 |
| P2-01–P2-06 | Explicitly deferred |
| G1 Definition of Done | Task 12 |

---

# Required End-State Tests

## Scenario A — successful Activation

```text
exact subscriber charge
        ↓
durable PENDING
        ↓
stable operation identity
        ↓
external activation
        ↓
operation-specific evidence
        ↓
fresh authoritative verification
        ↓
one Room transaction:
    account position
    ledger
    outbox
    pending = COMPLETED
        ↓
COMMIT
```

## Scenario B — successful Renewal/Extension

Same financial boundary as Activation, using exact renewal/extension charge and operation-specific evidence.

## Scenario C — successful Refill

Same financial boundary as Activation, using exact refill charge and operation-specific evidence.

## Scenario D — lost ACK with insufficient evidence

```text
exact charge persisted
        ↓
external request dispatched
        ↓
response lost
        ↓
current subscriber snapshot looks active
        ↓
UNKNOWN / INCONCLUSIVE
        ↓
NO guessed ledger
NO COMPLETED
NO FAILED
NO automatic redispatch
```

## Scenario E — lost ACK with deterministic evidence

```text
response lost
        ↓
operation-specific evidence
        ↓
fresh documented lookup/correlation
        ↓
success proven
        ↓
atomic local materialization
        ↓
COMPLETED
```

## Scenario F — controlled manual verification

```text
UNKNOWN
        ↓
controlled verification
        ↓
exact persisted amount/evidence displayed
        ↓
operator records external evidence
        ↓
evidence durably persisted
        ↓
normal verified-success resolver
        ↓
atomic materialization
        ↓
COMPLETED
```

The manual path MUST NOT:
- dispatch the original mutation;
- calculate a new amount;
- directly write `COMPLETED`;
- treat current subscriber state alone as evidence.

## Scenario G — materialization failure

```text
external success proven
        ↓
materialization begins
        ↓
ledger/account/outbox/COMPLETED transaction fails
        ↓
ROLLBACK
        ↓
COMPLETED absent
        ↓
operation recoverable
```

## Scenario H — restart after ambiguity

```text
UNKNOWN on disk
        ↓
restart
        ↓
worker finds operation
        ↓
remains unresolved
        ↓
no blind external mutation
```

## Scenario I — duplicate completed result

```text
same stable operation identity
        ↓
already COMPLETED
        ↓
return existing successful outcome
        ↓
no second ledger
no second account-position effect
no second outbox effect
```

## Scenario J — API error semantics

```text
HTTP 200 + isSuccessful=false
        -> business failure
        -> no ledger
        -> no COMPLETED

successful response + missing payload
        -> endpoint-specific invalid/unavailable result

account-cost/count/password failure
        -> explicit error/unavailable

real zero / real empty value
        -> legitimate value
```

## Scenario K — Firebase unavailable

```text
Firebase unavailable/offline
        ↓
local pending durably committed
        ↓
external ISP operation may execute
        ↓
local outcome/recovery survives
        ↓
cloud synchronization remains separate
```

## Scenario L — concurrent local/remote mutation

```text
same entity
+
local mutation
+
remote event
        ↓
genuine concurrent interleaving
        ↓
canonical deterministic final state
        ↓
no version downgrade
no duplicate ledger
no outbox loop
```

---

# Self-Review Passes

## Pass 1 — Matrix coverage

- G1-A through G1-M have named implementation/verification tasks.
- Activation, Renewal/Extension, and Refill share the same G1 financial safety boundary.
- API-01 through API-05 have explicit tests.
- P1 sync/concurrency/governance findings are not omitted.
- MIG-01 is covered.
- Deferred/resolved findings are protected from scope creep.
- Stable identity and duplicate-result semantics are explicitly covered.

## Pass 2 — Contract alignment

The plan preserves:

```text
reseller ledger
+
Earthlink operational API
+
local Room authority
+
Firebase cloud copy
+
uTower snapshot import
```

It preserves:

```text
ISP balance != subscriber debt/account position
```

It preserves Activation, Renewal/Extension, and Refill as ledger-producing ISP operations.

It does not delete historical ledger activity as correction.

It preserves INV-01 through INV-17.

## Pass 3 — No duplicate G1 work

G1-D and G1-F converge on one resolver policy:

```text
current-state-only inference is forbidden
```

No second recovery engine is introduced.

## Pass 4 — No speculative correlation

Only API/POC-proven evidence may establish deterministic correlation.

If correlation cannot be proven:

```text
UNKNOWN / INCONCLUSIVE
+
controlled manual verification
```

## Pass 5 — Financial safety

Before `COMPLETED`:

```text
external success
+
operation identity
+
exact intended amount
+
local financial materialization
```

And:

```text
INCONCLUSIVE != automatic external retry
```

## Pass 6 — Transaction safety

`COMPLETED` is the final terminal state in the same Room transaction as:

```text
account position
ledger
outbox
```

Failure rolls back financial materialization.

## Pass 7 — Freshness safety

Presentation/cache data remains separate from authoritative verification.

## Pass 8 — Firebase independence

Local G1 execution does not require Firestore availability after local intent is durably persisted.

## Pass 9 — Governance safety

`AGENTS.md` remains the required agent entry point.

Canonical API/POC paths are machine-checked.

The stale `e404f75` plan is historical/superseded rather than silently rewritten.

## Pass 10 — Agent executability

Every task has canonical inspection/modify targets, named components, explicit tests, expected behavior, bounded scope, and commit boundaries.

## Pass 11 — Regression protection

Frozen certification suites remain immutable contracts. No certification test may be weakened, deleted, bypassed, or rewritten merely to pass.

## Pass 12 — Scope control

Explicitly outside this plan unless a new contradiction is proven:

```text
generic distributed-database redesign
ERP/accounting redesign
money representation migration
backup/restore redesign
credential architecture redesign
generic ApiEnvelope<Any> cleanup
Firestore pagination micro-optimization
destructive DAO cleanup
full G3 Restore Merge implementation
new reconciliation engine
new synchronization state machine
new staging database
new identity registry
runtime governance registry
```

---

# Final Implementation Completion Rule

The implementation plan is complete only when:

- [ ] G1 Definition of Done is fully satisfied.
- [ ] Activation, Renewal/Extension, and Refill all use the same financial correctness boundary.
- [ ] Every P0 item is implemented and tested.
- [ ] API-01 through API-05 are closed.
- [ ] Manual verification is operational and evidence-controlled.
- [ ] Manual verification cannot directly mark `COMPLETED`.
- [ ] No current-state-only lost-ACK path can reach `COMPLETED`.
- [ ] No unresolved operation can blindly redispatch an external financial mutation.
- [ ] Stable operation identity is persisted before dispatch and reused through recovery/materialization.
- [ ] Duplicate observation of an already-completed operation is idempotently successful.
- [ ] `COMPLETED` implies the full atomic financial materialization invariant.
- [ ] Existing G1 restart/process-kill behavior remains safe.
- [ ] Firebase availability is not a prerequisite for local G1 execution after local durability.
- [ ] Explicit API business failure creates no financial ledger effect.
- [ ] Sync PARTIAL-VERIFICATION items are proven or explicitly non-blocking without speculative redesign.
- [ ] `MIGRATION_14_15` is verified without unnecessary `15→16`.
- [ ] `AGENTS.md` paths are corrected and machine-checked.
- [ ] stale `e404f75` remediation baseline is explicitly superseded.
- [ ] Frozen certification tests remain intact.
- [ ] `ALL BLOCKING ROWS PASS`.
- [ ] Final git diff contains no unrelated deferred work.

## Final implementation boundary

```text
G1 LEDGER-PRODUCING OPERATION
    Activation / Renewal / Extension / Refill
                    ↓
       EXACT INTENDED CHARGE
                    +
       STABLE OPERATION IDENTITY
                    ↓
          DURABLE PENDING
                    ↓
           EXTERNAL DISPATCH
                    ↓
      ┌─────────────┴─────────────┐
      │                           │
SUCCESS PROVEN              AMBIGUOUS
      │                           │
      │                     UNKNOWN / INCONCLUSIVE
      │                           │
      │                 NO GUESSED LEDGER
      │                 NO FAILED
      │                 NO COMPLETED
      │                 NO BLIND REDISPATCH
      │                           │
      │              deterministic OR controlled
      │              externally evidenced verification
      │                           │
      └──────────────┬────────────┘
                     ↓
       FRESH AUTHORITATIVE VERIFICATION
                     ↓
          VERIFIED EXTERNAL SUCCESS
                     ↓
          ONE ATOMIC ROOM TRANSACTION
             ├── account position
             ├── ledger
             ├── outbox
             └── pending = COMPLETED
                     ↓
                  COMMIT
```

## Non-negotiable terminal rule

```text
COMPLETED
    iff
    provable external success
    +
    exact operation identity
    +
    exact intended amount
    +
    sufficient authoritative verification evidence
    +
    atomic local financial materialization
```

Anything less remains recoverable and unresolved.
