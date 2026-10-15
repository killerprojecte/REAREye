package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.content.Context
import android.graphics.Bitmap
import java.util.concurrent.ExecutorService

class RearActivityAppsDataSource(
    context: Context,
    provider: RearActivityCatalogProvider? = null,
    executor: ExecutorService? = null,
) : RearActivityPageDataSource(context, provider, executor, false) {
    internal override fun snapshot(
        provider: RearActivityCatalogProvider, page: RearActivityPage,
        icon: (String) -> Bitmap?
    ): MamlDataSnapshot {
        val rows = RearActivityPage.apps(provider.applications())
        val selected = rows.drop(page.offset).take(page.limit)
        return MamlDataSnapshot(
            RearActivityPage.metadata(page, rows.size, selected.size) + mapOf(
                "appNames" to selected.map { it.appName }.toTypedArray(),
                "packageNames" to selected.map { it.packageName }.toTypedArray(),
                "icons" to selected.map { icon(it.packageName) }.toTypedArray(),
            ), selected.size
        )
    }

    companion object {
        const val TAG = "RearActivityAppsBinder"
        val schema = MamlDataSchema(
            listOf(
                MamlDataField("appNames", MamlDataType.STRING, array = true),
                MamlDataField("packageNames", MamlDataType.STRING, array = true),
                MamlDataField("icons", MamlDataType.BITMAP, array = true, nullable = true),
            ) + RearActivityPage.fields
        )

        fun definition() = MamlDataSourceDefinition(
            TAG, schema, RearActivityPage.parameters(false),
            MamlDataSourceFactory { RearActivityAppsDataSource(it) })
    }
}
