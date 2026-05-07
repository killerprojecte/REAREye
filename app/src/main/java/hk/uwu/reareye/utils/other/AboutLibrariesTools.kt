package hk.uwu.reareye.utils.other

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.OverlayDialog
import hk.uwu.reareye.ui.components.RearBadgePill
import hk.uwu.reareye.ui.components.card.SuperCard
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Serializable
data class AboutLibraries(
    val libraries: List<Library>,
    val licenses: Map<String, License>
)

@Serializable
data class Library(
    val uniqueId: String,
    val artifactVersion: String,
    val name: String,
    val description: String? = null,
    val website: String? = null,
    val developers: List<Developer> = emptyList(),
    val organization: Organization? = null,
    val licenses: List<String> = emptyList()
)

@Serializable
data class Developer(
    val name: String? = null,
    val organisationUrl: String? = null
)

@Serializable
data class Organization(
    val name: String,
    val url: String? = null
)

@Serializable
data class License(
    val name: String,
    val url: String,
    val content: String? = null
)

private val json = Json {
    ignoreUnknownKeys = true
}

fun loadLibraries(context: Context): AboutLibraries {
    val inputStream = context.resources.openRawResource(R.raw.aboutlibraries)

    val jsonString = inputStream
        .bufferedReader()
        .use { it.readText() }

    return json.decodeFromString(jsonString)
}

@Composable
fun LibraryItem(lib: Library, licenses: Map<String, License>) {
    val context = LocalContext.current
    val hasLink = lib.website != null
    var showLicense by remember { mutableStateOf<License?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        SuperCard(
            title = lib.name,
            summary = buildLibrarySummary(lib),
            endActions = {
                if (hasLink) {
                    Button(
                        colors = ButtonColors(
                            color = Color.Transparent,
                            disabledColor = Color.Transparent,
                            contentColor = Color.Transparent,
                            disabledContentColor = Color.Transparent,
                        ),
                        onClick = {
                            lib.website?.let { targetLink ->
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        targetLink.toUri()
                                    )
                                )
                            }
                        }
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Link,
                            tint = MiuixTheme.colorScheme.onSurface,
                            contentDescription = null,
                        )
                    }
                }
            },
            bottomAction = {
                if (lib.developers.isNotEmpty() || lib.licenses.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier
                            .height(16.dp)
                        ,
                        thickness = 1.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (lib.developers.isNotEmpty()) {
                        Box(modifier = Modifier.weight(1f)) {
                            LibraryDevelopersText(lib.developers)
                        }
                    }
                    if (lib.licenses.isNotEmpty()) {
                        Column (
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            lib.licenses.forEach { licenseId ->
                                val license = licenses[licenseId]
                                RearBadgePill(
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        license?.let { showLicense = it }
                                    },
                                    text = license?.name?.take(15) ?: if (licenseId.length > 15) licenseId.take(15) + "\u2026" else licenseId,
                                    emphasized = false
                                )
                            }
                        }
                    }
                }
                showLicense?.let { lic ->
                    val primaryColor = MiuixTheme.colorScheme.primary
                    OverlayDialog(
                        show = true,
                        title = lic.name,
                        onDismissRequest = { showLicense = null }
                    ) {
                        // 准备原始文本
                        val rawText = lic.content ?: lic.url

                        // 自动识别链接并构建 AnnotatedString
                        val annotatedText = remember(rawText) {
                            buildAnnotatedString {
                                append(rawText)
                                // 匹配 http/https 链接的正则表达式
                                val urlPattern = Regex("(https?://[\\w-]+(\\.[\\w-]+)+(/\\S*)?)")
                                urlPattern.findAll(rawText).forEach { match ->
                                    addLink(
                                        LinkAnnotation.Url(
                                            url = match.value,
                                            styles = TextLinkStyles(
                                                SpanStyle(
                                                    color = primaryColor ,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            )
                                        ),
                                        start = match.range.first,
                                        end = match.range.last + 1
                                    )
                                }
                            }
                        }

                        Text(
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .verticalScroll(rememberScrollState()),
                            text = annotatedText, // 传入处理好的 AnnotatedString
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }

            },
        )
    }
}

private fun buildLibrarySummary(lib: Library): String? {
    val parts = buildList {
        add(lib.artifactVersion)
        lib.organization?.name?.let { add(it) }
        lib.description?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

@Composable
private fun LibraryDevelopersText(developers: List<Developer>) {
    val annotated = buildAnnotatedString {
        developers.forEachIndexed { index, developer ->
            val name = developer.name ?: "Unknown"
            val url = developer.organisationUrl

            if (url != null) {
                val start = length
                append(name)
                addLink(
                    LinkAnnotation.Url(
                        url = url,
                        styles = TextLinkStyles(
                            SpanStyle(
                                color = MiuixTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        )
                    ),
                    start = start,
                    end = length,
                )
            } else {
                append(name)
            }

            if (index < developers.size - 1) {
                append(", ")
            }
        }
    }
    Text(text = annotated)
}