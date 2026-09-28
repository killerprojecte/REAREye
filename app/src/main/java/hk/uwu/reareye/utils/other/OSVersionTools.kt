package hk.uwu.reareye.utils.other

import android.content.Context
import android.util.Log
import hk.uwu.reareye.R
import hk.uwu.reareye.utils.other.DeviceConfigTools.getSystemProperties
import java.util.regex.Pattern

object OSVersionTools {

    private const val TAG = "OSVersionTools"
    private val VERSION_PATTERN: Pattern = Pattern.compile("^[a-zA-Z][0-9]{1,3}$")

    fun getXmsVersion(): String {
        return getSystemProperties("persist.sys.xms.version")
    }

    fun getRoXmsVersion(): String {
        return getSystemProperties("ro.mi.xms.version.incremental")
    }

    fun getOsVersionCode(): String {
        val str = getSystemProperties("ro.mi.os.version.incremental")
        return if (str.isEmpty() || !str.startsWith("OS") || str.length <= 2) {
            str
        } else {
            str.substring(2)
        }
    }

    fun addVersionSuffix(context: Context?): String {
        var xmsVersion = getXmsVersion()
        val roXmsVersion = getRoXmsVersion()
        val osVersionCode = getOsVersionCode()

        val isXmsValid = isValid(xmsVersion)
        val isRoValid = isValid(roXmsVersion)

        if (!isXmsValid && !isRoValid) {
            return osVersionCode
        }

        if (!isXmsValid || isRoValid) {
            xmsVersion = if (isXmsValid) {
                compareValidVersion(xmsVersion, roXmsVersion)
            } else {
                roXmsVersion
            }
        }

        return insertSuffixBeforeBeta(context, osVersionCode, xmsVersion)
    }

    private fun isValid(str: String?): Boolean {
        return !str.isNullOrEmpty() && VERSION_PATTERN.matcher(str).matches()
    }

    private fun compareValidVersion(s: String, s2: String): String {
        val c1 = s[0].lowercaseChar()
        val c2 = s2[0].lowercaseChar()

        if (c1 != c2) {
            return if (c1 > c2) s else s2
        }

        return try {
            val v1 = s.substring(1).toInt()
            val v2 = s2.substring(1).toInt()
            if (v1 >= v2) s else s2
        } catch (e: Exception) {
            Log.d(TAG, "compareValidVersion: parse failed $e")
            s
        }
    }

    private fun insertSuffixBeforeBeta(
        context: Context?,
        base: String,
        suffix: String
    ): String {
        var tail: String? = null

        val devString = context?.getString(R.string.developer_build)

        if (devString != null && base.endsWith(devString)) {
            tail = devString
        }

        if (base.endsWith("Beta")) {
            tail = "Beta"
        }

        return if (tail != null) {
            base.removeSuffix(tail).trim() + ".$suffix $tail"
        } else {
            "$base.$suffix"
        }
    }
}
