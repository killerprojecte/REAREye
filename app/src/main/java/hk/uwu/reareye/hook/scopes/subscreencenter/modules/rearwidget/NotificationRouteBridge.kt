package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification

internal object NotificationRouteBridgeContract {
    const val HOOK_HOST_PACKAGE = "com.xiaomi.subscreencenter"
    const val SOURCE_HOST_PACKAGE = "com.android.systemui"
    const val CARD_ID_PREFIX = "__ordinary_channel__"
    const val MODULE_PACKAGE = "hk.uwu.reareye"
    const val PENDING_DELETIONS_PREF = "rear_activity_card_pending_deletions"
    val deletionJournalLock = Any()

    fun validDeletionTarget(pkg: String?, business: String?, cardId: String?): Boolean =
        pkg == MODULE_PACKAGE && business != null && business.length in 1..128 &&
                Regex("[A-Za-z0-9_.-]+").matches(business) && cardId != null &&
                Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(cardId)

    fun validDeletionKeys(keys: Set<String>): Boolean =
        keys == setOf(Keys.PACKAGE_NAME, Keys.BUSINESS, Keys.CARD_ID)

    fun isTrustedDeletionCaller(callerUid: Int, moduleUid: Int, hostUid: Int): Boolean =
        callerUid >= 0 && moduleUid >= 0 && hostUid >= 0 && callerUid == moduleUid &&
                callerUid / 100_000 == hostUid / 100_000

    fun decodeDeletionRecord(record: String): Pair<String, String>? {
        val parts = record.split(':')
        return if (parts.size == 2 && validDeletionTarget(MODULE_PACKAGE, parts[0], parts[1])) {
            parts[0] to parts[1]
        } else null
    }

    fun deletionPayload(record: String): Bundle? {
        val (business, cardId) = decodeDeletionRecord(record) ?: return null
        return Bundle().apply {
            putString(Keys.PACKAGE_NAME, MODULE_PACKAGE)
            putString(Keys.BUSINESS, business)
            putString(Keys.CARD_ID, cardId)
        }
    }

    object Action {
        const val REQUEST_BINDER = "hk.uwu.reareye.notification.channel_route.REQUEST_BINDER"
    }

    object Subchannel {
        const val NOTIFICATION_POSTED = "notification_posted"
        const val NOTIFICATION_REMOVED = "notification_removed"
        const val ACTIVITY_CARD_DEFINITION_DELETED = "activity_card_definition_deleted"
    }

    object Keys {
        const val PACKAGE_NAME = "packageName"
        const val BUSINESS = "business"
        const val CARD_ID = "cardId"
        const val NOTIFICATION_ID = "notificationId"
        const val NOTIFICATION_KEY = "notificationKey"
        const val NOTIFICATION_TAG = "notificationTag"
        const val POST_TIME = "postTime"
        const val CHANNEL_ID = "channelId"
        const val TITLE = "title"
        const val TEXT = "text"
        const val BIG_TEXT = "bigText"
        const val SUB_TEXT = "subText"
        const val SHORT_CRITICAL_TEXT = "shortCriticalText"
        const val REMOVE_REASON = "removeReason"
    }
}

