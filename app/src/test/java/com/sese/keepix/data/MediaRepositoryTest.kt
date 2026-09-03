package com.sese.keepix.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the real [MediaRepository.getMediaPage] -- the keyset predicate
 * it builds, its termination contract (a page shorter than the requested
 * limit means nothing older remains), and the client-side cap that protects
 * it if a provider ever ignores `QUERY_ARG_LIMIT`.
 *
 * `ContentResolver`/`Cursor` are real Android SDK types with no usable
 * implementation on the host JVM. `Cursor` is an interface so it mocks
 * cleanly, but `MediaStore.Files.getContentUri` and
 * `ContentUris.withAppendedId` are static methods this class calls at
 * construction/per-row time, so they are mocked via `mockkStatic`. `Bundle`
 * -- built fresh inside `getMediaPage` and never hand back to the caller --
 * is inspected via `mockkConstructor`, which lets `verify` assert exactly
 * what was written into whichever Bundle instance the method constructed.
 */
class MediaRepositoryTest {

    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()

    private data class Row(val id: Long, val dateAdded: Long, val isVideo: Boolean)

    private fun setUpStatics() {
        every { context.contentResolver } returns resolver
        mockkStatic(MediaStore.Files::class)
        every { MediaStore.Files.getContentUri(any<String>()) } returns mockk(relaxed = true)
        mockkStatic(ContentUris::class)
        every { ContentUris.withAppendedId(any(), any()) } returns mockk(relaxed = true)
        mockkConstructor(Bundle::class)
    }

