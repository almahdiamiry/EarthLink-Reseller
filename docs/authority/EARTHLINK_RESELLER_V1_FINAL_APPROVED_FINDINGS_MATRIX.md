# EARTHLINK RESELLER V1 — MASTER FINDINGS / ROOT-CAUSE / IMPLEMENTATION MATRIX
## FINAL APPROVED BASELINE

**Status:** FINAL — APPROVED FOR IMPLEMENTATION-PLAN AUTHORING  
**Repository execution baseline:** `main` / `baee55f`  
**Last code-bearing remediation baseline:** `e99e878`

---

# 0. OWNER POLICY — AMBIGUOUS LOST-ACK G1 OUTCOMES

This policy is authoritative.

## 0.1 Operation-specific evidence

If the API returns operation-specific evidence, the application MUST preserve it and use it for verification.

```text
newuserdeposit
    -> response contains userIndex
    -> preserve exact userIndex
    -> GET /user/{userIndex}
    -> use documented operation-specific evidence
```

The same principle applies to `newtestuser`.

## 0.2 Insufficient automatic evidence

A later/current subscriber snapshot is NOT automatically proof that the original request succeeded.

```text
request sent
    -> response lost
    -> subscriber currently active
```

does NOT prove the original request definitely succeeded.

## 0.3 Required unresolved behavior

```text
UNKNOWN / INCONCLUSIVE
    -> do NOT mark FAILED
    -> do NOT mark COMPLETED
    -> do NOT create a guessed financial ledger entry
    -> preserve durable pending operation
    -> surface manual verification required
```

## 0.4 Manual verification

Manual verification is the approved operational resolution path when the API cannot deterministically correlate the original request.

It MUST:
- use externally observed server evidence;
- verify the actual operational/financial result;
- NOT rely only on the subscriber currently appearing active;
- preserve the exact intended amount already recorded;
- preserve known operation identity/evidence;
- prevent duplicate external dispatch;
- only then allow financial materialization/finalization.

There is no remaining human STOP GATE; this behavior is an implementation requirement.

---

# 1. P0 — G1 / FINANCIAL CORRECTNESS

| ID | Finding | Status | Root Cause | Required Closure |
|---|---|---|---|---|
| **G1-A** | Financial API may execute successfully while transport/timeout/parse failure moves durable pending operation to terminal `FAILED`. | **OPEN-CONFIRMED** | Unknown outcome collapsed into failure. | Ambiguity MUST remain `PENDING` / `INCONCLUSIVE`; `FAILED` requires authoritative non-execution evidence. |
| **G1-B** | `newuserdeposit` returns numeric `value` (`userIndex`) while Android models `ApiEnvelope<Boolean>`. | **OPEN-CONFIRMED** | Response model does not match API contract. | Use endpoint-specific typed response and preserve `userIndex`. |
| **G1-C** | Paid activation can persist `amountIqd=0`; recovery can infer amount from local pricing/fallback. | **OPEN-CONFIRMED** | Pending state lacks exact intended financial amount. | Persist exact intended charge before dispatch; recovery MUST never guess. |
| **G1-D** | Recovery can use generic current subscriber state as proof when operation-specific evidence is unavailable. | **OPEN-CONFIRMED** | Entity state treated as proof of historical operation. | Current state alone MUST NOT finalize the operation. |
| **G1-E** | `resolvePendingOperationVerifiedSuccess()` can establish terminal success outside complete financial materialization. | **OPEN-CONFIRMED** | Terminal state is not guaranteed atomic with financial effects. | Ledger + account position + outbox + `COMPLETED` MUST commit in one Room transaction. |
| **G1-F** | Production resolver can infer success from later/current subscriber state after lost ACK. | **OPEN-CONFIRMED — ENFORCEMENT OF G1-D** | Resolver applies prohibited state-only inference. | Remove state-only proof from resolver; otherwise remain `UNKNOWN` and use manual-verification path. |
| **G1-G** | `newuserdeposit` `userIndex` is not durably preserved as operation evidence. | **OPEN-CONFIRMED** | Numeric response discarded through Boolean typing. | Persist exact server `userIndex` and use it for verification. |
| **G1-H** | `getBalance()` cache can return stale data immediately after financial write. | **OPEN-CONFIRMED FRESHNESS RISK** | Presentation/cache data crosses into authoritative verification. | **Authoritative verification MUST bypass the presentation/cache layer** and request fresh server evidence. |
| **G1-I** | Statement evidence exists, but deterministic correlation to the same original operation is not proven. | **OPEN-VERIFICATION** | No proven deterministic correlation tuple. | Define only a tuple proven by API/POC; if unavailable remain `INCONCLUSIVE` and use manual verification. Do NOT invent correlation rules. |
| **G1-J** | `RESOLVING` exists in pending-operation model/contract but is not consistently represented at runtime. | **OPEN-DESIGN GAP** | Contract/runtime state semantics diverged. | Implement durable `RESOLVING` semantics or remove it from the contract. |
| **G1-K** | Verified-success path can reach `COMPLETED` when financial target cannot be materialized. | **OPEN-CONFIRMED** | Success not fully coupled to materialization. | Missing financial target MUST NOT yield `COMPLETED`. |
| **G1-L** | `newtestuser` returns numeric `userIndex` while Android models `ApiEnvelope<Boolean>`. | **OPEN-CONFIRMED** | Endpoint-contract/model mismatch. | Correct response model and preserve operation-specific identity. |
| **G1-M** | Pending verification can depend on API lookup paths using cached subscriber data. | **OPEN-CONFIRMED FRESHNESS RISK** | Recovery path not guaranteed to bypass cache. | Verification MUST explicitly use fresh authoritative API path where freshness matters. |

