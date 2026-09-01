package com.sese.keepix.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "migration-test"

/**
 * Regression test for the data-loss defect `fallbackToDestructiveMigration()`
 * was removed to fix: that call silently wiped every user's bin on any schema
 * bump. This proves the real [AppDatabase.MIGRATION_3_4] migration instead
 * upgrades a v3 database in place and existing bin rows survive, landing with
 * `pendingDeletion` defaulting to 0 (false) so an upgrade never marks a row
 * for removal on its own.
 *
 * `app/schemas/com.sese.keepix.db.AppDatabase/3.json` was generated from the
 * initial commit (`061d929`), the last point where the DB was genuinely at
 * version 3 -- see the task report for how it was produced (schema export
 * was only turned on during this pass, so Room never emitted it originally).
 */
@RunWith(AndroidJUnit4::class)
class RoomMigration3To4Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate3To4_preservesExistingBinRows_withPendingDeletionDefaultingToFalse() {
        // Build a v3 database and insert rows using only the columns that
        // existed at v3 (no `pendingDeletion` column yet).
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                """
                INSERT INTO bin_items
                    (id, mediaId, mediaUri, displayName, mediaType, dateTaken,
                     deletedAt, expiryAt, sessionId, retentionMode, width, height, durationMs)
                VALUES
                    (1, 100, 'content://media/100', 'a.jpg', 'IMAGE', 1000,
                     2000, 0, 'session-a', 'SESSION', 10, 20, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO bin_items
                    (id, mediaId, mediaUri, displayName, mediaType, dateTaken,
                     deletedAt, expiryAt, sessionId, retentionMode, width, height, durationMs)
                VALUES
                    (2, 200, 'content://media/200', 'b.mp4', 'VIDEO', 3000,
                     4000, 99999999999, '', 'TIMED', 30, 40, 5000)
                """.trimIndent()
            )
            close()
        }

        // Run the real migration under test.
        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            AppDatabase.MIGRATION_3_4
        )

        val cursor = migrated.query("SELECT * FROM bin_items ORDER BY id ASC")
        assertEquals(2, cursor.count)

        assertTrue(cursor.moveToFirst())
        assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
        assertEquals(100L, cursor.getLong(cursor.getColumnIndexOrThrow("mediaId")))
        assertEquals(
            "content://media/100",
            cursor.getString(cursor.getColumnIndexOrThrow("mediaUri"))
        )
        assertEquals(
            0,
            cursor.getInt(cursor.getColumnIndexOrThrow("pendingDeletion"))
        )

        assertTrue(cursor.moveToNext())
        assertEquals(2L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
        assertEquals(200L, cursor.getLong(cursor.getColumnIndexOrThrow("mediaId")))
        assertEquals(
            0,
            cursor.getInt(cursor.getColumnIndexOrThrow("pendingDeletion"))
        )
        cursor.close()
        migrated.close()
    }

    @Test
    fun migrate3To4_thenOpeningWithRoom_letsDaoReadThePreservedRows() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                """
                INSERT INTO bin_items
                    (id, mediaId, mediaUri, displayName, mediaType, dateTaken,
                     deletedAt, expiryAt, sessionId, retentionMode, width, height, durationMs)
                VALUES
                    (1, 100, 'content://media/100', 'a.jpg', 'IMAGE', 1000,
                     2000, 0, 'session-a', 'SESSION', 10, 20, 0)
                """.trimIndent()
            )
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4)

        // Re-open the migrated file through the real Room database (with the
        // real migration registered, exactly as AppDatabase.getDatabase does)
        // and confirm the DAO layer reads it back correctly -- not just the
        // raw SQL.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_3_4)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .build()

        val items = kotlinx.coroutines.runBlocking { db.binItemDao().getAllBinMediaIds() }
        assertEquals(listOf(100L), items)
        db.close()
    }
}
