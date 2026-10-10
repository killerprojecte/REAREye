package hk.uwu.reareye.hook

import hk.uwu.reareye.hook.core.DexKitBootstrap
import hk.uwu.reareye.hook.scopes.subscreencenter.SubscreenCenterScope
import hk.uwu.reareye.hook.scopes.system.SystemScope
import hk.uwu.reareye.hook.scopes.thememanager.ThemeManagerScope
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.ProcessContext
import hk.uwu.roxyhook.RoxyModule
import hk.uwu.roxyhook.RoxyRuntime
import hk.uwu.roxyhook.annotation.RoxyEntry
import hk.uwu.roxyhook.platform.libxposed.LibXposedHotReload
import hk.uwu.roxyhook.platform.libxposed.LibXposedPackageReplay
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam

/** RoxyHook entry. Static package scopes are declared in the Gradle configuration. */
@RoxyEntry
class HookEntry : RoxyModule(), LibXposedHotReload {
    // Keep the previous saved-state keys so an already running generation can reload into this one.
    private val replay = LibXposedPackageReplay("reareye.reload.")

    override fun PackageScope.onLoad() {
        DexKitBootstrap.ensureLoaded()
        replay.record(this)
        install(this)
    }

    override fun prepareHotReload(runtime: RoxyRuntime, param: HotReloadingParam): Boolean {
        // RoxyXposedModule owns preflight/quiesce/close ordering. This module only serializes
        // the package scopes needed to install the next generation.
        return replay.prepare(param)
    }

    override fun installAfterHotReload(
        runtime: RoxyRuntime, process: ProcessContext, param: HotReloadedParam
    ) {
        DexKitBootstrap.ensureLoaded()
        replay.replay(runtime, process, param, ::install)
    }

    private fun install(scope: PackageScope) = with(scope) {
        loadSystem(SystemScope())
        loadAll(SubscreenCenterScope())
        loadAll(ThemeManagerScope())
    }
}
