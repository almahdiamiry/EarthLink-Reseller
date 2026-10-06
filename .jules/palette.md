## 2026-05-02 - Icon-Action Semantic Alignment in Dialog Top Bars
**Learning:** In Compose UI, action buttons in dialogs or cards may sometimes copy code or icon assets from other toolbars (e.g., using `Icons.Default.Settings` instead of `Icons.Default.ContentCopy`), leading to screen-reader or visual mismatches where `contentDescription` says "Copy message text" but the icon displays a gear/settings image.
**Action:** Always visually align icon vector graphics with their corresponding `contentDescription` and click handlers in Compose UI screens.

## 2026-05-03 - RTL Layout Direction & Language Flow Observation in Standalone Compose Screens
**Learning:** Standalone navigation destination screens in Jetpack Compose that do not observe user language preferences from `PreferenceManager.languageFlow` may fall back to system layout direction, resulting in LTR rendering and English TalkBack accessibility announcements even when the user selected Arabic in app preferences.
**Action:** Use `(context.applicationContext as? EarthlinkApp)?.preferenceManager` with safe null fallback, observe `languageFlow`, and wrap the screen layout in `CompositionLocalProvider(LocalLayoutDirection provides (if (isAr) LayoutDirection.Rtl else LayoutDirection.Ltr))`.

## 2026-05-04 - Complete Localization Consistency across Top-Level Cards and Action Buttons
**Learning:** In multi-language Compose screens observing `languageFlow`, partial localization where lower content blocks (e.g. audit logs) are translated while top status cards and action buttons remain in hardcoded English creates a confusing hybrid-language UI for Arabic operators. Additionally, `SimpleDateFormat` used in date labels must be hoisted with `remember` to prevent GC allocation jank on recompositions.
**Action:** Always check all text nodes, status mappings, and action buttons in a composable for `currentLang == "ar"` checks and hoist `SimpleDateFormat` allocations using `remember`.

## 2026-10-06 - Localized Confirm Action Button Labels in Custom Compose Modal Dialogs
**Learning:** Custom Jetpack Compose modal dialogs (such as `ConfirmationDialog`) that accept custom content must provide an optional explicit confirm label parameter or check `currentLang == "ar"` to render localized action button text (e.g. "تأكيد" / "Confirm"), avoiding hardcoded English literals like "Save" in multi-language interfaces.
**Action:** Always accept an optional `confirmLabel: String? = null` parameter in modal dialog components and fall back to localized defaults like `confirmLabel ?: (if (isAr) "تأكيد" else "Confirm")`.
