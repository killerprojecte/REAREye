package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.content.ComponentName
import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** A missing binding is not the same as an unreadable binding: only the former may allocate. */
internal class WidgetBindingStore(
    private val preferences: SharedPreferences,
    private val recoveredRead: (Throwable) -> Unit = {}
) {
    fun read(key: String): WidgetBinding? {
        val storageKey = "binding:$key"
        val failure = try {
            val raw = preferences.getString(storageKey, null) ?: return null
            return decode(raw)
        } catch (error: JSONException) {
            error
        } catch (error: ClassCastException) {
            // SharedPreferences throws this when a stored entry has a non-string type.
            error
        }

        // Typed getters can be modified independently in a hooked host. Recover only from an
        // independently valid snapshot, never from a guessed ID/provider or a missing entry.
        // This also leaves genuinely malformed persisted data intact for diagnosis and repair.
        val binding = try {
            val raw = preferences.all[storageKey]
            if (raw !is String) throw JSONException("Binding snapshot is missing or not a string")
            decode(raw)
        } catch (snapshotFailure: Exception) {
            throw IllegalStateException(
                "Saved widget binding is unreadable; existing data was preserved",
                failure
            ).apply { addSuppressed(snapshotFailure) }
        }
        // Logging is best effort and must not turn a successful restoration into a failure.
        runCatching { recoveredRead(failure) }
        return binding
    }

    fun write(key: String, binding: WidgetBinding?) {
        val editor = preferences.edit()
        if (binding == null) editor.remove("binding:$key")
        else editor.putString("binding:$key", encode(binding))
        check(editor.commit()) { "Cannot persist widget binding" }
    }

    companion object {
        internal fun encode(binding: WidgetBinding): String = JSONObject()
            .put("id", binding.id).put("provider", binding.provider)
            .put("complete", binding.complete).put("phase", binding.phase?.name).toString()

        internal fun decode(raw: String): WidgetBinding {
            if ('\u0000' in raw) throw JSONException("Unexpected NUL in widget binding")
            val tokens = JSONTokener(raw)
            val json = JSONObject(tokens)
            if (tokens.nextClean() != '\u0000') {
                throw JSONException("Unexpected content after widget binding object")
            }
            val id = json.get("id")
            if (id !is Number || id.toDouble() != id.toInt().toDouble() || id.toInt() <= 0) {
                throw JSONException("Widget binding ID must be a positive integer")
            }
            val provider = json.get("provider")
            if (provider !is String || provider.count { it == '/' } != 1 ||
                provider.any { it.isWhitespace() }
            ) throw JSONException("Widget binding provider must be a flattened ComponentName")
            val component = ComponentName.unflattenFromString(provider)
            if (component == null || component.packageName.isEmpty() || component.className.isEmpty()) {
                throw JSONException("Widget binding provider must be a flattened ComponentName")
            }
            val complete = json.get("complete")
            if (complete !is Boolean) throw JSONException("Widget binding complete must be boolean")
            // Records written before phase tracking are still valid. Unknown phases are not
            // silently downgraded to legacy ALLOCATED behavior.
            val phase = if (!json.has("phase") || json.isNull("phase")) null else {
                val value = json.get("phase")
                if (value == "") null else WidgetPhase.entries.firstOrNull { it.name == value }
                    ?: throw JSONException("Unknown widget binding phase")
            }
            if (phase != null && complete != (phase == WidgetPhase.COMPLETE)) {
                throw JSONException("Widget binding phase disagrees with completion")
            }
            return WidgetBinding(id.toInt(), provider, complete, phase)
        }
    }
}
