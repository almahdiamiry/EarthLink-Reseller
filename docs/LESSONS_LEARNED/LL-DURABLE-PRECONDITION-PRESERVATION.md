# Lesson Learned: Preserving Durable Preconditions Across Seam Refactors

**Identifier:** `LL-DURABLE-PRECONDITION-PRESERVATION`  
**Status:** Permanent engineering lesson; non-authoritative practical knowledge.  
**Related Invariants:** INV-03 (Single-Writer Dispatch Claim), INV-04 (Canonical Materialization).

---

## 1. What Happened

During the MNT-09 boundary refactoring, execution ownership of subscriber refills was transferred from `UserDetailScreenV2` to `EarthlinkSearchViewModel`. The refactored `refillUser` method accepted an optional domain parameter: `account: LocalAccount? = null`.

Internally, `refillUser` evaluated:
```kotlin
val localAcc = account
    ?: localAccountRepository.getAccountByIdOneShot(userId)
    ?: localAccountRepository.findAccountByUsernameOrIdOneShot(userId)
val effectiveAcc = if (localAcc == null) {
    // Auto-create and persist new LocalAccount to Room
} else {
    localAcc
}
```

When an operator searched for a subscriber who existed on the ISP gateway but had no corresponding record in the local database, `UserDetailScreenV2` instantiated a transient, unpersisted `LocalAccount` in memory and passed it into `refillUser`.

Because `account` was non-null in memory, `localAcc` resolved immediately to that instance. As a result:
1. The database existence query was bypassed.
2. The Room persistence step was skipped.
3. The remote gateway dispatch executed and deducted balance on the ISP server.
4. Post-dispatch materialization in `resolvePendingOperationVerifiedSuccess` queried Room for `op.accountId`, found zero matching rows, and threw `MISSING_LOCAL_FINANCIAL_TARGET`.

The refactor preserved visible data flow across the interface, but silently dropped a durable precondition established in commit `810c99`.

---

## 2. Why Early Tests Missed the Defect

The full test suite (562 tests) passed cleanly after MNT-09 because of fixture over-optimization and missing state combinations:

1. **Clean Fixture Bias:** In `EarthlinkSearchViewModelSeamTest`, test setups pre-saved the target account into the database before invoking `refillUser`.
2. **Missing State Combination:** In `Step3DurableDispatchTest.test19`, `refillUser` was invoked with default `account = null`, which exercised the auto-create path.
3. **Orthogonal State Failure:** The test suite tested `account == null` (with no DB record) and `account != null` (with a pre-saved DB record). It never tested the third realistic production combination: `account != null` in memory while the database row is completely absent.
4. **Surface Detection:** The broken state combination was only observed when manually testing live search in Demo Mode.

This discovery does not mean manual testing is superior to automated testing. Manual testing surfaced the issue purely by chance. The true takeaway is that automated characterization tests must reflect production-shaped state combinations rather than idealized fixtures.

---

## 3. Reusable Engineering Rule

> **For high-risk mutation paths, characterize both the object state and the durable state assumed by the production path; explicitly test cases where an in-memory object exists while its required persistent record does not.**

### Operational Invariants
* **Object presence is not persistence:** Never equate `object != null` with `isPersisted == true`.
* **Verify storage directly:** For any mutation that requires a durable target, query the underlying database directly to confirm existence before initiating irreversible remote side effects.
* **Fail closed before network I/O:** If the local target cannot be confirmed or created in durable storage, abort the workflow immediately. Never perform remote financial dispatch while local persistence is uncertain.

---

## 4. Comparison with AGENTS.md Testing Playbook (§9)

Section 9.4 of the Testing Playbook already mandates three core reasoning questions:
* *Question 3:* What is the real production path?
* *Question 4:* What assumption am I relying on, and where is it proven?
* *Question 5:* What could falsify the claim?

The MNT-09 regression occurred because:
1. The characterization suite answered Question 3 assuming callers either pass `null` or pass a persisted account. It failed to map the UI path where an unpersisted transient object was passed.
2. The test setup answered Question 4 by assuming that an in-memory `LocalAccount` guaranteed a backing SQLite row.

### Identified Playbook Gap
The Testing Playbook currently focuses heavily on arithmetic correctness and concurrency gates. It does not explicitly demand **fixture symmetrization across memory and storage tiers**. When an interface accepts an in-memory entity, tests frequently assume the entity is already persisted.

---

## 5. Non-Authoritative Recommendation for Playbook Evolution

If maintainers decide to update `AGENTS.md` in a future governance cycle, consider adding the following requirement to Section 9.3 or 9.6:

> **Tiered State Combination Standard:**  
> When refactoring boundaries that precede durable writes or external network dispatches, characterization tests must verify all combinations of in-memory argument state and persistent database state:  
> 1. In-memory object present, database record present.  
> 2. In-memory object absent (`null`), database record absent.  
> 3. In-memory object present, database record absent.  
> 4. Database write failure (simulated I/O or constraint error prior to dispatch).
