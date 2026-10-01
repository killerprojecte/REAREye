package hk.uwu.reareye.script

import party.iroiro.luajava.JFunction
import party.iroiro.luajava.Lua
import party.iroiro.luajava.luajit.LuaJit
import party.iroiro.luajava.value.LuaValue
import java.nio.ByteBuffer

/** No Android, UI, storage singleton or IPC dependencies. Each session owns one Lua state. */
class ScriptRuntime(private val host: ScriptHost = object : ScriptHost {}) {
    fun open(
        project: ScriptProject,
        drafts: Map<String, String> = emptyMap(),
        cancellation: ScriptCancellation = ScriptCancellation(),
        timeoutMillis: Long = 5_000,
    ): Session = Session(project, drafts, cancellation, timeoutMillis)

    inner class Session internal constructor(
        private val project: ScriptProject,
        private val drafts: Map<String, String>,
        private val cancellation: ScriptCancellation,
        timeoutMillis: Long,
    ) : AutoCloseable {
        private val lua = LuaJit()
        private val deadline = System.nanoTime() + timeoutMillis.coerceIn(1, 30_000) * 1_000_000
        private var instructionChecks = 0
        private val output = mutableListOf<String>()
        private val handlers = linkedMapOf<String, LuaValue>()
        private lateinit var context: LuaValue
        val descriptor: ScriptDescriptor

        init {
            try {
                lua.openLibraries()
                bind("__budget") { state ->
                    val error = when {
                        cancellation.isCancelled() -> "Execution cancelled"
                        System.nanoTime() >= deadline -> "Execution timed out"
                        ++instructionChecks > 50_000 -> "Instruction limit exceeded"
                        else -> null
                    }
                    if (error == null) state.pushNil() else state.push(error)
                    1
                }
                bind("__read") { state ->
                    try {
                        require(project.kind != ScriptProjectKind.STANDALONE) { "Standalone scripts cannot load files" }
                        val path = state.toString(1) ?: error("Expected a relative file path")
                        ScriptFiles.resolve(project.root, path)
                        val text = drafts[path] ?: ScriptFiles.read(project.root, path)
                        require(text.toByteArray().size <= ScriptFiles.MAX_FILE_BYTES) { "File too large" }
                        state.push(text)
                        1
                    } catch (e: Exception) {
                        state.error(e.message ?: "Cannot read project file"); 0
                    }
                }
                bind("__now") { state -> state.push(host.now()); 1 }
                bind("__log") { state ->
                    val message = (state.toString(1) ?: "").take(4096)
                    if (output.size < 200) {
                        output += message; host.log(message)
                    }
                    0
                }
                bind("__root_exec") { state ->
                    try {
                        val command = state.toString(1)?.takeIf { it.isNotBlank() }
                            ?: error("Expected a root command")
                        state.pushValue(2)
                        val options = state.get()
                        val request = ScriptRootCommand(
                            command = command,
                            stdin = options.optionalString("stdin").orEmpty(),
                            timeoutMillis = options.optionalLong("timeoutMillis")
                                ?.coerceIn(100L, 120_000L) ?: 10_000L,
                        )
                        val result = host.rootExec(request)
                        val table = state.eval("return {}").first()
                        table.set("exitCode", toLua(result.exitCode))
                        table.set("stdout", toLua(result.stdout))
                        table.set("stderr", toLua(result.stderr))
                        table.set("timedOut", toLua(result.timedOut))
                        table.set("outputLimitExceeded", toLua(result.outputLimitExceeded))
                        state.push(table)
                        1
                    } catch (e: Exception) {
                        state.error(e.message ?: "Root command failed")
                        0
                    }
                }
                bind("__http_request") { state ->
                    try {
                        state.pushValue(1)
                        val requestTable = state.get()
                        require(requestTable.type() == Lua.LuaType.TABLE) {
                            "Expected an HTTP request table"
                        }
                        val url = requestTable.string("url")
                        require(url.isNotBlank()) { "HTTP URL cannot be blank" }
                        val query = requestTable.get("query").let { queryTable ->
                            if (queryTable.type() == Lua.LuaType.NIL) {
                                emptyMap()
                            } else {
                                require(queryTable.type() == Lua.LuaType.TABLE) {
                                    "HTTP query must be a table"
                                }
                                queryTable.entries.associate { entry ->
                                    require(entry.key.type() == Lua.LuaType.STRING) {
                                        "HTTP query names must be strings"
                                    }
                                    entry.key.toJavaObject()
                                        .toString() to entry.value.toJavaObject().toString()
                                }
                            }
                        }
                        val headers = requestTable.get("headers").let { headerTable ->
                            if (headerTable.type() == Lua.LuaType.NIL) {
                                emptyMap()
                            } else {
                                require(headerTable.type() == Lua.LuaType.TABLE) {
                                    "HTTP headers must be a table"
                                }
                                headerTable.entries.associate { entry ->
                                    require(entry.key.type() == Lua.LuaType.STRING) {
                                        "HTTP header names must be strings"
                                    }
                                    require(entry.value.type() == Lua.LuaType.STRING) {
                                        "HTTP header values must be strings"
                                    }
                                    entry.key.toJavaObject().toString() to
                                            entry.value.toJavaObject().toString()
                                }
                            }
                        }
                        val result = host.httpRequest(
                            ScriptHttpRequest(
                                url = url,
                                method = requestTable.string("method", "GET").uppercase(),
                                query = query,
                                headers = headers,
                                body = requestTable.optionalString("body"),
                                timeoutMillis = requestTable.optionalLong("timeoutMillis")
                                    ?.coerceIn(100L, 120_000L) ?: 15_000L,
                                followRedirects = requestTable.optionalBoolean("followRedirects")
                                    ?: true,
                                followSslRedirects = requestTable.optionalBoolean("followSslRedirects")
                                    ?: true,
                            )
                        )
                        val table = state.eval("return {}").first()
                        val headersTable = state.eval("return {}").first()
                        result.headers.forEach { (key, value) -> headersTable.set(key, value) }
                        table.set("statusCode", toLua(result.statusCode))
                        table.set("ok", toLua(result.ok))
                        table.set("headers", headersTable)
                        table.set("body", toLua(result.body))
                        table.set("contentType", toLua(result.contentType))
                        table.set("url", toLua(result.url))
                        state.push(table)
                        1
                    } catch (e: Exception) {
                        state.error(e.message ?: "HTTP request failed")
                        0
                    }
                }
                lua.run(BOOTSTRAP)
                // getGlobal pushes the value and get consumes that stack slot. Keep the
                // object in the session so it can be passed as the third handler argument.
                lua.getGlobal("__context")
                context = lua.get()
                context.set("projectId", project.id)
                project.componentId?.let { context.set("componentId", it) }
                lua.pushNil(); lua.setGlobal("__context")
                if (project.kind == ScriptProjectKind.STANDALONE) {
                    lua.run("require = nil; context_read_disabled = true")
                    context.set("readText", lua.eval("return nil").first())
                }
                val mainPath =
                    if (project.kind == ScriptProjectKind.STANDALONE) project.mainFile.name else "main.lua"
                val source = drafts[mainPath] ?: ScriptFiles.read(project.root, mainPath)
                require(!source.startsWith('\u001b')) { "Lua bytecode is not accepted" }
                val bytes = source.toByteArray(Charsets.UTF_8)
                require(bytes.size <= ScriptFiles.MAX_FILE_BYTES) { "File too large" }
                val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
                lua.load(buffer, "@$mainPath")
                lua.pCall(0, 1)
                val exports = lua.get()
                require(exports.type() == Lua.LuaType.TABLE) { "Entry must return a metadata table" }
                descriptor = parseDescriptor(exports)
            } catch (t: Throwable) {
                lua.close()
                throw t
            }
        }

        fun execute(
            providerId: String,
            params: Map<String, Any?>,
            config: Map<String, Any?>
        ): ScriptExecutionResult {
            val start = System.nanoTime()
            val provider = descriptor.providers.firstOrNull { it.id == providerId }
                ?: error("Unknown provider: $providerId")
            val normalized = descriptor.configs.associate { spec ->
                spec.id to normalizeConfig(
                    spec,
                    config[spec.id] ?: spec.defaultValue
                )
            }
            val result =
                handlers.getValue(providerId).call(toLua(params), toLua(normalized), context)
                    .firstOrNull()
            return ScriptExecutionResult(
                project.id,
                providerId,
                plain(result),
                elapsedMillis = (System.nanoTime() - start) / 1_000_000,
                mimeType = provider.mimeType,
                logs = output.toList(),
                expiresAt = if (provider.ttlMillis > 0) host.now() + provider.ttlMillis else 0
            )
        }

        private fun bind(name: String, function: (Lua) -> Int) {
            lua.push(JFunction(function)); lua.setGlobal(name)
        }

        private fun parseDescriptor(exports: LuaValue): ScriptDescriptor {
            val providerTable = exports.get("providers")
            require(providerTable.type() == Lua.LuaType.TABLE) { "providers must be a table" }
            val providers = providerTable.entries.map { entry ->
                val id = entry.key.toJavaObject().toString()
                require(ID.matches(id)) { "Invalid provider id: $id" }
                val value = entry.value
                val simple = value.type() == Lua.LuaType.FUNCTION
                require(simple || value.type() == Lua.LuaType.TABLE) { "Provider $id must be a function or table" }
                val handler = if (simple) value else value.get("handler")
                require(handler.type() == Lua.LuaType.FUNCTION) { "Provider $id needs a handler function" }
                handlers[id] = handler
                val mode =
                    if (simple) "IMMEDIATE" else value.string("mode", "IMMEDIATE").uppercase()
                require(mode in MODES) { "Unknown cache mode: $mode" }
                val ttl = if (simple) 0 else value.number("ttlMillis")?.toLong() ?: 0
                require(ttl in 0..86_400_000) { "ttlMillis must be between 0 and 86400000" }
                ScriptProvider(
                    id,
                    if (simple) id else value.string("name", id),
                    if (simple) "" else value.string("description"),
                    if (simple) "text/plain" else value.string("mimeType", "text/plain"),
                    mode,
                    ttl
                )
            }
            val configs = mutableListOf<ScriptConfigSpec>()
            val configTable = exports.get("configs")
            if (configTable.type() != Lua.LuaType.NIL) {
                require(configTable.type() == Lua.LuaType.TABLE) { "configs must be a table" }
                configTable.entries.forEach { entry ->
                    val spec = entry.value
                    val id = spec.string("id")
                    require(ID.matches(id) && configs.none { it.id == id }) { "Invalid or duplicate config id: $id" }
                    val type = spec.string("type", "STRING").uppercase()
                    require(
                        type in setOf(
                            "STRING",
                            "NUMBER",
                            "BOOLEAN",
                            "OPTIONS"
                        )
                    ) { "Unknown config type: $type" }
                    val options = linkedMapOf<String, String>()
                    if (type == "OPTIONS") spec.get("options").entries.forEach {
                        val option = it.value
                        val key = option.string("id")
                        require(key.isNotBlank() && !options.containsKey(key)) { "Invalid option id" }
                        options[key] = option.string("name", key)
                    }
                    val min = spec.number("min")
                    val max = spec.number("max")
                    val step = spec.number("step")
                    require(min == null || max == null || min <= max) { "Invalid numeric range for $id" }
                    require(step == null || step > 0) { "Invalid numeric step for $id" }
                    configs += ScriptConfigSpec(
                        id,
                        spec.string("name", id),
                        type,
                        spec.string("note"),
                        plain(spec.get("default")),
                        min,
                        max,
                        step,
                        options
                    )
                }
            }
            val declaredId = exports.string("id", project.mainFile.nameWithoutExtension)
            require(ID.matches(declaredId)) { "Invalid script id: $declaredId" }
            return ScriptDescriptor(
                declaredId,
                exports.string("name", project.name),
                exports.string("description"),
                exports.string("group"),
                providers,
                configs
            )
        }

        private fun toLua(value: Any?): LuaValue {
            when (value) {
                null -> lua.pushNil()
                is Boolean -> lua.push(value)
                is Number -> lua.push(value)
                is String -> lua.push(value)
                is Map<*, *> -> {
                    val table = lua.eval("return {}").first()
                    value.forEach { (key, item) -> table.set(key.toString(), toLua(item)) }
                    return table
                }

                is List<*> -> {
                    val table = lua.eval("return {}").first()
                    value.forEachIndexed { index, item -> table.set(index + 1, toLua(item)) }
                    return table
                }

                else -> error("Unsupported argument type")
            }
            return lua.get()
        }

        override fun close() {
            lua.close()
        }
    }

