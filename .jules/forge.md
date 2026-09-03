## 2026-09-03 - Filter History-Only Subscribers in Expiry Notifications
**Finding:** `ExpiryNotificationManager` queried all local accounts (`getAllOneShot()`) and posted expiration alert notifications without checking `account.isHistoryOnlySubscriber`.
**Learning:** History-only subscribers represent subscribers that disappeared from ISP or were soft-deleted to preserve financial history (`RED Invariant #2`). Processing them in background notification monitors generates false alarms for non-active subscribers.
**Prevention:** All background subscription/user monitors must explicitly check `if (account.isHistoryOnlySubscriber) continue` or query `WHERE isHistoryOnlySubscriber = 0`.
