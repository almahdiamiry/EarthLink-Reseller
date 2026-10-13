## 2.1 Standardized Pre-compiled Regex Patterns in NoteCleaner

**Learning:** Creating `Regex` instances dynamically inside loop iterations or item extraction functions (such as `extractGenuineNote` and `isNoiseOrRedundant` called per item during Jetpack Compose list rendering) incurs unnecessary `Pattern` compilation and garbage collection allocations.
**Action:** Always extract static regular expressions into top-level private `val` constants on singleton objects or repository companions when used repeatedly across list items.

## 2.2 Pre-indexed Map Lookups for Charge Matching in HistoryPresentationManager

**Learning:** Linear scanning (`fullList.any { ... }`) inside per-item list classification during Jetpack Compose `LazyColumn` scrolling creates $O(N^2)$ quadratic scanning overhead for paired history items.
**Action:** Pre-index non-snapshot ledger entries into a `Map<Pair<String, String>, LocalLedgerEntry>` cached with `remember(finalLedgerList)` to achieve $O(1)$ constant-time charge matching during history list rendering.
