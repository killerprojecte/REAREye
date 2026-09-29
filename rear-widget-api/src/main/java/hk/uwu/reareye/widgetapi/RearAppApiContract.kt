package hk.uwu.reareye.widgetapi

object RearAppApiContract {
    const val SERVICE_PERMISSION = RearWidgetApiContract.SERVICE_PERMISSION
    const val HOOK_HOST_PACKAGE = RearWidgetApiContract.HOOK_HOST_PACKAGE
    const val ACTION_REQUEST_HOOK_SERVICE = "hk.uwu.reareye.appapi.REQUEST_HOOK_SERVICE"

    object Extras {
        const val BUNDLE = "bundle"
        const val BINDER = "binder"
        const val FORCE_SYNC = "forceSync"
    }

    object BundleKeys {
        const val ITEMS = "items"
        const val APP_ID = "appId"
        const val TITLE = "title"
        const val COMPONENT_BUSINESS = "componentBusiness"
        const val TEMPLATE_PATH = "templatePath"
        const val BIND_PACKAGE = "bindPackage"
        const val OWNED_BY_REAREYE = "ownedByRearEye"
        const val CAN_RENAME = "canRename"
        const val CAN_DELETE = "canDelete"
        const val CAN_RENDER_PREVIEW = "canRenderPreview"
        const val APP_CARD_TYPE = "appCardType"
        const val ORDERED_APP_IDS = "orderedAppIds"
        const val SUCCESS = "success"
        const val ERROR = "error"
    }
}
