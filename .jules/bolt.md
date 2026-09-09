## 2.1 Standardized Pre-compiled Regex Patterns in NoteCleaner

**Learning:** Creating `Regex` instances dynamically inside loop iterations or item extraction functions (such as `extractGenuineNote` and `isNoiseOrRedundant` called per item during Jetpack Compose list rendering) incurs unnecessary `Pattern` compilation and garbage collection allocations.
**Action:** Always extract static regular expressions into top-level private `val` constants on singleton objects or repository companions when used repeatedly across list items.

## 2026-09-08 - Hoisting SimpleDateFormat in Compose Lists

**Learning:** Constructing `SimpleDateFormat` instances directly inside `LazyColumn` items rendering blocks or list `forEach` iterations causes repetitive allocation of heavy date formatting objects during scrolling and recomposition.
**Action:** Always hoist `SimpleDateFormat` instantiations above list rendering loops using `remember { SimpleDateFormat(...) }` at composable scope to prevent GC allocation jank on scroll.
