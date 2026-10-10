package hk.uwu.reareye.ui.components.script

import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.dingyi222666.monarch.languages.LuaLanguage
import io.github.rosemoe.sora.langs.monarch.MonarchColorScheme
import io.github.rosemoe.sora.langs.monarch.MonarchLanguage
import io.github.rosemoe.sora.langs.monarch.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.monarch.registry.MonarchGrammarRegistry
import io.github.rosemoe.sora.langs.monarch.registry.dsl.monarchLanguages
import io.github.rosemoe.sora.langs.monarch.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.monarch.registry.model.ThemeSource
import io.github.rosemoe.sora.langs.monarch.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion

class LuaCodeEditorState internal constructor() {
    internal val editor = mutableStateOf<CodeEditor?>(null)
    internal var appliedValue: String? = null
    fun text(): String = editor.value?.text?.toString().orEmpty()
}

@Composable
fun rememberLuaCodeEditorState(): LuaCodeEditorState = remember { LuaCodeEditorState() }

internal object LuaLanguageSupport {
    private var loaded = false
    private var theme: ThemeModel? = null

    @Synchronized
    fun apply(editor: CodeEditor): Boolean {
        val registry = MonarchGrammarRegistry.INSTANCE
        if (!loaded) {
            try {
                FileProviderRegistry.addProvider(AssetsFileResolver(editor.context.applicationContext.assets))
                registry.loadGrammars(monarchLanguages {
                    language("lua") {
                        monarchLanguage = LuaLanguage
                        defaultScopeName("source")
                        languageConfiguration = "textmate/lua/language-configuration.json"
                    }
                })
                loaded = true
            } catch (_: Exception) {
                return false
            }
        }
        val grammar = registry.findGrammar("source.lua") ?: return false
        val configuration = registry.findLanguageConfiguration("source.lua") ?: return false

        if (theme == null) {
            try {
                val loadedTheme = ThemeModel(
                    ThemeSource(
                        path = "",
                        name = "reareye",
                        rawSource = REAREYE_THEME,
                    ),
                )
                loadedTheme.load()
                registry.setTheme(loadedTheme)
                theme = loadedTheme
            } catch (_: Exception) {
                return false
            }
        }
        val language = MonarchLanguage(grammar, configuration, registry, true)
        // Monarch's built-in identifier completer is incremental and runs off the
        // editor thread. Keep the vocabulary here focused on Lua and REAREye APIs;
        // project identifiers are learned automatically from the current document.
        language.setCompleterKeywords(
            arrayOf(
                "and",
                "break",
                "do",
                "else",
                "elseif",
                "end",
                "false",
                "for",
                "function",
                "goto",
                "if",
                "in",
                "local",
                "nil",
                "not",
                "or",
                "repeat",
                "return",
                "then",
                "true",
                "until",
                "while",
                "string",
                "table",
                "math",
                "os",
                "require",
                "context",
                "params",
                "config",
                "providers",
                "handler",
                "mimeType",
                "readText",
                "log",
                "now",
                "ttlMillis",
                "CACHE_FIRST",
                "CACHE_ONLY",
                "IMMEDIATE",
                "REFRESH",
            )
        )
        editor.setEditorLanguage(language)
        editor.getComponent(EditorAutoCompletion::class.java).setEnabled(true)
        theme?.let {
            editor.colorScheme = MonarchColorScheme(it)
        }
        // setEditorLanguage attaches the analyzer, but the editor may already
        // contain the document loaded by Compose. Explicitly schedule one pass
        // so token spans and identifier completion are available immediately.
        editor.rerunAnalysis()
        return true
    }

    private val REAREYE_THEME = """
        {"name":"REAREye","type":"dark","colors":{"editor.foreground":"#D4D4D4","editor.background":"#1E1E1E"},"tokenColors":[
          {"settings":{"foreground":"#D4D4D4"}},
          {"scope":["comment","comment.lua"],"settings":{"foreground":"#6A9955","fontStyle":"italic"}},
          {"scope":["string","string.lua","string.escape.lua"],"settings":{"foreground":"#CE9178"}},
          {"scope":["keyword","keyword.$0","keyword.$0.lua","keyword.control.lua","keyword.local.lua"],"settings":{"foreground":"#569CD6","fontStyle":"bold"}},
          {"scope":["number","number.lua","number.float.lua","number.hex.lua"],"settings":{"foreground":"#B5CEA8"}},
          {"scope":["constant.language","constant.language.lua"],"settings":{"foreground":"#569CD6"}},
          {"scope":["variable","variable.lua","identifier.lua"],"settings":{"foreground":"#9CDCFE"}},
          {"scope":["delimiter","delimiter.lua","punctuation"],"settings":{"foreground":"#D4D4D4"}}
        ]}
    """.trimIndent()
}

@Composable
fun LuaCodeEditor(value: String, state: LuaCodeEditorState, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            CodeEditor(context).apply {
                typefaceText = Typeface.MONOSPACE
                typefaceLineNumber = Typeface.MONOSPACE
                setTextSize(14f)
                isWordwrap = false
                setText(value)
                state.appliedValue = value
                // Install the language before the final text assignment so the
                // analyzer tokenizes the initial document as well as later edits.
                if (LuaLanguageSupport.apply(this)) {
                    setText(value)
                    rerunAnalysis()
                }
                state.editor.value = this
            }
        },
        update = { editor ->
            state.editor.value = editor
            // Only replace the document when the caller explicitly loads a different
            // value. Recomposition while typing parameters must preserve editor edits.
            if (state.appliedValue != value) {
                state.appliedValue = value
                if (editor.text.toString() != value) {
                    editor.setText(value)
                    editor.rerunAnalysis()
                }
            }
        },
    )
    DisposableEffect(Unit) {
        onDispose {
            state.editor.value?.release()
            state.editor.value = null
        }
    }
}
