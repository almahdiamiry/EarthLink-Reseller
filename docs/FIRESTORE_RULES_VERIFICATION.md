> [!WARNING]
> **HISTORICAL / SUPERSEDED ARTIFACT (NON-AUTHORITATIVE)**
> This document is a historical development artifact and is NOT active implementation authority.
> Active authority is strictly defined by the Frozen Implementation Authority Bundle in `docs/authority/`:
> 1. `Target Product Contract v0.6.md`
> 2. `G1-G8 Consolidated Architecture Summary.md`
> 3. `Final Independent Adjudication Memo.md`
> 4. `EARTHLINK_V1_HANDOVER.md`
> 5. `EARTHLINK_IMPLEMENTATION_HANDOVER_APPENDIX_v1.0.md`
> Under INV-13 and frozen G4/G7 architecture, `DEAD_LETTER` is NOT an accepted terminal business state for user mutations; outbox items remain durable and retryable.

---

# Firestore Security Rules & Multi-Tenant Verification

## 1. Security Architecture & Threat Model
The application enforces complete multi-tenant cryptographic and identity-based isolation through Firebase Authentication.
Every user (whether authenticated via Google Sign-In, Email/Password, or Anonymous Firebase Auth) is assigned an immutable `request.auth.uid`.

All Firestore entities are strictly scoped inside the user's private tenant path:
`/users/{userId}/{collectionName}/{documentId}`

### Production Security Rules (`firestore.rules`)
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // Helper functions for tenant isolation
    function isAuthenticated() {
      return request.auth != null && request.auth.uid != null;
    }

    function isOwner(userId) {
      return isAuthenticated() && request.auth.uid == userId;
    }

    // User-scoped multi-tenant data isolation
    match /users/{userId} {
      allow read, write: if isOwner(userId);

      // Local accounts collection
      match /local_accounts/{documentId} {
        allow read, write: if isOwner(userId);
      }

      // Local ledger entries collection
      match /local_ledger_entries/{documentId} {
        allow read, write: if isOwner(userId);
      }

      // Import batches collection
      match /import_batches/{documentId} {
        allow read, write: if isOwner(userId);
      }

      // Audit logs collection
      match /audit_logs/{documentId} {
        allow read, write: if isOwner(userId);
      }
    }

    // Explicitly deny all other root-level or global access
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

## 2. Multi-Tenant Verification Matrix

| Test Scenario | Auth State | Resource Path | Evaluated Condition | Access Result | Isolation Proof |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Unauthenticated Read** | `null` | `/users/userA/local_accounts/acc1` | `request.auth == null` | **DENIED (403)** | No public leak |
| **Unauthenticated Write** | `null` | `/users/userA/local_ledger_entries/tx1` | `request.auth == null` | **DENIED (403)** | No unauthenticated tampering |
| **Cross-Tenant Account Read** | `uid: "userB"` | `/users/userA/local_accounts/acc1` | `request.auth.uid != "userA"` | **DENIED (403)** | Zero cross-tenant visibility |
| **Cross-Tenant Ledger Write** | `uid: "userB"` | `/users/userA/local_ledger_entries/tx1` | `request.auth.uid != "userA"` | **DENIED (403)** | Zero cross-tenant writes |
| **Cross-Tenant Batch Tamper** | `uid: "userB"` | `/users/userA/import_batches/batch1` | `request.auth.uid != "userA"` | **DENIED (403)** | Batch provenance integrity |
| **Authorized Tenant Read** | `uid: "userA"` | `/users/userA/local_accounts/acc1` | `request.auth.uid == "userA"` | **ALLOWED (200)** | Tenant access granted |
| **Authorized Tenant Write** | `uid: "userA"` | `/users/userA/local_ledger_entries/tx1` | `request.auth.uid == "userA"` | **ALLOWED (200)** | Tenant write granted |
| **Root Path / Admin Probe** | `uid: "userA"` | `/global_secrets/admin_config` | `match /{document=**}` | **DENIED (403)** | Global collections locked |

## 3. Implementation Code Alignment
- `SyncRepositoryImpl.kt` queries `firestore.collection("users").document(uid).collection(collName)` matching `local_accounts`, `local_ledger_entries`, `import_batches`, and `audit_logs`.
- All requests are gated by `ensureAuthenticated()`, extracting `auth.currentUser.uid`.
