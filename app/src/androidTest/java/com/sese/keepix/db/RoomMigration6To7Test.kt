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

private const val TEST_DB = "migration-test-6-7"

/**
 * Regression coverage for [AppDatabase.MIGRATION_6_7], which adds
 * [BinItemEntity.trashed] to `bin_items`. Modeled on `RoomMigration5To6Test`.
 *
 * Defaulting to 0 is the load-bearing part. Every row that already exists was
 * binned under the old behaviour, where the file was left untouched in
 * MediaStore -- so it is still visible in the user's gallery and still needs a
 * trash request. Defaulting to 1 would mark that whole backlog as already
 * hidden and nothing would ever go back and hide it.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigration6To7Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertV6BinRow(
        id: Long,
        mediaId: Long,
        pendingDeletion: Int = 0,
    ) = execSQL(
        """
        INSERT INTO bin_items
            (id, mediaId, mediaUri, displayName, mediaType, dateTaken, deletedAt,
             expiryAt, sessionId, retentionMode, width, height, durationMs, pendingDeletion)
        VALUES
            ($id, $mediaId, 'content://media/external/images/media/$mediaId', 'p$id.jpg',
             'IMAGE', 100, 200, 0, 's', 'SESSION', 4, 3, 0, $pendingDeletion)
        """.trimIndent()
    )

    @Test
    fun migrate6To7_preservesBinRows_andDefaultsTrashedToFalse() {
        helper.createDatabase(TEST_DB, 6).apply {
            insertV6BinRow(id = 1, mediaId = 42)
            insertV6BinRow(id = 2, mediaId = 99)
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB, 7, true, AppDatabase.MIGRATION_6_7
        )

        val cursor = migrated.query("SELECT id, mediaId, trashed FROM bin_items ORDER BY id ASC")
        assertEquals(2, cursor.count)

        assertTrue(cursor.moveToFirst())
        assertEquals(1L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
        assertEquals(42L, cursor.getLong(cursor.getColumnIndexOrThrow("mediaId")))
        assertFalse(
            "trashed must default to false: the file is still in the gallery",
            cursor.getInt(cursor.getColumnIndexOrThrow("trashed")) != 0
        )

        assertTrue(cursor.moveToNext())
        assertEquals(2L, cursor.getLong(cursor.getColumnIndexOrThrow("id")))
        assertFalse(
            "trashed must default to false: the file is still in the gallery",
            cursor.getInt(cursor.getColumnIndexOrThrow("trashed")) != 0
        )

        cursor.close()
        migrated.close()
    }

    @Test
    fun migrate6To7_thenOpeningWithRoom_showsEveryOldRowAsStillNeedingATrashRequest() {
        helper.createDatabase(TEST_DB, 6).apply {
            insertV6BinRow(id = 1, mediaId = 42)
            // A row already queued for permanent deletion must NOT be offered
            // for trashing: it is about to be removed outright, and trashing it
            // first would cost a second consent dialog for nothing.
            insertV6BinRow(id = 2, mediaId = 99, pendingDeletion = 1)
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 7, true, AppDatabase.MIGRATION_6_7)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_6_7)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .build()

        val untrashed = kotlinx.coroutines.runBlocking { db.binItemDao().getUntrashed().first() }
        assertEquals(listOf(42L), untrashed.map { it.mediaId })

        kotlinx.coroutines.runBlocking { db.binItemDao().markTrashed(listOf(1L)) }
        val afterMarking = kotlinx.coroutines.runBlocking { db.binItemDao().getUntrashed().first() }
        assertTrue("a trashed row must drop out of the queue", afterMarking.isEmpty())

        db.close()
    }

    /**
     * Same rationale as the equivalent test on every earlier migration: both
     * tests above register [AppDatabase.MIGRATION_6_7] explicitly, so neither
     * would notice if it were dropped from `getDatabase()`'s `addMigrations`.
     * That omission would wipe a real user's bin on upgrade while leaving the
     * suite green.
     */
    @Test
    fun getDatabase_singleton_migratesTheRealOnDiskDatabaseWithoutDataLoss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val realDbName = "keepix_database"

        context.deleteDatabase(realDbName)
        resetGetDatabaseSingleton()

        var realDb: AppDatabase? = null
        try {
            helper.createDatabase(realDbName, 6).apply {
                insertV6BinRow(id = 1, mediaId = 42)
                close()
            }

            realDb = AppDatabase.getDatabase(context)
            val ids = kotlinx.coroutines.runBlocking { realDb!!.binItemDao().getAllBinMediaIds() }
            assertEquals(listOf(42L), ids)

            val untrashed = kotlinx.coroutines.runBlocking {
                realDb!!.binItemDao().getUntrashed().first()
            }
            assertEquals(
                "an upgraded row is still in the gallery and must be queued for trashing",
                listOf(42L),
                untrashed.map { it.mediaId },
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
