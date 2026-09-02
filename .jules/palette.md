## 2026-05-02 - Icon-Action Semantic Alignment in Dialog Top Bars
**Learning:** In Compose UI, action buttons in dialogs or cards may sometimes copy code or icon assets from other toolbars (e.g., using `Icons.Default.Settings` instead of `Icons.Default.ContentCopy`), leading to screen-reader or visual mismatches where `contentDescription` says "Copy message text" but the icon displays a gear/settings image.
**Action:** Always visually align icon vector graphics with their corresponding `contentDescription` and click handlers in Compose UI screens.

## 2026-05-18 - State-Aware & Localized Password Toggle Accessibility
**Learning:** In Jetpack Compose `OutlinedTextField`s, setting `contentDescription = null` on password visibility toggle `IconButton`s leaves TalkBack screen readers announcing an unlabelled "Button, double tap to activate" without communicating the toggle state or action.
**Action:** Always provide state-aware and language-aware `contentDescription` strings (e.g., "Show password" / "Hide password" in English/Arabic) for password visibility trailing icon buttons.
