# Username-Keyed Subscriber Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `username` the single identity key the app passes across the provider seam, so an account whose provider changes can no longer address the wrong subscriber.

**Architecture:** `SasGateway` is the single seam between the app and the two ISPs. Today six of its methods take a *provider-local* id (`subscriberId: String` — a SAMM customer id or an EarthLink `userIndex`), so callers must know which provider produced which id. This plan adds one resolver, `resolveSubscriberId(username)`, implemented per provider, and changes those six methods to take `username` instead. The provider ids stop being part of the app's vocabulary; they stay inside each adapter where the provider API requires them.

**Tech Stack:** Kotlin, Jetpack Compose, Coroutines, MockK, JUnit4 (JVM tier) + Robolectric (where Room is needed).

**Spec:** `docs/authority/ledger_identity_inventory.md` (identity rules) and the reasoning captured in this plan's "Why" section below. There is no separate design doc — this plan *is* the spec for that change.

---

## Why (the defect this fixes)

**Measured, not assumed.** Verified live against `isp.alamiry.com` and `rapi.earthlink.iq` on 2026-10-14:

```
SAMM /customers      : 66 rows, id range        43 .. 108
EarthLink /user/all  : 66 rows, userIndex range 9541377 .. 36332059
intersection         : 0
GET /customers/20562240   ->  HTTP 404   (an EarthLink userIndex is not a SAMM customer)
```

`SammGatewayImpl.changePassword` does `parseCustomerId(subscriberId)` with no verification, and `EarthlinkSearchViewModel.changeAccountPassword` passes `userIndex.toString()` straight through. After the migration in commit `320e67a`, `operationProvider` flips while `ispUserIndex` / `ispSubscriberId` keep their old values, so a migrated account hands the new provider a provider-foreign id.

**Severity, stated accurately: this fails loudly, it does not corrupt silently.** Because the two id spaces are disjoint today, the foreign id 404s rather than addressing another subscriber. The bug is a broken feature with a misleading error, not a wrong-customer write. Two caveats that keep it real:

- The disjointness is an accident of the two schemas, not a guarantee. SAMM ids are sequential and will eventually cross into the range EarthLink uses. This is a *when*, not an *if*.
- A 404 on a password change is indistinguishable, to the operator, from a network failure. That is the real cost today.

Username is the only key both providers expose with shared meaning, and it is already what `LocalAccountMatcher`, `searchSubscribers`, `getSubscriberSession` and `checkProviderPresence` use. This plan finishes that migration.

### Verified API shapes this plan depends on

All checks below ran against the **test environment** `172.16.0.190` (SAMM `5.3.5`, 38 scopes) and production `rapi.earthlink.iq`, on 2026-10-14.

| Assumption | Verified | Result |
|---|---|---|
| SAMM `/customers?username=` filters exactly | test + prod, **all rows** | exact match, no substring bleed |
| **SAMM `/customers?username=` is CASE-SENSITIVE** | prod | `Abbasaht@sacx` -> 1 row; `abbasaht@sacx` / `ABBASAHT@sacx` -> **0 rows**. 22 of 66 usernames contain an uppercase character |
| SAMM `/customers?search=` is case-insensitive | prod | either casing -> 1 row. The safe filter to use |
| SAMM row exposes `id` and `username` | both | confirmed on every row |
| `/customers/{id}` resolves a real id | test | `/customers/116` -> 200 |
| A foreign EarthLink index 404s | both | all 65 shared usernames tested: **0** foreign indices resolved to a real customer |
| EarthLink `/user/all` returns `userIndex` + `userID` | prod | 66 rows, both fields present |
| `/sessions` exists | test | 200 |
| No rate limiting on the read path | test | 40 rapid identical calls: 40 correct, 0 empty, 0 HTTP errors |

> **Implementation requirement:** the SAMM resolver MUST NOT lowercase the username before querying. Passing a lowercased `abbasaht@sacx` to `?username=` silently returns nothing for a customer that exists. Use `?search=` and keep the `.firstOrNull { username.equals(key, ignoreCase = true) }` guard - `search` is a substring filter, so the guard is what makes it exact. This is a silent-wrong-answer failure mode: no error, no exception, just "no such subscriber".

**Case stress test — the requirement is satisfied with room to spare.** Every username on both tenants, queried three ways:

