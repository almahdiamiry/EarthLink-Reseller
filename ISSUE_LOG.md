# Issue Log and Systematic Debugging

| Issue ID | Description | Status | Suspected Root Cause / Resolution | Priority |
| :--- | :--- | :--- | :--- | :--- |
| BUG-001 | Google Login: Accounts show empty on first login | Resolved | Added sync trigger and initial restore/hydration flow after Google Sign-In authentication. | High |
| BUG-002 | Add Debt/Payment: Works once, then UI becomes greyed out (disabled) | Resolved | Fast non-blocking UI transaction path, proper state resetting, and deterministic unique transaction ID generation. | High |
| BUG-003 | Extend Subscription: Extends by 48h (1 day 23h) and consumes 1 Test User | By Design (ISP Behavior) | Earthlink API natively handles extensions by granting ~48 hours of grace period and deducting 1 unit from the "Test Users" balance. This is ISP-side logic, not a local app bug. | Low |
| BUG-004 | Activation: No UI feedback provided | Resolved | Added user-facing snackbar feedback and operation status notification for activation flow. | Low |
| BUG-005 | MoneyParser: Improper scaling of 500 (IQD vs 500k) | Resolved | Fixed logic to strictly scale only when `amount < 100` (e.g., 50 -> 50,000), allowing exact values for 250, 500, 750. | High |
| BUG-006 | Debt/Payment: Add debt not working (as reported by user) | Resolved | Resolved via non-blocking UI state management and deterministic idempotency coordinates. | High |
| BUG-008 | Dashboard clear data deletes all ledger history | Resolved | Preserved ledger entries via soft-deletion / history protection and decoupled lifecycle states. | Critical |
| ARCH-001 | Missing ISP Deletion Reconciliation & History-Only Decoupling | Resolved | Implemented `IspDisappearanceReconciler` using authoritative `earthlinkUsername` ↔ `userID` mapping and dedicated `isHistoryOnlySubscriber` field (MIGRATION_12_13). | Critical |

## Multi-pass Deep Dive & Architecture Findings

### 1. Synchronization (Firestore Storage & ISP Data)
- **Finding:** Stripping ISP-provided metadata (`rawJson`, `stateSource`, `latitude`, `longitude`, etc.) from the Firestore sync payload aligns with the Business Need.
- **Rationale:** 
  1. The ISP (Earthlink) is the ultimate authority for these fields.
  2. Local fields mapped to reseller input (Debt, Advance, Loan, Phone, Notes, Names) must be synced.
  3. Re-syncing ISP data to Firestore wastes bandwidth. The app pulls these from Earthlink API, not Firebase.
- **Action:** The stripping logic implemented in `SyncRepositoryImpl.kt` protects the business logic.

### 2. ISP Deletion Reconciliation & History Preservation (RC-03 / RC-04)
- **Finding:** ISP-departed subscribers must retain local debt and transaction history without being purged or conflated with version tracking metadata.
- **Resolution:**
  1. Decoupled `LocalAccount.isLegacy` (version-tracking fallback suppression) from subscriber lifecycle.
  2. Added monotonic `LocalAccount.isHistoryOnlySubscriber` via `MIGRATION_12_13`.
  3. Implemented `IspDisappearanceReconciler.kt` comparing authoritative ISP subscriber lists against local accounts with valid `earthlinkUsername`. Missing accounts with active debt/ledger history transition monotonically to history-only status.
  4. Filtered active queries to exclude history-only subscribers while preserving them in dedicated historical ("محذوفة") filters.

### 3. Extend Subscription Logic (BUG-003)
- **Finding:** The extension duration (1 day 23 hours) and the drop in Test Users (7 to 6) is controlled entirely by Earthlink's API (`gateway.extendUser`).
- **Rationale:** Earthlink deducts a "Test User" credit to grant a temporary 48-hour extension. Our app simply sends the `extend-subscription` HTTP request. We cannot change how Earthlink processes the extension mathematically.
- **Action:** This is an ISP behavior (By Design), not an app bug. No code change needed; user education required.

