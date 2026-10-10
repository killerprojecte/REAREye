package hk.uwu.reareye.script

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlinx.coroutines.runBlocking

class ScriptDataProvider : ContentProvider() {
    private val columns =
        arrayOf("project_id", "provider_id", "mime_type", "data", "error", "elapsed_millis")

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val context = context ?: return MatrixCursor(columns)
        val segments = uri.pathSegments.filter { it.isNotBlank() }
        val projectId = segments.getOrNull(0).orEmpty()
        val providerId = segments.getOrNull(1).orEmpty()
        val params: Map<String, Any?> = buildMap {
            uri.queryParameterNames
                .filterNot { it == "project" || it == "provider" }
                .forEach { key ->
                    val values = uri.getQueryParameters(key)
                    put(key, if (values.size == 1) values.first() else values)
                }
        }
        val project = ScriptProjectStore(context).project(
            uri.getQueryParameter("project")?.takeIf { it.isNotBlank() } ?: projectId
        )
        val result = if (project == null) {
            ScriptExecutionResult(projectId, providerId, error = "Script project not found")
        } else {
            runBlocking {
                ScriptExecutor(context).execute(
                    project,
                    uri.getQueryParameter("provider") ?: providerId,
                    params
                )
            }
        }
        return MatrixCursor(columns).apply {
            addRow(
                arrayOf<Any?>(
                    result.projectId,
                    result.providerId,
                    result.mimeType,
                    result.data?.toString().orEmpty(),
                    result.error.orEmpty(),
                    result.elapsedMillis,
                )
            )
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.reareye.script"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
