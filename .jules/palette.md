## 2026-05-02 - Icon-Action Semantic Alignment in Dialog Top Bars
**Learning:** In Compose UI, action buttons in dialogs or cards may sometimes copy code or icon assets from other toolbars (e.g., using `Icons.Default.Settings` instead of `Icons.Default.ContentCopy`), leading to screen-reader or visual mismatches where `contentDescription` says "Copy message text" but the icon displays a gear/settings image.
**Action:** Always visually align icon vector graphics with their corresponding `contentDescription` and click handlers in Compose UI screens.

## 2026-05-03 - RTL Layout Direction & Language Flow Observation in Standalone Compose Screens
**Learning:** Standalone navigation destination screens in Jetpack Compose that do not observe user language preferences from `PreferenceManager.languageFlow` may fall back to system layout direction, resulting in LTR rendering and English TalkBack accessibility announcements even when the user selected Arabic in app preferences.
**Action:** Use `(context.applicationContext as? EarthlinkApp)?.preferenceManager` with safe null fallback, observe `languageFlow`, and wrap the screen layout in `CompositionLocalProvider(LocalLayoutDirection provides (if (isAr) LayoutDirection.Rtl else LayoutDirection.Ltr))`.