    companion object {
        private val ID = Regex("^[A-Za-z0-9_.-]+$")
        private val MODES = setOf("IMMEDIATE", "CACHE_FIRST", "CACHE_ONLY", "REFRESH")
        private fun LuaValue.string(key: String, fallback: String = ""): String {
            val value = get(key)
            if (value.type() == Lua.LuaType.NIL) return fallback
            require(value.type() == Lua.LuaType.STRING) { "$key must be a string" }
            return value.toJavaObject().toString()
        }

        private fun LuaValue.optionalString(key: String): String? = get(key).let {
            if (it.type() == Lua.LuaType.NIL) null else {
                require(it.type() == Lua.LuaType.STRING) { "$key must be a string" }
                it.toJavaObject().toString()
            }
        }

        private fun LuaValue.optionalLong(key: String): Long? = get(key).let {
            if (it.type() == Lua.LuaType.NIL) null else {
                require(it.type() == Lua.LuaType.NUMBER) { "$key must be a number" }
                it.toNumber().toLong()
            }
        }

        private fun LuaValue.optionalBoolean(key: String): Boolean? = get(key).let {
            if (it.type() == Lua.LuaType.NIL) null else {
                require(it.type() == Lua.LuaType.BOOLEAN) { "$key must be a boolean" }
                it.toBoolean()
            }
        }

        private fun LuaValue.number(key: String): Double? = get(key).let {
            if (it.type() == Lua.LuaType.NIL) null else {
                require(it.type() == Lua.LuaType.NUMBER) { "$key must be a number" }; it.toNumber()
            }
        }

        private fun plain(
            value: LuaValue?,
            depth: Int = 0,
            budget: IntArray = intArrayOf(10000)
        ): Any? {
            require(depth < 24 && --budget[0] >= 0) { "Result is cyclic or exceeds size limits" }
            return when (value?.type()) {
                null, Lua.LuaType.NIL -> null
                Lua.LuaType.STRING -> value.toJavaObject().toString()
                    .also { require(it.length <= 262144) { "Result too large" } }

                Lua.LuaType.NUMBER -> value.toNumber()
                    .also { require(it.isFinite()) { "Non-finite result" } }

                Lua.LuaType.BOOLEAN -> value.toBoolean()
                Lua.LuaType.TABLE -> {
                    val entries = value.entries.toList()
                    if (entries.isNotEmpty() && entries.all { it.key.type() == Lua.LuaType.NUMBER } && entries.map { it.key.toNumber() }
                            .toSet() == (1..entries.size).map { it.toDouble() }.toSet()) {
                        (1..entries.size).map { plain(value.get(it), depth + 1, budget) }
                    } else entries.associate {
                        require(it.key.type() == Lua.LuaType.STRING) { "Return tables must be arrays or have string keys" }
                        it.key.toJavaObject().toString() to plain(it.value, depth + 1, budget)
                    }
                }

                else -> error("Returned value must be data, not a function or userdata")
            }
        }

        fun normalizeConfig(spec: ScriptConfigSpec, value: Any?): Any = when (spec.type) {
            "NUMBER" -> {
                var number =
                    (value as? Number)?.toDouble() ?: value?.toString()?.toDoubleOrNull() ?: 0.0
                require(number.isFinite()) { "Invalid number: ${spec.id}" }
                spec.step?.let {
                    number = (spec.min ?: 0.0) + kotlin.math.round(
                        (number - (spec.min ?: 0.0)) / it
                    ) * it
                }
                number.coerceIn(spec.min ?: -Double.MAX_VALUE, spec.max ?: Double.MAX_VALUE)
            }

            "BOOLEAN" -> value == true || value?.toString() == "true"
            "OPTIONS" -> value?.toString()?.takeIf { it in spec.options }
                ?: spec.options.keys.firstOrNull().orEmpty()

            else -> value?.toString().orEmpty()
        }

        private val BOOTSTRAP = """
            jit.off()
            local budget, read, now, log = __budget, __read, __now, __log
            local root_exec, http_request = __root_exec, __http_request
            local compile, setenv, raise = loadstring, setfenv, error
            local function check() local err = budget(); if err then raise(err, 0) end end
            debug.sethook(check, "", 1000)
            local protected, xprotected = pcall, xpcall
            local function checked(...) check(); return ... end
            pcall = function(...) check(); return checked(protected(...)) end
            xpcall = function(...) check(); return checked(xprotected(...)) end
            local modules, loading = {}, {}
            require = function(name)
                check()
                assert(type(name) == "string" and name:match("^[%a_][%w_.]*$") and not name:find("..", 1, true), "Invalid module name")
                if modules[name] ~= nil then return modules[name] end
                assert(not loading[name], "Circular require: " .. name)
                local path = name:gsub("%.", "/") .. ".lua"
                local ok, source = protected(read, path)
                if not ok then path = name:gsub("%.", "/") .. "/init.lua"; source = read(path) end
                assert(source:byte(1) ~= 27, "Lua bytecode is not accepted")
                local chunk, err = compile(source, "@" .. path)
                assert(chunk, err)
                setenv(chunk, _G)
                loading[name] = true
                local value = chunk()
                loading[name] = nil
                if value == nil then value = true end
                modules[name] = value
                return value
            end
            print = function(...)
                local values = {}
                for i = 1, select('#', ...) do values[i] = tostring(select(i, ...)) end
                log(table.concat(values, '\t'))
            end
            local function http_with(method, url, body, options)
                options = options or {}
                options.url = url
                options.method = method
                options.body = body
                return http_request(options)
            end
            __context = {
                now = function() return now() end,
                log = function(_, text) log(tostring(text)) end,
                readText = function(_, path) return read(path) end,
                root = { exec = function(_, command, options) return root_exec(command, options) end },
                http = {
                    request = function(_, request) return http_request(request) end,
                    get = function(_, url, options) return http_with("GET", url, nil, options) end,
                    post = function(_, url, body, options) return http_with("POST", url, body, options) end,
                    put = function(_, url, body, options) return http_with("PUT", url, body, options) end,
                    patch = function(_, url, body, options) return http_with("PATCH", url, body, options) end,
                    delete = function(_, url, options) return http_with("DELETE", url, nil, options) end,
                },
            }
            os = {time = os.time, date = os.date, difftime = os.difftime, clock = os.clock}
            io = nil; package = nil; debug = nil; jit = nil; ffi = nil; java = nil; luajava = nil
            load = nil; loadstring = nil; loadfile = nil; dofile = nil; getfenv = nil; setfenv = nil; coroutine = nil
            __budget = nil; __read = nil; __now = nil; __log = nil
            __root_exec = nil; __http_request = nil
        """.trimIndent()
    }
}
