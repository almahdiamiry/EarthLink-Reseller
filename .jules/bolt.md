## 2.1 Standardized Pre-compiled Regex Patterns in NoteCleaner

**Learning:** Creating `Regex` instances dynamically inside loop iterations or item extraction functions (such as `extractGenuineNote` and `isNoiseOrRedundant` called per item during Jetpack Compose list rendering) incurs unnecessary `Pattern` compilation and garbage collection allocations.
**Action:** Always extract static regular expressions into top-level private `val` constants on singleton objects or repository companions when used repeatedly across list items.

## 2026-03-31 - Hoisting SimpleDateFormat Allocations in Compose List Composables

**Learning:** Instantiating `SimpleDateFormat` inside Jetpack Compose list rendering callbacks or loop iterations (such as `LazyColumn` `items` or `.forEach` blocks) causes repeated creation of heavy `SimpleDateFormat`, `Calendar`, and `DateFormatSymbols` objects on every scroll and recomposition frame, generating GC pressure and UI frame lag.
**Action:** Always hoist date formatters outside list item rendering callbacks using `remember { SimpleDateFormat(...) }` at composable scope so formatters are instantiated once per composable lifecycle.
