package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Generation checks and all disk operations share one lock: delete wins over late callbacks. */
internal class ActivityHostSelectionStore private constructor(
    val file: File,
    private val epoch: Long
) {
    interface Persistence {
        fun read(file: File): String?
        fun write(file: File, content: String)
        fun delete(file: File)
    }

    @Volatile
    private var retired = false
    fun retire() = synchronized(lock) { retired = true }
    fun isActive(): Boolean = synchronized(lock) { !retired && generations[file.path] == epoch }
    fun <T> active(block: () -> T): T = synchronized(lock) {
        check(!retired && generations[file.path] == epoch) { "Card binding was deleted or retired" }
        block()
    }

    fun restore(): ActivityHostSelectionState = active {
        val raw = persistence.read(file)
        if (raw == null) ActivityHostSelectionState(MamlCardScopeFiles.newInstance()).also(::write)
        else decode(raw)
    }

    fun write(state: ActivityHostSelectionState) = active {
        persistence.write(file, encode(state.validate()))
    }

    companion object {
        private val lock = Any()
        private val generations = mutableMapOf<String, Long>()
        internal var persistence: Persistence = object : Persistence {
            override fun read(file: File): String? {
                val atomic = AtomicFile(file)
                if (!file.exists() && !File(file.path + ".bak").exists()) return null
                return atomic.openRead().use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count =
                            stream.read(buffer, 0, minOf(buffer.size, 1_048_577 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        require(output.size() <= 1_048_576) { "Card selection file too large" }
                    }
                    output.toByteArray().toString(Charsets.UTF_8)
                }
            }

            override fun write(file: File, content: String) {
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                val atomic = AtomicFile(file)
                val stream = atomic.startWrite()
                try {
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    require(bytes.size <= 1_048_576) { "Card selection file too large" }
                    stream.write(bytes)
                    atomic.finishWrite(stream)
                    // AtomicFile may only log a failed rename. Never publish launch on silent failure.
                    check(read(file) == content) { "Atomic Activity selection commit did not persist" }
                } catch (error: Throwable) {
                    atomic.failWrite(stream)
                    throw error
                }
            }

            override fun delete(file: File) {
                AtomicFile(file).delete()
            }
        }

        /** The XML path is issued by trusted MamlCardScopeFiles, never by the MAML document. */
        fun selectionFile(cardFile: File): File {
            require(Regex("[0-9a-f]{64}\\.xml").matches(cardFile.name))
            val absolute = cardFile.absoluteFile
            require(absolute.path.split(File.separatorChar).none { it == "." || it == ".." }) {
                "Card path contains traversal segments"
            }
            val cards = requireNotNull(absolute.parentFile)
            require(cards.name == "reareye-maml-cards-v1")
            // Context.filesDir may contain platform aliases (e.g. /data/user/0 -> /data/data).
            // Resolve only that trusted ancestor; card/selection children must not redirect.
            val filesDir = requireNotNull(cards.parentFile).canonicalFile
            val expectedCards = File(filesDir, "reareye-maml-cards-v1")
            require(cards.canonicalFile == expectedCards) { "Card directory escapes files directory" }
            require(cardFile.canonicalFile == File(expectedCards, cardFile.name)) {
                "Card file redirects outside its binding"
            }
            val target = File(
                File(filesDir, "reareye-maml-activities-v1"),
                "${cardFile.nameWithoutExtension}.json"
            )
            require(target.canonicalFile == target) { "Noncanonical selection path" }
            return target
        }

        fun open(cardFile: File): ActivityHostSelectionStore = synchronized(lock) {
            val file = selectionFile(cardFile)
            val generation = generations.getOrPut(file.path) { 0L }
            ActivityHostSelectionStore(file, generation)
        }

        fun delete(cardFile: File) = synchronized(lock) {
            val file = selectionFile(cardFile)
            generations[file.path] = (generations[file.path] ?: 0L) + 1L
            persistence.delete(file)
            check(
                listOf(
                    file,
                    File(file.path + ".bak"),
                    File(file.path + ".new")
                ).none { it.exists() }) {
                "Cannot permanently delete Activity selection"
            }
        }

        internal fun encode(state: ActivityHostSelectionState): String {
            val entries = JSONObject()
            state.entries.forEach { (pkg, entry) ->
                entries.put(
                    pkg,
                    JSONObject().put("component", entry.component).put("dpi", entry.dpi)
                )
            }
            return JSONObject().put("version", 1).put("instance", state.instance)
                .put("activePackage", state.activePackage ?: JSONObject.NULL)
                .put("entries", entries).toString()
        }

        internal fun decode(raw: String): ActivityHostSelectionState {
            require(raw.length <= 1_048_576)
            val json = JSONObject(raw)
            require(json.getInt("version") == 1)
            val saved = json.getJSONObject("entries")
            require(saved.length() <= 256)
            val entries = mutableMapOf<String, ActivityHostSelectionState.Entry>()
            saved.keys().forEach { pkg ->
                val entry = saved.getJSONObject(pkg)
                val dpiValue = entry.get("dpi")
                require(dpiValue is Number && dpiValue.toDouble() == dpiValue.toInt().toDouble())
                entries[pkg] =
                    ActivityHostSelectionState.Entry(entry.getString("component"), dpiValue.toInt())
            }
            return ActivityHostSelectionState(
                json.getString("instance"),
                if (json.isNull("activePackage")) null else json.getString("activePackage"), entries
            ).validate()
        }
    }
}
