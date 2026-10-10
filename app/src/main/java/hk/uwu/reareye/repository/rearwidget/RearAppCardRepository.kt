package hk.uwu.reareye.repository.rearwidget

import android.content.Context
import android.os.Bundle
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.widgetapi.RearAppApiClient
import hk.uwu.reareye.widgetapi.RearAppApiContract
import hk.uwu.reareye.widgetapi.RearAppCardInfo
import hk.uwu.reareye.widgetapi.RearAppOperationResult

object RearAppCardRepository {
    @Volatile
    private var remoteClient: RearAppApiClient? = null

    fun loadCatalog(context: Context): List<RearAppCardInfo> {
        return runCatching {
            withClient(context) { client -> parseCatalog(client.getCatalog()) }
        }.getOrDefault(emptyList())
    }

    fun register(
        context: Context,
        prefsManager: PrefsManager,
        title: String,
        componentBusiness: String,
    ): RearAppOperationResult {
        return runCatching {
            RearWidgetManagerRepository.refreshRuntimeFromPrefs(context, prefsManager)
            withClient(context) { client ->
                RearAppOperationResult.fromBundle(
                    client.registerAppCard(title.trim(), componentBusiness.trim())
                )
            }
        }.getOrElse { failure(it) }
    }

    fun rename(
        context: Context,
        appId: String,
        title: String,
    ): RearAppOperationResult {
        return runCatching {
            withClient(context) { client ->
                RearAppOperationResult.fromBundle(client.renameAppCard(appId.trim(), title.trim()))
            }
        }.getOrElse { failure(it) }
    }

    fun delete(context: Context, appId: String): RearAppOperationResult {
        return runCatching {
            withClient(context) { client ->
                RearAppOperationResult.fromBundle(client.deleteAppCard(appId.trim()))
            }
        }.getOrElse { failure(it) }
    }

    fun renderPreview(context: Context, appId: String): RearAppOperationResult {
        return runCatching {
            withClient(context) { client ->
                RearAppOperationResult.fromBundle(
                    client.renderAppCardPreview(appId.trim())
                )
            }
        }.getOrElse { failure(it) }
    }

    fun reorder(context: Context, orderedAppIds: List<String>): RearAppOperationResult {
        return runCatching {
            withClient(context) { client ->
                RearAppOperationResult.fromBundle(client.reorderAppCards(orderedAppIds))
            }
        }.getOrElse { failure(it) }
    }

    @Suppress("DEPRECATION")
    private fun parseCatalog(bundle: Bundle): List<RearAppCardInfo> {
        return bundle
            .getParcelableArrayList<Bundle>(RearAppApiContract.BundleKeys.ITEMS)
            .orEmpty()
            .mapNotNull(RearAppCardInfo::fromBundle)
    }

    private fun failure(throwable: Throwable): RearAppOperationResult {
        return RearAppOperationResult(
            success = false,
            error = throwable.message ?: "RearApp API service is unavailable",
        )
    }

    @Synchronized
    private fun <T> withClient(context: Context, block: (RearAppApiClient) -> T): T {
        val appContext = context.applicationContext
        val client = remoteClient ?: RearAppApiClient().also { remoteClient = it }
        return runCatching {
            if (!client.bind(appContext)) {
                error("RearApp API service is not connected")
            }
            block(client)
        }.onFailure {
            client.unbind()
            if (remoteClient === client) remoteClient = null
        }.getOrThrow()
    }
}
