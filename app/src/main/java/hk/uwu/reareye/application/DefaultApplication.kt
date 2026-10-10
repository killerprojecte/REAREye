package hk.uwu.reareye.application

import android.app.Application
import hk.uwu.reareye.hook.support.XposedModuleStatus
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.repository.contributor.ContributorRepository
import hk.uwu.reareye.ui.config.LegacyPreferenceMigrationCoordinator
import hk.uwu.reareye.ui.config.RootLegacyPreferenceSource
import hk.uwu.reareye.utils.effect.FrameSampler

class DefaultApplication : Application() {
    private lateinit var legacyPreferenceMigrationCoordinator: LegacyPreferenceMigrationCoordinator

    override fun onCreate() {
        super.onCreate()
        runCatching { XposedModuleStatus.ensureServiceListener() }
            .onFailure {
                YLog.error(
                    "Unable to register libxposed service listener at application startup",
                    it
                )
            }
        legacyPreferenceMigrationCoordinator = LegacyPreferenceMigrationCoordinator(
            context = applicationContext,
            source = RootLegacyPreferenceSource(applicationContext),
        )
        legacyPreferenceMigrationCoordinator.start()
        runCatching { FrameSampler.prepare(applicationContext) }
            .onFailure { YLog.error("Unable to prepare frame sampler", it) }
        ContributorRepository.preload()
    }
}