    /**
     * Stubs the query and captures the exact Bundle instance production code
     * passed to it, for direct verification (see the two predicate tests
     * below) -- more reliable than `anyConstructed<Bundle>()`, which in
     * practice did not recognize calls made on the Bundle from inside a
     * `withContext(Dispatchers.IO)` coroutine running on a different thread
     * than the one that registered `mockkConstructor`.
     */
    private fun stubQuery(cursor: Cursor, bundleSlot: CapturingSlot<Bundle> = slot()) {
        every {
            resolver.query(any(), any(), capture(bundleSlot), any<CancellationSignal>())
        } returns cursor
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /** An empty (zero-row) cursor: exercises query-building without needing row data. */
    private fun emptyCursor(): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.moveToNext() } returns false
        every { cursor.extras } returns null
        return cursor
    }

    /** A cursor over [rows] in order, honoring QUERY_ARG_LIMIT unless [honorsLimit] is false. */
    private fun cursorOf(rows: List<Row>, honorsLimit: Boolean = true): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID) } returns 0
        every { cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE) } returns 1
        every { cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED) } returns 2
        every { cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME) } returns 3
        every { cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH) } returns 4
        every { cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT) } returns 5
        every { cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION) } returns 6

        every { cursor.moveToNext() } returnsMany (rows.map { true } + false)
        every { cursor.getLong(0) } returnsMany rows.map { it.id }
        every { cursor.getInt(1) } returnsMany rows.map {
            if (it.isVideo) MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO else MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        }
        every { cursor.getLong(2) } returnsMany rows.map { it.dateAdded }
        every { cursor.getString(3) } returnsMany rows.map { "file_${it.id}" }
        every { cursor.getInt(4) } returnsMany rows.map { 0 }
        every { cursor.getInt(5) } returnsMany rows.map { 0 }
        every { cursor.getLong(6) } returnsMany rows.map { 0L }

        if (honorsLimit) {
            val extras = mockk<Bundle>()
            every { extras.getStringArray(ContentResolver.EXTRA_HONORED_ARGS) } returns
                arrayOf(ContentResolver.QUERY_ARG_LIMIT)
            every { cursor.extras } returns extras
        } else {
            every { cursor.extras } returns null
        }
        return cursor
    }

    @Test
    fun `first page with no cursor uses the base selection and no keyset predicate`(): Unit = runBlocking {
        setUpStatics()
        val bundleSlot = slot<Bundle>()
        stubQuery(emptyCursor(), bundleSlot)

        MediaRepository(context).getMediaPage(after = null, limit = 25)

        val bundle = bundleSlot.captured
        verify {
            bundle.putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
            )
        }
        verify {
            bundle.putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("1", "3")
            )
        }
        verify { bundle.putInt(ContentResolver.QUERY_ARG_LIMIT, 25) }
        verify {
            bundle.putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC, ${MediaStore.Files.FileColumns._ID} DESC"
            )
        }
    }

    @Test
    fun `paging past a cursor builds the exact keyset predicate, ties included`(): Unit = runBlocking {
        setUpStatics()
        val bundleSlot = slot<Bundle>()
        stubQuery(emptyCursor(), bundleSlot)

        MediaRepository(context).getMediaPage(after = MediaPageKey(dateAdded = 1000L, id = 5L), limit = 10)

        val bundle = bundleSlot.captured
        val base = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        val expectedSelection = "($base) AND (" +
            "${MediaStore.Files.FileColumns.DATE_ADDED} < ? OR (" +
            "${MediaStore.Files.FileColumns.DATE_ADDED} = ? AND ${MediaStore.Files.FileColumns._ID} < ?))"
        verify {
            bundle.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, expectedSelection)
        }
        // The tie-safe args: base type args, then dateAdded twice (both
        // branches of the OR) and id once -- NOT a plain "<=" on dateAdded,
        // which would wrongly exclude every row that ties with the cursor.
        verify {
            bundle.putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("1", "3", "1000", "1000", "5")
            )
        }
    }

    @Test
    fun `a page shorter than the requested limit is returned as-is (termination signal)`(): Unit = runBlocking {
        setUpStatics()
        stubQuery(
            cursorOf(
                listOf(
                    Row(id = 1L, dateAdded = 200L, isVideo = false),
                    Row(id = 2L, dateAdded = 100L, isVideo = true)
                )
            )
        )

        val page = MediaRepository(context).getMediaPage(after = null, limit = 5)

        assertEquals(2, page.size)
        assertEquals(1L, page[0].id)
        assertEquals(200L, page[0].dateAdded)
        assertEquals(false, page[0].isVideo)
        assertEquals(2L, page[1].id)
        assertEquals(true, page[1].isVideo)
    }

    @Test
    fun `client-side loop caps the result at limit even if the provider ignores QUERY_ARG_LIMIT`(): Unit = runBlocking {
        setUpStatics()
        val rows = (0 until 5).map { Row(id = it.toLong(), dateAdded = (100 - it).toLong(), isVideo = false) }
        stubQuery(cursorOf(rows, honorsLimit = false))

        val page = MediaRepository(context).getMediaPage(after = null, limit = 2)

        assertEquals(2, page.size)
        assertEquals(0L, page[0].id)
        assertEquals(1L, page[1].id)
    }

    @Test
    fun `two rows tied on dateAdded are both returned in cursor order`(): Unit = runBlocking {
        setUpStatics()
        stubQuery(
            cursorOf(
                listOf(
                    Row(id = 10L, dateAdded = 1000L, isVideo = false),
                    Row(id = 9L, dateAdded = 1000L, isVideo = false)
                )
            )
        )

        val page = MediaRepository(context).getMediaPage(after = null, limit = 5)

        assertEquals(listOf(10L, 9L), page.map { it.id })
        assertEquals(listOf(1000L, 1000L), page.map { it.dateAdded })
    }

    @Test
    fun `a null cursor (query failure) yields an empty page without throwing`(): Unit = runBlocking {
        setUpStatics()
        every { resolver.query(any(), any(), any(), any<CancellationSignal>()) } returns null

        val page = MediaRepository(context).getMediaPage(after = null, limit = 10)

        assertTrue(page.isEmpty())
    }

    @Test(expected = MediaAccessException::class)
    fun `a SecurityException from the provider surfaces as MediaAccessException`(): Unit = runBlocking {
        setUpStatics()
        every {
            resolver.query(any(), any(), any(), any<CancellationSignal>())
        } throws SecurityException("no permission")

        MediaRepository(context).getMediaPage(after = null, limit = 10)
    }
}
