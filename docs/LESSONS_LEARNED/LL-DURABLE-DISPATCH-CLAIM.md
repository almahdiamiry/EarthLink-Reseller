# Lesson Learned: Durable Dispatch Claims

**Identifier:** `LL-DURABLE-DISPATCH-CLAIM`
**Status:** Permanent engineering lesson; non-authoritative.

## What Happened?
Initial implementations relied on in-memory concurrency controls (`inflightAccountLocks` inside `EarthlinkSearchViewModel`) to prevent duplicate financial network dispatches. Because in-memory mutexes are scoped to process and ViewModel lifecycles, background process crashes, activity recreation, and cold restarts left the dispatch boundary unprotected, creating potential double-dispatch vulnerabilities.

## Why It Mattered
Financial actions (subscriber activations, balance refills, debt records) produce irreversible real-world money mutations on the ISP server. If a network call is dispatched without a hardware-level persistent claim, a crash or race condition can cause the same financial operation to execute multiple times.

## What to Do Differently
1. **Enforce Single-Claim Authority in SQLite:** Require an atomic database state claim (`status = 'PENDING' AND dispatchClaimCount = 0` updated to `dispatchClaimCount = 1` inside a Room transaction) before dispatching any external request.
2. **Treat In-Memory Locks as UI Coalescers Only:** In-memory mutexes may be used to coalesce UI double-taps, but must never be treated as the durable correctness or concurrency boundary.
3. **Persist Dispatch Intent Before Network I/O:** Never initiate an external financial network call without an immutable pending record committed to persistent storage.
