# Lesson Learned: Persistent Lifecycle State vs. Ephemeral Resolution Outcome

**Identifier:** `LL-LIFECYCLE-VS-OUTCOME`
**Status:** Permanent engineering lesson; non-authoritative.

## What Happened?
During outbox and network dispatch implementation, ephemeral network verification results (such as `INCONCLUSIVE` due to network timeouts) were initially mixed with persistent database entity statuses. This led to fragmented status schemas and ambiguous state transitions.

## Why It Mattered
Conflating network resolution results with persistent lifecycle states risks writing unverified outcomes into the authoritative local database. For example, treating an inconclusive network timeout as a terminal failure could allow re-dispatch of an already successful transaction, while prematurely marking it completed could corrupt local ledger accounting.

## What to Do Differently
1. **Separate Entity Lifecycle from Resolution Results:** Maintain strict distinction between persistent database lifecycle states (`PENDING`, `DISPATCHING`, `RESOLVED`, `FAILED`) and transient execution/verification outcomes (`VERIFIED_SUCCESS`, `VERIFIED_FAILURE`, `INCONCLUSIVE`).
2. **Fail Safe on Inconclusive Outcomes:** When an outcome is inconclusive, keep the persistent record in `PENDING(dispatchClaimCount=1)` without mutating local ledger balances. Subsequent reconciliation or operator verification can then safely re-query the external system without risking double execution.
3. **Canonical Materialization:** Ensure terminal state transitions and financial ledger mutations pass through a single, centralized materializer rather than ad-hoc inline writers.
