package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.content.Context
import android.graphics.Bitmap
import java.util.concurrent.ExecutorService

class RearActivitiesDataSource(
    context: Context,
    provider: RearActivityCatalogProvider? = null,
    executor: ExecutorService? = null,
) : RearActivityPageDataSource(context, provider, executor, true) {
    internal override fun snapshot(
        provider: RearActivityCatalogProvider, page: RearActivityPage,
        icon: (String) -> Bitmap?
    ): MamlDataSnapshot {
        val rows = if (page.packageName.isBlank()) emptyList() else
            RearActivityPage.activities(provider.activities(page.packageName), page.packageName)
        val selected = rows.drop(page.offset).take(page.limit)
        return MamlDataSnapshot(
            RearActivityPage.metadata(page, rows.size, selected.size) + mapOf(
                "packageName" to page.packageName,
                "components" to selected.map { it.component.flattenToString() }.toTypedArray(),
                "activityNames" to selected.map { it.activityName }.toTypedArray(),
                "activityClasses" to selected.map { it.activityClassName }.toTypedArray(),
                "icons" to selected.map { icon(it.component.packageName) }.toTypedArray(),
            ), selected.size
        )
    }

    companion object {
        const val TAG = "RearActivitiesBinder"
        val schema = MamlDataSchema(
            listOf(
                MamlDataField("packageName", MamlDataType.STRING),
                MamlDataField("components", MamlDataType.STRING, array = true),
                MamlDataField("activityNames", MamlDataType.STRING, array = true),
                MamlDataField("activityClasses", MamlDataType.STRING, array = true),
                MamlDataField("icons", MamlDataType.BITMAP, array = true, nullable = true),
            ) + RearActivityPage.fields
        )

        fun definition() = MamlDataSourceDefinition(
            TAG, schema, RearActivityPage.parameters(true),
            MamlDataSourceFactory { RearActivitiesDataSource(it) })
    }
}
