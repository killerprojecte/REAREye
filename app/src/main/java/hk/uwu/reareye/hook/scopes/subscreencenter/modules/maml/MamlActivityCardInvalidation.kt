package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.content.Context
import java.io.File

/** Called only after the transport verifies the module Binder UID and current user. */
object MamlActivityCardInvalidation {
    private val lock = Any()
    private val listeners = mutableSetOf<(String) -> Unit>()

    internal fun observe(retire: (String) -> Unit): AutoCloseable {
        synchronized(lock) { listeners += retire }
        return AutoCloseable { synchronized(lock) { listeners -= retire } }
    }

    /** Null means malformed/unavailable evidence: refuse restoration, but do not erase data. */
    internal fun definitionExists(raw: String, business: String, cardId: String): Boolean? =
        runCatching {
            require(raw.length <= 4_194_304)
            val rows = org.json.JSONArray(raw)
            var found = false
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val id = row.getString("id")
                val pkg = row.getString("packageName")
                val name = row.getString("business")
                require(id.isNotBlank() && pkg.isNotBlank() && name.isNotBlank())
                if (id == cardId && pkg == "hk.uwu.reareye" && name == business) found = true
            }
            found
        }.getOrNull()

    fun deleteDefinition(context: Context, business: String, cardId: String): Boolean {
        val identity = MamlCardScopeFiles.moduleIdentity(
            "hk.uwu.reareye", "hk.uwu.reareye", business, cardId,
        ) ?: return false
        return runCatching {
            // Retire every live root before erasing disk state. No old callback can recreate it.
            val callbacks = synchronized(lock) { listeners.toList() }
            callbacks.forEach { it(identity) }
            val app = context.applicationContext ?: context
            val file =
                MamlCardScopeFiles(File(app.filesDir, "reareye-maml-cards-v1")).config(identity)
            ActivityHostSelectionStore.delete(file)
            true
        }.getOrDefault(false)
    }
}