### G1 closure invariant

`COMPLETED` MUST imply:

```text
external success is provable
+
exact operation identity is known
+
exact intended amount is known
+
ledger exists
+
account position is materialized
+
outbox exists
+
all financial effects + COMPLETED are committed atomically
```

```text
UNKNOWN != FAILED
UNKNOWN != COMPLETED
```

---

# 2. P0 — API CONTRACT / ERROR SEMANTICS

| ID | Finding | Status | Root Cause | Required Closure |
|---|---|---|---|---|
| **API-01** | `safeApiCall()` can interpret `isSuccessful=true` + `value=null` as success/default for Boolean-style endpoints. | **OPEN-CONFIRMED** | Generic wrapper assigns business meaning to missing payload. | Remove generic null-success inference; endpoint contract must define whether null is valid success. |
| **API-02** | `getAccountCost()` converts API/parse failure into `0.0`. | **OPEN-CONFIRMED** | Error/unknown represented as valid financial number. | Preserve explicit error/unavailable state; zero means actual zero only. |
| **API-03** | `getTestUsersCount()` failure returns `0`. | **OPEN-CONFIRMED** | Error/value conflation. | Preserve unknown/error separately from zero. |
| **API-04** | `getActiveTestUsersCount()` failure returns `0`. | **OPEN-CONFIRMED** | Error/value conflation. | Preserve unknown/error separately from zero. |
| **API-05** | Password-reveal failures return empty string. | **OPEN-CONFIRMED** | Failure/unavailable conflated with legitimate empty value. | Distinguish success, unavailable, and failure. |
| **API-06** | Account-cost parsing uses raw response/message heuristics rather than typed endpoint result. | **OPEN-CLEANUP** | Weak external-contract typing. | Strengthen stable contract without speculative schema work. |
| **API-07** | Some flexible endpoints use permissive `ApiEnvelope<Any>`-style models. | **OPEN-CLEANUP** | Schema variance led to permissive typing. | Strengthen only stable contracts. |

> `API-01` through `API-05` are confirmed correctness/error-semantics findings, not cleanup-only items.

---

# 3. P1 — UI / DASHBOARD / ERROR PROPAGATION

| ID | Finding | Status | Root Cause | Required Closure |
|---|---|---|---|---|
| **UI-01** | Failed sync does not consistently refresh pending/failed counters. | **OPEN-CONFIRMED** | UI refresh is success-path biased. | Re-read Room counters after success and failure/retry paths. |
| **DATA-01** | Dashboard prepaid-needed calculation may scan all local accounts before determining fallback is required. | **OPEN-CONFIRMED** | `0.0` overloaded as zero and fallback sentinel. | Separate known-zero from unavailable; full scan only when fallback is required. |
| **DATA-02** | Prepaid-needed fallback can represent failure as `0.0`. | **OPEN-CONFIRMED** | Error represented as valid business value. | Preserve `UNKNOWN` / `UNAVAILABLE` separately from zero. |
| **UI-02** | Local/synthetic statement rows can be mixed with server-authoritative results. | **OPEN-CLEANUP** | Provenance is not explicit. | Distinguish synthetic rows from server evidence. |