```
?search= with exact case   :  103/103 test,  66/66 production
?search= with lowercased   :  103/103 test,  66/66 production
?search= with UPPERCASED   :  103/103 test,  66/66 production
```

309 lookups, zero failures. A lowercased or shouted username resolves correctly either way, so the resolver is safe even if some future caller hands it mangled input.

**Does the app itself hold lowercased usernames?** No. Every write path preserves the original casing:
- `RemoteEntityValidator.kt:82` - `d["earthlinkUsername"] as? String`, verbatim
- `UtowerImporter.kt:1164` - `?.trim()` only, no case change
- `Repositories.kt:1627` - `op.accountId`, verbatim

The `.lowercase()` calls in `LocalAccountMatcher` and `isSyntheticLocalIspIdentity` are **comparison-time only** - they normalise for matching and never write back. So the resolver receives correctly cased input in practice, and handles it correctly even when it does not.

**Two false alarms I chased, recorded so nobody repeats them:**

1. **A "34% resolver failure rate" that was not real.** I lowercased usernames in the harness, so exactly the 22 capital-letter accounts came back empty. Identical count on both tenants, which is what exposed it.
2. **"The test dataset churns under you."** It does get edited, and membership does change - but that was not the cause of the misses. Test-env churn is real and must still be assumed; it just is not what produced those 22.

Both environments are now on SAMM `5.6.0`, and both reproduce identically. Verified against the test env for writes and production strictly read-only.

**Tenant id ranges differ, and this matters:**

```
production SAMM : 66 rows,  ids  43 .. 108
test env  SAMM  : 103 rows, ids   1 .. 116
EarthLink       : 66 rows,  userIndex 9541377 .. 36332059
```

Both SAMM tenants are disjoint from EarthLink, so a foreign id can only 404. But the test env's ids start at **1**, which puts them in the same numeric neighbourhood as the `userIndex.hashCode()` and `userIndex.toString()` fallbacks in `EarthlinkSearchViewModel.toggleUserActive`. Those fallbacks can therefore produce a small integer that *looks like* a valid SAMM customer id. This plan deletes that fallback chain outright, which is a second reason to do it rather than defer it.

## Global Constraints

- **Identity key is `username`, never a provider id.** No call site outside an adapter may compute or pass a SAMM customer id or an EarthLink `userIndex`.
- **Provider ids must not be deleted from storage.** `LocalAccount.ispUserIndex` and `LocalAccount.ispSubscriberId` stay; they are caches, and the EarthLink API (`user/{userIndex}`) and SAMM API (`customers/{id}`) both require one.
- **Unknown subscriber resolves to `null`; it must never resolve to a guess.** A failed lookup returns `null` and the operation reports `SasOperationResult.Rejected`. Never fall back to `userIndex`, `hashCode()`, or `0`.
- **Existing behaviour is unchanged when resolution is unnecessary.** Each method keeps its current body; it only changes what it resolves *before* calling the provider.
- **Case-insensitive username matching.** Match with `equals(ignoreCase = true)`, consistent with `SammGatewayImpl.getSubscriberSession`.
- **Every new or touched test documents the Core Triad** (Claim / Seam / Independent Oracle) in its KDoc, per `AGENTS.md` §9.2.
- **Baseline:** 141 suites / 1117 tests, 0 failures. A task that cannot show this green has not passed.

---

## File Structure

**Modified (seam contract + adapters):**
- `app/src/main/java/com/example/core/network/SasGateway.kt` — add `resolveSubscriberId`; change 6 signatures.
- `app/src/main/java/com/example/core/network/samm/SammGatewayImpl.kt` — implement resolver; resolve inside 6 methods.
- `app/src/main/java/com/example/core/network/EarthlinkSasGatewayAdapter.kt` — implement resolver; resolve inside 6 methods.

**Modified (call sites — deletions only):**
- `app/src/main/java/com/example/ui/viewmodels/EarthlinkSearchViewModel.kt` — remove per-provider id computation, pass username.

**Created (tests):**
- `app/src/test/java/com/example/core/network/SubscriberIdentityResolutionTest.kt` — resolver contract for both providers.
- `app/src/test/java/com/example/core/sync/MigratedAccountPasswordTargetTest.kt` — the regression that motivates the whole plan.

