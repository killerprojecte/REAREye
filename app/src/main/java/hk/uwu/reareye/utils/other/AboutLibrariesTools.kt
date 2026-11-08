package hk.uwu.reareye.utils.other

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.RearBadgeGroup
import hk.uwu.reareye.ui.components.RearBadgeItem
import hk.uwu.reareye.ui.components.card.SuperCard
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import hk.uwu.mixui.components.Button
import hk.uwu.mixui.components.ButtonColors
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import hk.uwu.mixui.theme.MixTheme

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

private const val LicenseBadgeOverflowText = "..."
private const val LicenseCreditsAnimationDurationMillis = 220

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
    var showLicenses by remember { mutableStateOf<List<License>>(emptyList()) }
    var expandLicensesInline by remember { mutableStateOf(false) }

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
                            lib.website.let { targetLink ->
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
                            tint = MixTheme.colorScheme.onSurface,
                            contentDescription = null,
                        )
                    }
                }
            },
            bottomAction = {
                if (lib.developers.isNotEmpty() || lib.licenses.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier
                            .height(16.dp),
                        thickness = 1.dp,
                        color = MixTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                }
                val licenseEntries = lib.licenses.map { licenseId ->
                    licenseId to licenses[licenseId]
                }
                LibraryCreditsRow(
                    developers = lib.developers,
                    licenseBadges = licenseEntries.map { (licenseId, license) ->
                        RearBadgeItem(
                            text = licenseBadgeText(licenseId, license),
                            emphasized = false,
                            onClick = license?.let { targetLicense ->
                                { showLicenses = listOf(targetLicense) }
                            },
                        )
                    },
                    expanded = expandLicensesInline,
                    onExpand = { expandLicensesInline = true },
                    onCollapse = { expandLicensesInline = false },
                )
                if (showLicenses.isNotEmpty()) {
                    val primaryColor = MixTheme.colorScheme.primary
                    val dialogTitle = remember(showLicenses) {
                        showLicenses.joinToString(" / ") { it.name }
                    }
                    OverlayBottomSheet(
                        show = true,
                        title = dialogTitle,
                        onDismissRequest = { showLicenses = emptyList() }
                    ) {
                        val rawText = remember(showLicenses) {
                            showLicenses.joinToString("\n\n") { license ->
                                buildString {
                                    append(license.name)
                                    append("\n\n")
                                    append(license.content ?: license.url)
                                }
                            }
                        }

                        val annotatedText = remember(rawText) {
                            buildAnnotatedString {
                                append(rawText)
                                val urlPattern = Regex("(https?://[\\w-]+(\\.[\\w-]+)+(/\\S*)?)")
                                urlPattern.findAll(rawText).forEach { match ->
                                    addLink(
                                        LinkAnnotation.Url(
                                            url = match.value,
                                            styles = TextLinkStyles(
                                                SpanStyle(
                                                    color = primaryColor,
                                                    fontWeight = FontWeight.Bold,
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
                                .fillMaxWidth()
                                .heightIn(max = 560.dp)
                                .padding(top = 8.dp)
                                .verticalScroll(rememberScrollState()),
                            text = annotatedText,
                            color = MixTheme.colorScheme.onSurfaceVariantSummary
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

private fun licenseBadgeText(licenseId: String, license: License?): String {
    return license?.name ?: licenseId
}

@Composable
private fun LibraryCreditsRow(
    developers: List<Developer>,
    licenseBadges: List<RearBadgeItem>,
    expanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
) {
    if (developers.isEmpty() && licenseBadges.isEmpty()) return

    val badgeSpacing = 12.dp
    val authorWordSpacing = 4.dp
    val authorText = buildLibraryDevelopersText(developers)
    val authorWords = remember(authorText) { splitAnnotatedWords(authorText) }
    val overflowBadges = remember(licenseBadges, onExpand) {
        if (licenseBadges.isEmpty()) {
            emptyList()
        } else {
            listOf(
                RearBadgeItem(
                    text = LicenseBadgeOverflowText,
                    emphasized = false,
                    onClick = onExpand,
                )
            )
        }
    }
    val collapsedBySpaceState = remember { mutableStateOf(false) }
    val shouldExpand = expanded && collapsedBySpaceState.value
    val authorTargetAlpha = if (shouldExpand) 0f else 1f
    val authorAlpha by animateFloatAsState(
        targetValue = authorTargetAlpha,
        animationSpec = tween(durationMillis = LicenseCreditsAnimationDurationMillis),
        label = "license-author-alpha",
    )
    val fullBadgeAlpha by animateFloatAsState(
        targetValue = if (!collapsedBySpaceState.value || shouldExpand) 1f else 0f,
        animationSpec = tween(durationMillis = LicenseCreditsAnimationDurationMillis),
        label = "license-full-badge-alpha",
    )
    val overflowBadgeAlpha by animateFloatAsState(
        targetValue = if (collapsedBySpaceState.value && !shouldExpand) 1f else 0f,
        animationSpec = tween(durationMillis = LicenseCreditsAnimationDurationMillis),
        label = "license-overflow-badge-alpha",
    )
    val rowHeightProgress by animateFloatAsState(
        targetValue = if (shouldExpand) 1f else 0f,
        animationSpec = tween(durationMillis = LicenseCreditsAnimationDurationMillis),
        label = "license-row-height-progress",
    )

    Layout(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = shouldExpand,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCollapse,
            ),
        content = {
            authorWords.forEach { word ->
                LibraryDevelopersText(
                    text = word,
                    modifier = Modifier.graphicsLayer { alpha = authorAlpha },
                )
            }
            Box(contentAlignment = Alignment.CenterEnd) {
                if (licenseBadges.isNotEmpty()) {
                    RearBadgeGroup(
                        badges = licenseBadges,
                        modifier = Modifier.graphicsLayer { alpha = fullBadgeAlpha },
                        horizontalAlignment = Alignment.End,
                    )
                }
            }
            Box(contentAlignment = Alignment.CenterEnd) {
                if (overflowBadges.isNotEmpty()) {
                    RearBadgeGroup(
                        badges = overflowBadges,
                        modifier = Modifier.graphicsLayer { alpha = overflowBadgeAlpha },
                        horizontalAlignment = Alignment.End,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val maxWidth = constraints.maxWidth
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val wordMeasurables = measurables.take(authorWords.size)
        val fullBadgeMeasurable = measurables[authorWords.size]
        val overflowBadgeMeasurable = measurables[authorWords.size + 1]
        val hasAuthor = authorWords.isNotEmpty()
        val hasLicenses = licenseBadges.isNotEmpty()
        val requestedBadgeSpacingPx = if (hasAuthor && hasLicenses) badgeSpacing.roundToPx() else 0
        val wordSpacingPx = authorWordSpacing.roundToPx()

        val overflowBadgePlaceable = overflowBadgeMeasurable.measure(looseConstraints)
        val fullBadgePlaceable =
            fullBadgeMeasurable.measure(looseConstraints.copy(maxWidth = maxWidth))
        val fullBadgeFitsOneLine =
            !hasLicenses || fullBadgePlaceable.height <= overflowBadgePlaceable.height
        val firstWordWidth = wordMeasurables.firstOrNull()
            ?.maxIntrinsicWidth(constraints.maxHeight)
            ?.coerceAtMost(maxWidth)
            ?: 0
        val fullBadgeSpacingPx = requestedBadgeSpacingPx.coerceAtMost(
            (maxWidth - fullBadgePlaceable.width).coerceAtLeast(0)
        )
        val firstLineWidthWithFullBadge =
            (maxWidth - fullBadgePlaceable.width - fullBadgeSpacingPx).coerceAtLeast(0)
        val collapsedBySpace = hasLicenses && hasAuthor &&
                (!fullBadgeFitsOneLine || firstWordWidth > firstLineWidthWithFullBadge)
        collapsedBySpaceState.value = collapsedBySpace
        val collapsedBadgePlaceable =
            if (collapsedBySpace) overflowBadgePlaceable else fullBadgePlaceable
        val collapsedBadgeSpacingPx = requestedBadgeSpacingPx.coerceAtMost(
            (maxWidth - collapsedBadgePlaceable.width).coerceAtLeast(0)
        )
        val collapsedFirstLineMaxWidth = if (hasLicenses && hasAuthor) {
            (maxWidth - collapsedBadgePlaceable.width - collapsedBadgeSpacingPx).coerceAtLeast(0)
        } else {
            maxWidth
        }

        val expandedBadgePlaceable = fullBadgePlaceable
        val expandedBadgeHeight = fullBadgePlaceable.height

        data class WordPlacement(
            val placeableIndex: Int,
            val x: Int,
            val y: Int,
        )

        fun measureAuthor(firstLineMaxWidth: Int): Pair<List<Placeable>, List<WordPlacement>> {
            val wordPlaceables = mutableListOf<Placeable>()
            val wordPlacements = mutableListOf<WordPlacement>()
            var lineIndex = 0
            var currentX = 0
            var currentY = 0
            var currentLineHeight = if (hasLicenses) collapsedBadgePlaceable.height else 0

            fun currentLineMaxWidth(): Int {
                return if (lineIndex == 0) firstLineMaxWidth else maxWidth
            }

            fun moveToNextLine() {
                currentY += currentLineHeight
                lineIndex += 1
                currentX = 0
                currentLineHeight = 0
            }

            wordMeasurables.forEach { measurable ->
                val intrinsicWidth =
                    measurable.maxIntrinsicWidth(constraints.maxHeight).coerceAtMost(maxWidth)
                var lineMaxWidth = currentLineMaxWidth()
                var wordX = if (currentX == 0) 0 else currentX + wordSpacingPx

                if (currentX > 0 && wordX + intrinsicWidth > lineMaxWidth) {
                    moveToNextLine()
                    lineMaxWidth = currentLineMaxWidth()
                    wordX = 0
                }

                val wordMaxWidth = (lineMaxWidth - wordX).takeIf { it > 0 } ?: lineMaxWidth
                val placeable = measurable.measure(
                    looseConstraints.copy(maxWidth = intrinsicWidth.coerceAtMost(wordMaxWidth))
                )
                wordPlacements += WordPlacement(
                    placeableIndex = wordPlaceables.size,
                    x = wordX,
                    y = currentY,
                )
                wordPlaceables += placeable
                currentX = wordX + placeable.width
                currentLineHeight = maxOf(currentLineHeight, placeable.height)
            }
            return wordPlaceables to wordPlacements
        }

        val (wordPlaceables, wordPlacements) = measureAuthor(collapsedFirstLineMaxWidth)
        val authorHeight = if (hasAuthor && wordPlacements.isNotEmpty()) {
            wordPlacements.maxOf { placement ->
                placement.y + wordPlaceables[placement.placeableIndex].height
            }
        } else {
            0
        }
        val collapsedContentHeight =
            maxOf(authorHeight, if (hasLicenses) collapsedBadgePlaceable.height else 0)
        val expandedContentHeight = if (hasLicenses) expandedBadgeHeight else collapsedContentHeight
        val layoutHeight = (collapsedContentHeight +
                ((expandedContentHeight - collapsedContentHeight) * rowHeightProgress).toInt())
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        val collapsedBadgeY = ((layoutHeight - collapsedBadgePlaceable.height) / 2).coerceAtLeast(0)
        val fullBadgeY = ((layoutHeight - expandedBadgePlaceable.height) / 2).coerceAtLeast(0)

        layout(maxWidth, layoutHeight) {
            wordPlacements.forEach { placement ->
                val placeable = wordPlaceables[placement.placeableIndex]
                placeable.placeRelative(placement.x, placement.y)
            }
            if (hasLicenses) {
                if (!collapsedBySpace || shouldExpand || fullBadgeAlpha > 0.01f) {
                    expandedBadgePlaceable.placeRelative(
                        maxWidth - expandedBadgePlaceable.width,
                        fullBadgeY
                    )
                }
                if (collapsedBySpace && (!shouldExpand || overflowBadgeAlpha > 0.01f)) {
                    collapsedBadgePlaceable.placeRelative(
                        maxWidth - collapsedBadgePlaceable.width,
                        collapsedBadgeY
                    )
                }
            }
        }
    }
}

private fun splitAnnotatedWords(text: AnnotatedString): List<AnnotatedString> {
    if (text.isEmpty()) return emptyList()

    val words = mutableListOf<AnnotatedString>()
    var index = 0
    while (index < text.length) {
        while (index < text.length && text[index] == ' ') {
            index += 1
        }
        val start = index
        while (index < text.length && text[index] != ' ') {
            index += 1
        }
        if (start < index) {
            words += text.subSequence(start, index)
        }
    }
    return words
}

@Composable
private fun buildLibraryDevelopersText(developers: List<Developer>): AnnotatedString {
    return buildAnnotatedString {
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
                                color = MixTheme.colorScheme.primary,
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
}

@Composable
private fun LibraryDevelopersText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
        text = text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}
