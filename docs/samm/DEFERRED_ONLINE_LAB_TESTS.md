# Deferred Online Laboratory Tests Registry
## Status: DEFERRED (Live PPPoE / CHR / RADIUS Infrastructure Offline)

This document formally records all tests and behavioral verifications that require live subscriber connectivity, MikroTik CHR routing, RADIUS accounting, or active PPPoE sessions.

Per the **Provider-Independent Login + Deployment Flexibility Plan (Section 0, 2, 4)**:
- SAMM control plane validation, credential isolation, provider login, deployment replacement, and failure containment are **executed now**.
- The tests listed below are **explicitly deferred** until the physical/virtual network access plane (CHR-SERVER, CHR-CLI, PPPoE test subscribers) is brought online.
- **Rule:** A deferred data-plane test is never converted into an architectural blocker, and positive results are never fabricated.

---

## 1. Deferred Data-Plane / CHR Test Matrix

| ID | Operation / Scenario | Required Environment | Reason for Deferral | Planned Verification Once Online |
|:---|:---|:---|:---|:---|
| **DEF-01** | Live PPPoE subscriber session disconnect on `suspend` | SAMM API + active MikroTik CHR + online PPPoE client | CHRs currently offline; RADIUS PoD (Packet of Disconnect) / CoA cannot be verified on the wire. | Verify customer suspension sends RADIUS disconnect to CHR and active PPPoE interface drops immediately. |
| **DEF-02** | PPPoE session reconnect & IP acquisition on `activate` | SAMM API + active MikroTik CHR + PPPoE client | Subscriber client offline; dynamic IP assignment and rate limiting cannot be observed. | Verify suspended user activation allows PPPoE client to re-establish session and obtain route. |
| **DEF-03** | Real-time traffic shaping / rate-limit enforcement after `assign-plan` | Active PPPoE stream through CHR | No physical traffic or bandwidth queue to inspect. | Verify changing plan pushes updated RADIUS rate limits and adjusts MikroTik queue trees in real time. |
| **DEF-04** | Live subscriber session termination on password change | Active PPPoE connection | PPPoE client offline. | Verify password change triggers disconnect or rejects re-authentication on reconnect attempt. |
| **DEF-05** | Real-time online/offline status reflection (`is_online`, IP, MAC) | Active RADIUS accounting (Acct-Status-Type) | CHR accounting daemon offline. | Verify `GET /customers/{id}` accurately reports live IP, MAC address, and uptime from RADIUS accounting packets. |
| **DEF-06** | CHR reboot / outage resilience on active subscriber sessions | MikroTik CHR instance control | CHR virtual machines not provisioned/running in current lab. | Verify app and SAMM recover gracefully when CHR rejoins cluster without duplicate ledger debt. |

---

## 2. Invariants Certified Without Live Data Plane
The following control-plane and application guarantees are **fully certified independently** in the local codebase and lab harness:
1. **Control-Plane Mutation Acceptance:** SAMM accepts `POST /customers/{id}/suspend`, `POST /customers/{id}/activate`, `POST /customers/{id}/renew`, and `PATCH /customers/{id}` without error.
2. **Deterministic Verification Oracles:** The app's `verifyOperationOutcome` accurately evaluates customer status flags (`active` vs `suspended`), expiration dates, and assigned plan IDs returned by the SAMM control plane.
3. **Fail-Closed Financial Isolation:** All mutations explicitly pass `generate_invoice=false` ensuring zero unintended invoice debt.
4. **Provider Login & Independence:** Application opens and operates fully in `SAMM_ONLY` mode, `EARTHLINK_ONLY` mode, or `DUAL` mode without requiring active subscriber sessions.
