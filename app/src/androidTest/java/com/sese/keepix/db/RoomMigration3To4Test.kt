package com.sese.keepix.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
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
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .build()

        val items = kotlinx.coroutines.runBlocking { db.binItemDao().getAllBinMediaIds() }
        assertEquals(listOf(100L), items)
        db.close()
    }

    /**
     * Both tests above apply [AppDatabase.MIGRATION_3_4] *explicitly*
     * (`runMigrationsAndValidate(..., MIGRATION_3_4)` / their own
     * `.addMigrations(MIGRATION_3_4)`), so neither ever calls
     * [AppDatabase.getDatabase] -- the one place a real user's database is
     * actually opened. A literal revert of the original fix (dropping
     * `.addMigrations(MIGRATION_3_4)` from `getDatabase()` and reinstating
     * `.fallbackToDestructiveMigration()` there, `AppDatabase.kt:35-46`)
     * would leave both of those tests green while silently wiping a real
     * user's bin on upgrade again -- exactly the regression this suite
     * exists to prevent.
     *
     * This test closes that gap: it creates a v3 file at the app's REAL
     * database name (`"keepix_database"`, the literal string
     * [AppDatabase.getDatabase] passes to `Room.databaseBuilder`), resets the
     * `getDatabase()` singleton via reflection so the next call is forced to
     * rebuild it, then calls the actual, unmodified
     * `AppDatabase.getDatabase(context)` -- the same call
     * `KeepixViewModel`'s constructor makes -- and asserts the row survives.
     * If `getDatabase()`'s builder wiring reverted to
     * `fallbackToDestructiveMigration()`, Room would detect the "no explicit
     * path from 3 to 4" situation not by throwing but by silently dropping
     * and recreating every table, and `getAllBinMediaIds()` below would come
     * back empty instead of `[100L]`.
     */
    @Test
    fun getDatabase_singleton_migratesTheRealOnDiskDatabaseWithoutDataLoss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val realDbName = "keepix_database"

        // Clean slate: a leftover file from a previous run/process, or from
        // another test in this same instrumentation session having already
        // touched the real db name, must not leak into this test.
        context.deleteDatabase(realDbName)
        resetGetDatabaseSingleton()

        var realDb: AppDatabase? = null
        try {
            // Seed a v3 file at the exact real path AppDatabase.getDatabase
            // resolves ("keepix_database"), NOT a scratch name.
            helper.createDatabase(realDbName, 3).apply {
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

            // The real production entry point -- unmodified, singleton and
            // all -- exactly as KeepixViewModel's constructor calls it.
            realDb = AppDatabase.getDatabase(context)
            val items = kotlinx.coroutines.runBlocking { realDb.binItemDao().getAllBinMediaIds() }

            // Row exists at all: getDatabase() did NOT destructively wipe it.
            assertEquals(listOf(100L), items)

            // And it landed with pendingDeletion defaulting to 0/false, not
            // marked pending: getPendingDeletion() selects `pendingDeletion
            // = 1`, so our migrated row must NOT appear in it.
            val pending = kotlinx.coroutines.runBlocking {
                firstPendingDeletionSnapshot(realDb.binItemDao())
            }
            assertTrue(pending.isEmpty())
        } finally {
            // Close the real connection BEFORE deleting the file, or the
            // delete can silently fail while it's still held open.
            realDb?.close()
            resetGetDatabaseSingleton()
            context.deleteDatabase(realDbName)
        }
    }

    /**
     * [AppDatabase.getDatabase]'s cache is a private `@Volatile var INSTANCE`
     * declared inside its companion object. Verified against the compiled
     * bytecode (`javap`): Kotlin actually places that backing field as a
     * static field directly on the OUTER class -- `AppDatabase` -- not on the
     * generated `AppDatabase$Companion` class, which carries no fields at
     * all. So it's reached directly via `AppDatabase::class.java`, not
     * through a `Companion` field first (an earlier version of this helper
     * assumed the latter and threw `NoSuchFieldException` on `INSTANCE` since
     * `Companion` has none). Resetting it forces the next `getDatabase()`
     * call to rebuild -- otherwise a prior test (in this class or another one
     * sharing this instrumentation process, e.g. `RestoreSwipeQueueTest`,
     * which also calls `getDatabase()` indirectly via `KeepixViewModel`)
     * could hand back an already-open instance and this test would silently
     * pass without ever touching the file we seeded.
     */
    private fun resetGetDatabaseSingleton() {
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
        }.set(null, null)
    }

    private suspend fun firstPendingDeletionSnapshot(dao: BinItemDao): List<BinItemEntity> =
        dao.getPendingDeletion().first()
}