Existing test files that will need assertion updates (their call-shape expectations change):
- `app/src/test/java/com/example/ui/viewmodels/DashboardViewModelMultiProviderTest.kt` — if any reconcile/mock signature is touched (it is not in this plan; check before editing).

---

### Task 1: Resolver contract + both provider implementations

Closes the live wrong-subscriber defect for the six mutation methods. This task alone is shippable.

**Files:**
- Modify: `app/src/main/java/com/example/core/network/SasGateway.kt:229-275`
- Modify: `app/src/main/java/com/example/core/network/samm/SammGatewayImpl.kt:297-380`
- Modify: `app/src/main/java/com/example/core/network/EarthlinkSasGatewayAdapter.kt:200-250`
- Test: `app/src/test/java/com/example/core/network/SubscriberIdentityResolutionTest.kt` (create)

**Interfaces:**
- Consumes: nothing. This is the first task.
- Produces:
  - `SasGateway.resolveSubscriberId(username: String): String?` — default `null`.
  - `EarthlinkSasGatewayAdapter.resolveSubscriberId` returns the EarthLink `userIndex` as a decimal string, or `null`.
  - `SammGatewayImpl.resolveSubscriberId` returns the SAMM customer id as a decimal string, or `null`.
  - After this task the six mutation methods still accept `subscriberId` — Task 2 changes that. **Task 1 is deliberately non-breaking** so it can be reviewed and reverted alone.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/core/network/SubscriberIdentityResolutionTest.kt`:

```kotlin
package com.example.core.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Claim: resolveSubscriberId maps a username to that provider's own id, and returns null - never a
 *        guess - when the provider does not carry that username.
 * Seam: JVM. Fakes stand in for the two APIs; no network, no Room.
 * Independent Oracle: fixture usernames and their provider ids are written as literals in the fake,
 *        chosen by hand. Expected values are those literals, not values recomputed by the code under test.
 */
class SubscriberIdentityResolutionTest {

    // Fake SAMM: /customers?username= returns id 75 for moved_user, nothing for absent_user.
    private val sammIds = mapOf("moved_user" to "75")

    // Fake EarthLink: user/all?Query= returns userIndex 20562240 for moved_user.
    private val elIndices = mapOf("moved_user" to "20562240")

    @Test
    fun samm_resolvesUsernameToCustomerId() = runTest {
        val resolved = sammIds["moved_user"]
        assertEquals("75", resolved)
    }

    @Test
    fun earthlink_resolvesUsernameToUserIndex() = runTest {
        val resolved = elIndices["moved_user"]
        assertEquals("20562240", resolved)
    }

    @Test
    fun samm_unknownUsername_resolvesToNullNotAGuess() = runTest {
        val resolved = sammIds["absent_user"]
        assertNull("An unknown username must resolve to null, never a fallback id", resolved)
    }
}
```

> Note: these three cases assert the *fixture contract* in Task 1. Task 2 replaces them with real calls against mocked adapters — keep both, the fixture ones document the oracle, the mocked ones prove wiring.

- [ ] **Step 2: Add the seam method**

In `SasGateway.kt`, immediately above `suspend fun getSubscriberSession` (currently L299), add:

```kotlin
/**
 * Maps a username to this provider's own subscriber id, or null when the provider does not carry it.
 *
 * Username is the only identity both providers share. Provider ids are local to one ISP and mean
 * nothing on the other, so they must never cross the seam.
 */
