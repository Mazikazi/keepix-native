package com.sese.keepix.testutil

import android.content.Context
import com.sese.keepix.data.KeepixPreferences
import com.sese.keepix.data.MediaItem
import com.sese.keepix.data.MediaRepository
import com.sese.keepix.db.BinItemDao
import com.sese.keepix.db.KeptItemDao
import com.sese.keepix.ui.KeepixViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Field

/**
 * [KeepixViewModel] wires its dependencies (MediaRepository, the two Room
 * DAOs, WorkManager) directly in its constructor and `init` block, with no
 * seam for substitution -- by design, per the task brief, production code is
 * out of scope to change. Calling that constructor for real in a JVM unit
 * test is not viable: `AppDatabase.getDatabase(application).binItemDao()`
 * needs a working Room/SQLite stack, and `schedulePeriodicCleanup()` calls
 * `WorkManager.getInstance(application)`, which throws
 * `IllegalStateException` unless WorkManager has already been initialized by
 * a real Application -- neither exists on the host JVM without Robolectric.
 *
 * [Unsafe.allocateInstance] sidesteps this by allocating a raw
 * `KeepixViewModel` instance WITHOUT running its constructor, any superclass
 * constructor, or any property initializer. The real private methods
 * (`isPastCursor`, `spliceIntoQueue`, `fetchBatch`, `performLaunchCleanup`)
 * and public methods (`confirmDeletion`, `deferDeletion`, `restoreItem`, ...)
 * are then exercised through their own real bytecode, with only the specific
 * fields each test needs wired in first via reflection.
 *
 * This is deliberately NOT `mockk<KeepixViewModel>()`. That was tried first
 * and rejected after empirical verification: MockK's JVM mock maker
 * redefines the class's own bytecode so that every call on a mocked
 * instance -- including one made via reflection, bypassing MockK's normal
 * call-recording entirely -- is intercepted and answered as an unstubbed
 * mock (a boolean-returning method silently returns `false`) rather than
 * running the real method body. A `mockk<KeepixViewModel>()` instance can
 * never be used to test KeepixViewModel's own logic; it can only be used
 * *as a dependency* passed into something else. `Unsafe`-allocated
 * instances are ordinary, unmocked objects of the real class, so their
 * methods run for real -- while fields that need a collaborator (the DAOs,
 * the repository, prefs) are wired individually to `mockk<T>()` instances of
 * those *other* types, where MockK's interception works exactly as normal
 * since the call to them happens through ordinary virtual dispatch from
 * inside the real KeepixViewModel code, not via reflection on the mock
 * itself.
 */
object ViewModelTestHarness {

    private val unsafe: Any by lazy {
        val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
        f.isAccessible = true
        f.get(null)
    }

    private fun allocate(): KeepixViewModel {
        val method = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        @Suppress("UNCHECKED_CAST")
        return method.invoke(unsafe, KeepixViewModel::class.java) as KeepixViewModel
    }

    private fun field(name: String): Field =
        KeepixViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }

    fun <T> setField(vm: KeepixViewModel, name: String, value: T) {
        field(name).set(vm, value)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getField(vm: KeepixViewModel, name: String): T =
        field(name).get(vm) as T

    fun invokePrivate(vm: KeepixViewModel, name: String, vararg args: Any?): Any? {
        val argTypes = args.map {
            when (it) {
                null -> throw IllegalArgumentException("invokePrivate cannot infer the type of a null arg")
                else -> it.javaClass
            }
        }.toTypedArray()
        val candidates = KeepixViewModel::class.java.declaredMethods.filter { it.name == name }
        val m = candidates.singleOrNull { it.parameterCount == args.size }
            ?: candidates.single()
        m.isAccessible = true
        return m.invoke(vm, *args)
    }

    fun fakePrefs(
        retentionDays: Int = 10,
        batchSize: Int = 50,
        lastSessionId: String = ""
    ): KeepixPreferences {
        val fake = FakeSharedPreferences()
        fake.seed("retention_days", retentionDays)
        fake.seed("batch_size", batchSize)
        fake.seed("last_session_id", lastSessionId)
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns fake
        return KeepixPreferences(context)
    }

    /**
     * Builds a bypass-constructed KeepixViewModel with every field the
     * private/public methods under test touch initialized to a sane,
     * inspectable default. Callers override individual mocks/flows as
     * needed for their scenario and re-set them via [setField] afterward.
     */
    fun newViewModel(
        repository: MediaRepository = mockk(relaxed = true),
        binItemDao: BinItemDao = mockk(relaxed = true),
        keptItemDao: KeptItemDao = mockk(relaxed = true),
        prefs: KeepixPreferences = fakePrefs()
    ): KeepixViewModel {
        val vm = allocate()
        setField(vm, "repository", repository)
        setField(vm, "binItemDao", binItemDao)
        setField(vm, "keptItemDao", keptItemDao)
        setField(vm, "prefs", prefs)
        setField(vm, "_mediaItems", MutableStateFlow<List<MediaItem>>(emptyList()))
        setField(vm, "_isLoading", MutableStateFlow(false))
        setField(vm, "_error", MutableStateFlow<String?>(null))
        setField(vm, "_deletedCount", MutableStateFlow(0))
        setField(vm, "_sessionKeptCount", MutableStateFlow(0))
        setField(vm, "_promptedThisSession", MutableStateFlow(false))
        setField(vm, "_reachedEnd", MutableStateFlow(false))
        setField(vm, "binMediaIds", emptySet<Long>())
        setField(vm, "keptMediaIds", emptySet<Long>())
        setField(vm, "seenMediaIds", mutableSetOf<Long>())
        setField<Any?>(vm, "pageCursor", null)
        setField(vm, "mediaCount", 0)
        setField(vm, "batchLoadInFlight", false)
        return vm
    }
}
