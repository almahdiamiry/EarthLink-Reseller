## 2.1 Standardized Pre-compiled Regex Patterns in NoteCleaner

**Learning:** Creating `Regex` instances dynamically inside loop iterations or item extraction functions (such as `extractGenuineNote` and `isNoiseOrRedundant` called per item during Jetpack Compose list rendering) incurs unnecessary `Pattern` compilation and garbage collection allocations.
**Action:** Always extract static regular expressions into top-level private `val` constants on singleton objects or repository companions when used repeatedly across list items.

## Pre-indexing Ledger Entries in HistoryPresentationManager

**Learning:** In list processing routines like `prepareDisplayLedgerList` that match paired entries (such as pairing renewal charge and payment entries by ID), performing linear scans (`rawList.find`) inside loop iterations results in $O(N^2)$ quadratic scanning during screen rendering. Pre-indexing candidate entries into a map keyed by unique identifiers (`Pair(id, accountId)`) transforms lookup complexity into $O(1)$ and reduces total list preparation time to $O(N)$.
**Action:** Always pre-index list items into a hash map before performing cross-referencing loops in UI data presentation managers.