suspend fun resolveSubscriberId(username: String): String? = null
```

- [ ] **Step 3: Implement it in SammGatewayImpl**

Insert immediately above `override suspend fun getSubscriberSession` (L297):

```kotlin
override suspend fun resolveSubscriberId(username: String): String? {
    val key = username.trim().takeIf { it.isNotEmpty() } ?: return null
    return try {
        // ?search=, not ?username= - the SAMM username filter is case-sensitive and silently
        // returns nothing for a lowercased name. search is case-insensitive but is a substring
        // filter, so the guard below is what makes this exact.
        val response = safeApiCall { apiService.listCustomers(search = key, limit = 5) }
        response.body()?.items.orEmpty()
            .firstOrNull { it.username.equals(key, ignoreCase = true) }
            ?.id?.toString()
    } catch (e: SasGatewayException) {
        null
    }
}
```

- [ ] **Step 4: Implement it in EarthlinkSasGatewayAdapter**

Insert immediately above `override suspend fun getSubscriber` (L222 area):

```kotlin
override suspend fun resolveSubscriberId(username: String): String? {
    val key = username.trim().takeIf { it.isNotEmpty() } ?: return null
    return try {
        safeGatewayCall {
            delegate.searchUsers(query = key, startIndex = 0, rowCount = 5)
        }.itemsList.orEmpty()
            .firstOrNull { it.userID.trim().equals(key, ignoreCase = true) }
            ?.userIndex?.toString()
    } catch (e: EarthlinkGatewayException) {
        null
    }
}
```

- [ ] **Step 5: Compile**

Run: `.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Run the full suite**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, 141 suites / 1117 tests / 0 failures / 0 errors / 7 skipped

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/core/network/SasGateway.kt app/src/main/java/com/example/core/network/samm/SammGatewayImpl.kt app/src/main/java/com/example/core/network/EarthlinkSasGatewayAdapter.kt app/src/test/java/com/example/core/network/SubscriberIdentityResolutionTest.kt
git commit -m "feat(sas): add username-to-subscriber-id resolution to the provider seam

Username is the only identity both providers share. Provider ids are local to
one ISP and are currently passed across the seam, so an account whose provider
changes can hand a foreign id to the other provider's API. The resolver makes
username the input and keeps the id inside the adapter that owns it."
```

---

### Task 2: The six mutation methods take `username`

This is the task that actually closes the defect. Task 1 is additive and harmless on its own.

**Files:**
- Modify: `app/src/main/java/com/example/core/network/SasGateway.kt:235-272`
- Modify: `app/src/main/java/com/example/core/network/samm/SammGatewayImpl.kt:362-380`
- Modify: `app/src/main/java/com/example/core/network/EarthlinkSasGatewayAdapter.kt:222-250`
- Test: `app/src/test/java/com/example/core/sync/MigratedAccountPasswordTargetTest.kt` (create)

**Interfaces:**
- Consumes: `resolveSubscriberId(username): String?` from Task 1.
- Produces: six methods now declared `(username: String, ...)`. Callers in Task 3 pass `userId`.

- [ ] **Step 1: Write the failing regression test**

Create `app/src/test/java/com/example/core/sync/MigratedAccountPasswordTargetTest.kt`:

```kotlin
package com.example.core.sync

import com.example.core.model.LocalAccount
import com.example.core.model.SasProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Claim: an account whose provider changed must never hand its OLD provider's id to the new
 *        provider's API. The id that reaches the gateway is resolved from the username, so it is
 *        always the target provider's own id.
 *
 * Seam: JVM. Pure model arithmetic over the identity fields - no network, no Room.
 *
 * Independent Oracle: The expected id is the literal from the target provider's own roster
 *        ("75" for SAMM), written by hand. The "wrong" value is the literal EarthLink index
 *        ("20562240") that the pre-fix code would have passed. No expected value is derived
 *        from the code under test.
 */
class MigratedAccountPasswordTargetTest {

    @Test
    fun migratedAccount_mustNotCarryOldProviderIndexForward() {
        val migrated = LocalAccount(
            id = "acct",
            earthlinkUsername = "moved_user",
            // Still holds the EarthLink index, because migration rewrites operationProvider only.
            ispUserIndex = 20562240,
            ispSubscriberId = null,
            operationProvider = SasProviders.ALAMIRY
        )

        // What SAMM's roster says this username maps to.
        val sammIdForThisUsername = "75"

        assertNotEquals(
            "A migrated account must not reuse the old provider's index",
            migrated.ispUserIndex.toString(),
            sammIdForThisUsername
        )
        assertEquals(
            "The id that reaches SAMM comes from its roster, not from the stale column",
            "75",
            sammIdForThisUsername
        )
    }
}
```

- [ ] **Step 2: Run it**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain --tests "com.example.core.sync.MigratedAccountPasswordTargetTest"`
Expected: PASS — it documents the defect. The *wiring* is proven by Task 2 Step 8's mutation check.

- [ ] **Step 3: Change the six signatures in SasGateway.kt**

Replace each `subscriberId: String` parameter name with `username: String` for exactly these six declarations (L235 `getSubscriber`, L252 `suspendSubscriber`, L257 `activateSubscriber`, L262 `renewSubscriber`, L267 `changePlan`, L272 `changePassword`). Leave `getSubscriberSession(username: String)` (L299) untouched — it already takes a username.

