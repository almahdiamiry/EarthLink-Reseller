# Provider Independent Login & Deployment Flexibility Scenarios

## 1. Executive Summary & Purpose
This document specifies and certifies the behavior of the EarthLink Reseller application operating in **Provider-Independent Mode** post-SAMM integration.

It operationalizes the requirements of:
1. Provider-independent application session unlock (`NONE`, `EARTHLINK_ONLY`, `SAMM_ONLY`, `BOTH`).
2. Completely decoupled credential lifecycles between EarthLink Gateway and SAMM (Al-Amiry) Gateway.
3. Zero-loss provider removal and deployment replacement safety.
4. Gating EarthLink-specific financial metrics (such as the Reseller Balance capsule) when operating in SAMM-only mode.
5. Explicit preservation of data integrity invariants (RED Invariants 1–10).

---

## 2. Core State Machine: `ProviderAccessState`

The application session is governed by `ProviderAccessState`:

| Provider Access State | EarthLink Configured | SAMM Configured | App Unlocked (`isAppUnlocked`) | Reseller Balance Displayed | ISP Warning Displayed |
|:---|:---:|:---:|:---:|:---:|:---:|
| `NONE` | ❌ No | ❌ No | ❌ Locked (`LoginScreen`) | N/A | N/A |
| `EARTHLINK_ONLY` | ✅ Yes | ❌ No | ✅ Unlocked | ✅ Yes | ✅ Yes (if credentials empty) |
| `SAMM_ONLY` | ❌ No | ✅ Yes | ✅ Unlocked | ❌ Hidden | ❌ Hidden |
| `BOTH` | ✅ Yes | ✅ Yes | ✅ Unlocked | ✅ Yes | ✅ Yes (if credentials empty) |

### Key Session Rule:
```text
At least one supported provider configured
    → Application unlocks and is usable (isAppUnlocked == true)

Provider credential error or clearing of one provider
    → Does NOT cascade into logging out the other provider
```

---

## 3. Credential Lifecycle Matrix

| Operation | EarthLink State | SAMM State | Outcome & Session Transition |
|:---|:---|:---|:---|
| **Operator logs into EarthLink** | Configured | Unconfigured | Transitions `NONE` $\rightarrow$ `EARTHLINK_ONLY`. App unlocks. |
| **Operator logs into SAMM** | Unconfigured | Configured | Transitions `NONE` $\rightarrow$ `SAMM_ONLY`. App unlocks. |
| **Operator adds SAMM to EarthLink** | Configured | Configured | Transitions `EARTHLINK_ONLY` $\rightarrow$ `BOTH`. Both available. |
| **Operator removes EarthLink** | Cleared | Configured | Transitions `BOTH` $\rightarrow$ `SAMM_ONLY`. App remains unlocked! SAMM credentials untouched. |
| **Operator removes SAMM** | Configured | Cleared | Transitions `BOTH` $\rightarrow$ `EARTHLINK_ONLY`. App remains unlocked! EarthLink credentials untouched. |
| **Operator replaces SAMM server/token** | Untouched | Updated | Config version increments (`sammConfigVersionFlow`). `SasGatewayRouter` invalidates client. |
| **Operator clears all credentials** | Cleared | Cleared | Transitions to `NONE`. App locks and returns to `LoginScreen`. |

---

## 4. Domain Account Identity & Provider Independence

### Invariant Guarantee:
1. **Login Provider $\neq$ Account Provider:**
   The active login session tab or configured provider in `Settings` does **not** change `LocalAccount.operationProvider` for existing accounts.
2. **Provider Removal Safety:**
   Removing SAMM or EarthLink credentials leaves Room records untouched:
   - `LocalAccount` records remain intact with their existing `operationProvider`.
   - `LocalLedgerEntry` financial history remains immutable.
   - `PendingExternalOperation` records retain their assigned `operationProvider` and fail closed during dispatch if their target provider credentials are removed, preventing any cross-provider fallback.
3. **Zero Cross-Provider Fallback:**
   An operation assigned to `ALAMIRY` will **never** dispatch to EarthLink, even if SAMM is unreachable, offline, or has had its credentials removed.

---

## 5. Automated Verification Evidence

The behavior described in this specification is continuously verified by:
- [`ProviderIndependentLoginTest.kt`](../../app/src/test/java/com/example/ui/viewmodels/ProviderIndependentLoginTest.kt) (8 unit & lifecycle tests under Robolectric)
- [`PreferenceManagerSammTest.kt`](../../app/src/test/java/com/example/core/security/PreferenceManagerSammTest.kt) (7 unit tests for EncryptedSharedPreferences)
- [`AdversarialIntegrationKitTest.kt`](../../app/src/test/java/com/example/ui/viewmodels/AdversarialIntegrationKitTest.kt) (13 tests verifying zero fallback, pending immutability, and credential isolation)
- Full suite execution: `1027/1027` tests passing (`BUILD SUCCESSFUL`).
- Build verification: `assembleDebug` clean.
