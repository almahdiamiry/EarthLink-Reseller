## 2025-05-20 - Password Field Visibility Toggle Accessibility
**Learning:** In Jetpack Compose password input fields, icon-only trailing buttons toggling visibility require dynamic state-aware `contentDescription` attributes (handling both state change e.g. Show vs Hide and language localization e.g. English vs Arabic) so screen readers accurately announce the action to visually impaired users.
**Action:** Always provide dynamic, state-aware, localized `contentDescription` strings for password visibility toggle icons.