---

# 4. P1 — SYNC / REMOTE OWNERSHIP / CONCURRENCY

| ID | Finding | Status | Root Cause | Required Closure |
|---|---|---|---|---|
| **RC-09c** | Remote field ownership remains generic for some business fields. | **PARTIAL** | Field-level authority not fully centralized. | Enumerate fields and explicitly enforce ownership where ambiguous. |
| **SYNC-04** | Every `remote_version:*` writer must use monotonic DAO boundary; final writer inventory remains required. | **PARTIAL-VERIFICATION** | Historical direct writers existed. | Inventory all writers; prove no raw writer can lower stored version; equality is idempotent/no-op. |
| **SYNC-06** | Same-entity local-vs-remote handling is stronger, but complete interleaving proof remains incomplete. | **PARTIAL-VERIFICATION** | Coverage does not prove required concurrent interleavings. | Exercise real concurrent remote processing + local mutation and assert canonical final invariant. |
| **WS13** | Persistence restart test exists, but exact production worker boundary is not fully exercised. | **PARTIAL-VERIFICATION** | Test boundary is closer to repository recovery than worker execution. | Exercise `SyncWorker.doWork()` or exact extracted production boundary. |
| **WS15** | Explicit ordering tests exist, but genuine concurrency for both orderings is incomplete. | **PARTIAL-VERIFICATION** | Ordering tests are not equivalent to concurrent interleavings. | Run genuinely concurrent local-first and remote-first scenarios with deterministic assertions. |
| **IMP-04** | Replace/import requires end-to-end resurrection regression. | **PARTIAL-VERIFICATION** | Replacement interacts with generation, tombstones, cursors, reconciliation. | Run real pull/replace regression including stale remote entities. |

Historical coordinator-lock closure remains **RESOLVED** unless contradicted.

---

# 5. P1 — GOVERNANCE / AUTHORITY

| ID | Finding | Status | Root Cause | Required Closure |
|---|---|---|---|---|
| **GOV-01** | `AGENTS.md` references API docs/POC under `DOC/...` while repository uses `docs/...`. | **OPEN-CONFIRMED** | Governance path diverged from actual tree. | Correct references to actual `docs/...` paths. |
| **GOV-02** | `EARTHLINK_RESELLER_V1_REMEDIATION_PLAN_v6_FINAL_OWNER_DECISIONS.md` still declares stale baseline `e404f75` while current `main` is `baee55f`. | **OPEN-CONFIRMED** | Historical plan was not reissued after later remediation/document commits. | Next Implementation Plan MUST explicitly supersede the stale plan and use `baee55f` as execution baseline. |

Known current paths:

```text
docs/earthlink_reseller_app_api_documentation_v0_7_0.md
docs/earthlink_app_api_poc_v0_6_48.py
```

---

# 6. P2 — PERFORMANCE / VERIFICATION

| ID | Finding | Status | Required Closure |
|---|---|---|---|
| **PERF-01** | Firestore pull may issue one extra empty-page request when final page is smaller than limit. | **OPEN-OPTIMIZATION** | Stop when page size is below limit if measurement justifies. No integrity impact implied. |
| **API-08** | Statement UI loads returned collection directly; API result bounds are not fully characterized. | **OPEN-VERIFICATION** | Verify pagination/result limits before classifying as memory/correctness defect. |

---

# 7. MIGRATION / BACKUP VERIFICATION

| ID | Finding | Status | Required Closure |
|---|---|---|---|
| **MIG-01** | Existing `MIGRATION_14_15` adds `correctsEntryId` and is already part of current schema history. | **VERIFY-EXISTING** | Verify existing 14→15 migration against `earthlink_backup.zip` and upgrade paths. Do NOT create 15→16 merely to repeat this work. |

---

# 8. DEFERRED / ACCEPTED ITEMS

