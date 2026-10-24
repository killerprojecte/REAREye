package hk.uwu.reareye.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.interaction.ApprovalMode
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun approvalModeTitle(mode: ApprovalMode): String = stringResource(
    when (mode) {
        ApprovalMode.ASK -> R.string.mary_mode_ask
        ApprovalMode.AUTO -> R.string.mary_mode_auto
        ApprovalMode.ALLOW_ALL -> R.string.mary_mode_all
    }
)

@Composable
internal fun approvalModeSummary(mode: ApprovalMode): String = stringResource(
    when (mode) {
        ApprovalMode.ASK -> R.string.mary_mode_ask_summary
        ApprovalMode.AUTO -> R.string.mary_mode_auto_summary
        ApprovalMode.ALLOW_ALL -> R.string.mary_mode_all_summary
    }
)

private fun lineIcon(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { data ->
            addPath(
                pathData = addPathNodes(data), fill = null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round
            )
        }
    }.build()

// Keep the standard 24 x 24 Material geometry and its consistent 2-unit outline.
private val ApprovalHand = Icons.Outlined.FrontHand
private val ApprovalAlert = Icons.Outlined.GppMaybe

// Material has no question-shield icon: reuse the exact standard shield outline,
// adding only a centered question mark rather than redrawing or stretching the shield.
private val ApprovalQuestion =
    ImageVector.Builder("ApprovalQuestion", 24.dp, 24.dp, 24f, 24f).apply {
        val shield = Icons.Outlined.Shield.root[0] as VectorPath
        addPath(pathData = shield.pathData, fill = shield.fill, pathFillType = shield.pathFillType)
        addPath(
            pathData = addPathNodes("M10 9a2 2 0 0 1 4 0c0 1.5-2 1.5-2 3"),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        addPath(pathData = addPathNodes("M11 14h2v2h-2Z"), fill = SolidColor(Color.Black))
    }.build()
private val ApprovalCheck = lineIcon("ApprovalCheck", "M5 12l4 4L19 6")
internal fun approvalModeIcon(mode: ApprovalMode): ImageVector = when (mode) {
    ApprovalMode.ASK -> ApprovalHand
    ApprovalMode.AUTO -> ApprovalQuestion
    ApprovalMode.ALLOW_ALL -> ApprovalAlert
}

private val FullAccessWarning = Color(0xFFE97833)

@Composable
private fun approvalModeAccent(mode: ApprovalMode): Color =
    if (mode == ApprovalMode.ALLOW_ALL) FullAccessWarning else MiuixTheme.colorScheme.primary

/** Compact composer entry; the settings page uses the exact same options below. */
@Composable
internal fun ApprovalModeButton(
    mode: ApprovalMode,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (ApprovalMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val currentOnExpandedChange by rememberUpdatedState(onExpandedChange)
    val shown = expanded && enabled
    SideEffect { currentOnExpandedChange(shown) }
    DisposableEffect(Unit) { onDispose { currentOnExpandedChange(false) } }
    val title = approvalModeTitle(mode)
    val accent = approvalModeAccent(mode)
    val action = stringResource(R.string.mary_chat_approval_action, title)
    Box {
        IconButton(
            enabled = enabled,
            onClick = {
                expanded = !expanded
                currentOnExpandedChange(expanded)
                if (expanded) keyboard?.hide()
            },
            modifier = Modifier
                .size(44.dp)
                .testTag("mary-approval-mode")
                .semantics {
                    contentDescription = action
                    stateDescription = title
                    if (!enabled) disabled()
                },
        ) {
            Icon(
                approvalModeIcon(mode), contentDescription = null,
                tint = accent.copy(alpha = accent.alpha * if (enabled) 1f else 0.5f),
                modifier = Modifier
                    .size(22.dp)
                    .testTag("mary-approval-current-${mode.name}")
            )
        }
        ApprovalModePopup(shown, mode, { expanded = false }) {
            expanded = false
            onSelected(it)
        }
    }
}

@Composable
internal fun MaryApprovalModePreference(
    mode: ApprovalMode,
    onSelected: (ApprovalMode) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ArrowPreference(
            title = stringResource(R.string.mary_settings_ui_approval_mode),
            summary = approvalModeTitle(mode),
            enabled = enabled,
            holdDownState = expanded,
            onClick = { expanded = true },
            modifier = Modifier.testTag("mary-settings-approval-mode"),
        )
        ApprovalModePopup(expanded && enabled, mode, { expanded = false }) {
            expanded = false
            onSelected(it)
        }
    }
}

@Composable
private fun ApprovalModePopup(
    show: Boolean,
    mode: ApprovalMode,
    onDismiss: () -> Unit,
    onSelected: (ApprovalMode) -> Unit,
) {
    OverlayListPopup(
        show = show,
        popupModifier = Modifier,
        popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
        alignment = PopupPositionProvider.Align.Start,
        enableWindowDim = true,
        onDismissRequest = onDismiss,
        maxHeight = null,
        minWidth = 300.dp,
        renderInRootScaffold = true,
    ) {
        Column(Modifier
            .widthIn(max = 420.dp)
            .testTag("mary-approval-options")) {
            Text(
                stringResource(R.string.mary_chat_approval_title),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            ApprovalMode.entries.forEach { option ->
                val selected = mode == option
                val accent = approvalModeAccent(option)
                val titleColor = if (selected) accent else MiuixTheme.colorScheme.onSurfaceContainer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mary-approval-option-${option.name}")
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelected(option) })
                        .semantics(mergeDescendants = true) {}
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        approvalModeIcon(option), contentDescription = null,
                        tint = if (option == ApprovalMode.ALLOW_ALL) accent else titleColor,
                        modifier = Modifier
                            .size(20.dp)
                            .testTag("mary-approval-option-icon-${option.name}")
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            approvalModeTitle(option), style = MiuixTheme.textStyles.body2,
                            fontWeight = FontWeight.Medium, color = titleColor,
                            modifier = Modifier.testTag("mary-approval-option-title-${option.name}")
                        )
                        Text(
                            approvalModeSummary(option), style = MiuixTheme.textStyles.footnote1,
                            color = if (selected && option == ApprovalMode.ALLOW_ALL) accent
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.testTag("mary-approval-option-summary-${option.name}")
                        )
                    }
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        if (selected) Icon(
                            ApprovalCheck, contentDescription = null, tint = accent,
                            modifier = Modifier
                                .size(18.dp)
                                .testTag("mary-approval-selected-${option.name}")
                        )
                    }
                }
            }
        }
    }
}
