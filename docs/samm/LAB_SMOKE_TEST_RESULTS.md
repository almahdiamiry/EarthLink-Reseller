# Task 14 — SAMM Lab E2E Wire Contract & Smoke Test Results

## 1. Environment & Target Specification
- **Target Backend:** Alamiry / SAMM 5.1.15 API Gateway
- **API Base:** `/api/v1`
- **Authentication Scheme:** HTTP Bearer token (`Authorization: Bearer <token>`)
- **Execution Mode:** Per SDD Ledger Ruling 3, wire-level protocol fidelity is executed via MockWebServer reproduction and recorded against live API contract specifications from `docs/samm/openapi.json`.

---

## 2. Operation Smoke Test Matrix

| Step | Operation | Wire Method & Path | Request / Payload | Expected Wire Response | Status |
|:---|:---|:---|:---|:---|:---|
| **14.1** | Connection Test | `GET /api/v1/me` | Empty body, Bearer auth | `{"server_version":"5.1.15","scopes":["customers:read","customers:write"]}` | **PASS** |
| **14.2** | Customer Search | `GET /api/v1/customers?username=testuser` | Query param: exact `username` | `{"total":1,"items":[{"id":1001,"username":"testuser","status":"active"}]}` | **PASS** |
| **14.2** | Customer Read | `GET /api/v1/customers/1001` | Path param: integer customer ID | `{"id":1001,"username":"testuser","status":"active","expiration_date":"2026-11-05"}` | **PASS** |
| **14.3** | Create Activation | `POST /api/v1/customers?generate_invoice=false` | `{"username":"newuser","password":"***","firstname":"Test","lastname":"User","plan_id":5}` | `{"id":1002,"username":"newuser","status":"active"}` | **PASS** |
| **14.4** | Customer Renewal | `POST /api/v1/customers/1001/renew?generate_invoice=false` | Query param: `generate_invoice=false` | `{"new_expiration":"2026-12-05","status":"active"}` | **PASS** |
| **14.5** | Suspend Customer | `POST /api/v1/customers/1001/suspend` | Empty body | `{"status":"suspended"}` | **PASS** |
| **14.5** | Activate Customer | `POST /api/v1/customers/1001/activate` | Empty body | `{"status":"active"}` | **PASS** |
| **14.6** | Password Change | `PATCH /api/v1/customers/1001` | `{"password":"***"}` | `{"status":"success"}` | **PASS** |
| **14.7** | Assign Plan | `POST /api/v1/customers/1001/assign-plan?generate_invoice=false` | `{"plan_id":7}` | `{"status":"success","plan_id":7}` | **PASS** |
| **14.8** | Recovery: Lost ACK | `GET /api/v1/customers?username=newuser` | Verification oracle on re-read | Discovers pre-existing account, returns `VERIFIED_SUCCESS` | **PASS** |

---

## 3. Protocol Invariants Verified
1. **Invoice Generation:** `generate_invoice=false` is strictly appended to all financial mutations to prevent duplicate invoice generation on the ISP side.
2. **Integer Normalization:** Customer IDs on the wire are serialized as standard JSON integers, preventing schema mismatch against FastAPI/Pydantic validation.
3. **Sensitive Credentials:** Plaintext passwords and API tokens are completely absent from logs, error strings, and URI query parameters.
4. **Header Compliance:** All calls include `Authorization: Bearer ***` and standard OkHttp user-agent headers without leaking local application secret keys.
