## 2.1 Standardized Pre-compiled Regex Patterns in NoteCleaner

**Learning:** Creating `Regex` instances dynamically inside loop iterations or item extraction functions (such as `extractGenuineNote` and `isNoiseOrRedundant` called per item during Jetpack Compose list rendering) incurs unnecessary `Pattern` compilation and garbage collection allocations.
**Action:** Always extract static regular expressions into top-level private `val` constants on singleton objects or repository companions when used repeatedly across list items.

## 2.2 Hoisting Date Formatters in Compose Item Scope

**Learning:** Instantiating heavy objects like `SimpleDateFormat` inside item scopes of Jetpack Compose `LazyColumn` or iteration blocks (`forEachIndexed`) re-executes pattern compilation, locale initialization, and `Calendar` construction for every rendered list item on every recomposition.
**Action:** Always hoist `SimpleDateFormat` instances outside item iteration scopes using `remember { SimpleDateFormat(...) }` at the parent composable level.
