# Lesson Learned: G1-I — Statement Correlation Contract Closure

**Identifier:** `LL-G1-I-STATEMENT-CORRELATION-CONTRACT`
**Status:** Historical forensic knowledge; non-authoritative practical engineering note.

> **Important Boundary:** This document is historical forensic knowledge and prior investigation evidence. It is **NON-AUTHORITATIVE** and does **NOT** override current code, `AGENTS.md`, or product contracts (`Target Product Contract v0.6`, `Final Independent Adjudication Memo`). Current code and authoritative project documents remain primary.
>
> This record documents closure of a contract/evidence gap. It does **NOT** represent a production code fix.

---

## 1. Finding Identity

| Field | Value |
|-------|-------|
| **ID** | `G1-I` |
| **Title** | Statement Correlation Contract |
| **Finding Matrix Entry** | [`docs/authority/EARTHLINK_RESELLER_V1_FINAL_APPROVED_FINDINGS_MATRIX.md:80`](docs/authority/EARTHLINK_RESELLER_V1_FINAL_APPROVED_FINDINGS_MATRIX.md) |
| **Root Cause** | `RCA-11`: "API exposes operation/statement evidence but local modeling does not guarantee deterministic correlation." |
| **Final Status** | `CLOSED — CONTRACT / EVIDENCE GAP` · No implementation defect confirmed. |

---

## 2. What Is Proven

The following facts were established by repository evidence inspection:

1. **API documents `transactionID` as "Transaction reference."**
   - Source: [`docs/earthlink_reseller_app_api_documentation_v0_7_0.md:1209`](docs/earthlink_reseller_app_api_documentation_v0_7_0.md) — table row: "`transactionID` | Transaction reference."
   - This is the **sole** definition of `transactionID` in the API documentation.

2. **The POC reads and displays `transactionID` but does not correlate it.**
   - Source: [`docs/earthlink_app_api_poc_v0_6_48.py:4095`](docs/earthlink_app_api_poc_v0_6_48.py) — `safe_get(row, "transactionID", ...)` followed by print at lines 4109-4110.
   - The POC performs no matching of `transactionID` against mutation results or pending operation identifiers.

3. **No mutation endpoint response includes `transactionID`.**
   - `POST /user/newtestuser` → `{ "value": userIndex, "responseMessage", "isSuccessful" }` — no `transactionID`.
     Source: [`docs/earthlink_reseller_app_api_documentation_v0_7_0.md:692-700`](docs/earthlink_reseller_app_api_documentation_v0_7_0.md)
   - `POST /user/newuserdeposit` → `{ "value": userIndex, "responseMessage", "isSuccessful" }` — no `transactionID`.
     Source: [`docs/earthlink_reseller_app_api_documentation_v0_7_0.md:764-771`](docs/earthlink_reseller_app_api_documentation_v0_7_0.md)
   - `POST /user/extend/{userIndex}` → `{ "value": true, "responseMessage", "isSuccessful" }` — no `transactionID`.
     Source: [`docs/earthlink_reseller_app_api_documentation_v0_7_0.md:887-894`](docs/earthlink_reseller_app_api_documentation_v0_7_0.md)
   - `POST /user/newrefilldeposit` → Boolean-style response — no `transactionID`.

4. **Local pending-operation identifiers are client-generated.**
   - `businessTransactionId` = `"tx_" + UUID.randomUUID()` (client-generated).
   - `operationIntentId` = `UUID.randomUUID().toString()` (client-generated).
   - Source: [`app/src/main/java/com/example/data/repository/Repositories.kt`](app/src/main/java/com/example/data/repository/Repositories.kt) — `PendingExternalOperation` entity and callers in `EarthlinkSearchViewModel.kt`.

5. **No authoritative mutation → transactionID mapping was found.**
   - The Owner Policy (§0.1, lines 14-26 of the Findings Matrix) defines the chain `newuserdeposit → userIndex → GET /user/{userIndex}` but does **not** include `transactionID`.
   - No authority document (Target Product Contract v0.6, Final Independent Adjudication Memo) mentions `transactionID` or statement correlation.

6. **No captured raw statement fixtures exist in the repository.**
   - No raw JSON dumps of statement responses with actual `transactionID` values.
   - No test fixtures demonstrating repeated retrieval of the same row with consistent `transactionID`.

---

## 3. What Is Not Proven

The following properties are **unproven** and cannot be established from available evidence:

| Property | Status | Reason |
|----------|--------|--------|
| Uniqueness scope of `transactionID` | UNPROVEN | API docs do not state whether it is unique per row, per operation, per reseller, or globally. |
| Stability across repeated statement queries | UNPROVEN | No captured evidence shows the same `transactionID` on re-query. |
| Presence for all V1 operation types | UNPROVEN | No evidence that activations, renewals, refills, and test-user operations all produce a `transactionID`. |
| Mutation-to-statement mapping | UNPROVEN | Mutation responses do not return `transactionID`; no POC demonstrates the link. |
| Uniqueness of the current heuristic tuple `(userID, withdrawalAmount, isWithdrawal, timestamp ±90s)` | UNPROVEN | Two legitimate operations on the same subscriber with the same amount within 180 seconds would collide. |
| Any authoritative relationship between `transactionID` and `businessTransactionId` / `operationIntentId` | UNPROVEN | The client never possesses both simultaneously in any V1 operation path. |

