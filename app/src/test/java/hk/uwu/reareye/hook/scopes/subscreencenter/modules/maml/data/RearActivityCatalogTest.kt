package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class RearActivityCatalogTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val main get() = shadowOf(Looper.getMainLooper())

    private class Worker : AbstractExecutorService() {
        val tasks = mutableListOf<Runnable>()
        var stopped = false
        override fun execute(command: Runnable) {
            tasks += command
        }

        override fun shutdown() {
            stopped = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true; return tasks.toMutableList().also { tasks.clear() }
        }

        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
        fun drain() {
            val pending = tasks.toList().also { tasks.clear() }
            Thread { pending.forEach { it.run() } }.apply { start(); join() }
        }
    }

    private open class Provider : RearActivityCatalogProvider {
        val iconCalls = mutableListOf<Pair<String, Int>>()
        var mainThreadWork = false
        override fun applications(): List<RearActivityApp> {
            mainThreadWork = mainThreadWork || Looper.myLooper() == Looper.getMainLooper()
            return (0..49).map { RearActivityApp("pkg%02d".format(it), "App%02d".format(it)) }
        }

        override fun activities(packageName: String) = (0..9).map {
            RearActivityEntry(ComponentName(packageName, "$packageName.Activity$it"), "Activity$it")
        }

        override fun icon(packageName: String, size: Int): Bitmap? {
            mainThreadWork = mainThreadWork || Looper.myLooper() == Looper.getMainLooper()
            iconCalls += packageName to size
            return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        }
    }

    private fun request(vararg values: Pair<String, Any>) = MamlDataRequest(mapOf(*values))
    private fun success(result: MamlDataResult?) = (result as MamlDataResult.Success).snapshot

    @Test
    fun filterMatchesLaunchPolicyAndRejectsForeignPackage() {
        val good = RearActivityEntry(ComponentName("a", "a.Main"), "Main")
        val candidates = listOf(
            good,
            good,
            good.copy(enabled = false),
            good.copy(appEnabled = false),
            good.copy(exported = false),
            good.copy(permission = "a.PRIVATE"),
            good.copy(targetActivity = "a.Main"),
            good.copy(component = ComponentName("other", "other.Main"))
        )
        assertEquals(listOf(good), RearActivityPage.activities(candidates, "a"))
        assertTrue(good.copy(permission = " ").selectable)
        assertFalse(good.copy(targetActivity = "").selectable)
        assertEquals(
            listOf("a", "z"), RearActivityPage.apps(
                listOf(
                    RearActivityApp("z", "Beta"), RearActivityApp("a", "Alpha"),
                    RearActivityApp("hidden", "A", false), RearActivityApp("a", "Alpha")
                )
            ).map { it.packageName })
    }

    @Test
    fun normalAppsPrecedeSystemAppsWithDeterministicOrderingWithinEachGroup() {
        val apps = listOf(
            RearActivityApp("sys.z", "Beta", isSystemApp = true),
            RearActivityApp("user.z", "Zulu"),
            RearActivityApp("sys.a", "Alpha", isSystemApp = true),
            RearActivityApp("user.b", "alpha"),
            RearActivityApp("user.a", "Alpha"),
            RearActivityApp("hidden", "A", enabled = false, isSystemApp = true),
        )
        assertEquals(
            listOf("user.a", "user.b", "user.z", "sys.a", "sys.z"),
            RearActivityPage.apps(apps + apps[0]).map { it.packageName })
    }

    @Test
    fun packageManagerFlagsClassifySystemAndUpdatedSystemApplications() {
        val packages = mapOf(
            "example.normal" to 0,
            "example.system" to ApplicationInfo.FLAG_SYSTEM,
            "example.updated" to ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
        )
        packages.forEach { (pkg, flags) ->
            val app =
                ApplicationInfo().apply { packageName = pkg; enabled = true; this.flags = flags }
            shadowOf(context.packageManager).installPackage(PackageInfo().apply {
                packageName = pkg; applicationInfo = app
            })
        }
        val apps = RearSystemActivityCatalog(context).applications().associateBy { it.packageName }
        assertFalse(apps.getValue("example.normal").isSystemApp)
        assertTrue(apps.getValue("example.system").isSystemApp)
        assertTrue(apps.getValue("example.updated").isSystemApp)
    }

    @Test
    fun appPaginationUsesNormalFirstOrderingBeforeTakingRows() {
        val provider = object : Provider() {
            override fun applications() = listOf(
                RearActivityApp("sys", "Alpha", isSystemApp = true),
                RearActivityApp("user.z", "Zulu"), RearActivityApp("user.a", "Beta"),
            )
        }
        val worker = Worker();
        val source = RearActivityAppsDataSource(context, provider, worker)
        try {
            var output: MamlDataResult? = null
            source.query(request("limit" to 1, "offset" to 0)) { output = it }
            worker.drain(); main.idle()
            assertArrayEquals(arrayOf("user.a"), success(output).values["packageNames"] as Array<*>)
            source.query(request("limit" to 1, "offset" to 2)) { output = it }
            worker.drain(); main.idle()
            assertArrayEquals(arrayOf("sys"), success(output).values["packageNames"] as Array<*>)
        } finally {
            source.close()
        }
    }

    @Test
    fun relativeActivityClassNameExpandsWithoutChangingLaunchComponent() {
        val entry = RearActivityEntry(ComponentName("example", ".a.Activity"), "Activity")
        assertEquals("example.a.Activity", entry.activityClassName)
        assertEquals("example/.a.Activity", entry.component.flattenToString())
    }

    @Test
    fun appsPageIsAtomicWithRequestMetadataAndOnlyPageIconsLoaded() {
        val provider = Provider();
        val worker = Worker()
        val source = RearActivityAppsDataSource(context, provider, worker)
        try {
            var output: MamlDataResult? = null
            source.query(request("offset" to 4, "limit" to 7, "iconSize" to 96)) { output = it }
            assertNull(output); assertTrue(provider.iconCalls.isEmpty())
            worker.drain(); main.idle()
            val page = RearActivityAppsDataSource.schema.validate(success(output))
            assertEquals(7, page.count); assertEquals(50.0, page.values["total"])
            assertEquals(4.0, page.values["offset"]); assertEquals(1.0, page.values["hasMore"])
            assertEquals(7.0, page.values["requestLimit"]); assertEquals(
                96.0,
                page.values["requestIconSize"]
            )
            assertEquals((4..10).map { "pkg%02d".format(it) to 96 }, provider.iconCalls)
            assertFalse(provider.mainThreadWork)
            source.query(request("offset" to Int.MAX_VALUE)) { output = it }
            worker.drain(); main.idle()
            assertEquals(0, success(output).count); assertEquals(
                0.0,
                success(output).values["hasMore"]
            )
            assertEquals(7, provider.iconCalls.size)
        } finally {
            source.close()
        }
        assertFalse(worker.stopped)
    }

    @Test
    fun activitiesPublishMatchingPackageAndReuseActualApplicationIcon() {
        val provider = Provider();
        val worker = Worker()
        val source = RearActivitiesDataSource(context, provider, worker)
        try {
            var output: MamlDataResult? = null
            source.query(request("package" to "a", "offset" to 8)) { output = it }
            worker.drain(); main.idle()
            val page = RearActivitiesDataSource.schema.validate(success(output))
            assertEquals(2, page.count); assertEquals("a", page.values["packageName"])
            assertEquals(10.0, page.values["total"]); assertEquals(0.0, page.values["hasMore"])
            assertArrayEquals(
                arrayOf("a/a.Activity8", "a/a.Activity9"),
                page.values["components"] as Array<*>
            )
            assertArrayEquals(
                arrayOf("a.Activity8", "a.Activity9"),
                page.values["activityClasses"] as Array<*>
            )
            assertArrayEquals(
                arrayOf("Activity8", "Activity9"),
                page.values["activityNames"] as Array<*>
            )
            assertEquals(listOf("a" to 64), provider.iconCalls)
            val icons = page.values["icons"] as Array<*>
            assertSame(icons[0], icons[1])
            source.query(request()) { output = it }; worker.drain(); main.idle()
            assertEquals(0, success(output).count); assertEquals(
                "",
                success(output).values["packageName"]
            )
        } finally {
            source.close()
        }
    }

    @Test
    fun iconFailuresAreNullablePlaceholdersButDirectoryFailuresAreExplicit() {
        val worker = Worker()
        val provider = object : Provider() {
            override fun icon(packageName: String, size: Int): Bitmap? = error("icon unavailable")
        }
        val source = RearActivityAppsDataSource(context, provider, worker)
        val broken = RearActivitiesDataSource(context, object : Provider() {
            override fun activities(packageName: String): List<RearActivityEntry> =
                error("Package disappeared")
        }, worker)
        try {
            var output: MamlDataResult? = null
            source.query(request()) { output = it }; worker.drain(); main.idle()
            val page = RearActivityAppsDataSource.schema.validate(success(output))
            assertEquals(3, page.count)
            assertTrue((page.values["icons"] as Array<*>).all { it == null })
            broken.query(request("package" to "a")) { output = it }; worker.drain(); main.idle()
            assertEquals("Package disappeared", (output as MamlDataResult.Failure).message)
        } finally {
            source.close(); broken.close()
        }
    }

    @Test
    fun malformedParametersFailBeforeProviderWork() {
        val provider = Provider();
        val worker = Worker()
        val source = RearActivityAppsDataSource(context, provider, worker)
        try {
            for (value in listOf(
                "limit" to 8, "limit" to 0, "iconSize" to 97,
                "iconSize" to 0, "offset" to -1, "offset" to Double.NaN
            )) {
                var output: MamlDataResult? = null
                source.query(request(value)) { output = it }; worker.drain(); main.idle()
                assertTrue(output is MamlDataResult.Failure)
            }
            assertTrue(provider.iconCalls.isEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                RearActivityPage.read(request("package" to 123), true)
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun replacementExplicitCancellationPauseAndCloseRejectQueuedDelivery() {
        val worker = Worker();
        val source = RearActivitiesDataSource(context, Provider(), worker)
        var calls = 0
        try {
            val handle = source.query(request("package" to "a")) { calls += 100 }
            handle.close(); handle.close(); worker.drain(); main.idle()
            source.query(request("package" to "a")) { calls += 100 }; worker.drain()
            source.query(request("package" to "b")) {
                calls++; assertEquals("b", success(it).values["packageName"])
            }; worker.drain(); main.idle()
            assertEquals(1, calls)
            source.query(request("package" to "a")) { calls++ }; worker.drain()
            source.onPause(); source.onResume(); main.idle(); assertEquals(1, calls)
            source.query(request("package" to "b")) { calls++ }; worker.drain()
            source.close(); main.idle(); assertEquals(1, calls)
            source.query(request("package" to "a")) { calls++ }; assertTrue(worker.tasks.isEmpty())
        } finally {
            source.close()
        }
    }

    @Test
    fun bitmapDimensionsAndRetainedCacheHaveHardBudgets() {
        val provider = object : Provider() {
            override fun icon(packageName: String, size: Int): Bitmap {
                iconCalls += packageName to size
                return Bitmap.createBitmap(240, 200, Bitmap.Config.ARGB_8888)
            }
        }
        val worker = Worker();
        val source = RearActivityAppsDataSource(context, provider, worker)
        try {
            for (offset in 0..49 step 7) {
                var output: MamlDataResult? = null
                source.query(request("offset" to offset, "limit" to 7, "iconSize" to 96)) {
                    output = it
                }
                worker.drain(); main.idle()
                (success(output).values["icons"] as Array<*>).forEach {
                    val bitmap = it as Bitmap
                    assertTrue(bitmap.width <= 96 && bitmap.height <= 96)
                }
                assertTrue(source.icons.size() <= 32)
                assertTrue(source.icons.retainedWeight() <= 1024L * 1024)
            }
            source.onPause(); assertEquals(0, source.icons.size())
        } finally {
            source.close()
        }
    }

    @Test
    fun packageChangeAndConfigurationInvalidateCancelAndDetachListeners() {
        val worker = Worker();
        val provider = Provider()
        val source = RearActivityAppsDataSource(context, provider, worker)
        val before = shadowOf(context).registeredReceivers.size
        var invalidations = 0;
        var deliveries = 0
        try {
            source.subscribeInvalidations { invalidations++ }
            assertEquals(before + 1, shadowOf(context).registeredReceivers.size)
            source.query(request()) { deliveries++ }; worker.drain(); main.idle()
            assertEquals(3, source.icons.size())
            context.sendBroadcast(Intent(Intent.ACTION_PACKAGE_CHANGED, Uri.parse("package:pkg00")))
            main.idle(); assertEquals(1, invalidations); assertEquals(0, source.icons.size())
            source.query(request()) { deliveries++ }; worker.drain()
            context.onConfigurationChanged(Configuration(context.resources.configuration))
            main.idle(); assertEquals(2, invalidations)
            // Main-thread configuration invalidation must cancel even already-queued delivery.
            assertEquals(1, deliveries)
            assertEquals(0, source.icons.size())
            source.onPause(); assertEquals(before, shadowOf(context).registeredReceivers.size)
            source.onResume(); assertEquals(before + 1, shadowOf(context).registeredReceivers.size)
            source.close(); assertEquals(before, shadowOf(context).registeredReceivers.size)
        } finally {
            source.close()
        }
    }

    @Test
    fun replacingRemovalIsNotPermanentUninstall() {
        val intent = Intent(Intent.ACTION_PACKAGE_REMOVED, Uri.parse("package:a"))
        assertTrue(RearActivityCatalogWatcher.isFinalRemoval(intent))
        intent.putExtra(Intent.EXTRA_REPLACING, true)
        assertFalse(RearActivityCatalogWatcher.isFinalRemoval(intent))
        assertFalse(RearActivityCatalogWatcher.isFinalRemoval(Intent(Intent.ACTION_PACKAGE_REPLACED)))
    }

    @Test
    fun slowNonInterruptibleProviderDoesNotBuildUnboundedQueue() {
        val entered = CountDownLatch(1);
        val release = Semaphore(0)
        val provider = object : Provider() {
            override fun applications(): List<RearActivityApp> {
                entered.countDown(); release.acquireUninterruptibly(); return emptyList()
            }
        }
        val source = RearActivityAppsDataSource(context, provider)
        val worker = RearActivityPageDataSource::class.java.getDeclaredField("worker")
            .apply { isAccessible = true }.get(source) as ThreadPoolExecutor
        var calls = 0
        try {
            source.query(request()) { calls++ }; assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(100) { source.query(request()) { calls++ } }
            assertTrue(worker.queue.size <= 1)
            source.close(); assertTrue(worker.isShutdown)
        } finally {
            release.release(); source.close()
        }
        assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS)); main.idle(); assertEquals(
            0,
            calls
        )
    }
}
