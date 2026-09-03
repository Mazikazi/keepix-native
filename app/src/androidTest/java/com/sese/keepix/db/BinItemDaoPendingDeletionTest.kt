package com.sese.keepix.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DAO-level coverage for the `pendingDeletion` mark-then-confirm predicates:
 * the expiry queries must exclude rows already marked, and the mark/unmark/
 * read helpers must behave as advertised. Runs against a real in-memory Room
 * database (current v4 schema) rather than a migrated one -- that's
 * [RoomMigration3To4Test]'s job.
 */
@RunWith(AndroidJUnit4::class)
class BinItemDaoPendingDeletionTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BinItemDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.binItemDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sessionItem(id: Long, mediaId: Long, sessionId: String) = BinItemEntity(
        id = id,
        mediaId = mediaId,
        mediaUri = "content://media/$mediaId",
        retentionMode = "SESSION",
        sessionId = sessionId
    )

    private fun timedItem(id: Long, mediaId: Long, expiryAt: Long) = BinItemEntity(
        id = id,
        mediaId = mediaId,
        mediaUri = "content://media/$mediaId",
        retentionMode = "TIMED",
        expiryAt = expiryAt
    )

    @Test
    fun getExpiredSessionItems_excludesRowsAlreadyMarkedPending() = runBlocking {
        dao.insert(sessionItem(1, 100, sessionId = "old-session"))
        dao.insert(sessionItem(2, 200, sessionId = "old-session"))
        dao.markPendingDeletion(listOf(2L))

        val expired = dao.getExpiredSessionItems(currentSessionId = "new-session")

        assertEquals(listOf(1L), expired.map { it.id })
    }

    @Test
    fun getExpiredTimedItems_excludesRowsAlreadyMarkedPending() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insert(timedItem(1, 100, expiryAt = now - 1000))
        dao.insert(timedItem(2, 200, expiryAt = now - 1000))
        dao.markPendingDeletion(listOf(2L))

        val expired = dao.getExpiredTimedItems(now)

        assertEquals(listOf(1L), expired.map { it.id })
    }

    @Test
    fun getExpiredTimedItems_excludesSessionModeAndNotYetExpiredRows() = runBlocking {
        val now = System.currentTimeMillis()
        dao.insert(timedItem(1, 100, expiryAt = now - 1000)) // expired
        dao.insert(timedItem(2, 200, expiryAt = now + 1_000_000)) // not yet expired
        dao.insert(sessionItem(3, 300, sessionId = "s")) // session mode, expiryAt=0

        val expired = dao.getExpiredTimedItems(now)

        assertEquals(listOf(1L), expired.map { it.id })
    }

    @Test
    fun markPendingDeletion_neverDropsTheRow() = runBlocking {
        dao.insert(sessionItem(1, 100, sessionId = "s"))

        dao.markPendingDeletion(listOf(1L))

        val all = dao.getAllBinMediaIds()
        assertEquals(listOf(100L), all)
        assertTrue(dao.getPendingDeletionSnapshot().any { it.id == 1L })
    }

    @Test
    fun unmarkPendingDeletion_reversesTheMark() = runBlocking {
        dao.insert(sessionItem(1, 100, sessionId = "s"))
        dao.markPendingDeletion(listOf(1L))

        dao.unmarkPendingDeletion(listOf(1L))

        assertFalse(dao.getPendingDeletionSnapshot().any { it.id == 1L })
        // Un-marking must not have deleted the row either.
        assertEquals(listOf(100L), dao.getAllBinMediaIds())
    }

    @Test
    fun deleteByIds_dropsOnlyTheGivenRows() = runBlocking {
        dao.insert(sessionItem(1, 100, sessionId = "s"))
        dao.insert(sessionItem(2, 200, sessionId = "s"))
        dao.markPendingDeletion(listOf(1L, 2L))

        dao.deleteByIds(listOf(1L))

        val remaining = dao.getAllBinMediaIds()
        assertEquals(listOf(200L), remaining)
    }

    /** Snapshot helper: reads [BinItemDao.getPendingDeletion]'s current value once. */
    private suspend fun BinItemDao.getPendingDeletionSnapshot(): List<BinItemEntity> =
        getPendingDeletion().first()
}
