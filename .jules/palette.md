## 2026-05-02 - Icon-Action Semantic Alignment in Dialog Top Bars
**Learning:** In Compose UI, action buttons in dialogs or cards may sometimes copy code or icon assets from other toolbars (e.g., using `Icons.Default.Settings` instead of `Icons.Default.ContentCopy`), leading to screen-reader or visual mismatches where `contentDescription` says "Copy message text" but the icon displays a gear/settings image.
**Action:** Always visually align icon vector graphics with their corresponding `contentDescription` and click handlers in Compose UI screens.

## 2026-05-03 - RTL Layout Direction & Language Flow Observation in Standalone Compose Screens
**Learning:** Standalone navigation destination screens in Jetpack Compose that do not observe user language preferences from `PreferenceManager.languageFlow` may fall back to system layout direction, resulting in LTR rendering and English TalkBack accessibility announcements even when the user selected Arabic in app preferences.
**Action:** Use `(context.applicationContext as? EarthlinkApp)?.preferenceManager` with safe null fallback, observe `languageFlow`, and wrap the screen layout in `CompositionLocalProvider(LocalLayoutDirection provides (if (isAr) LayoutDirection.Rtl else LayoutDirection.Ltr))`.

## 2026-05-04 - Complete Localization Consistency across Top-Level Cards and Action Buttons
**Learning:** In multi-language Compose screens observing `languageFlow`, partial localization where lower content blocks (e.g. audit logs) are translated while top status cards and action buttons remain in hardcoded English creates a confusing hybrid-language UI for Arabic operators. Additionally, `SimpleDateFormat` used in date labels must be hoisted with `remember` to prevent GC allocation jank on recompositions.
**Action:** Always check all text nodes, status mappings, and action buttons in a composable for `currentLang == "ar"` checks and hoist `SimpleDateFormat` allocations using `remember`.

## 2026-05-05 - Dynamic Layout Direction & Status Grid Localization in Standalone Screens
**Learning:** Standalone Compose status and dashboard screens (such as `DashboardStatusScreen`) that observe `languageFlow` must dynamically condition `LocalLayoutDirection` on `isAr` (`if (isAr) LayoutDirection.Rtl else LayoutDirection.Ltr`) and utilize both `titleAr` and `titleEn` from domain status filter enums (`DashboardStatusFilter`). Hardcoding `LocalLayoutDirection` to `LayoutDirection.Rtl` or referencing only `filter.titleAr` forces English users into a reversed RTL layout with Arabic card headers.
**Action:** Always wrap top-level layout trees in `CompositionLocalProvider(LocalLayoutDirection provides (if (isAr) LayoutDirection.Rtl else LayoutDirection.Ltr))` and select `filter.titleAr` / `filter.titleEn` based on `isAr`.