| ID | Item | Status | Reason |
|---|---|---|---|
| **DEF-01** | Generic raw destructive DAO methods remain broader than domain authority. | **DEFERRED** | Separate maintenance/governance work. |
| **DEF-02** | Misleading `deleteAllLedgerEntries()` naming. | **DEFERRED** | Rename/quarantine after caller inventory. |
| **DEF-03** | Broad `clearAllData()` / forced-signout destructive authority. | **DEFERRED** | Separate destructive-reset policy/UX review. |
| **DEF-04** | Double/REAL money migration. | **DEFERRED** | No current contradiction requires it for G1. |
| **DEF-05** | Stronger cloud credential key architecture. | **ACCEPTED-RISK** | Separate security-hardening track. |
| **DEF-06** | Broader Backup/Restore redesign. | **DEFERRED** | Change only when concrete lineage defect is proven. |

---

# 9. RESOLVED HISTORICAL FINDINGS — DO NOT REOPEN WITHOUT CONTRADICTION

| ID | Status | Reason |
|---|---|---|
| **AUDIT-001 / RC-05** | **RESOLVED** | Normal account lifecycle no longer physically deletes financial history. |
| **AUDIT-002** | **RESOLVED** | Current ledger FK uses `NO ACTION`. |
| **AUDIT-003** | **RESOLVED** | Startup/worker pending-operation sweep exists. |
| **AUDIT-004** | **RESOLVED** | Balance reconstruction preserves current loan semantics. |
| **AUDIT-008 / RC-09a** | **RESOLVED** | Server-version domain separated from local timestamps. |
| **AUDIT-013 / RC-09b** | **RESOLVED** | Firebase UID rechecked across await boundaries. |
| **AUDIT-014 / RC-10** | **RESOLVED** | Remote transport separated from long-lived business coordinator ownership. |
| **AUDIT-016 / RC-13** | **RESOLVED** | `syncing` outbox rows preserved. |
| **AUDIT-009 / RC-12** | **RESOLVED BY OWNER POLICY** | Unknown transaction types remain observable and financially neutral. |
| **WS9A** | **RESOLVED** | Financial corrections are additive/reversal based. |
| **WS9B** | **RESOLVED BY OWNER DECISION** | Only unaccepted imports can be rolled back destructively. |
| **WS9C** | **IMPLEMENTED / VERIFY E2E** | Replace handles obsolete remote entities but requires full regression proof. |
| **WS10** | **RESOLVED** | Repository-owned serialized G1 resolver exists. |
| **WS10.5** | **IMPLEMENTED / VERIFY** | Monotonic `remote_version` DAO boundary exists; writer inventory remains required. |
| **WS11** | **RESOLVED BY OWNER POLICY** | Unknown transaction type remains observable. |
| **WS14** | **IMPLEMENTED** | Destructive UI gating uses `AppBuildConfig`. |

---

# 10. ROOT-CAUSE MASTER MATRIX

| RCA | Root Cause | Findings |
|---|---|---|
| **RCA-01** | Unknown external outcome collapsed into terminal success/failure. | G1-A, G1-D, G1-F |
| **RCA-02** | Android response model does not match actual external API contract. | G1-B, G1-G, G1-L |
| **RCA-03** | Durable pending state does not preserve authoritative financial facts. | G1-C, G1-K |
| **RCA-04** | Pending terminal status is not guaranteed to share financial commit boundary. | G1-E, G1-K |
| **RCA-05** | Current entity state treated as proof of specific historical operation. | G1-D, G1-F |
| **RCA-06** | Cached presentation data can cross into authoritative verification. | G1-H, G1-M |
| **RCA-07** | Error/null/parse states represented using valid business values. | API-01..05, DATA-02 |
| **RCA-08** | Remote business-field authority not completely enumerated. | RC-09c |
| **RCA-09** | Generic destructive primitives remain broader than domain authority. | DEF-01..03 |
| **RCA-10** | Verification proves implementation presence more readily than runtime interleaving behavior. | SYNC-04, SYNC-06, WS13, WS15 |
| **RCA-11** | API exposes operation/statement evidence but local modeling does not guarantee deterministic correlation. | G1-I |
| **RCA-12** | Governance artifacts can retain stale paths/baselines after later changes. | GOV-01, GOV-02 |

---

# 11. IMPLEMENTATION PRIORITY MATRIX

## P0