internal data class NotificationRouteSnapshot(
    val packageName: String,
    val notificationId: Int,
    val notificationKey: String?,
    val notificationTag: String?,
    val postTime: Long,
    val channelId: String,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val shortCriticalText: String?,
) {
    fun stableKey(): String {
        return notificationKey?.takeIf { it.isNotBlank() }
            ?: buildString {
                append(packageName)
                append(':').append(notificationId)
                notificationTag?.takeIf { it.isNotBlank() }?.let {
                    append(':').append(it)
                }
            }
    }

    fun cardId(): String {
        return "${NotificationRouteBridgeContract.CARD_ID_PREFIX}:${stableKey()}"
    }

    fun toBundle(): Bundle {
        return Bundle().apply {
            putString(NotificationRouteBridgeContract.Keys.PACKAGE_NAME, packageName)
            putInt(NotificationRouteBridgeContract.Keys.NOTIFICATION_ID, notificationId)
            putLong(NotificationRouteBridgeContract.Keys.POST_TIME, postTime)
            putString(NotificationRouteBridgeContract.Keys.CHANNEL_ID, channelId)
            putString(NotificationRouteBridgeContract.Keys.NOTIFICATION_KEY, notificationKey)
            putString(NotificationRouteBridgeContract.Keys.NOTIFICATION_TAG, notificationTag)
            putString(NotificationRouteBridgeContract.Keys.TITLE, title)
            putString(NotificationRouteBridgeContract.Keys.TEXT, text)
            putString(NotificationRouteBridgeContract.Keys.BIG_TEXT, bigText)
            putString(NotificationRouteBridgeContract.Keys.SUB_TEXT, subText)
            putString(
                NotificationRouteBridgeContract.Keys.SHORT_CRITICAL_TEXT,
                shortCriticalText,
            )
        }
    }

    fun toRemovalBundle(removeReason: Int): Bundle {
        return toBundle().apply {
            putInt(NotificationRouteBridgeContract.Keys.REMOVE_REASON, removeReason)
        }
    }

    companion object {
        fun fromBundle(bundle: Bundle?): NotificationRouteSnapshot? {
            if (bundle == null) return null
            val packageName = bundle.getString(NotificationRouteBridgeContract.Keys.PACKAGE_NAME)
                ?.trim()
                .orEmpty()
            val channelId = bundle.getString(NotificationRouteBridgeContract.Keys.CHANNEL_ID)
                ?.trim()
                .orEmpty()
            if (packageName.isBlank() || channelId.isBlank()) return null
            if (!bundle.containsKey(NotificationRouteBridgeContract.Keys.NOTIFICATION_ID)) return null

            return NotificationRouteSnapshot(
                packageName = packageName,
                notificationId = bundle.getInt(NotificationRouteBridgeContract.Keys.NOTIFICATION_ID),
                notificationKey = bundle.getString(NotificationRouteBridgeContract.Keys.NOTIFICATION_KEY)
                    ?.trim()
                    ?.ifBlank { null },
                notificationTag = bundle.getString(NotificationRouteBridgeContract.Keys.NOTIFICATION_TAG)
                    ?.trim()
                    ?.ifBlank { null },
                postTime = bundle.getLong(NotificationRouteBridgeContract.Keys.POST_TIME, 0L),
                channelId = channelId,
                title = bundle.getString(NotificationRouteBridgeContract.Keys.TITLE),
                text = bundle.getString(NotificationRouteBridgeContract.Keys.TEXT),
                bigText = bundle.getString(NotificationRouteBridgeContract.Keys.BIG_TEXT),
                subText = bundle.getString(NotificationRouteBridgeContract.Keys.SUB_TEXT),
                shortCriticalText = bundle.getString(
                    NotificationRouteBridgeContract.Keys.SHORT_CRITICAL_TEXT,
                ),
            )
        }

        fun fromStatusBarNotification(
            sbn: StatusBarNotification,
            requirePlainExtras: Boolean = true,
        ): NotificationRouteSnapshot? {
            val packageName = sbn.packageName?.trim().orEmpty()
            val channelId = sbn.notification.channelId?.trim()?.ifBlank { null } ?: return null
            if (packageName.isBlank()) return null

            val extras = sbn.notification.extras ?: Bundle.EMPTY
            if (requirePlainExtras) {
                if (!extras.getString("miui.focus.param").isNullOrBlank()) return null
                if (!extras.getString("miui.rear.param").isNullOrBlank()) return null
            }

            return NotificationRouteSnapshot(
                packageName = packageName,
                notificationId = sbn.id,
                notificationKey = sbn.key?.trim()?.ifBlank { null },
                notificationTag = sbn.tag?.trim()?.ifBlank { null },
                postTime = sbn.postTime,
                channelId = channelId,
                title = extractTextCandidate(
                    extras,
                    Notification.EXTRA_TITLE,
                    Notification.EXTRA_TITLE_BIG,
                ),
                text = extractTextCandidate(extras, Notification.EXTRA_TEXT),
                bigText = extractTextCandidate(extras, Notification.EXTRA_BIG_TEXT),
                subText = extractTextCandidate(
                    extras,
                    Notification.EXTRA_SUB_TEXT,
                    Notification.EXTRA_SUMMARY_TEXT,
                ),
                shortCriticalText = extractTextCandidate(extras, "android.shortCriticalText"),
            )
        }

        fun identityKeyFor(sbn: StatusBarNotification): String? {
            return fromStatusBarNotification(sbn, requirePlainExtras = false)?.stableKey()
        }

        private fun extractTextCandidate(extras: Bundle, vararg keys: String): String? {
            keys.forEach { key ->
                val text = extras.getCharSequence(key)?.toString()?.trim().orEmpty()
                if (text.isNotBlank()) return text
            }
            return null
        }
    }
}
