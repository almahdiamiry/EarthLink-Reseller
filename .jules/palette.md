## 2026-05-02 - Icon-Action Semantic Alignment in Dialog Top Bars
**Learning:** In Compose UI, action buttons in dialogs or cards may sometimes copy code or icon assets from other toolbars (e.g., using `Icons.Default.Settings` instead of `Icons.Default.ContentCopy`), leading to screen-reader or visual mismatches where `contentDescription` says "Copy message text" but the icon displays a gear/settings image.
**Action:** Always visually align icon vector graphics with their corresponding `contentDescription` and click handlers in Compose UI screens.

## 2026-05-03 - State-Aware Localized Accessibility Descriptions for Password Toggles
**Learning:** In Compose UI OutlinedTextField password trailing icons, setting `contentDescription = null` leaves interactive password visibility toggles unlabelled for screen reader users (TalkBack).
**Action:** Always provide state-aware, localized `contentDescription`s (e.g. "Show password" / "Hide password") reflecting the control's current state and active language preference.
