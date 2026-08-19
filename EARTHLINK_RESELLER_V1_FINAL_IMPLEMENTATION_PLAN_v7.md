# EARTHLINK RESELLER V1 — FINAL IMPLEMENTATION PLAN v6

**Status:** `IMPLEMENTATION READY` with two mandatory owner stop-gates.

## 0. FROZEN / EXECUTION RULES

- Authority: `docs/authority/*` frozen contracts + `contract/*.yaml`.
- G8 stays `CLOSED`; do not weaken/rewrite invariants.
- No generic reconciliation/sync/identity engine, second ledger, new archive state, or Firebase authority.
- Do not edit historical migrations or delete/disable tests to obtain PASS.
- Network I/O never runs inside the financial Room transaction.
- **Realism rule:** critical tests use the real production path (`UI/ViewModel → Repository/Coordinator → DAO/transaction`), not a helper-only substitute.

## 1. EXECUTION ORDER

```text
G5 Identity
→ isLegacy semantics
→ delete-path classification
→ BUG-008
→ G1 durability
→ BUG-002
→ Firebase ownership
→ ISP snapshot + identity
→ legacy lifecycle
→ active/history queries
→ cross-system regression
→ full regression
```

---

# 2. G5 — FINANCIAL IDENTITY

**Current points**
```text
TransactionDeduplicator.kt
→ fallback: accountId + occurredAt + amountIqd + typeRaw + note
UtowerImporter.kt
→ actual import entry
Repositories.kt:2055
→ thin wrapper to importer; not a second dedup implementation
```

**Change**
- Preserve `sourceExternalId` when available.
- Remove the value-tuple fallback as a universal financial identity.
- Missing source identity → deterministic source-specific fallback only.
- Never use list index/order as universal identity.

**Must prove**
```text
same source row × repeat import → 1
same values + distinct rows → 2
reordered input → same IDs
two-device convergence → same IDs
```

**Exit**
```text
[ ] identity source classified
[ ] unsafe fallback removed/bounded
[ ] G5 identity/convergence tests pass
```

---

# 3. RC-03 — `isLegacy` THREE-WAY COLLISION

**Current points**
```text
UtowerImporter.kt:1144
→ isLegacy = existing.isLegacy && isLegacy
UtowerImporter.kt:1183
→ LocalAccount.isLegacy = isLegacy
RemoteSyncCoordinator.kt:169
→ existing.isLegacy || existing.id.startsWith("acc_legacy")
ResolveLocalVersionTest.kt:112
→ isLegacy = true
```

**Meanings**
```text
A. JSON format
B. business lifecycle
C. legacy-ID/version-resolution signal
```

### STOP — HUMAN/OWNER DECISION REQUIRED

Before changing `resolveLocalVersionState()` or `ResolveLocalVersionTest.kt`, stop and record one decision:

```text
A. Coupling intentional → preserve required behavior, but separate naming/semantic concepts.
B. Coupling accidental → separate version-resolution signal from LocalAccount.isLegacy and update the test.
```

The agent must not infer intent from the existing test.

**After decision**
- Rename import flag to an explicit format meaning such as `isLegacyJsonFormat`.
- Import/restore/ordinary merge/stale remote must not perform `true → false` on business lifecycle.
- Only authoritative ISP lifecycle may establish `false → true`.

**Must prove**
```text
active + legacy JSON → active
legacy + modern JSON → legacy
legacy + legacy JSON → legacy
legacy + restore → legacy
legacy + stale remote false → legacy
```

**Exit**
```text
[ ] every isLegacy read/write/consumer classified
[ ] all 3 meanings resolved intentionally
[ ] STOP decision recorded
[ ] lifecycle monotonicity proven
[ ] ResolveLocalVersionTest matches decision
```

---

# 4. RC-01 — BUG-008 FINANCIAL HISTORY

**Current points**
```text
Models.kt:357-364
→ LocalLedgerEntry FK = ON DELETE CASCADE
Repositories.kt:1095
→ deleteAccount() deletes ledger then account
LocalAccountDetailScreen
→ production delete → ViewModel.deleteAccountLocal()
RemoteSyncCoordinator.kt:406-407
→ remote AccountDelete deletes ledger then account
DB VERSION = 12
PRAGMA foreign_keys = ON
```

