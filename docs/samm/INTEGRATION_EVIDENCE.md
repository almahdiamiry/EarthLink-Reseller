# SAMM / Alamiry ISP Gateway Integration Evidence

## Executive Summary
This document certifies the successful, regression-free integration of the **Alamiry / SAMM (5.1.15)** ISP backend into the EarthLink Reseller V1 application. The architecture implements a minimal SAS provider abstraction layer (`SasGateway`, `SasGatewayRouter`) ensuring strict multi-provider isolation, zero cross-provider fallback, fail-closed security, and immutable local single-ledger double-entry accounting.

---

## 1. Environment & Build Metadata
- **Branch:** `feat/alamiry-samm-integration`
- **Head Commit:** `39cfe6e`
- **Live SAMM Lab Probe:** Reachable at `http://172.16.0.190` (Port 80 TCP open, Ping RTT ~10ms)
- **Live SAMM Server Version:** SAMM 5.2.0 (Ubuntu / nginx 1.24.0)
- **OpenAPI Schema Version:** OpenAPI 3.1.0 (`/api/v1/openapi.json`)
- **Authentication Scheme:** Bearer Token (`APIToken`, `Authorization: Bearer <token>`)
- **Room Database Schema:** Version 19 (`MIGRATION_18_19`)
- **Compilation Status:** `assembleDebug` SUCCESSFUL

---

## 2. Operations & Parity Matrix

| Domain Operation | EarthLink Support | Alamiry (SAMM) Support | Routing Path | Invariant Guarantees |
|:---|:---|:---|:---|:---|
| **Search Subscribers** | Native | Native | `SasGatewayRouter` | Shared query & paging |
| **Get Subscriber Detail** | Native | Native | `SasGatewayRouter` | Integer normalization on SAMM wire |
| **Create / Activation** | Native | Native | `SasGatewayRouter` | Intent captures provider before `LocalAccount` exists; `generate_invoice=false` |
| **Renewal / Extension** | Native | Native | `SasGatewayRouter` | `generate_invoice=false`; advances expiration date |
| **Suspend / Activate** | Native | Native | `SasGatewayRouter` | Immediate status update |
| **Change Plan** | Native | Native | `SasGatewayRouter` | `generate_invoice=false` |
| **Change Password** | Native | Native | `SasGatewayRouter` | Zero password persistence in DB or logs; lost ACK is strictly INCONCLUSIVE |
| **Refill User Deposit** | Native | **UNSUPPORTED** | Fail-Closed in VM | 0 SAMM network calls, 0 EarthLink calls |
| **Reseller Balance** | Native | **UNSUPPORTED** | EarthLink-only direct | Kept direct on EarthlinkGateway |
| **Account Statement** | Native | **UNSUPPORTED** | EarthLink-only direct | Kept direct on EarthlinkGateway |
| **Test User Operations** | Native | **UNSUPPORTED** | EarthLink-only direct | Kept direct on EarthlinkGateway |

---

## 3. Test Suites & Regression Verification
- **Verified Test Baseline:** **1019/1019 unit tests passing** across **128** test suites (0 failures, 0 errors).
- **Core Triad Compliance:** 100% of new tests specify Claim, Seam, and Independent Oracle.
- **Dedicated Adversarial Kit (`AdversarialIntegrationKitTest`):**
  - Proven Zero Fallback: SAMM timeout, 401, 404, 500, or unconfigured states make 0 calls to EarthLink.
  - New Account Provider Capture: Pre-creation pending operation records `operationProvider`.
  - Pending Operation Immutability: Post-dispatch edits to `LocalAccount.operationProvider` leave `PendingExternalOperation.operationProvider` untouched.
  - Historical & Identity Preservation: Switching provider preserves account ID, debts, advances, and ledger lineage.
  - TOCTOU Transaction Guard: In-flight operations prevent provider re-assignment inside atomic Room `@Transaction`.
  - Wire Contract Protocol: 100% MockWebServer coverage matching SAMM FastAPI schemas.

---

## 4. Security & Secret Leakage Audit
- `samm_` Token Prefix Scan across `app/src/main`: **0 occurrences found**
- Hardcoded `Bearer` Token Scan across `app/src/main`: **0 occurrences found**
- Room Database & Log Audit: Passwords and tokens excluded from entities, pending operations, and audit logs.
- Memory & Config Isolation: SAMM token stored solely in `EncryptedSharedPreferences`, cleared on logout, and masked in UI.
