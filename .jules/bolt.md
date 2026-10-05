## 2.1 Standardized Pre-compiled Regex Patterns in NoteCleaner

**Learning:** Creating `Regex` instances dynamically inside loop iterations or item extraction functions (such as `extractGenuineNote` and `isNoiseOrRedundant` called per item during Jetpack Compose list rendering) incurs unnecessary `Pattern` compilation and garbage collection allocations.
**Action:** Always extract static regular expressions into top-level private `val` constants on singleton objects or repository companions when used repeatedly across list items.

## 2.2 Composite Map Indexing for History List Pairing

**Learning:** In ledger history presentation (`HistoryPresentationManager.prepareDisplayLedgerList`), searching for matching charges across unindexed lists using `rawList.find` inside a loop over payment entries produces $O(N^2)$ linear scans.
**Action:** Pre-index non-snapshot entries into a composite key Map (e.g. `Pair(id, accountId)`) in a single $O(N)$ pass to perform $O(1)$ lookups during charge-payment folding.
