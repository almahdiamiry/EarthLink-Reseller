package com.example

import com.example.core.model.LocalAccount
import com.example.core.sync.SubscriberMatcher
import org.junit.Assert.*
import org.junit.Test

/**
 * Claim: WS7 / ID-02, ID-03, ID-07 — Stage 3 matching must reject candidates with conflicting usernames,
 * and UtowerImporter must not silently link ambiguous phone/name candidates (INV-09 / RED-9).
 * Seam: JVM headless matcher execution.
 * Independent Oracle: Invariant RED-9 (Identity & Provenance Integrity).
 */
class Workstream7ImportMatchingCollisionTest {

    @Test
    fun subscriberMatcher_stage3_rejectsConflictingUsername() {
        val candidate = LocalAccount(
            id = "acc_001",
            earthlinkUsername = "user_existing_b",
            displayName = "User B",
            phone1 = "07701234567"
        )

        // Incoming record has username "user_incoming_a" but identical phone "07701234567"
        val matched = SubscriberMatcher.matchSubscriber(
            candidates = listOf(candidate),
            username = "user_incoming_a",
            phone = "07701234567"
        )

        assertNull("Stage 3 phone match must reject candidate with conflicting username", matched)
    }

    @Test
    fun subscriberMatcher_stage3_matchesWhenUsernameNotConflicting() {
        val candidate = LocalAccount(
            id = "acc_002",
            earthlinkUsername = null, // Local-only without ISP username
            displayName = "User No Username",
            phone1 = "07701234567"
        )

        val matched = SubscriberMatcher.matchSubscriber(
            candidates = listOf(candidate),
            username = "user_incoming_a",
            phone = "07701234567"
        )

        assertNotNull("Stage 3 phone match succeeds when candidate has no conflicting username", matched)
        assertEquals("acc_002", matched?.id)
    }

    @Test
    fun subscriberMatcher_multipleCandidatesWithSamePhone_returnsNullDueToAmbiguity() {
        val c1 = LocalAccount(id = "acc_003", displayName = "User 1", phone1 = "07709999999")
        val c2 = LocalAccount(id = "acc_004", displayName = "User 2", phone1 = "07709999999")

        val matched = SubscriberMatcher.matchSubscriber(
            candidates = listOf(c1, c2),
            phone = "07709999999"
        )
        assertNull("Ambiguous phone matching multiple candidates must return null", matched)
    }

    @Test
    fun subscriberMatcher_stage4_rejectsConflictingUsername() {
        val candidate = LocalAccount(
            id = "acc_005",
            earthlinkUsername = "user_x",
            displayName = "Ali Hassan"
        )

        val matched = SubscriberMatcher.matchSubscriber(
            candidates = listOf(candidate),
            username = "user_y",
            name = "Ali Hassan"
        )
        assertNull("Stage 4 name match must reject candidate with conflicting username", matched)
    }
}
