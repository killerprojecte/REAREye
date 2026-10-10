package hk.uwu.reareye.widgetapi

import android.os.Bundle

data class RearAppCardInfo(
    val appId: String,
    val title: String,
    val componentBusiness: String?,
    val templatePath: String,
    val bindPackage: String?,
    val ownedByRearEye: Boolean,
    val canRename: Boolean,
    val canDelete: Boolean,
    val canRenderPreview: Boolean,
    val appCardType: Int,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(RearAppApiContract.BundleKeys.APP_ID, appId)
        putString(RearAppApiContract.BundleKeys.TITLE, title)
        putString(RearAppApiContract.BundleKeys.COMPONENT_BUSINESS, componentBusiness)
        putString(RearAppApiContract.BundleKeys.TEMPLATE_PATH, templatePath)
        putString(RearAppApiContract.BundleKeys.BIND_PACKAGE, bindPackage)
        putBoolean(RearAppApiContract.BundleKeys.OWNED_BY_REAREYE, ownedByRearEye)
        putBoolean(RearAppApiContract.BundleKeys.CAN_RENAME, canRename)
        putBoolean(RearAppApiContract.BundleKeys.CAN_DELETE, canDelete)
        putBoolean(RearAppApiContract.BundleKeys.CAN_RENDER_PREVIEW, canRenderPreview)
        putInt(RearAppApiContract.BundleKeys.APP_CARD_TYPE, appCardType)
    }

    companion object {
        fun fromBundle(bundle: Bundle?): RearAppCardInfo? {
            bundle ?: return null
            val appId = bundle.getString(RearAppApiContract.BundleKeys.APP_ID)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            return RearAppCardInfo(
                appId = appId,
                title = bundle.getString(RearAppApiContract.BundleKeys.TITLE).orEmpty(),
                componentBusiness = bundle
                    .getString(RearAppApiContract.BundleKeys.COMPONENT_BUSINESS)
                    ?.takeIf { it.isNotBlank() },
                templatePath = bundle
                    .getString(RearAppApiContract.BundleKeys.TEMPLATE_PATH)
                    .orEmpty(),
                bindPackage = bundle
                    .getString(RearAppApiContract.BundleKeys.BIND_PACKAGE)
                    ?.takeIf { it.isNotBlank() },
                ownedByRearEye = bundle.getBoolean(
                    RearAppApiContract.BundleKeys.OWNED_BY_REAREYE,
                    false,
                ),
                canRename = bundle.getBoolean(RearAppApiContract.BundleKeys.CAN_RENAME, false),
                canDelete = bundle.getBoolean(RearAppApiContract.BundleKeys.CAN_DELETE, false),
                canRenderPreview = bundle.getBoolean(
                    RearAppApiContract.BundleKeys.CAN_RENDER_PREVIEW,
                    false,
                ),
                appCardType = bundle.getInt(RearAppApiContract.BundleKeys.APP_CARD_TYPE, 0),
            )
        }
    }
}

data class RearAppOperationResult(
    val success: Boolean,
    val error: String? = null,
    val appId: String? = null,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putBoolean(RearAppApiContract.BundleKeys.SUCCESS, success)
        putString(RearAppApiContract.BundleKeys.ERROR, error)
        putString(RearAppApiContract.BundleKeys.APP_ID, appId)
    }

    companion object {
        fun fromBundle(bundle: Bundle?): RearAppOperationResult {
            return RearAppOperationResult(
                success = bundle?.getBoolean(RearAppApiContract.BundleKeys.SUCCESS, false) == true,
                error = bundle?.getString(RearAppApiContract.BundleKeys.ERROR),
                appId = bundle?.getString(RearAppApiContract.BundleKeys.APP_ID),
            )
        }
    }
}
