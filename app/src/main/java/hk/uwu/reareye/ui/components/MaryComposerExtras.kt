package hk.uwu.reareye.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import hk.uwu.mixui.theme.MixTheme
import hk.uwu.mixui.components.*
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.MaryAvailableModel
import hk.uwu.reareye.agent.MaryUiAttachment

internal fun maryModelDisplayName(id: String) = when (id) {
    "gpt-6.2-sol" -> "GPT 6.2 Sol"
    "deepseek-v4.1-flash" -> "DeepSeek v4.1 Flash"
    else -> id
}

internal data class MaryComposerModels(
    val selectedKey: String?,
    val displayName: String,
    val choices: List<MaryAvailableModel>,
    val onSelect: (String) -> Unit,
)

@Composable
internal fun MaryReasoningModelDropdown(
    models: MaryComposerModels,
    effort: String?,
    enabled: Boolean,
) {
    val secondary = MixTheme.colorScheme.onSurfaceContainerVariant
    val titleStyle = MixTheme.textStyles.title2.copy(
        fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
    )
    val options = remember(models.choices) {
        models.choices.map { GlassSheetDropdownOption(it.id, it.name, it.providerName) }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GlassSheetDropdown(
            options = options,
            selectedId = models.selectedKey,
            onSelect = models.onSelect,
            enabled = enabled,
            modifier = Modifier.testTag("mary-reasoning-model-header"),
        ) { expanded ->
            Row(
                Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    models.displayName, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline().testTag("mary-reasoning-model-name"),
                )
                if (effort != null) Text(
                    effort, color = secondary, style = titleStyle, maxLines = 1,
                    modifier = Modifier.alignByBaseline().testTag("mary-reasoning-level"),
                )
                Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    stringResource(R.string.mary_chat_choose_model), tint = secondary, modifier = Modifier.size(20.dp))
            }
        }
        if (options.isEmpty()) {
            Text(stringResource(R.string.mary_chat_no_configured_model), color = secondary, style = MixTheme.textStyles.body2)
        }
    }
}

internal data class MaryComposerAttachments(
    val drafts: List<MaryUiAttachment>,
    val importing: Boolean,
    val onChooseFile: () -> Unit,
    val onChooseImage: () -> Unit,
    val onRemoveDraft: (String) -> Unit,
    val menuBottomInset: Dp = 0.dp,
)

@Composable
internal fun MaryComposerAttachButton(locked: Boolean, attachments: MaryComposerAttachments?) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = !locked && attachments != null && !attachments.importing
    val label = stringResource(R.string.mary_action_attach)
    val anchor: @Composable () -> Unit = {
        IconButton(onClick = { expanded = !expanded }, enabled = enabled,
            modifier = Modifier.size(44.dp).testTag("mary-composer-attach")) {
            Icon(Icons.Outlined.Add, label, tint = MixTheme.colorScheme.onSurfaceContainer, modifier = Modifier.size(28.dp))
        }
    }
    if (attachments == null) anchor()
    else GlassAnchoredMenu(
        expanded = expanded, onExpandedChange = { expanded = it }, enabled = enabled,
        placement = GlassAnchoredMenuPlacement.AboveAnchor(bottomInset = attachments.menuBottomInset),
        options = listOf(
            GlassMenuOption(title = stringResource(R.string.mary_attachment_choose_file)),
            GlassMenuOption(title = stringResource(R.string.mary_attachment_choose_image)),
        ),
        onOptionClick = { index ->
            when (index) {
                0 -> attachments.onChooseFile()
                1 -> attachments.onChooseImage()
            }
        },
    ) { anchor() }
}

@Composable
internal fun MaryComposerAttachmentStrip(
    attachments: MaryComposerAttachments,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (attachments.drafts.isEmpty() && !attachments.importing) return
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (attachments.importing) {
            Row(
                Modifier.clip(RoundedCornerShape(16.dp)).background(MixTheme.colorScheme.surfaceContainer)
                    .testTag("mary-attachment-importing").semantics { liveRegion = LiveRegionMode.Polite }
                    .heightIn(min = 44.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                Text(stringResource(R.string.mary_attachment_importing), style = MixTheme.textStyles.body2)
            }
        }
        attachments.drafts.forEach { file ->
            key(file.id) {
                MaryAttachmentCard(
                    attachment = file,
                    modifier = Modifier.widthIn(max = 172.dp).testTag("mary-draft-file-${file.id}"),
                    onRemove = { attachments.onRemoveDraft(file.id) },
                    removeEnabled = enabled,
                )
            }
        }
    }
}

@Composable
internal fun MaryAttachmentCard(
    attachment: MaryUiAttachment,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    removeEnabled: Boolean = true,
) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(MixTheme.colorScheme.surfaceContainer)
            .padding(start = 6.dp, end = if (onRemove == null) 12.dp else 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MaryAttachmentThumbnail(attachment)
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                attachment.name, style = MixTheme.textStyles.body2, fontWeight = FontWeight.Medium,
                color = MixTheme.colorScheme.onSurfaceContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                attachment.detail, style = MixTheme.textStyles.footnote2, color = MixTheme.colorScheme.onSurfaceContainerVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, enabled = removeEnabled, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Close, stringResource(R.string.mary_attachment_remove, attachment.name), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MaryAttachmentThumbnail(attachment: MaryUiAttachment) {
    val preview = attachment.previewUri.takeIf { attachment.isImage }
    var imageReady by remember(preview) { mutableStateOf(false) }
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MixTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (!imageReady) {
            Icon(if (attachment.isImage) Icons.Outlined.Image else Icons.Outlined.Description, null,
                tint = MixTheme.colorScheme.onSurfaceContainerVariant, modifier = Modifier.size(20.dp))
        }
        if (preview != null) {
            AsyncImage(
                model = preview, contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, onSuccess = { imageReady = true }, onError = { imageReady = false },
            )
        }
    }
}
