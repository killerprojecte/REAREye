package hk.uwu.reareye.script

import android.content.Context
import java.io.File

object ScriptPaths {
    fun root(context: Context): File =
        context.filesDir.resolve("script-projects").also { it.mkdirs() }

    fun standalone(context: Context): File =
        root(context).resolve("standalone").also { it.mkdirs() }

    fun projects(context: Context): File = root(context).resolve("projects").also { it.mkdirs() }
    fun components(context: Context): File =
        root(context).resolve("components").also { it.mkdirs() }

    fun cache(context: Context): File = root(context).resolve("cache").also { it.mkdirs() }
}
