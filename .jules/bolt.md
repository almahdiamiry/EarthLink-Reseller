## 2.1 Standardized Pre-compiled Regex Patterns in NoteCleaner

**Learning:** Creating `Regex` instances dynamically inside loop iterations or item extraction functions (such as `extractGenuineNote` and `isNoiseOrRedundant` called per item during Jetpack Compose list rendering) incurs unnecessary `Pattern` compilation and garbage collection allocations.
**Action:** Always extract static regular expressions into top-level private `val` constants on singleton objects or repository companions when used repeatedly across list items.

## 2.2 Pre-indexed Map Lookup for Item Classification in List Composables

**Learning:** Calling list search methods (such as `fullList.any { ... }`) inside item composition blocks of Jetpack Compose `LazyColumn` forces an $O(N)$ linear scan for every rendered item, yielding $O(N^2)$ quadratic scanning during UI scrolling.
**Action:** Hoist pre-indexed lookup maps (such as `remember(list) { list.associateBy { ... } }`) or pass an optional lookup map to classification helpers so list items perform $O(1)$ key lookups during recomposition and scroll passes.
