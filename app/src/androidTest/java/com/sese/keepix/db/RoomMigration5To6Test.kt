package com.sese.keepix.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "migration-test-5-6"

/**
 * Regression coverage for [AppDatabase.MIGRATION_5_6]. Modelled on
 * `RoomMigration4To5Test`, including its third test, which is the one that
 * matters: the first two apply the migration explicitly and would stay green
 * through a revert that dropped it from `getDatabase()`.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigration5To6Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate5To6_addsAnEmptyJournal_andPreservesKeptRows() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                """
                INSERT INTO kept_items
                    (id, mediaId, mediaUri, displayName, mediaType, dateTaken,
                     keptAt, width, height, durationMs, isFavorite, pendingFavoriteSync)
                VALUES
                    (1, 42, 'content://media/external/images/media/42', 'a.jpg',
                     'IMAGE', 100, 200, 4, 3, 0, 1, 0)
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_5_6)

        val kept = migrated.query("SELECT id, isFavorite FROM kept_items")
        assertTrue(kept.moveToFirst())
        assertEquals(1L, kept.getLong(kept.getColumnIndexOrThrow("id")))
        assertEquals(1, kept.getInt(kept.getColumnIndexOrThrow("isFavorite")))
        assertEquals(1, kept.count)
        kept.close()

        val journal = migrated.query("SELECT COUNT(*) FROM compression_journal")
        assertTrue(journal.moveToFirst())
        assertEquals("a fresh journal must be empty", 0, journal.getInt(0))
        journal.close()

        migrated.close()
    }

    /**
     * See the identical test in `RoomMigration4To5Test` for the full reasoning:
     * this is the only one that exercises [AppDatabase.getDatabase], the single
     * place a real user's database is opened, so it is the only one that would
     * catch MIGRATION_5_6 being dropped from `.addMigrations(...)` or
     * `fallbackToDestructiveMigration()` reappearing there.
     */
    @Test
    fun getDatabase_singleton_migratesTheRealOnDiskDatabaseWithoutDataLoss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val realDbName = "keepix_database"

        context.deleteDatabase(realDbName)
        resetGetDatabaseSingleton()

        var realDb: AppDatabase? = null
        try {
            helper.createDatabase(realDbName, 5).apply {
                execSQL(
                    """
                    INSERT INTO kept_items
                        (id, mediaId, mediaUri, displayName, mediaType, dateTaken,
                         keptAt, width, height, durationMs, isFavorite, pendingFavoriteSync)
                    VALUES
                        (1, 42, 'content://media/external/images/media/42', 'a.jpg',
                         'IMAGE', 100, 200, 4, 3, 0, 0, 0)
                    """.trimIndent()
                )
                close()
            }

            realDb = AppDatabase.getDatabase(context)
            val ids = kotlinx.coroutines.runBlocking { realDb!!.keptItemDao().getAllKeptMediaIds() }
            assertEquals("getDatabase must migrate, not wipe", listOf(42L), ids)

            val journalCount = kotlinx.coroutines.runBlocking {
                realDb!!.compressionJournalDao().count()
            }
            assertEquals(0, journalCount)
        } finally {
            realDb?.close()
            resetGetDatabaseSingleton()
            context.deleteDatabase(realDbName)
        }
    }

    /** Kotlin puts INSTANCE's backing field on AppDatabase, not on Companion. */
    private fun resetGetDatabaseSingleton() {
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
        }.set(null, null)
    }
}
