# Issue Log and Systematic Debugging

| Issue ID | Description | Status | Suspected Root Cause | Priority |
| :--- | :--- | :--- | :--- | :--- |
| BUG-001 | Google Login: Accounts show empty on first login | Discovery | Missing `triggerSyncOneShot()` after OAuth success. Token is saved, but local DB remains empty until next app launch. | High |
| BUG-002 | Add Debt/Payment: Works once, then UI becomes greyed out (disabled) | Discovery | Empty Idempotency Key causes silent DB constraint violation; `isSubmitting` flag never resets to `false` on untrapped exceptions. | High |
| BUG-003 | Extend Subscription: Extends by 48h (1 day 23h) and consumes 1 Test User | By Design (ISP Behavior) | Earthlink API natively handles extensions by granting ~48 hours of grace period and deducting 1 unit from the "Test Users" balance. This is ISP-side logic, not a local app bug. | Low |
| BUG-004 | Activation: No UI feedback provided | Discovery | Missing toast/snackbar in `recordAccountActivation` | Low |
| BUG-005 | MoneyParser: Improper scaling of 500 (IQD vs 500k) | Resolved | Fixed logic to strictly scale only when `amount < 100` (e.g., 50 -> 50,000), allowing exact values for 250, 500, 750. | High |
| BUG-006 | Debt/Payment: Add debt not working (as reported by user) | Discovery | Same as BUG-002 (Greyed out UI block). | High |
| BUG-008 | Dashboard clear data deletes all ledger history | Discovery | `LocalAccount` has `ON DELETE CASCADE` constraint on `LocalLedgerEntry`. Wipes local financial history when cache is cleared, violating Business Need. | Critical |

## Multi-pass Deep Dive & Architecture Findings

### 1. Synchronization (Firestore Storage & ISP Data)
- **Finding:** Stripping ISP-provided metadata (`rawJson`, `stateSource`, `latitude`, `longitude`, etc.) from the Firestore sync payload aligns with the Business Need.
- **Rationale:** 
  1. The ISP (Earthlink) is the ultimate authority for these fields.
  2. Local fields mapped to reseller input (Debt, Advance, Loan, Phone, Notes, Names) must be synced.
  3. Re-syncing ISP data to Firestore wastes bandwidth. The app should pull these from Earthlink API, not Firebase.
- **Action:** The stripping logic implemented in `SyncRepositoryImpl.kt` protects the business logic.

### 4. Codebase Audit: Severe Architectural Violations Found
During a full codebase audit, two massive structural defects were identified that directly contradict the `Target Product Contract`:

- **Violation 1: UI Blocking via `DataOperationCoordinator` (Root cause of BUG-002, BUG-006, BUG-007)**
  - **The Defect:** Every single local database write (`recordAccountPayment`, `recordAccountDebt`, `recordAccountActivation`) in `Repositories.kt` is wrapped in `DataOperationCoordinator.withOperation(DataOperationMode.SYNC)`. 
  - **The Impact:** This shares a global mutex lock with the background `syncLoop()` in `SyncRepositoryImpl`. If Firebase sync takes 30 seconds (due to network or large payloads), any attempt by the reseller to add a payment in the UI will freeze (Greyed Out) for 30 seconds waiting for the lock.
  - **Contract Breach:** The contract explicitly states: *"no UI/user interaction, Firebase/ISP network wait, or externally blocking await occurs inside that transaction."*

- **Violation 2: Missing ISP Deletion Reconciliation (Legacy GC Rule NOT Implemented)**
  - **The Defect:** While the contract states that ISP-deleted users must not lose local history, there is **zero logic** in `SyncRepositoryImpl` or `DashboardViewModel` to actually cross-reference the live Earthlink user list with the local database and mark missing users as `isLegacy = true`.
  - **The Impact:** If a user is deleted from Earthlink, they remain in the local database exactly as they were (as if they are still active). Furthermore, the Garbage Collection rule (purging zero-debt deleted users) is completely missing.
  - **Action Required:** A reconciliation pass must be built that compares `gateway.searchUsers` results against the local database. Any local account not found in the ISP response must be checked: if `debtIqd > 0`, mark `isLegacy = true`. If `debtIqd == 0` (and no other balances), safely delete from local DB.

### 2. Extend Subscription Logic (BUG-003)
- **Finding:** The extension duration (1 day 23 hours) and the drop in Test Users (7 to 6) is controlled entirely by Earthlink's API (`gateway.extendUser`).
- **Rationale:** Earthlink deducts a "Test User" credit to grant a temporary 48-hour extension. Our app simply sends the `extend-subscription` HTTP request. We cannot change how Earthlink processes the extension mathematically.
- **Action:** This is an ISP behavior (By Design), not an app bug. No code change needed; user education required.