**Change**
- Create exactly `MIGRATION_12_13` removing account→ledger cascade.
- Never edit historical migrations.
- Remove ledger deletion from production account lifecycle and remote `AccountDelete`.
- Keep explicitly destructive user/developer reset semantics only where contract allows them.

**Must prove**
```text
real UI delete + ≥2 ledger rows → rows survive
real Remote AccountDelete → rows survive
pre-13 DB → MIGRATION_12_13 → safe schema
```

**Exit**
```text
[ ] no production ledger deletion
[ ] no remote ledger deletion
[ ] CASCADE removed from current schema
[ ] MIGRATION_12_13 passes
[ ] reset paths classified
```

---

# 5. RC-02 — BUG-002 + G1 DURABILITY

**Current points**
```text
SyncRepositoryImpl.executeSyncPassInternal()
→ DataOperationCoordinator.withOperation(SYNC) around full sync/network pass

SyncRepositoryImpl.triggerSyncOneShot()
→ singleFlightMutex + pendingRunAfterCurrent

Also audit:
DataOperationCoordinator
PendingExternalOperation
SyncOutbox
REMOTE_APPLY
all DataOperationMode.SYNC callers
```

**Change**
Preserve the canonical local mutation boundary:

```text
short Room transaction
+ serialization
+ business state
+ ledger/current position
+ stable identity
+ durable intent/outbox
→ COMMIT
```

Then let network transport run independently.

Do **not** remove `DataOperationCoordinator` wholesale, bypass INV-11, or put network I/O inside the financial transaction.

**Must prove**
```text
slow real sync + real payment → payment commits before sync finishes
process death after external success → recoverable
lost ACK / timeout / duplicate initiation / unknown outcome → safe
same operation ID + divergent payload → contract-compliant
coordinator re-entry → safe
```

**Exit**
```text
[ ] every blocking mechanism classified
[ ] singleFlightMutex cannot reintroduce local-mutation blocking
[ ] pendingRunAfterCurrent verified
[ ] PendingExternalOperation + SyncOutbox durable
[ ] no network in financial transaction
[ ] INV-11 passes
```

---

# 6. FIREBASE — BIDIRECTIONAL OWNERSHIP

**Current surfaces**
```text
current local→Firebase account projection
app/src/main/java/com/example/core/sync/RemoteEntityValidator.kt:13-84
→ validateAndMapAccount()
→ lines 55-79 use the same generic pattern:
   d["field"] as? T ?: existingLocalAccount?.field
→ line 59: isLegacy = d["isLegacy"] as? Boolean ?: existingLocalAccount?.isLegacy ?: false
→ this lets stale remote false override an existing local true
→ the same remote-wins-if-present pattern also affects ownership-sensitive fields
RemoteSyncCoordinator remote-apply path
```

**Mandatory field set**

Classify exactly these fields before changing projection/merge:

```text
debtIqd, advanceIqd, loanIqd,
openingDebtIqd, openingAdvanceIqd, openingLoanIqd,
lastPaymentAt,
earthlinkUsername, displayName, phone1, phone2, nanoIp, note, isLegacy,
rawJson, packageName, towerName, zoneName,
address, latitude, longitude,
stateSource, stateConfidence, snapshotCapturedAt
```

For each: `authority / upload / remote-apply / overwrite / merge / lifecycle`.

**Important:** snapshot fields must be reconciled against frozen authority, not excluded by convenience.

**Change**
- Explicit upload projection.
- Replace the generic remote-wins-if-present merge for ownership-sensitive fields with per-field rules derived from the ownership matrix.
- `isLegacy` MUST use dedicated monotonic merge semantics, not the generic Elvis fallback:
  `remote == true || existingLocalAccount?.isLegacy == true`
  therefore remote `false` can never override an existing local `true`.
- Stale cloud cannot overwrite ISP/local-owned state.
- `Firebase AccountDelete` is transport/cloud lifecycle only; it is **not** ISP disappearance authority.
- `REMOTE_APPLY` must not create a new synchronization obligation/echo.

**Must prove**
```text
stale cloud protected fields → local authority survives
legacy=true + stale remote isLegacy=false → legacy=true
remote AccountDelete → ledger survives + no ISP legacy decision
Device A lifecycle write → Device B apply → no echo write
```

**Exit**
```text
[ ] exact field set classified
[ ] upload/apply policies explicit
[ ] ownership symmetric
[ ] stale overwrite blocked
[ ] AccountDelete cannot establish ISP disappearance
[ ] no REMOTE_APPLY echo
```