---

## 4. Why This Is a Contract / Evidence Gap

The API-side statement evidence exists — the `GET /affiliate/deposit/accountStatement` endpoint returns rows with fields including `transactionID`. However, **deterministic operation-specific correlation is not defined or proven** in any authority document.

This distinction is mandatory:

```
unique statement identifier
    ≠
identifier of the original external operation
```

A unique statement-row ID would only be useful for correlation if there were a **proven, authoritative mapping** from that ID to the original pending external operation. No such mapping exists in the V1 API contract, POC implementation, or captured evidence. The client generates its own identifiers (`businessTransactionId`, `operationIntentId`) at dispatch time, and no subsequent API response exposes a matching server-side identifier.

---

## 5. Current Safe V1 Behavior

The current recovery path intentionally preserves fail-closed behavior when deterministic evidence is unavailable:

```
insufficient deterministic evidence
    ↓
INCONCLUSIVE
    ↓
preserve durable pending operation
    ↓
manual verification
```

This is mandated by the Owner Policy (§0.3, lines 40-49 of the Findings Matrix):

> "UNKNOWN / INCONCLUSIVE → do NOT mark FAILED, do NOT mark COMPLETED, do NOT create a guessed financial ledger entry, preserve durable pending operation, surface manual verification required"

The current heuristic correlation (`verifyRenewalViaStatement` at `Repositories.kt:1504-1537`) correctly produces `INCONCLUSIVE` when zero or multiple candidates match, which routes to `resolvePendingOperationInconclusive()` and preserves the pending operation for manual resolution via `submitManualVerificationEvidence()`.

**This does not claim the current heuristic is mathematically collision-free.** It only records that the fail-closed fallback is correct.

---

## 6. Closure Boundary

G1-I is **CLOSED as a forensic finding** because the investigation established that the missing deterministic correlation is a **contract/evidence gap**, not a confirmed implementation defect.

It remains eligible for future reopening **ONLY** if authoritative new evidence appears, such as:

- Official API documentation defining `transactionID` semantics (uniqueness scope, stability, operation coverage) and its relationship to mutation requests.
- An authoritative mutation → statement mapping (e.g., mutation response returns a `transactionID` that matches the statement row).
- Captured live evidence (raw JSON fixtures from the gateway) demonstrating deterministic correlation.
- An explicit product/owner decision authorizing a different V1 evidence rule for statement-based correlation.

---

## 7. Future-Agent Prevention Rules

1. **Do NOT invent a statement correlation tuple.** The G1-I finding (line 80 of the Findings Matrix) explicitly requires: "Do NOT invent correlation rules."
2. **Do NOT treat `transactionID` uniqueness as proof of original-operation identity.** A unique statement-row ID is not sufficient without a proven mapping to the pending operation.
3. **Do NOT treat current subscriber state as operation-specific success evidence.** The Owner Policy (§0.2) explicitly forbids using subscriber existence/active state alone as proof of historical execution.
4. **Do NOT mark `VERIFIED_SUCCESS` without deterministic operation-specific evidence.** When correlation is unavailable, the correct behavior is `INCONCLUSIVE → manual verification`.
5. **When deterministic evidence is unavailable, preserve `INCONCLUSIVE`/manual verification behavior.** This is the required fail-closed V1 safety mechanism.
6. **Do NOT reopen G1-I merely because `transactionID` exists in the API.** The presence of a field does not establish correlation semantics.

---

## 8. Evidence Sources Inspected

| Source | Path | Result |
|--------|------|--------|
| API documentation | `docs/earthlink_reseller_app_api_documentation_v0_7_0.md` | `transactionID` documented as "Transaction reference." No mutation endpoint returns it. |
| POC implementation | `docs/earthlink_app_api_poc_v0_6_48.py:4095` | Reads and displays `transactionID`; no correlation performed. |
| Statement model | `app/src/main/java/com/example/core/model/Models.kt:326-340` | `AccountStatementItem` does not capture `transactionID`. |
| Recovery implementation | `app/src/main/java/com/example/data/repository/Repositories.kt:1504-1537` | Heuristic 4-tuple correlation; correct INCONCLUSIVE fallback. |
| Findings matrix / Owner Policy | `docs/authority/EARTHLINK_RESELLER_V1_FINAL_APPROVED_FINDINGS_MATRIX.md` | G1-I at line 80; Owner Policy §0.1-§0.4 at lines 14-64. |
| Target Product Contract v0.6 | `docs/authority/Target Product Contract v0.6.md` | Zero mentions of `transactionID`, statement correlation, or deterministic correlation. |
| Final Independent Adjudication Memo | `docs/authority/Final Independent Adjudication Memo.md` | Zero mentions of `transactionID` or statement correlation. |
| Changelog (current) | `CHANGELOG.md:18` | WS1 notes statement correlation timezone alignment — implementation detail, not authority. |
| Changelog (archive) | `docs/historical/CHANGELOG_V1_REMEDIATION_ARCHIVE.md` | No G1-I entry; WS1 mentions 4-tuple correlation but no authoritative tuple. |
| Test fixtures | `app/src/test/java/com/example/*` | Tests use mock `AccountStatementItem`; no raw API response fixtures. |

---

*This document is historical forensic knowledge and prior investigation evidence. It does NOT override current code, AGENTS.md, or product contracts.*