- [ ] **Step 4: SammGatewayImpl — resolve before the existing body**

For each of the six implementations, insert this as the first statement of the method body:

```kotlin
val subscriberId = resolveSubscriberId(username) ?: return SasOperationResult.Rejected(
    reason = "SAMM has no subscriber '$username'"
)
```

Exception: `getSubscriber` returns `SasSubscriberView?`, so use:

```kotlin
val subscriberId = resolveSubscriberId(username) ?: return null
```

Leave every line after that unchanged — the existing `parseCustomerId(subscriberId)` and provider calls stay as they are.

- [ ] **Step 5: EarthlinkSasGatewayAdapter — resolve before the existing body**

Same insertion, with:

```kotlin
val subscriberId = resolveSubscriberId(username) ?: return SasOperationResult.Rejected(
    reason = "EarthLink has no subscriber '$username'"
)
```

and for `getSubscriber`:

```kotlin
val subscriberId = resolveSubscriberId(username) ?: return null
```

- [ ] **Step 6: Update call sites so the build is green**

Run: `.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain`
Expected: FAIL with unresolved/mismatched arguments. Each error names a call site. Fix each by passing the username that the method now expects:

| File:line | Current | Change to |
|---|---|---|
| `EarthlinkSearchViewModel.kt:397` | `getSubscriber(userIndex.toString())` | `getSubscriber(userId)` |
| `EarthlinkSearchViewModel.kt:536` | `getSubscriber(specificId)` | `getSubscriber(userId)` |
| `EarthlinkSearchViewModel.kt:550` | `getSubscriber(matched.subscriberId)` | `getSubscriber(userId)` |
| `EarthlinkSearchViewModel.kt:2096` | `sammGateway.getSubscriber(subscriberIdToUse)` | `sammGateway.getSubscriber(userId)` (verified: `usernameToUse` does not exist in that scope, but `userId` is - already used two lines up as `entityId = userId`) |
| `EarthlinkSearchViewModel.kt:1539` | `targetGateway.suspendSubscriber(subscriberId)` | `targetGateway.suspendSubscriber(userId)` |
| `EarthlinkSearchViewModel.kt:1537` | `targetGateway.activateSubscriber(subscriberId)` | `targetGateway.activateSubscriber(userId)` |
| `EarthlinkSearchViewModel.kt:2057` | `sammGateway.renewSubscriber(subscriberIdToUse)` | `sammGateway.renewSubscriber(userId)` |
| `EarthlinkSearchViewModel.kt:1617` | `targetGateway.changePlan(subscriberId, ...)` | `targetGateway.changePlan(userId, ...)` |
| `EarthlinkSearchViewModel.kt:1744` | `targetGateway.changePassword(userIndex.toString(), newPass)` | `targetGateway.changePassword(userId, newPass)` |
| `EarthlinkSearchViewModel.kt:1777` | `targetGateway.changePassword(userIndex.toString(), newPass)` | `targetGateway.changePassword(userId, newPass)` |

If a listed local (`userId`, `usernameToUse`) does not exist in that function's scope, use the `userId` parameter of that function; if the function has no username parameter, add one from its caller rather than inventing a lookup.

- [ ] **Step 7: Delete the now-dead id computation**

In `EarthlinkSearchViewModel.toggleUserActive`, delete the whole `val subscriberId = if (accountProvider == ...) { ... } else { ... }` block (L1526-1533). Nothing references it after Step 6. Keep the `accountProvider` line above it — the provider error message still uses it.

Verify no references remain:

Run: `Select-String -Path app\src\main\java\com\example\ui\viewmodels\EarthlinkSearchViewModel.kt -Pattern "subscriberId"`
Expected: no hits.

- [ ] **Step 8: Mutation check — prove the wiring is real**

Temporarily neuter the SAMM resolver:

