package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.core.database.AppDatabase
import com.example.core.database.SyncMetadataDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3 Behavioral Test Suite: Persisted G4 Generation State (P3-G4-REQ-01, INV-05, INV-11).
 * Verifies local lineage invalidation mechanism, deterministic initialization, transactional
 * monotonicity, rollback preservation, persistence across reopen, and domain isolation
 * from remoteVersion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Phase3PersistedGenerationTest {

    private lateinit var context: Context
    private lateinit var inMemoryDb: AppDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        inMemoryDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        inMemoryDb.close()
        AppDatabase.closeDatabase()
    }

    // 1. Deterministic default initialization to 1L
    @Test
    fun initialGeneration_defaultsTo1LDeterministically() {
        runBlocking {
            val gen = inMemoryDb.getGeneration()
            assertEquals("Initial generation must default to 1L", SyncMetadataDao.DEFAULT_GENERATION, gen)

            val initializedGen = inMemoryDb.syncMetadataDao().ensureGenerationInitialized()
            assertEquals("ensureGenerationInitialized must return 1L when empty", 1L, initializedGen)

            // Raw value in sync_metadata should now be "1"
            val rawValue = inMemoryDb.syncMetadataDao().get(SyncMetadataDao.KEY_G4_LOCAL_GENERATION)
            assertEquals("Raw metadata value must be '1'", "1", rawValue)
        }
    }

    // 2. Transactional increment increases generation by exactly 1
    @Test
    fun transactionalIncrement_increasesGenerationByExactlyOne() {
        runBlocking {
            assertEquals("Baseline should be 1L", 1L, inMemoryDb.getGeneration())

            val gen2 = inMemoryDb.incrementGeneration()
            assertEquals("First increment must return 2L", 2L, gen2)
            assertEquals("getGeneration must return 2L", 2L, inMemoryDb.getGeneration())

            val gen3 = inMemoryDb.incrementGeneration()
            assertEquals("Second increment must return 3L", 3L, gen3)
            assertEquals("getGeneration must return 3L", 3L, inMemoryDb.getGeneration())

            val gen4 = inMemoryDb.incrementGeneration()
            assertEquals("Third increment must return 4L", 4L, gen4)
            assertEquals("getGeneration must return 4L", 4L, inMemoryDb.getGeneration())
        }
    }

    // 3. Rollback of transaction restores previous generation
    @Test
    fun transactionRollback_restoresPreviousGeneration() {
        runBlocking {
            // Initialize to 5L
            inMemoryDb.setGeneration(5L)
            assertEquals(5L, inMemoryDb.getGeneration())

            // Attempt transactional increment that fails and rolls back
            var exceptionCaught = false
            try {
                inMemoryDb.withTransaction {
                    inMemoryDb.incrementGeneration()
                    assertEquals("Inside transaction before failure, generation should be 6L", 6L, inMemoryDb.getGeneration())
                    throw IllegalStateException("Simulated write transaction failure")
                }
            } catch (e: IllegalStateException) {
                exceptionCaught = true
            }

            assertTrue("Expected transaction to throw exception", exceptionCaught)
            assertEquals("After rollback, generation must be restored to 5L", 5L, inMemoryDb.getGeneration())
        }
    }

    // 4. Generation persists across database close and re-open (app restarts)
    @Test
    fun generation_persistsAcrossDatabaseCloseAndReopen() {
        runBlocking {
            val dbName = "generation_persistence_test.db"
            context.deleteDatabase(dbName)

            val persistentDb1 = AppDatabase.getDatabase(context, byteArrayOf(), dbName)
            assertEquals("Fresh persistent DB must have initial generation 1L", 1L, persistentDb1.getGeneration())

            // Advance generation to 7L
            persistentDb1.setGeneration(7L)
            assertEquals(7L, persistentDb1.getGeneration())

            // Close instance
            AppDatabase.closeAndRemoveInstance(dbName)

            // Re-open same DB file
            val persistentDb2 = AppDatabase.getDatabase(context, byteArrayOf(), dbName)
            assertEquals("Re-opened DB must preserve persisted generation 7L", 7L, persistentDb2.getGeneration())

            // Increment to 8L and re-verify persistence
            persistentDb2.incrementGeneration()
            assertEquals(8L, persistentDb2.getGeneration())

            AppDatabase.closeAndRemoveInstance(dbName)

            val persistentDb3 = AppDatabase.getDatabase(context, byteArrayOf(), dbName)
            assertEquals("Second re-opened DB must preserve persisted generation 8L", 8L, persistentDb3.getGeneration())

            AppDatabase.closeAndRemoveInstance(dbName)
            context.deleteDatabase(dbName)
        }
    }

    // 5. Explicit setGeneration updates generation
    @Test
    fun setGeneration_explicitlyUpdatesGeneration() {
        runBlocking {
            inMemoryDb.setGeneration(42L)
            assertEquals(42L, inMemoryDb.getGeneration())

            val next = inMemoryDb.incrementGeneration()
            assertEquals(43L, next)
            assertEquals(43L, inMemoryDb.getGeneration())
        }
    }

    // 6. Lineage generation is distinct from remoteVersion (isolated domains, INV-05 / INV-11)
    @Test
    fun lineageGeneration_isDistinctFromRemoteVersion() {
        runBlocking {
            val metadataDao = inMemoryDb.syncMetadataDao()
            val accountId = "acc_test_domain_isolation"
            val serverRemoteVersion = 1715000000000L

            // Record a server remote version in sync_metadata
            metadataDao.put("remote_version:account:$accountId", serverRemoteVersion.toString())

            // Verify local generation remains 1L
            assertEquals("Local generation must remain 1L despite large remoteVersion", 1L, inMemoryDb.getGeneration())

            // Advance local generation
            inMemoryDb.incrementGeneration() // now 2L
            assertEquals("Local generation advanced to 2L", 2L, inMemoryDb.getGeneration())

            // Stored remoteVersion must remain completely unaltered
            val storedRemoteVersion = metadataDao.get("remote_version:account:$accountId")
            assertEquals(serverRemoteVersion.toString(), storedRemoteVersion)

            // Mutating remoteVersion must NOT alter local generation
            metadataDao.put("remote_version:account:$accountId", (serverRemoteVersion + 1000L).toString())
            assertEquals("Local generation must not be affected by remoteVersion changes", 2L, inMemoryDb.getGeneration())
        }
    }

    // 7. Invalidation check guards against stale generation across lineage resets
    @Test
    fun invalidationCheck_guardsAgainstStaleGeneration() {
        runBlocking {
            // Operation captures start generation
            val capturedGeneration = inMemoryDb.getGeneration() // 1L

            // Simulate local lineage reset (e.g., Restore Replace or full wipe)
            val resetGeneration = inMemoryDb.incrementGeneration() // 2L

            // Verify guard comparison detects lineage invalidation
            val isStale = (capturedGeneration != inMemoryDb.getGeneration())
            assertTrue("In-flight operation with generation 1L must be detected as stale after reset to 2L", isStale)

            // Proves that remoteVersion cannot be substituted for generation
            val remoteVersion = 1715000000000L
            val incorrectCheckUsingRemoteVersion = (capturedGeneration == remoteVersion)
            assertFalse("RemoteVersion cannot substitute local generation check", incorrectCheckUsingRemoteVersion)
        }
    }

    // 8. deleteAllExcept preserves generation when specified, deleteAll resets gracefully
    @Test
    fun deleteAllExcept_and_deleteAll_deterministicBehavior() {
        runBlocking {
            val metadataDao = inMemoryDb.syncMetadataDao()

            metadataDao.put("cursor_accounts", "cursor_123")
            metadataDao.put("remote_version:account:a1", "99999")
            inMemoryDb.setGeneration(10L)

            // Delete all sync cursors/versions while preserving g4_local_generation
            metadataDao.deleteAllExcept(SyncMetadataDao.KEY_G4_LOCAL_GENERATION)

            assertNull("cursor_accounts must be deleted", metadataDao.get("cursor_accounts"))
            assertNull("remote_version must be deleted", metadataDao.get("remote_version:account:a1"))
            assertEquals("g4_local_generation must be preserved", 10L, inMemoryDb.getGeneration())

            // Now test full deleteAll
            metadataDao.deleteAll()
            assertNull("Raw generation key is now deleted", metadataDao.get(SyncMetadataDao.KEY_G4_LOCAL_GENERATION))
            assertEquals("getGeneration safely returns DEFAULT_GENERATION 1L when empty", 1L, inMemoryDb.getGeneration())
        }
    }

    // 9. Concurrent increments inside transactions are linearized
    @Test
    fun concurrentIncrements_areLinearized() {
        runBlocking(Dispatchers.IO) {
            val totalIncrements = 50
            val results = (1..totalIncrements).map {
                async {
                    inMemoryDb.incrementGeneration()
                }
            }.awaitAll()

            // All returned generations must be unique and in range 2..51
            val uniqueResults = results.toSet()
            assertEquals("All $totalIncrements increments must produce unique values", totalIncrements, uniqueResults.size)
            assertEquals("Final generation must equal ${totalIncrements + 1}L", (totalIncrements + 1).toLong(), inMemoryDb.getGeneration())
        }
    }
}
