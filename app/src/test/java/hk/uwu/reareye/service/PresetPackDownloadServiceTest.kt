package hk.uwu.reareye.service

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Looper
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowPowerManager
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = PresetDownloadTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PresetPackDownloadServiceTest {
    private lateinit var controller: ServiceController<PresetPackDownloadService>
    private lateinit var service: PresetPackDownloadService
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        PresetPackDownloadService.clearResult()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val probe = request.headers["Range"] == "bytes=0-0"
                return MockResponse.Builder()
                    .code(206)
                    .setHeader("ETag", "\"test\"")
                    .setHeader(
                        "Content-Range",
                        if (probe) "bytes 0-0/1024" else "bytes 0-1023/1024"
                    )
                    .body(Buffer().write(ByteArray(if (probe) 1 else 1024)))
                    .apply { if (!probe) bodyDelay(10, TimeUnit.SECONDS) }
                    .build()
            }
        }
        server.start()
        controller = Robolectric.buildService(PresetPackDownloadService::class.java).create()
        service = controller.get()
    }

    @After
    fun tearDown() {
        controller.destroy()
        awaitStopped()
        server.close()
        PresetPackDownloadService.clearResult()
    }

    private fun start() {
        val result = service.onStartCommand(Intent().apply {
            putExtra("url", server.url("/pack").toString())
            putExtra("version", "1")
            putExtra("tag", "v1")
            putExtra("name", "pack.rpp")
            putExtra("size", 1024L)
        }, 0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Service.START_REDELIVER_INTENT, result)
        assertTrue(PresetPackDownloadService.state.value.running)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, service.foregroundServiceType)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    private fun awaitStopped() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (PresetPackDownloadService.state.value.running && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertFalse(PresetPackDownloadService.state.value.running)
    }

    @Test
    fun pauseStopsForegroundWorkAndReleasesWakeLock() {
        start()
        service.onStartCommand(Intent().setAction("hk.uwu.reareye.PAUSE_PRESET_DOWNLOAD"), 0, 2)
        awaitStopped()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(
            PresetPackDownloadService.state.value.message,
            PresetPackDownloadService.state.value.message!!.contains("已保留进度")
        )
    }

    @Test
    fun systemTimeoutStopsServiceAndPreservesResumeMessage() {
        start()
        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        awaitStopped()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(
            PresetPackDownloadService.state.value.message,
            PresetPackDownloadService.state.value.message!!.contains("系统时限")
        )
    }

    @Test
    fun foregroundStartFailureDoesNotLeaveUiBusy() {
        shadowOf(service).setThrowInStartForeground(IllegalStateException("quota exhausted"))
        val result =
            service.onStartCommand(Intent().putExtra("url", "https://example.invalid/pack"), 0, 1)
        assertEquals(Service.START_NOT_STICKY, result)
        assertFalse(PresetPackDownloadService.state.value.running)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(PresetPackDownloadService.state.value.message!!.contains("quota exhausted"))
    }
}

// Unit tests do not load the merged Android manifest; use the application's prefs namespace.
class PresetDownloadTestApplication : Application() {
    override fun getPackageName(): String = "hk.uwu.reareye"
}