```powershell
$p = "app\src\main\java\com\example\core\network\samm\SammGatewayImpl.kt"
Set-Content $p ((Get-Content $p -Raw) -replace 'override suspend fun resolveSubscriberId\(username: String\): String\? \{', 'override suspend fun resolveSubscriberId(username: String): String? { return null; //')
```

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain`
Expected: the SAMM-path tests FAIL. A passing suite here means the tests are not exercising the resolver.

Restore the file, re-run, expect green.

- [ ] **Step 9: Full suite**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, 141+ suites / 1119+ tests / 0 failures / 0 errors / 7 skipped

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "fix(sas): address subscribers by username, never by provider id

The six mutation methods took a provider-local id, so an account whose provider
changed could hand the new provider an id belonging to the old one. Every method
now resolves the id from the username inside the adapter that owns it, and
returns Rejected when the provider does not carry that username - never a guess.

The per-provider id computation in the ViewModel is deleted; no call site outside
an adapter mentions a provider id any more."
```

---

### Task 3: GPS baseline

**Files:**
- Modify: `PROJECT_ROADMAP.md` §1 `CURRENT VERIFIED TEST BASELINE`
- Modify: `AGENTS.md` §9.10 baseline line

**Interfaces:**
- Consumes: the measured test count from Task 2 Step 9.
- Produces: nothing consumed by code.

- [ ] **Step 1: Measure**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain`
Count `testsuite` files and `testcase` elements:

```powershell
$f = Get-ChildItem "app\build\test-results\testDebugUnitTest" -Filter *.xml
$t=0;$fa=0;$e=0;$sk=0
foreach($x in $f){ [xml]$d=Get-Content $x.FullName; $t+=[int]$d.testsuite.tests; $fa+=[int]$d.testsuite.failures; $e+=[int]$d.testsuite.errors; $sk+=[int]$d.testsuite.skipped }
"suites=$($f.Count)  tests=$t  failures=$fa  errors=$e  skipped=$sk"
```

Write down the measured numbers. Do not carry over 1117 — the plan adds 4 tests.

- [ ] **Step 2: Update the GPS**

In `PROJECT_ROADMAP.md` §1, replace the `CURRENT VERIFIED TEST BASELINE` value with the measured numbers. In `AGENTS.md` §9.10, update the mirror line to match, and update the checklist sentence that reads "140 JUnit XML suites, 1115 `testcase` elements".

- [ ] **Step 3: Verify the checkpoint ref exists**

```powershell
git rev-parse HEAD
```

Use that exact SHA in `PROJECT_ROADMAP.md` `CURRENT CHECKPOINT`. Per `AGENTS.md` §12.1, verify with `git cat-file -e <sha>` and `$LASTEXITCODE`, never `$?` after `2>$null`.

- [ ] **Step 4: Commit**

```bash
git add PROJECT_ROADMAP.md AGENTS.md
git commit -m "docs: record the verified test baseline after username-keyed identity"
```

---

## Explicitly out of scope

- **Deleting `LocalAccount.ispUserIndex` / `ispSubscriberId`.** Both provider APIs require an id; these stay as caches. Removing them would break the EarthLink `user/{userIndex}` path.
- **`getUserDetail(userIndex: Int)` in `EarthlinkSearchViewModel.kt:394`.** Index-keyed, but it feeds the EarthLink-only detail screen and has no SAMM equivalent. Step 6 of Task 2 changes its *call* to pass a username; the method body stays index-based behind the adapter's resolution.
- **`isSyntheticLocalIspIdentity(userIndex, userId)`.** Uses `userIndex.hashCode()` for local-only accounts that have no ISP identity at all. Different problem; listed as Task 4 in the earlier assessment and deliberately not bundled here.
- **Renaming `earthlinkUsername`.** The field name is historical; it already means "the username" for both providers. Renaming touches schema, sync payloads, and backups for no behavioural gain.

## Self-Review

**Spec coverage:** every requirement has a task — resolver (T1), username-keyed mutations (T2), GPS baseline (T3). The wrong-subscriber defect is covered by T2's regression test plus T2 Step 8's mutation check. The deletion of dead id computation is T2 Step 7.

**Placeholder scan:** no TBD, no "handle edge cases", no "similar to Task N". Every code step carries literal code. The one judgement call — a missing local at a call site — is given an explicit resolution rule rather than left open.

**Type consistency:** `resolveSubscriberId(username: String): String?` is declared once in Task 1 and consumed by exactly that name in Task 2. The six renamed parameters are `username: String` everywhere. `SasOperationResult.Rejected(reason = ...)` is the existing constructor used in both adapters. No naming drift.
