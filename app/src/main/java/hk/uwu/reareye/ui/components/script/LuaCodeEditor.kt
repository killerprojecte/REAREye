package hk.uwu.reareye.ui.components.script

import android.graphics.Typeface
import android.view.ViewTreeObserver
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.viewinterop.AndroidView
import hk.uwu.mixui.theme.MixTheme
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
    internal var verticalScroll by mutableIntStateOf(0)
        private set
    internal fun updateScroll() { verticalScroll = editor.value?.offsetY?.coerceAtLeast(0) ?: 0 }
    internal var appliedValue: String? = null
    internal var appliedDark: Boolean? = null
    fun text(): String = editor.value?.text?.toString().orEmpty()
}

@Composable
fun rememberLuaCodeEditorState(): LuaCodeEditorState = remember { LuaCodeEditorState() }

internal object LuaLanguageSupport {
    private var loaded = false
    private var darkTheme: ThemeModel? = null
    private var lightTheme: ThemeModel? = null

    @Synchronized
    private fun resolveTheme(dark: Boolean): ThemeModel? {
        val cached = if (dark) darkTheme else lightTheme
        if (cached != null) return cached
        return try {
            val loadedTheme = ThemeModel(
                ThemeSource(
                    path = "",
                    name = if (dark) "reareye-dark" else "reareye-light",
                    rawSource = if (dark) REAREYE_DARK_THEME else REAREYE_LIGHT_THEME,
                ),
            )
            loadedTheme.load()
            if (dark) darkTheme = loadedTheme else lightTheme = loadedTheme
            loadedTheme
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun apply(editor: CodeEditor, dark: Boolean = true): Boolean {
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
        val activeTheme = resolveTheme(dark) ?: return false
        registry.setTheme(activeTheme)

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
                "ipairs",
                "pairs",
                "pcall",
                "xpcall",
                "tonumber",
                "tostring",
                "type",
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
        editor.colorScheme = MonarchColorScheme(activeTheme)
        // setEditorLanguage attaches the analyzer, but the editor may already
        // contain the document loaded by Compose. Explicitly schedule one pass
        // so token spans and identifier completion are available immediately.
        editor.rerunAnalysis()
        return true
    }

    private val REAREYE_DARK_THEME = """
        {"name":"REAREye Dark","type":"dark","colors":{"editor.foreground":"#D4D4D4","editor.background":"#1E1E1E"},"tokenColors":[
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

    private val REAREYE_LIGHT_THEME = """
        {"name":"REAREye Light","type":"light","colors":{"editor.foreground":"#1F2328","editor.background":"#F7F8FA"},"tokenColors":[
          {"settings":{"foreground":"#1F2328"}},
          {"scope":["comment","comment.lua"],"settings":{"foreground":"#4E7A3E","fontStyle":"italic"}},
          {"scope":["string","string.lua","string.escape.lua"],"settings":{"foreground":"#A31515"}},
          {"scope":["keyword","keyword.$0","keyword.$0.lua","keyword.control.lua","keyword.local.lua"],"settings":{"foreground":"#0550AE","fontStyle":"bold"}},
          {"scope":["number","number.lua","number.float.lua","number.hex.lua"],"settings":{"foreground":"#098658"}},
          {"scope":["constant.language","constant.language.lua"],"settings":{"foreground":"#0550AE"}},
          {"scope":["variable","variable.lua","identifier.lua"],"settings":{"foreground":"#001080"}},
          {"scope":["delimiter","delimiter.lua","punctuation"],"settings":{"foreground":"#1F2328"}}
        ]}
    """.trimIndent()
}

@Composable
fun LuaCodeEditor(value: String, state: LuaCodeEditorState, modifier: Modifier = Modifier) {
    val isDark = MixTheme.colorScheme.surface.luminance() < 0.5f
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
                state.appliedDark = isDark
                // Install the language before the final text assignment so the
                // analyzer tokenizes the initial document as well as later edits.
                if (LuaLanguageSupport.apply(this, dark = isDark)) {
                    setText(value)
                    rerunAnalysis()
                }
                state.editor.value = this
            }
        },
        update = { editor ->
            state.editor.value = editor
            if (state.appliedDark != isDark) {
                state.appliedDark = isDark
                LuaLanguageSupport.apply(editor, dark = isDark)
                editor.invalidate()
            }
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
    val nativeEditor = state.editor.value
    DisposableEffect(nativeEditor) {
        // ScrollEvent.endY is a target during fling/ensurePositionVisible, not the
        // displayed offset. Read the actual Sora scroller on each native draw.
        val observer = nativeEditor?.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            state.updateScroll()
            true
        }
        observer?.addOnPreDrawListener(listener)
        state.updateScroll()
        onDispose { if (observer?.isAlive == true) observer.removeOnPreDrawListener(listener) }
    }
    DisposableEffect(Unit) {
        onDispose {
            state.editor.value?.release()
            state.editor.value = null
        }
    }
}
