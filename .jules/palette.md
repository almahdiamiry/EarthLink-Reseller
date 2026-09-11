## 2026-05-02 - Icon-Action Semantic Alignment in Dialog Top Bars
**Learning:** In Compose UI, action buttons in dialogs or cards may sometimes copy code or icon assets from other toolbars (e.g., using `Icons.Default.Settings` instead of `Icons.Default.ContentCopy`), leading to screen-reader or visual mismatches where `contentDescription` says "Copy message text" but the icon displays a gear/settings image.
**Action:** Always visually align icon vector graphics with their corresponding `contentDescription` and click handlers in Compose UI screens.

## 2026-05-02 - Localized Content Descriptions and Layout Direction on Secondary Composable Screens
**Learning:** Secondary or standalone composable screens (such as detail or billing screens) that do not inherit `CompositionLocalProvider(LocalLayoutDirection provides ...)` from a top-level parent wrapper will render in default LTR and fall back to hardcoded English `contentDescription` strings unless explicitly bound to `EarthlinkApp.preferenceManager`'s `languageFlow`.
**Action:** Wrap secondary screens in `CompositionLocalProvider(LocalLayoutDirection provides (if (isAr) LayoutDirection.Rtl else LayoutDirection.Ltr))` and pass `isAr`-based localized strings to `contentDescription` on all `IconButton`s.