---

# 7. RC-04 — ISP DISAPPEARANCE / LEGACY LIFECYCLE

**STATUS: GREENFIELD — NO CURRENT BULK/PAGINATION CALLER**

**Build on existing API surface**
```text
EarthlinkApiService.searchUsers(StartIndex, RowCount, ...)
```
Pagination parameters already exist. Do not add a new endpoint or generic reconciliation engine.

### STOP — IDENTITY CONFIRMATION REQUIRED

Before evaluator code, confirm the exact mapping:

```text
LocalAccount.sourceExternalId
↔
authoritative subscriber identifier from searchUsers/getUserDetail
```

The implementation agent must not guess this mapping.

**Snapshot requirement**

Only:

```text
complete successful snapshot
+ eligible active subscriber
+ authoritative identity absent
→ isLegacy = true
```

Completeness requires successful/valid response, all pages, valid scope, no partial/timeout/error. A failed request represented as `[]` is not authoritative.

**Identity rule**
- Use the existing ISP identity authority.
- Do not create a new identity registry.
- Do not use name/phone/address as primary disappearance identity unless frozen authority explicitly requires it.
- Exclude already-history-only/legacy accounts from active disappearance evaluation.

**Must prove**
```text
present → active
absent + complete → legacy
absent + debt>0 → account+ledger preserved
absent + debt=0 → account+ledger preserved
partial/error/timeout/unknown completeness → no transition
valid zero-result → act only if zero-result is proven authoritative
```

**Exit**
```text
[ ] GREENFIELD acknowledged
[ ] searchUsers pagination reused
[ ] identity mapping confirmed before evaluator code
[ ] complete snapshot proven
[ ] only false → true
[ ] history preserved
```

---

# 8. ACTIVE / HISTORY QUERIES

Audit existing surfaces only:

```text
getAll()
searchAccounts()
active filters
ViewModels
dashboard/list/count queries
```

Required:

```text
active → isLegacy = false
historical → include isLegacy = true
```

---

# 9. REAL-PATH CROSS-SYSTEM REGRESSION

```text
1. real UI delete → ledger survives
2. real Remote AccountDelete → ledger survives
3. MIGRATION_12_13 → safe schema/history
4. identical-value distinct transactions → both survive
5. repeat import → one transaction
6. legacy + modern import → legacy
7. legacy + stale remote false → legacy
8. resolveLocalVersionState → owner-approved semantics
9. slow sync + real payment → payment not blocked
10. process death/lost ACK/duplicate initiation → safe
11. stale Firebase fields → local authority survives
12. AccountDelete → no ISP lifecycle transition
13. REMOTE_APPLY → no echo
14. complete ISP disappearance → legacy
15. partial/error snapshot → no transition
16. debt 0/positive disappearance → history preserved
17. legacy excluded from active queries
18. two-device financial identity convergence
19. two-device legacy convergence
20. restore/import/remote merge → invariants preserved
```

---

# 10. GLOBAL PROHIBITIONS

```text
no production ledger deletion
no account-lifecycle ON DELETE CASCADE
no import-format isLegacy → business lifecycle assignment
no true → false lifecycle reset via import/restore/stale remote
no Firebase AccountDelete → ISP disappearance
no incomplete ISP snapshot → legacy
no unsafe value-based financial identity
no network I/O in financial transaction
no DataOperationCoordinator bypass/replacement shortcut
no generic reconciliation/sync/identity architecture
no weakening tests or historical migrations
```

---

# 11. FINAL REVIEW GATES

### Primary — completeness
```text
[ ] RC-01..RC-05 covered
[ ] every isLegacy consumer classified
[ ] every synchronization blocker classified
[ ] every destructive path classified
[ ] G5/G1/Firebase/ISP covered
```

### Secondary — contract / Red Team
```text
[ ] frozen invariants unchanged
[ ] G8 remains closed
[ ] no new authority
[ ] no financial loss/identity collapse
[ ] no hidden blocking path/echo loop
[ ] no cloud/ISP authority inversion
```

### Third — implementability
```text
[ ] every code change has exact current entry point
[ ] every critical change has real-path proof
[ ] migration path explicit
[ ] existing-device behavior explicit
[ ] two-device behavior explicit
[ ] both STOP gates resolved by owner
```

