package hk.uwu.reareye.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.system.StructStat
import hk.uwu.reareye.BuildConfig
import hk.uwu.reareye.utils.RootHelper
import hk.uwu.reareye.utils.SharedRuntimeFileStore
import hk.uwu.reareye.widgetapi.RearWallpaperApiContract
import java.io.IOException

/** Gives the wallpaper hook one narrowly scoped operation under the module app's Root grant. */
class RearRuntimeMetadataProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == PRESERVE) { "Unknown wallpaper metadata operation" }
        val context = checkNotNull(context)
        val callerUid = Binder.getCallingUid()
        val hostUid = context.packageManager.getPackageUid(RearWallpaperApiContract.HOOK_HOST_PACKAGE, 0)
        if (callerUid != hostUid || callingPackage != RearWallpaperApiContract.HOOK_HOST_PACKAGE) {
            throw SecurityException("Wallpaper metadata requires the back screen host")
        }

        val name = requireNotNull(arg)
        require(STAGED_NAME.matches(name)) { "Invalid wallpaper staging file name" }
        val stat = requireNotNull(extras).getLongArray(INODES)
        require(stat != null && stat.size == 4 && stat[1] != 0L && stat[3] != 0L) {
            "Invalid wallpaper file identity"
        }
        val userId = Process.myUid() / 100_000
        val args = stat.joinToString(" ") { java.lang.Long.toUnsignedString(it) }
        val program = context.assets.open("rear-runtime-metadata.sh").bufferedReader().useLines { lines ->
            lines.joinToString(separator = "\n", postfix = "\n")
        }
        val identity = Binder.clearCallingIdentity()
        try {
            // Keep the script out of su's command argument. Its shell reads the script directly.
            val result = RootHelper.executeRootCommandDetailed(
                "exec /system/bin/sh -eu -s $userId '$name' $args",
                stdin = program,
            )
            val error = when {
                result.timedOut -> "Root metadata operation timed out"
                result.exitCode != 0 || result.outputLimitExceeded ->
                    "Root could not preserve wallpaper metadata: ${result.stderr.trim().take(500)}"
                else -> null
            }
            return Bundle().apply { putString(ERROR, error) }
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    internal companion object {
        private const val PRESERVE = "preserve_runtime_metadata"
        private const val INODES = "inodes"
        private const val ERROR = "error"
        private val STAGED_NAME = Regex("""\.runtime\.json\.[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\.tmp""")
        private val URI = Uri.parse("content://${BuildConfig.APPLICATION_ID}.rear.runtime.metadata")

        fun preserve(
            context: Context,
            stagedName: String,
            staged: StructStat,
            original: SharedRuntimeFileStore.Identity,
        ) {
            val extras = Bundle().apply {
                putLongArray(INODES, longArrayOf(original.device, original.inode, staged.st_dev, staged.st_ino))
            }
            val result = context.contentResolver.call(URI, PRESERVE, stagedName, extras)
                ?: throw IOException("Wallpaper Root metadata provider is unavailable")
            result.getString(ERROR)?.let { throw IOException(it) }
        }
    }
}