```text
P0-01  Correct newuserdeposit response model
P0-02  Correct newtestuser response model
P0-03  Persist returned server userIndex / operation evidence
P0-04  Persist exact intended financial amount BEFORE dispatch
P0-05  Make transport / timeout / parse ambiguity UNKNOWN-safe
P0-06  Remove current-state-only success inference from production resolver
P0-07  Define deterministic operation-evidence correlation only from proven API/POC evidence
P0-08  If correlation unavailable, remain UNKNOWN and expose manual verification
P0-09  Manual verification uses externally observed evidence, not state-only inference
P0-10  Authoritative verification bypasses presentation/cache layer
P0-11  Atomically materialize account position + ledger + outbox + COMPLETED
P0-12  Missing financial target cannot produce COMPLETED
P0-13  Restart/sweep cannot guess success/failure
P0-14  Unresolved operation cannot be blindly re-dispatched
P0-15  Remove generic null-success inference
P0-16  Remove error -> zero / error -> empty-value semantics
```

## P1

```text
P1-01  Explicit API error/result semantics
P1-02  Statement evidence/correlation strengthening
P1-03  Dashboard/UI error-state corrections
P1-04  remote_version writer inventory
P1-05  Real concurrent local-vs-remote tests
P1-06  Exact production worker-boundary restart test
P1-07  Replace end-to-end resurrection regression
P1-08  Correct AGENTS.md API/POC paths
P1-09  Supersede stale remediation-plan baseline with baee55f
```

## P2 / Deferred

```text
P2-01  Firestore pagination micro-optimization
P2-02  Broad ApiEnvelope<Any> cleanup
P2-03  Destructive DAO naming/authority cleanup
P2-04  Money migration
P2-05  Broader backup/restore redesign
P2-06  Credential architecture hardening
```

---

# 12. G1 DEFINITION OF DONE

```text
[ ] newuserdeposit response correctly typed
[ ] newtestuser response correctly typed
[ ] returned userIndex durably preserved where required
[ ] exact intended financial amount persisted before dispatch
[ ] ambiguous transport/timeout/parse outcomes remain UNKNOWN/PENDING
[ ] current subscriber state alone cannot finalize lost-ACK operation
[ ] G1-F resolver enforcement removes state-only inference
[ ] deterministic evidence correlation implemented only where API/POC proves it
[ ] no speculative statement correlation rule introduced
[ ] insufficient evidence remains UNKNOWN and follows manual-verification path
[ ] manual verification requires externally observed evidence
[ ] manual verification cannot invent amount or operation identity
[ ] authoritative verification bypasses presentation/cache layer
[ ] ledger + account position + outbox + COMPLETED are one atomic Room commit
[ ] missing financial target cannot produce COMPLETED
[ ] restart/sweep cannot guess success/failure
[ ] unresolved operations cannot be blindly re-dispatched
[ ] generic null-success inference removed
[ ] account-cost/count/password errors no longer masquerade as valid values
```

---

# 13. FINAL AUTHORITY / PLAN BOUNDARY

The next Implementation Plan MUST:

1. use `baee55f` as the execution baseline;
2. explicitly supersede stale plan baseline `e404f75`;
3. treat G1-A through G1-M and API-01 through API-05 according to this matrix;
4. avoid duplicating G1-D and G1-F implementation work;
5. implement manual verification as an explicit controlled resolution path;
6. never invent statement correlation when deterministic evidence is unavailable;
7. verify existing `MIGRATION_14_15` rather than creating unnecessary 15→16;
8. keep deferred/accepted items outside immediate G1 implementation unless a new contradiction is proven;
9. preserve resolved historical findings as closed unless new evidence contradicts closure.

---

# 14. FINAL VERDICT

**FINAL — APPROVED FOR IMPLEMENTATION-PLAN AUTHORING.**

The central correctness boundary is:

```text
EXTERNAL API
    ↓
exact operation identity + exact intended amount
    ↓
durable PENDING
    ↓
external dispatch
    ↓
┌───────────────────────┬───────────────────────┐
│ PROVABLE OUTCOME      │ AMBIGUOUS OUTCOME      │
│                       │                       │
│ fresh authoritative   │ UNKNOWN / PENDING     │
│ verification         │         ↓             │
│                       │ manual verification   │
│                       │ using external        │
│                       │ observed evidence     │
└───────────┬───────────┴───────────┬───────────┘
            └─────────────┬─────────┘
                          ↓
              atomic financial materialization
                          ↓
                      COMPLETED
```

The system MUST NOT use:

```text
subscriber happens to be active
current balance looks plausible
current expiration looks plausible
response disappeared
```

as sufficient proof of a specific historical financial operation.

No additional P0 architecture finding is required at this point.
