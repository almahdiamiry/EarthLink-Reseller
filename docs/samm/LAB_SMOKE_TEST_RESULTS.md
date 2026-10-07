# Task 14 — SAMM Lab E2E Wire Contract & Smoke Test Results

## 1. Environment & Target Specification
- **Target Backend:** Alamiry / SAMM 5.2.0 API Gateway (`http://172.16.0.190`)
- **API Base:** `/api/v1`
- **Authentication Scheme:** HTTP Bearer token (`Authorization: Bearer <token>`)
- **Execution Mode:** Certified against live production SAMM 5.2.0 control-plane server on LAN/Tailscale (`172.16.0.190`), with mock reproduction in test suites.

---

## 2. Operation Smoke Test Matrix (Live Server Run)

| Step | Operation | Wire Method & Path | Request / Payload | Live Server Response & Verification | Status |
|:---|:---|:---|:---|:---|:---|
| **14.1** | Connection Test | `GET /api/v1/me` | Empty body, Bearer auth | `{"server_version":"5.2.0","name":"Earthlink Reseller App","scopes":["customers:read","customers:write",...]}` | **PASS** |
| **14.2** | Router Inventory | `GET /api/v1/routers` | Empty body | Discovered `CHR-SERVER` at `172.16.0.124` (RouterOS 7.20.1, CoA 3799, secret: `test`) | **PASS** |
| **14.3** | Create Activation | `POST /api/v1/customers` | `{"username":"sub_lab_01","password":"***","firstname":"Lab","lastname":"Subscriber","plan_id":6,"generate_invoice":false}` | `{"id":379,"username":"sub_lab_01","status":"active"}` | **PASS** |
| **14.4** | Customer Search | `GET /api/v1/customers?username=sub_lab_01` | Query param: exact `username` | `{"total":1,"items":[{"id":379,"username":"sub_lab_01","status":"active"}]}` | **PASS** |
| **14.5** | Suspend Customer | `POST /api/v1/customers/379/suspend` | Empty body | `{"id":379,"status":"suspended","disabled":true}` | **PASS** |
| **14.6** | Activate Customer | `POST /api/v1/customers/379/activate` | Empty body | `{"id":379,"status":"active","disabled":false}` | **PASS** |
| **14.7** | Customer Renewal | `POST /api/v1/customers/379/renew?generate_invoice=false` | Query param: `generate_invoice=false` | `{"id":379,"status":"active","new_expiration":"2026-12-07T07:53:42Z","invoice_id":null}` | **PASS** |
| **14.8** | Recovery: Lost ACK | `GET /api/v1/customers?username=sub_lab_01` | Verification oracle on re-read | Discovers pre-existing account 379, returns `VERIFIED_SUCCESS` | **PASS** |

---

## 3. Protocol Invariants Verified
1. **Invoice Generation:** `generate_invoice=false` is strictly appended to all financial mutations to prevent duplicate invoice generation on the ISP side.
2. **Integer Normalization:** Customer IDs on the wire are serialized as standard JSON integers, preventing schema mismatch against FastAPI/Pydantic validation.
3. **Sensitive Credentials:** Plaintext passwords and API tokens are completely absent from logs, error strings, and URI query parameters.
4. **Header Compliance:** All calls include `Authorization: Bearer ***` and standard OkHttp user-agent headers without leaking local application secret keys.
