## 2026-03-12 - History-Only Subscriber Expiry Notification Filter
**Defect:** `ExpiryNotificationManager` background loops checked expiry dates for all local accounts in Room, failing to filter out tombstoned historical accounts (`isHistoryOnlySubscriber == true`).
**Learning:** Tombstoned accounts are retained in Room to preserve financial history (RED Invariant 2), but they represent subscribers deleted from the ISP. Background monitoring loops (such as subscription expiry checks) must filter out `isHistoryOnlySubscriber == true` accounts to prevent false alert notifications.
**Prevention:** In background account iteration loops, add `if (account.isHistoryOnlySubscriber) continue` before performing active subscriber status or expiry calculations.
