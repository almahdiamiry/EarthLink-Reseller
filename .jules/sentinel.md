## 2026-09-29 - Explicit Redirect Disabling on Credential-Posting HTTP Clients
**Vulnerability:** Auxiliary OkHttpClient created inside `refreshEarthlinkToken` omitted `.followRedirects(false).followSslRedirects(false)`, unlike the primary `okHttpClient`.
**Learning:** Auxiliary or helper HTTP clients used for authentication or token refreshing can bypass primary security policies if redirect settings are not mirrored.
**Prevention:** Always explicitly configure `.followRedirects(false)` and `.followSslRedirects(false)` across all HTTP client instances handling credentials or sensitive payload transfers.
