package com.sese.keepix.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "migration-test-7-8"

/**
 * Regression coverage for [AppDatabase.MIGRATION_7_8], which adds the
 * `compressed_items` table behind the Compressed tab.
 *
 * The table is purely additive, so the interesting part is what an upgraded
 * install reports: an empty history and 0 bytes saved. Anything else would be
 * invented -- nothing before this shipped recorded a shrink.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigration7To8Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertV7BinRow(id: Long) = execSQL(
        """
        INSERT INTO bin_items
            (id, mediaId, mediaUri, displayName, mediaType, dateTaken, deletedAt,
             expiryAt, sessionId, retentionMode, width, height, durationMs,
             pendingDeletion, trashed)
        VALUES
            ($id, $id, 'content://media/external/images/media/$id', 'p$id.jpg',
             'IMAGE', 100, 200, 0, 's', 'SESSION', 4, 3, 0, 0, 0)
        """.trimIndent()
    )

    @Test
    fun migrate7To8_keepsTheBinAndAddsAnEmptyCompressedHistory() {
        helper.createDatabase(TEST_DB, 7).apply {
            insertV7BinRow(1)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 8, true, AppDatabase.MIGRATION_7_8
        )

        migrated.query("SELECT id FROM bin_items").use { c ->
            assertEquals("the migration must not touch the bin", 1, c.count)
        }
        migrated.query("SELECT * FROM compressed_items").use { c ->
            assertEquals(0, c.count)
        }
        migrated.close()
    }

    /**
     * Same rationale as every earlier migration's equivalent test: the test
     * above registers [AppDatabase.MIGRATION_7_8] explicitly, so it would not
     * notice the migration being dropped from `getDatabase()`'s
     * `addMigrations` -- an omission that would wipe a real user's bin on
     * upgrade while leaving the suite green.
     */
    @Test
    fun getDatabase_singleton_migratesTheRealOnDiskDatabaseWithoutDataLoss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val realDbName = "keepix_database"

        context.deleteDatabase(realDbName)
        resetGetDatabaseSingleton()

        var realDb: AppDatabase? = null
        try {
            helper.createDatabase(realDbName, 7).apply {
                insertV7BinRow(42)
                close()
            }

            realDb = AppDatabase.getDatabase(context)
            val ids = kotlinx.coroutines.runBlocking { realDb!!.binItemDao().getAllBinMediaIds() }
            assertEquals(listOf(42L), ids)

            val history = kotlinx.coroutines.runBlocking {
                realDb!!.compressedItemDao().getAll().first()
            }
            assertTrue("an upgraded install has no shrink history", history.isEmpty())

            val saved = kotlinx.coroutines.runBlocking {
                realDb!!.compressedItemDao().getTotalSaved().first()
            }
            assertEquals(
                "SUM over an empty table is NULL, and must not surface as a crash",
                null,
                saved,
            )
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
