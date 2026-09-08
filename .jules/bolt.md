## 2.1 Standardized Pre-compiled Regex Patterns in NoteCleaner

**Learning:** Creating `Regex` instances dynamically inside loop iterations or item extraction functions (such as `extractGenuineNote` and `isNoiseOrRedundant` called per item during Jetpack Compose list rendering) incurs unnecessary `Pattern` compilation and garbage collection allocations.
**Action:** Always extract static regular expressions into top-level private `val` constants on singleton objects or repository companions when used repeatedly across list items.

## 2.2 Hoisting SimpleDateFormat Allocations in Jetpack Compose Composables

**Learning:** Instantiating `java.text.SimpleDateFormat` inside Jetpack Compose list rendering blocks (`LazyColumn` / `items`) causes repeated object allocations and garbage collection overhead during list scrolling.
**Action:** Always hoist `SimpleDateFormat` creation outside list item callbacks using `remember { SimpleDateFormat(...) }` at composable scope.
