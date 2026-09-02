package com.sese.keepix.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_DB = "migration-test-4-5"

/**
 * Regression coverage for [AppDatabase.MIGRATION_4_5], which adds
 * [KeptItemEntity.isFavorite] and [KeptItemEntity.pendingFavoriteSync] to
 * `kept_items`. Modeled on `RoomMigration3To4Test`: proves the real migration
 * upgrades a v4 database in place, existing kept rows survive, and both new
 * columns default to 0 (false) so an upgrade never marks anything favorite or
 * queues a sync on its own.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigration4To5Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate4To5_preservesKeptRows_andDefaultsFavoriteFlagsToFalse() {
        // Build a v4 database and insert rows using only the columns that
        // existed at v4 (no isFavorite / pendingFavoriteSync columns yet).
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                """
                INSERT INTO kept_items
                    (id, mediaId, mediaUri, displayName, mediaType, dateTaken,
                     keptAt, width, height, durationMs)
                VALUES
                    (1, 42, 'content://media/external/images/media/42', 'a.jpg',
                     'IMAGE', 100, 200, 4, 3, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO kept_items
                    (id, mediaId, mediaUri, displayName, mediaType, dateTaken,
                     keptAt, width, height, durationMs)
                VALUES
                    (2, 99, 'content://media/external/video/media/99', 'b.mp4',
                     'VIDEO', 300, 400, 30, 40, 5000)
                """.trimIndent()
            )
            close()
        }

        // Run the real migration under test.
        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 5, true, AppDatabase.MIGRATION_4_5
        )

        val cursor = migrated.query(
            "SELECT id, mediaId, isFavorite, pendingFavoriteSync FROM kept_items ORDER BY id ASC"
        )
        assertEquals(2, cursor.count)

        assertTrue(cursor.moveToFirst())
        assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
        assertEquals(42L, cursor.getLong(cursor.getColumnIndexOrThrow("mediaId")))
        assertFalse(
            "isFavorite must default to false",
            cursor.getInt(cursor.getColumnIndexOrThrow("isFavorite")) != 0
        )
        assertFalse(
            "pendingFavoriteSync must default to false",
            cursor.getInt(cursor.getColumnIndexOrThrow("pendingFavoriteSync")) != 0
        )

        assertTrue(cursor.moveToNext())
        assertEquals(2L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
        assertEquals(99L, cursor.getLong(cursor.getColumnIndexOrThrow("mediaId")))
        assertFalse(
            "isFavorite must default to false",
            cursor.getInt(cursor.getColumnIndexOrThrow("isFavorite")) != 0
        )
        assertFalse(
            "pendingFavoriteSync must default to false",
            cursor.getInt(cursor.getColumnIndexOrThrow("pendingFavoriteSync")) != 0
        )

        cursor.close()
        migrated.close()
    }

    @Test
    fun migrate4To5_thenOpeningWithRoom_letsDaoReadThePreservedRows() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                """
                INSERT INTO kept_items
                    (id, mediaId, mediaUri, displayName, mediaType, dateTaken,
                     keptAt, width, height, durationMs)
                VALUES
                    (1, 42, 'content://media/external/images/media/42', 'a.jpg',
                     'IMAGE', 100, 200, 4, 3, 0)
                """.trimIndent()
            )
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 5, true, AppDatabase.MIGRATION_4_5)

        // Re-open the migrated file through the real Room database (with the
        // real migration registered, exactly as AppDatabase.getDatabase does)
        // and confirm the DAO layer reads it back correctly -- not just the
        // raw SQL.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .build()

        val items = kotlinx.coroutines.runBlocking { db.keptItemDao().getAllKeptMediaIds() }
        assertEquals(listOf(42L), items)

        val favorites = kotlinx.coroutines.runBlocking { db.keptItemDao().getFavoriteItems().first() }
        assertTrue("no row should be favorite after a fresh migration", favorites.isEmpty())

        val pendingSync =
            kotlinx.coroutines.runBlocking { db.keptItemDao().getPendingFavoriteSync().first() }
        assertTrue("no row should be pending sync after a fresh migration", pendingSync.isEmpty())

        db.close()
    }

    /**
     * Both tests above apply [AppDatabase.MIGRATION_4_5] *explicitly*
     * (`runMigrationsAndValidate(..., MIGRATION_4_5)` / its own
     * `.addMigrations(MIGRATION_4_5)`), so neither ever calls
     * [AppDatabase.getDatabase] -- the one place a real user's database is
     * actually opened. A revert that dropped `MIGRATION_4_5` from
     * `.addMigrations(...)` in `getDatabase()` (or reinstated
     * `.fallbackToDestructiveMigration()` there) would leave both of those
     * tests green while silently wiping a real user's kept library on
     * upgrade -- exactly the regression this test exists to catch.
     *
     * This creates a v4 file at the app's REAL database name
     * (`"keepix_database"`, the literal string [AppDatabase.getDatabase]
     * passes to `Room.databaseBuilder`), resets the `getDatabase()` singleton
     * via reflection so the next call is forced to rebuild it, then calls the
     * actual, unmodified `AppDatabase.getDatabase(context)` -- the same call
     * `KeepixViewModel`'s constructor makes -- and asserts the row survives
     * with the favorite flags defaulted to false.
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
            // Seed a v4 file at the exact real path AppDatabase.getDatabase
            // resolves ("keepix_database"), NOT a scratch name.
            helper.createDatabase(realDbName, 4).apply {
                execSQL(
                    """
                    INSERT INTO kept_items
                        (id, mediaId, mediaUri, displayName, mediaType, dateTaken,
                         keptAt, width, height, durationMs)
                    VALUES
                        (1, 42, 'content://media/external/images/media/42', 'a.jpg',
                         'IMAGE', 100, 200, 4, 3, 0)
                    """.trimIndent()
                )
                close()
            }

            // The real production entry point -- unmodified, singleton and
            // all -- exactly as KeepixViewModel's constructor calls it.
            realDb = AppDatabase.getDatabase(context)
            val items = kotlinx.coroutines.runBlocking { realDb.keptItemDao().getAllKeptMediaIds() }

            // Row exists at all: getDatabase() did NOT destructively wipe it.
            assertEquals(listOf(42L), items)

            // And it landed with both favorite flags defaulting to 0/false:
            // getFavoriteItems() / getPendingFavoriteSync() select their
            // respective `= 1`, so our migrated row must NOT appear in either.
            val favorites = kotlinx.coroutines.runBlocking {
                realDb!!.keptItemDao().getFavoriteItems().first()
            }
            assertTrue(favorites.isEmpty())

            val pendingSync = kotlinx.coroutines.runBlocking {
                realDb!!.keptItemDao().getPendingFavoriteSync().first()
            }
            assertTrue(pendingSync.isEmpty())
        } finally {
            // Close the real connection BEFORE deleting the file, or the
            // delete can silently fail while it's still held open.
            realDb?.close()
            resetGetDatabaseSingleton()
            context.deleteDatabase(realDbName)
        }
    }

    /**
     * See the identical helper and rationale in `RoomMigration3To4Test`:
     * Kotlin places `INSTANCE`'s backing field directly on `AppDatabase`, not
     * on the generated `Companion` class.
     */
    private fun resetGetDatabaseSingleton() {
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
        }.set(null, null)
    }
}