### Final readiness
```text
[ ] reviewer findings incorporated
[ ] targeted fail→fix→pass evidence recorded
[ ] migration/schema evidence recorded
[ ] concurrency evidence recorded
[ ] Firebase evidence recorded
[ ] ISP lifecycle evidence recorded
[ ] G5/G1 evidence recorded
[ ] full regression passes
[ ] actual exit codes recorded
[ ] no unresolved high-severity unknown
```

---

# 12. FINAL DECISION

```text
IMPLEMENTATION READY — OWNER DECISIONS RECORDED
```

### DECISION 1 — RC-03: isLegacy semantics

REJECTED: Option A (preserve coupling) and Option B (decouple C from B while keeping A+B on the same LocalAccount.isLegacy column) as originally framed.

RATIONALE (evidence from resolveLocalVersion()):
The "ledger" branch of resolveLocalVersion() already uses a dedicated, purpose-built field for this exact role — existing.isSnapshotHistory — not a reused business field. This proves the original design intent: one dedicated boolean per entity type meaning "this record predates reliable version tracking," used only for version-resolution fallback suppression.

DECISION:
1. `LocalAccount.isLegacy` KEEPS its current meaning and current behavior in `resolveLocalVersionState()` UNCHANGED:
   - Meaning: "this account record predates reliable version tracking."
   - Role in version resolution: suppresses the zero-version fallback so pre-tracking accounts do not falsely win over newer incoming versions.
   - Tests in `ResolveLocalVersionTest.kt` remain valid as-is.
2. The business concept of "history-only / former subscriber (subscriber departed ISP while retaining debt/ledger history)" is a SEPARATE LIFECYCLE STATE and must NOT reuse `LocalAccount.isLegacy`.
3. Add a NEW column to `LocalAccount`: `isHistoryOnlySubscriber: Boolean = false`.
   - Migration (MIGRATION_12_13 or 13_14): `ALTER TABLE local_accounts ADD COLUMN isHistoryOnlySubscriber INTEGER NOT NULL DEFAULT 0;`
   - Also remove `CASCADE` on `local_ledger_entries.accountId` in this migration (per RC-01).
4. `UtowerImporter.kt:1144` and `:1183` must be rewritten to write ONLY to the import-format flag (rename to `isLegacyJsonFormat` locally in that file) and must NEVER write to `LocalAccount.isLegacy` or to the new lifecycle field.
5. `RemoteEntityValidator.kt:197`: replace with monotonic merge for the new field:
   `if (remote == true) true else existingLocalAccount?.isHistoryOnlySubscriber ?: false`
   (never resets true → false).

### DECISION 2 — RC-04: ISP subscriber identity mapping

EVIDENCE:
- `LocalAccount.sourceExternalId` is NOT the ISP identifier — it is an import-source key from utower files, unrelated to the live reseller API.
- The reseller API defines the subscriber identity as `userID: String` (e.g. "ali_hassan", "user_1001", or full username).
- In `LocalAccount`, this corresponds to `LocalAccount.earthlinkUsername` (String?, nullable/indexed).
- The existing `hashCode()`-based matching in `EarthlinkSearchViewModel.kt` (`abs(acc.id.hashCode()) == userIndex`) is broken and MUST NOT be consulted or reused.

DECISION:
1. Confirmed Mapping: `LocalAccount.earthlinkUsername` ↔ API field `userID`.
2. Any `LocalAccount` with a null/blank `earthlinkUsername` MUST be excluded entirely from ISP-disappearance evaluation — it cannot be evaluated against an ISP response because it has no ISP identity.
3. ISP-disappearance algorithm:
   - Input: `authoritativeIspUserIds: Set<String>` from a COMPLETE, verified ISP subscriber list fetch.
   - For each `LocalAccount` where `earthlinkUsername != null && earthlinkUsername.isNotBlank()`:
     - If `earthlinkUsername NOT IN authoritativeIspUserIds`:
       - Transition: set `isHistoryOnlySubscriber = true` (monotonic, never set back to false by sync).
       - Log audit entry: `ISP_SUBSCRIBER_DISAPPEARED` with `accountId` and `earthlinkUsername`.
4. If the ISP fetch is partial, failed, or timed out: ABORT evaluation entirely (INV-12: no transitions on incomplete data).

# END OF FINAL IMPLEMENTATION PLAN v7
