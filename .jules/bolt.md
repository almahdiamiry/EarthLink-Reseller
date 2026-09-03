## 2.1 Standardized Pre-compiled Regex Patterns in NoteCleaner

**Learning:** Creating `Regex` instances dynamically inside loop iterations or item extraction functions (such as `extractGenuineNote` and `isNoiseOrRedundant` called per item during Jetpack Compose list rendering) incurs unnecessary `Pattern` compilation and garbage collection allocations.
**Action:** Always extract static regular expressions into top-level private `val` constants on singleton objects or repository companions when used repeatedly across list items.

## 2026-09-03 - DateFormat Allocation Avoidance in List Mapping

**Learning:** Instantiating `SimpleDateFormat` inside collection mapping closures (`.map { ... }` or loops) parses format patterns and instantiates calendars repeatedly on every element, causing redundant heap allocations and GC pressure during presentation layer model transformations.
**Action:** Lift `SimpleDateFormat` (or equivalent thread-confined date formatters) outside collection mapping loops to a local variable immediately prior to iteration.
