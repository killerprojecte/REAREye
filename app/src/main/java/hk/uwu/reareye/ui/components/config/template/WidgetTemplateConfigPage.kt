package hk.uwu.reareye.ui.components.config.template

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatAlignLeft
import androidx.compose.material.icons.automirrored.rounded.FormatAlignRight
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearwidget.RearWidgetAnimationField
import hk.uwu.reareye.repository.rearwidget.RearWidgetAnimationSaveConfig
import hk.uwu.reareye.repository.rearwidget.RearWidgetBackgroundModeField
import hk.uwu.reareye.repository.rearwidget.RearWidgetBackgroundModeSaveConfig
import hk.uwu.reareye.repository.rearwidget.RearWidgetColorField
import hk.uwu.reareye.repository.rearwidget.RearWidgetColorGroupField
import hk.uwu.reareye.repository.rearwidget.RearWidgetColorGroupSaveConfig
import hk.uwu.reareye.repository.rearwidget.RearWidgetCustomColorField
import hk.uwu.reareye.repository.rearwidget.RearWidgetCustomColorSaveConfig
import hk.uwu.reareye.repository.rearwidget.RearWidgetCustomEditLinkField
import hk.uwu.reareye.repository.rearwidget.RearWidgetDateField
import hk.uwu.reareye.repository.rearwidget.RearWidgetDateSetSaveConfig
import hk.uwu.reareye.repository.rearwidget.RearWidgetDropDownField
import hk.uwu.reareye.repository.rearwidget.RearWidgetImagePickField
import hk.uwu.reareye.repository.rearwidget.RearWidgetImageSelectField
import hk.uwu.reareye.repository.rearwidget.RearWidgetIntentField
import hk.uwu.reareye.repository.rearwidget.RearWidgetIntentSaveConfig
import hk.uwu.reareye.repository.rearwidget.RearWidgetManagerRepository
import hk.uwu.reareye.repository.rearwidget.RearWidgetMultiImageConfig
import hk.uwu.reareye.repository.rearwidget.RearWidgetMultiImageSelectField
import hk.uwu.reareye.repository.rearwidget.RearWidgetMultiModeImageListField
import hk.uwu.reareye.repository.rearwidget.RearWidgetMultiModeImageListSaveConfig
import hk.uwu.reareye.repository.rearwidget.RearWidgetOnOffField
import hk.uwu.reareye.repository.rearwidget.RearWidgetOneConfig
import hk.uwu.reareye.repository.rearwidget.RearWidgetRangeField
import hk.uwu.reareye.repository.rearwidget.RearWidgetStringVarField
import hk.uwu.reareye.repository.rearwidget.RearWidgetTextField
import hk.uwu.reareye.repository.rearwidget.alignValue
import hk.uwu.reareye.repository.rearwidget.colorValue
import hk.uwu.reareye.repository.rearwidget.dropDownValue
import hk.uwu.reareye.repository.rearwidget.formatDateMillis
import hk.uwu.reareye.repository.rearwidget.imageValue
import hk.uwu.reareye.repository.rearwidget.isValidDateString
import hk.uwu.reareye.repository.rearwidget.onOffValue
import hk.uwu.reareye.repository.rearwidget.parseDateStringToMillis
import hk.uwu.reareye.repository.rearwidget.putAlignValue
import hk.uwu.reareye.repository.rearwidget.putColorValue
import hk.uwu.reareye.repository.rearwidget.putDropDownValue
import hk.uwu.reareye.repository.rearwidget.putImageValue
import hk.uwu.reareye.repository.rearwidget.putIntentConfig
import hk.uwu.reareye.repository.rearwidget.putOnOffValue
import hk.uwu.reareye.repository.rearwidget.putSeekBarValue
import hk.uwu.reareye.repository.rearwidget.putStringVarValue
import hk.uwu.reareye.repository.rearwidget.putTextSizeValue
import hk.uwu.reareye.repository.rearwidget.putTextValue
import hk.uwu.reareye.repository.rearwidget.resolvedHint
import hk.uwu.reareye.repository.rearwidget.resolvedLabel
import hk.uwu.reareye.repository.rearwidget.resolvedTitle
import hk.uwu.reareye.repository.rearwidget.seekBarValue
import hk.uwu.reareye.repository.rearwidget.stringVarValue
import hk.uwu.reareye.repository.rearwidget.textSizeValue
import hk.uwu.reareye.repository.rearwidget.textValue
import hk.uwu.reareye.repository.widgettemplate.WidgetTemplateConfigRepository
import hk.uwu.reareye.repository.widgettemplate.WidgetTemplateField
import hk.uwu.reareye.repository.widgettemplate.WidgetTemplateSchema
import hk.uwu.reareye.ui.components.OverlayDialog
import hk.uwu.reareye.ui.components.rememberRearWidgetTemplatePreviewBitmap
import hk.uwu.reareye.widgetapi.RearWidgetTemplateConfigState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun WidgetTemplateConfigScreenContent(
    modifier: Modifier = Modifier,
    business: String,
    sourceFilePath: String,
    cardStorageKey: String,
    currentConfigJson: String?,
    onBack: () -> Unit,
    onSave: (String?) -> Unit,
    embedded: Boolean = false,
    allowContentPresentation: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    titleText: String? = null,
    loadingText: String? = null,
    unavailableText: String? = null,
    stateResolver: suspend (Context, String, String, String?) -> RearWidgetTemplateConfigState? = { context, businessName, sourcePath, configJson ->
        RearWidgetManagerRepository.resolveTemplateConfigState(
            context = context,
            business = businessName,
            sourceFilePath = sourcePath,
            currentOneConfigJson = configJson,
        )
    },
    configNormalizer: suspend (Context, String, WidgetTemplateSchema, String) -> String? = { context, businessName, schemaForSave, encoded ->
        RearWidgetManagerRepository.resolveTemplateConfigState(
            context = context,
            business = businessName,
            sourceFilePath = schemaForSave.sourcePath,
            currentOneConfigJson = encoded,
        )?.oneConfigJson
    },
) {
    val context = LocalContext.current
    val saveScope = rememberCoroutineScope()
    var loading by remember(business, sourceFilePath, currentConfigJson) { mutableStateOf(true) }
    var schema by remember(business, sourceFilePath, currentConfigJson) {
        mutableStateOf<WidgetTemplateSchema?>(null)
    }
    var workingConfig by remember(business, sourceFilePath, currentConfigJson) {
        mutableStateOf<RearWidgetOneConfig?>(null)
    }

    LaunchedEffect(business, sourceFilePath, currentConfigJson) {
        loading = true
        val state = withContext(Dispatchers.IO) {
            stateResolver(context, business, sourceFilePath, currentConfigJson)
        }
        val decodedState = withContext(Dispatchers.Default) {
            val decodedSchema = state?.templateSchemaJson
                ?.let(WidgetTemplateConfigRepository::decodeSchema)
            val decodedConfig = state?.oneConfigJson
                ?.let(WidgetTemplateConfigRepository::decodeOneConfig)
                ?: RearWidgetOneConfig()
            decodedSchema to decodedConfig
        }
        schema = decodedState.first
        workingConfig = decodedState.second
        loading = false
    }

    val resolvedSchema = schema
    val resolvedConfig = workingConfig
    TemplateVarConfigScreenScaffold(
        modifier = modifier,
        title = titleText ?: stringResource(R.string.rear_widget_card_template_title),
        embedded = embedded,
        contentPadding = contentPadding,
        loading = loading || !allowContentPresentation,
        schema = resolvedSchema,
        config = resolvedConfig,
        hasEditableItems = resolvedSchema?.items?.isNotEmpty() == true,
        loadingText = loadingText ?: stringResource(R.string.rear_widget_card_template_loading),
        unavailableText = unavailableText
            ?: stringResource(R.string.rear_widget_card_template_unavailable),
        confirmText = stringResource(R.string.rear_widget_confirm),
        resetText = stringResource(R.string.rear_widget_card_template_use_defaults),
        onBack = onBack,
        onConfirm = {
            val schemaForSave = resolvedSchema ?: return@TemplateVarConfigScreenScaffold
            saveScope.launch {
                val encoded = WidgetTemplateConfigRepository.encodeOneConfig(
                    workingConfig ?: RearWidgetOneConfig(),
                )
                val normalized = withContext(Dispatchers.IO) {
                    configNormalizer(context, business, schemaForSave, encoded)
                }
                onSave(normalized?.takeIf { it.isNotBlank() })
            }
        },
        onReset = { onSave(null) },
        editorItems = { schemaValue, configValue ->
            widgetTemplateEditorItems(
                business = business,
                cardStorageKey = cardStorageKey,
                schema = schemaValue,
                workingConfig = configValue,
                onConfigChange = { workingConfig = it },
            )
        },
    )
}

private fun LazyListScope.widgetTemplateEditorItems(
    business: String,
    cardStorageKey: String,
    schema: WidgetTemplateSchema,
    workingConfig: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    schema.groupItems().forEachIndexed { groupIndex, (_, items) ->
        items.forEachIndexed { fieldIndex, field ->
            item(
                key = "template_field_${groupIndex}_${fieldIndex}_${field.name}_${field.tagName}",
                contentType = "template_field",
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    WidgetTemplateFieldEditor(
                        field = field,
                        business = business,
                        cardStorageKey = cardStorageKey,
                        templateSourcePath = schema.sourcePath,
                        config = workingConfig,
                        onConfigChange = onConfigChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetTemplateFieldEditor(
    field: WidgetTemplateField,
    business: String,
    cardStorageKey: String,
    templateSourcePath: String,
    config: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    when (field) {
        is RearWidgetTextField -> TextFieldEditor(field, config, onConfigChange)
        is RearWidgetColorField -> ColorFieldEditor(field, config, onConfigChange)
        is RearWidgetColorGroupField -> ColorGroupFieldEditor(field, config, onConfigChange)
        is RearWidgetRangeField -> RangeFieldEditor(field, config, onConfigChange)
        is RearWidgetDropDownField -> DropDownFieldEditor(field, config, onConfigChange)
        is RearWidgetImageSelectField -> ImageSelectFieldEditor(
            field,
            business,
            templateSourcePath,
            config,
            onConfigChange,
        )

        is RearWidgetImagePickField -> ImagePickFieldEditor(
            field = field,
            business = business,
            cardStorageKey = cardStorageKey,
            templateSourcePath = templateSourcePath,
            config = config,
            onConfigChange = onConfigChange,
        )

        is RearWidgetMultiImageSelectField -> MultiImageFieldEditor(
            field,
            business,
            templateSourcePath,
            config,
            onConfigChange,
        )

        is RearWidgetIntentField -> IntentFieldEditor(field, config, onConfigChange)
        is RearWidgetDateField -> DateFieldEditor(field, config, onConfigChange)
        is RearWidgetOnOffField -> OnOffFieldEditor(field, config, onConfigChange)
        is RearWidgetCustomColorField -> CustomColorFieldEditor(field, config, onConfigChange)
        is RearWidgetBackgroundModeField -> BackgroundModeFieldEditor(
            field,
            business,
            templateSourcePath,
            config,
            onConfigChange,
        )

        is RearWidgetMultiModeImageListField -> MultiModeImageListFieldEditor(
            field,
            business,
            templateSourcePath,
            config,
            onConfigChange,
        )

        is RearWidgetAnimationField -> AnimationFieldEditor(field, config, onConfigChange)
        is RearWidgetStringVarField -> FreeTextStringFieldEditor(
            title = field.resolvedTitle(),
            summary = stringResource(R.string.rear_widget_card_template_string_var_summary),
            initialValue = config.stringVarValue(field.name) ?: field.value,
            onValueChange = { onConfigChange(config.putStringVarValue(field.name, it)) },
        )

        is RearWidgetCustomEditLinkField -> CustomEditLinkFieldEditor(field)
    }
}

@Composable
private fun TextFieldEditor(
    field: RearWidgetTextField,
    config: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    val presets = field.valueList
    val current = config.textValue(field.name) ?: presets.firstOrNull().orEmpty()
    val summary = buildList {
        field.resolvedHint().takeIf { it.isNotBlank() }?.let(::add)
        if (field.minLength > 0 || field.maxLength > 0) {
            add(
                stringResource(
                    R.string.rear_widget_card_template_text_length_summary,
                    field.minLength,
                    field.maxLength,
                )
            )
        }
    }.joinToString("\n").ifBlank { null }
    if (!field.editable && presets.isNotEmpty()) {
        SingleChoicePreference(
            title = field.resolvedTitle(),
            summary = current,
            options = presets,
            selectedValue = current,
            onSelected = { onConfigChange(config.putTextValue(field.name, it)) },
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FreeTextStringFieldEditor(
            title = field.resolvedTitle(),
            summary = summary,
            initialValue = current,
            singleLine = false,
            onValueChange = { next ->
                val normalized = next.take(field.maxLength.takeIf { it > 0 } ?: Int.MAX_VALUE)
                onConfigChange(config.putTextValue(field.name, normalized))
            },
        )
        if (presets.isNotEmpty()) {
            SingleChoicePreference(
                title = stringResource(R.string.rear_widget_card_template_pick_preset),
                summary = current,
                options = presets,
                selectedValue = current,
                onSelected = { onConfigChange(config.putTextValue(field.name, it)) },
            )
        }
    }
}

@Composable
private fun DropDownFieldEditor(
    field: RearWidgetDropDownField,
    config: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    val selectedValue = config.dropDownValue(field.name)
        ?: field.items.getOrNull(field.defaultIndex.coerceAtLeast(0))?.value
        ?: field.items.firstOrNull()?.value
            .orEmpty()
    val selectedItem = field.items.firstOrNull { it.value == selectedValue }
    val selectedLabel = selectedItem?.resolvedLabel() ?: selectedValue
    val summary = buildString {
        append(selectedLabel)
        selectedItem?.contentDescription?.takeIf { it.isNotBlank() }?.let {
            append("\n")
            append(it)
        }
    }
    SingleChoicePreference(
        title = field.resolvedTitle(),
        summary = summary,
        options = field.items.map { it.resolvedLabel() },
        optionSummaries = field.items.map { it.contentDescription },
        selectedValue = selectedLabel,
        onSelected = { optionLabel ->
            val selected = field.items.firstOrNull { it.resolvedLabel() == optionLabel }
                ?: return@SingleChoicePreference
            onConfigChange(config.putDropDownValue(field.name, selected.value))
        },
    )
}

@Composable
private fun ColorFieldEditor(
    field: RearWidgetColorField,
    config: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    val options = field.values
    val current = config.colorValue(field.name) ?: options.firstOrNull().orEmpty()
    ColorValueEditor(
        title = field.resolvedTitle(),
        palette = options,
        currentValue = current,
        onValueChange = { onConfigChange(config.putColorValue(field.name, it)) },
    )
}

@Composable
private fun ColorGroupFieldEditor(
    field: RearWidgetColorGroupField,
    config: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    val current = config.colorGroupConfig ?: RearWidgetColorGroupSaveConfig(
        name = field.name,
        selectColors = field.items.associate { it.name to it.values.firstOrNull().orEmpty() },
        index = 0,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TemplateSectionHeader(title = field.resolvedTitle())
        field.items.forEachIndexed { index, item ->
            val currentValue =
                current.selectColors[item.name] ?: item.values.firstOrNull().orEmpty()
            ColorValueEditor(
                title = item.name,
                palette = item.values,
                currentValue = currentValue,
                onValueChange = { selected ->
                    onConfigChange(
                        config.copy(
                            colorGroupConfig = current.copy(
                                name = field.name,
                                selectColors = LinkedHashMap(current.selectColors).apply {
                                    put(item.name, selected)
                                },
                                index = index,
                            )
                        )
                    )
                },
            )
        }
    }
}

@Composable
private fun RangeFieldEditor(
    field: RearWidgetRangeField,
    config: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    val current = when (field.tagName) {
        "FontSize" -> config.textSizeValue(field.name) ?: field.defaultValue
        "Align" -> config.alignValue(field.name) ?: field.defaultValue
        else -> config.seekBarValue(field.name) ?: field.defaultValue
    }.coerceIn(field.from, field.to.takeIf { it >= field.from } ?: field.from)

    val rangeEnd = field.to.takeIf { it >= field.from } ?: field.from
    if (field.tagName == "Align" && rangeEnd >= field.from) {
        val alignValues = (field.from..rangeEnd).toList()
        if (alignValues.size == 3) {
            AlignFieldEditor(
                title = field.resolvedTitle(),
                selectedValue = current,
                values = alignValues,
                onSelected = { onConfigChange(config.putAlignValue(field.name, it)) },
            )
        } else {
            val options = alignValues.map(Int::toString)
            SingleChoicePreference(
                title = field.resolvedTitle(),
                summary = current.toString(),
                options = options,
                selectedValue = current.toString(),
                onSelected = {
                    val normalized = it.toIntOrNull() ?: current
                    onConfigChange(config.putAlignValue(field.name, normalized))
                },
            )
        }
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compactWidth = maxWidth < 360.dp
        val contentHorizontalPadding = if (compactWidth) 12.dp else 16.dp
        val sliderHorizontalPadding = if (compactWidth) 8.dp else 12.dp
        val previewFontSize = if (compactWidth) current.coerceAtMost(34).sp else current.sp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = contentHorizontalPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TemplateSectionHeader(
                title = field.resolvedTitle(),
                summary = stringResource(R.string.rear_widget_card_template_range_summary, current),
                modifier = Modifier.padding(bottom = 2.dp),
                insideMargin = PaddingValues(vertical = 10.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = field.from.toString(),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Slider(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = sliderHorizontalPadding),
                    value = current.toFloat(),
                    onValueChange = { next ->
                        val normalized = next.roundToInt().coerceIn(field.from, rangeEnd)
                        val nextConfig = when (field.tagName) {
                            "FontSize" -> config.putTextSizeValue(field.name, normalized)
                            "Align" -> config.putAlignValue(field.name, normalized)
                            else -> config.putSeekBarValue(field.name, normalized)
                        }
                        onConfigChange(nextConfig)
                    },
                    valueRange = field.from.toFloat()..rangeEnd.toFloat(),
                    steps = (rangeEnd - field.from - 1).coerceAtLeast(0),
                )
                Text(
                    text = rangeEnd.toString(),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (field.tagName == "FontSize" || (field.tagName == "SeekBar" && field.uiType == 1)) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = contentHorizontalPadding, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.rear_widget_card_template_font_preview_title),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = stringResource(R.string.rear_widget_card_template_font_preview_text),
                            fontSize = previewFontSize,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlignFieldEditor(
    title: String,
    selectedValue: Int,
    values: List<Int>,
    onSelected: (Int) -> Unit,
) {
    val options = listOf(
        AlignGraphicOption(
            value = values[0],
            title = stringResource(R.string.align_left_horizontally),
            icon = Icons.AutoMirrored.Rounded.FormatAlignLeft,
        ),
        AlignGraphicOption(
            value = values[1],
            title = stringResource(R.string.align_center_horizontally),
            icon = Icons.Rounded.FormatAlignCenter,
        ),
        AlignGraphicOption(
            value = values[2],
            title = stringResource(R.string.align_right_horizontally),
            icon = Icons.AutoMirrored.Rounded.FormatAlignRight,
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TemplateSectionHeader(title = title)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                options.forEach { option ->
                    val selected = selectedValue == option.value
                    val interactionSource = remember(option.value) { MutableInteractionSource() }
                    val pressed by interactionSource.collectIsPressedAsState()
                    Card(
                        modifier = Modifier
                            .width(112.dp)
                            .shadow(
                                elevation = if (pressed) TemplateOptionCardSelectedShadow else 0.dp,
                                shape = TemplateOptionCardShape,
                                clip = false,
                            )
                            .clip(TemplateOptionCardShape)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)
                                },
                                shape = TemplateOptionCardShape,
                            )
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                            ) { onSelected(option.value) },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = option.icon,
                                contentDescription = null,
                                tint = if (selectedValue == option.value) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onBackground
                                },
                            )
                            Text(
                                text = option.title,
                                style = MiuixTheme.textStyles.body2,
                                color = if (selected) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageSelectFieldEditor(
    field: RearWidgetImageSelectField,
    business: String,
    templateSourcePath: String,
    config: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    val currentValue = config.imageValue(field.name) ?: field.values.firstOrNull()?.value.orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TemplateSectionHeader(title = field.resolvedTitle())
        field.values.forEachIndexed { index, option ->
            TemplateImageOptionCard(
                business = business,
                templateSourcePath = templateSourcePath,
                imageValue = option.value,
                label = option.label?.takeIf { it.isNotBlank() }
                    ?: stringResource(
                        R.string.rear_widget_card_template_image_option_index,
                        index + 1
                    ),
                secondaryLabel = null,
                selected = currentValue == option.value,
                selectionMode = ImageOptionSelectionMode.Single,
                onClick = { onConfigChange(config.putImageValue(field.name, option.value)) },
            )
        }
    }
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
private fun ImagePickFieldEditor(
    field: RearWidgetImagePickField,
    business: String,
    cardStorageKey: String,
    templateSourcePath: String,
    config: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    val context = LocalContext.current
    val currentValue = config.imageValue(field.name).orEmpty()
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val storedPath = RearWidgetManagerRepository.importCardCustomImage(
            context = context,
            cardKey = cardStorageKey,
            fieldName = field.name,
            uri = uri,
        )
        if (storedPath.isNullOrBlank()) {
            Toast.makeText(
                context,
                context.getString(R.string.rear_widget_card_template_image_pick_failed),
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            onConfigChange(config.putImageValue(field.name, storedPath))
            Toast.makeText(
                context,
                context.getString(R.string.rear_widget_card_template_image_pick_imported),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TemplateSectionHeader(title = field.resolvedTitle())
        if (field.options.isNotEmpty()) {
            field.options.forEachIndexed { index, option ->
                TemplateImageOptionCard(
                    business = business,
                    templateSourcePath = templateSourcePath,
                    imageValue = option.value,
                    label = option.label?.takeIf { it.isNotBlank() }
                        ?: stringResource(
                            R.string.rear_widget_card_template_image_option_index,
                            index + 1
                        ),
                    secondaryLabel = null,
                    selected = currentValue == option.value,
                    selectionMode = ImageOptionSelectionMode.Single,
                    onClick = { onConfigChange(config.putImageValue(field.name, option.value)) },
                )
            }
        } else {
            Text(
                text = stringResource(R.string.rear_widget_card_template_image_pick_summary),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Button(
                onClick = { imagePickerLauncher.launch(arrayOf("image/*")) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text = stringResource(R.string.rear_widget_card_template_pick_custom_image))
            }
            currentValue.takeIf { it.isNotBlank() }?.let { imageValue ->
                TemplateImageOptionCard(
                    business = business,
                    templateSourcePath = templateSourcePath,
                    imageValue = imageValue,
                    label = stringResource(R.string.rear_widget_card_template_current_image_preview),
                    secondaryLabel = null,
                    selected = true,
                    selectionMode = ImageOptionSelectionMode.None,
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun MultiImageFieldEditor(
    field: RearWidgetMultiImageSelectField,
    business: String,
    templateSourcePath: String,
    config: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    val current = config.multiImageConfig?.takeIf { it.name == field.name }?.selectImages
        ?: field.defaultSelection.ifEmpty {
            List(field.items.size) { index -> if (index == 0) 1.0 else 0.0 }
        }
    val hasDarkVariant = field.items.any { !it.valueDark.isNullOrBlank() }
    var previewVariant by remember(field.name) { mutableStateOf(ImagePreviewVariantTab.Light) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TemplateSectionHeader(title = field.resolvedTitle())
        if (hasDarkVariant) {
            TabRowWithContour(
                tabs = listOf(
                    stringResource(R.string.rear_widget_card_template_preview_light),
                    stringResource(R.string.rear_widget_card_template_preview_dark),
                ),
                selectedTabIndex = if (previewVariant == ImagePreviewVariantTab.Light) 0 else 1,
                onTabSelected = {
                    previewVariant =
                        if (it == 0) ImagePreviewVariantTab.Light else ImagePreviewVariantTab.Dark
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        field.items.forEachIndexed { index, option ->
            val checked = current.getOrNull(index)?.let { it != 0.0 } == true
            TemplateImageOptionCard(
                business = business,
                templateSourcePath = templateSourcePath,
                imageValue = option.value,
                darkImageValue = option.valueDark,
                label = option.resolvedLabel().takeIf { it.isNotBlank() }
                    ?: stringResource(
                        R.string.rear_widget_card_template_image_option_index,
                        index + 1
                    ),
                secondaryLabel = option.contentDescription,
                selected = checked,
                selectionMode = if (field.single) ImageOptionSelectionMode.Single else ImageOptionSelectionMode.Multi,
                previewVariant = previewVariant,
                onClick = {
                    val enabled = !checked
                    val next = MutableList(field.items.size) { itemIndex ->
                        current.getOrNull(itemIndex) ?: 0.0
                    }
                    if (field.single) {
                        next.indices.forEach { next[it] = 0.0 }
                        next[index] = if (enabled) 1.0 else 0.0
                    } else {
                        next[index] = if (enabled) 1.0 else 0.0
                    }
                    onConfigChange(
                        config.copy(
                            multiImageConfig = RearWidgetMultiImageConfig(
                                name = field.name,
                                selectImages = next,
                            )
                        )
                    )
                },
            )
        }
    }
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
private fun IntentFieldEditor(
    field: RearWidgetIntentField,
    config: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    val context = LocalContext.current
    val current =
        config.intentSaveConfig?.get(field.name) ?: RearWidgetIntentSaveConfig(name = field.name)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TemplateSectionHeader(
            title = field.resolvedTitle(),
            summary = listOfNotNull(field.action, field.packageName, field.className)
                .joinToString(separator = " | ")
                .ifBlank { stringResource(R.string.rear_widget_card_template_intent_summary) },
        )
        if (!field.action.isNullOrBlank() || !field.packageName.isNullOrBlank() ||
            !field.className.isNullOrBlank() || !field.uri.isNullOrBlank()
        ) {
            ArrowPreference(
                title = stringResource(R.string.rear_widget_card_template_intent_launch),
                summary = stringResource(R.string.rear_widget_card_template_intent_launch_summary),
                onClick = {
                    runCatching {
                        context.startActivity(buildIntentFromField(field).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }.onFailure {
                        Toast.makeText(
                            context,
                            context.getString(R.string.rear_widget_card_template_intent_launch_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }
        field.returnValue.forEachIndexed { index, key ->
            val valueType = field.valueType.getOrNull(index).orEmpty()
            val numberValue = current.returnValueNumber[key]
            val stringValue = current.returnValueString[key]
            val initial =
                numberValue?.toString() ?: stringValue ?: field.defaultValue.getOrNull(index)
                    .orEmpty()
            FreeTextStringFieldEditor(
                title = "$key (${valueType.ifBlank { "string" }})",
                summary = null,
                initialValue = initial,
                onValueChange = { raw ->
                    val strings = LinkedHashMap(current.returnValueString)
                    val numbers = LinkedHashMap(current.returnValueNumber)
                    if (valueType.equals("boolean", ignoreCase = true)) {
                        strings.remove(key)
                        numbers[key] = if (raw == "1" || raw.equals("true", true)) 1.0 else 0.0
                    } else if (valueType.equals("int", true) || valueType.equals("float", true) ||
                        valueType.equals("double", true) || valueType.equals("number", true)
                    ) {
                        raw.toDoubleOrNull()?.let { numbers[key] = it }
                        strings.remove(key)
                    } else {
                        strings[key] = raw
                        numbers.remove(key)
                    }
                    onConfigChange(
                        config.putIntentConfig(
                            field.name,
                            RearWidgetIntentSaveConfig(
                                name = field.name,
                                returnValueString = strings,
                                returnValueNumber = numbers,
                            )
                        )
                    )
                },
            )
        }
    }
}

@Composable
private fun DateFieldEditor(
    field: RearWidgetDateField,
    config: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    val current = config.dateSetConfig?.takeIf { it.name == field.name }
        ?: RearWidgetDateSetSaveConfig(
            name = field.name,
            date = field.defaultDate,
            repeatName = field.repeatName,
            repeatValue = field.repeatValue,
        )
    var text by remember(
        field.name,
        current.date
    ) { mutableStateOf(formatDateMillis(current.date)) }
    val valid = isValidDateString(text)

    LaunchedEffect(current.date) {
        text = formatDateMillis(current.date)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TemplateSectionHeader(
            title = field.resolvedTitle(),
            modifier = Modifier.padding(bottom = 2.dp),
            insideMargin = PaddingValues(vertical = 10.dp),
        )
        TextField(
            value = text,
            onValueChange = { next ->
                text = next
                parseDateStringToMillis(next)?.let { millis ->
                    onConfigChange(
                        config.copy(
                            dateSetConfig = current.copy(date = millis)
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.rear_widget_card_template_date_label),
            singleLine = true,
        )
        Text(
            text = if (valid) {
                stringResource(R.string.rear_widget_card_template_date_summary, current.repeatValue)
            } else {
                stringResource(R.string.rear_widget_card_template_date_invalid)
            },
            style = MiuixTheme.textStyles.body2,
            color = if (valid) {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            } else {
                MiuixTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun OnOffFieldEditor(
    field: RearWidgetOnOffField,
    config: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    SwitchPreference(
        title = field.resolvedTitle(),
        checked = config.onOffValue(field.name) ?: field.defaultOn,
        onCheckedChange = { onConfigChange(config.putOnOffValue(field.name, it)) },
    )
}

@Composable
private fun CustomColorFieldEditor(
    field: RearWidgetCustomColorField,
    config: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    val current = config.customColorSaveConfig?.takeIf { it.name == field.name }
        ?: RearWidgetCustomColorSaveConfig(
            name = field.name,
            value = field.defaultValue.ifBlank { field.values.firstOrNull().orEmpty() },
            index = field.index,
        )
    ColorValueEditor(
        title = field.resolvedTitle(),
        palette = field.values,
        currentValue = current.value,
        onValueChange = {
            onConfigChange(
                config.copy(customColorSaveConfig = current.copy(value = it))
            )
        },
    )
}

@Composable
private fun BackgroundModeFieldEditor(
    field: RearWidgetBackgroundModeField,
    business: String,
    templateSourcePath: String,
    config: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    val current = config.backgroundModeSaveConfig?.takeIf { it.name == field.name }
        ?: RearWidgetBackgroundModeSaveConfig(
            name = field.name,
            value = field.value,
            uriName = field.uriName,
            uri = field.uri,
            videoName = field.videoName,
            videoUri = field.videoUri,
            foregroundName = field.foregroundName,
            foregroundUri = field.foregroundUri,
            resType = field.resType,
        )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TemplateSectionHeader(
            title = field.resolvedTitle(),
            summary = stringResource(
                R.string.rear_widget_card_template_background_binding_summary,
                field.uriName.ifBlank { "-" },
                field.videoName.ifBlank { "-" },
                field.foregroundName.ifBlank { "-" },
            ),
        )
        IntValueEditor(
            title = stringResource(R.string.rear_widget_card_template_background_mode_value),
            initialValue = current.value,
            onValueChange = {
                onConfigChange(
                    config.copy(
                        backgroundModeSaveConfig = current.copy(
                            value = it
                        )
                    )
                )
            },
        )
        FreeTextStringFieldEditor(
            title = stringResource(R.string.rear_widget_card_template_background_uri),
            initialValue = current.uri,
            onValueChange = { onConfigChange(config.copy(backgroundModeSaveConfig = current.copy(uri = it))) },
        )
        current.uri.takeIf { it.isNotBlank() }?.let { imageUri ->
            TemplateImageOptionCard(
                business = business,
                templateSourcePath = templateSourcePath,
                imageValue = imageUri,
                label = stringResource(R.string.rear_widget_card_template_current_image_preview),
                secondaryLabel = null,
                selected = true,
                selectionMode = ImageOptionSelectionMode.None,
                onClick = {},
            )
        }
        FreeTextStringFieldEditor(
            title = stringResource(R.string.rear_widget_card_template_background_video_uri),
            initialValue = current.videoUri,
            onValueChange = {
                onConfigChange(config.copy(backgroundModeSaveConfig = current.copy(videoUri = it)))
            },
        )
        FreeTextStringFieldEditor(
            title = stringResource(R.string.rear_widget_card_template_background_foreground_uri),
            initialValue = current.foregroundUri,
            onValueChange = {
                onConfigChange(config.copy(backgroundModeSaveConfig = current.copy(foregroundUri = it)))
            },
        )
        current.foregroundUri.takeIf { it.isNotBlank() }?.let { foregroundUri ->
            TemplateImageOptionCard(
                business = business,
                templateSourcePath = templateSourcePath,
                imageValue = foregroundUri,
                label = stringResource(R.string.rear_widget_card_template_foreground_preview),
                secondaryLabel = null,
                selected = true,
                selectionMode = ImageOptionSelectionMode.None,
                onClick = {},
            )
        }
        IntValueEditor(
            title = stringResource(R.string.rear_widget_card_template_background_res_type),
            initialValue = current.resType,
            onValueChange = {
                onConfigChange(config.copy(backgroundModeSaveConfig = current.copy(resType = it)))
            },
        )
    }
}

@Composable
private fun MultiModeImageListFieldEditor(
    field: RearWidgetMultiModeImageListField,
    business: String,
    templateSourcePath: String,
    config: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    val current = config.multiModeImageListSaveConfig?.takeIf { it.name == field.name }
        ?: RearWidgetMultiModeImageListSaveConfig(
            name = field.name,
            size = field.size,
            itemList = field.items.map { child ->
                RearWidgetBackgroundModeSaveConfig(
                    name = "",
                    value = child.value,
                    uriName = "",
                    uri = child.uri,
                    videoName = "",
                    videoUri = child.videoUri,
                    foregroundName = "",
                    foregroundUri = child.foregroundUri,
                    resType = child.resType,
                )
            },
        )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TemplateSectionHeader(title = field.resolvedTitle())
        IntValueEditor(
            title = stringResource(R.string.rear_widget_card_template_list_size),
            initialValue = current.size,
            onValueChange = {
                onConfigChange(config.copy(multiModeImageListSaveConfig = current.copy(size = it)))
            },
        )
        current.itemList.orEmpty().forEachIndexed { index, item ->
            MultiModeImageItemEditor(
                index = index,
                business = business,
                templateSourcePath = templateSourcePath,
                item = item,
                onItemChange = { next ->
                    val nextItems = current.itemList.orEmpty().toMutableList().apply {
                        this[index] = next
                    }
                    onConfigChange(
                        config.copy(
                            multiModeImageListSaveConfig = current.copy(itemList = nextItems)
                        )
                    )
                },
            )
        }
    }
}

@Composable
private fun MultiModeImageItemEditor(
    index: Int,
    business: String,
    templateSourcePath: String,
    item: RearWidgetBackgroundModeSaveConfig,
    onItemChange: (RearWidgetBackgroundModeSaveConfig) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TemplateSectionHeader(
                title = stringResource(
                    R.string.rear_widget_card_template_list_item_title,
                    index + 1
                ),
            )
            IntValueEditor(
                title = stringResource(R.string.rear_widget_card_template_background_mode_value),
                initialValue = item.value,
                onValueChange = { onItemChange(item.copy(value = it)) },
            )
            FreeTextStringFieldEditor(
                title = stringResource(R.string.rear_widget_card_template_background_uri),
                initialValue = item.uri,
                onValueChange = { onItemChange(item.copy(uri = it)) },
            )
            item.uri.takeIf { it.isNotBlank() }?.let { imageUri ->
                TemplateImageOptionCard(
                    business = business,
                    templateSourcePath = templateSourcePath,
                    imageValue = imageUri,
                    label = stringResource(R.string.rear_widget_card_template_current_image_preview),
                    secondaryLabel = null,
                    selected = true,
                    selectionMode = ImageOptionSelectionMode.None,
                    onClick = {},
                )
            }
            FreeTextStringFieldEditor(
                title = stringResource(R.string.rear_widget_card_template_background_video_uri),
                initialValue = item.videoUri,
                onValueChange = { onItemChange(item.copy(videoUri = it)) },
            )
            FreeTextStringFieldEditor(
                title = stringResource(R.string.rear_widget_card_template_background_foreground_uri),
                initialValue = item.foregroundUri,
                onValueChange = { onItemChange(item.copy(foregroundUri = it)) },
            )
            item.foregroundUri.takeIf { it.isNotBlank() }?.let { foregroundUri ->
                TemplateImageOptionCard(
                    business = business,
                    templateSourcePath = templateSourcePath,
                    imageValue = foregroundUri,
                    label = stringResource(R.string.rear_widget_card_template_foreground_preview),
                    secondaryLabel = null,
                    selected = true,
                    selectionMode = ImageOptionSelectionMode.None,
                    onClick = {},
                )
            }
            IntValueEditor(
                title = stringResource(R.string.rear_widget_card_template_background_res_type),
                initialValue = item.resType,
                onValueChange = { onItemChange(item.copy(resType = it)) },
            )
        }
    }
}

@Composable
private fun AnimationFieldEditor(
    field: RearWidgetAnimationField,
    config: RearWidgetOneConfig,
    onConfigChange: (RearWidgetOneConfig) -> Unit,
) {
    val current = config.animationSaveConfig?.takeIf { it.name == field.name }
        ?: RearWidgetAnimationSaveConfig(
            name = field.name,
            x = field.x,
            y = field.y,
            scaleX = field.scaleX,
            scaleY = field.scaleY,
            rotation = field.rotation,
            displayScale = field.displayScale,
        )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TemplateSectionHeader(title = field.resolvedTitle())
        DoubleValueEditor("x", current.x) {
            onConfigChange(config.copy(animationSaveConfig = current.copy(x = it)))
        }
        DoubleValueEditor("y", current.y) {
            onConfigChange(config.copy(animationSaveConfig = current.copy(y = it)))
        }
        DoubleValueEditor("scaleX", current.scaleX) {
            onConfigChange(config.copy(animationSaveConfig = current.copy(scaleX = it)))
        }
        DoubleValueEditor("scaleY", current.scaleY) {
            onConfigChange(config.copy(animationSaveConfig = current.copy(scaleY = it)))
        }
        DoubleValueEditor("rotation", current.rotation) {
            onConfigChange(config.copy(animationSaveConfig = current.copy(rotation = it)))
        }
        DoubleValueEditor("displayScale", current.displayScale) {
            onConfigChange(config.copy(animationSaveConfig = current.copy(displayScale = it)))
        }
    }
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
private fun CustomEditLinkFieldEditor(field: RearWidgetCustomEditLinkField) {
    val context = LocalContext.current
    ArrowPreference(
        title = stringResource(R.string.rear_widget_card_template_external_editor),
        summary = field.deeplink,
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, field.deeplink.toUri()).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }.onFailure {
                Toast.makeText(
                    context,
                    context.getString(R.string.rear_widget_card_template_external_editor_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
    )
}

@Composable
private fun ColorValueEditor(
    title: String,
    palette: List<String>,
    currentValue: String,
    onValueChange: (String) -> Unit,
) {
    var rawValue by remember(title, currentValue) { mutableStateOf(currentValue) }
    var showAdvancedPicker by remember(title) { mutableStateOf(false) }
    LaunchedEffect(currentValue) {
        rawValue = currentValue
    }

    val parsedColor = remember(rawValue) { parseColorInt(rawValue) }
    val composeColor = parsedColor?.let(::Color) ?: Color.White

    fun commitColor(color: Color) {
        val formatted = formatColorInt(color.toArgb())
        rawValue = formatted
        onValueChange(formatted)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TemplateSectionHeader(
            title = title,
            modifier = Modifier.padding(bottom = 2.dp),
            insideMargin = PaddingValues(vertical = 10.dp),
        )
        if (palette.isNotEmpty()) {
            Text(
                text = stringResource(R.string.rear_widget_card_template_color_palette),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            ColorPalette(
                color = composeColor,
                onColorChanged = { commitColor(it) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                palette.forEach { option ->
                    val parsedOption = parseColorInt(option) ?: return@forEach
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .border(
                                width = if (option.equals(currentValue, true)) 2.dp else 1.dp,
                                color = if (option.equals(currentValue, true)) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                                shape = RoundedCornerShape(999.dp),
                            )
                            .background(Color(parsedOption))
                            .clickable { commitColor(Color(parsedOption)) },
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.rear_widget_card_template_color_manual),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        TextField(
            value = rawValue,
            onValueChange = {
                rawValue = it
                parseColorInt(it)?.let { parsed ->
                    onValueChange(formatColorInt(parsed))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = title,
            singleLine = true,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .border(
                            1.dp,
                            MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            RoundedCornerShape(999.dp)
                        )
                        .background(parsedColor?.let(::Color) ?: Color.Transparent),
                )
                Column(
                    modifier = Modifier.padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.rear_widget_card_template_color_advanced),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = rawValue.ifBlank { stringResource(R.string.rear_widget_card_template_color_invalid) },
                        color = if (parsedColor == null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onBackground,
                    )
                }
                Button(onClick = { showAdvancedPicker = true }) {
                    Text(text = stringResource(R.string.rear_widget_card_template_open_color_picker))
                }
            }
        }
    }

    OverlayDialog(
        show = showAdvancedPicker,
        title = stringResource(R.string.rear_widget_card_template_color_advanced),
        onDismissRequest = { showAdvancedPicker = false },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ColorPicker(
                color = composeColor,
                onColorChanged = { commitColor(it) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { showAdvancedPicker = false },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(text = stringResource(R.string.rear_widget_confirm))
            }
        }
    }
}

private fun parseColorInt(raw: String): Int? {
    val normalized = raw.trim()
    if (normalized.isBlank()) return null
    return runCatching { normalized.toColorInt() }.getOrNull()
}

private fun buildIntentFromField(field: RearWidgetIntentField): Intent {
    val intent = Intent().apply {
        field.action?.takeIf { it.isNotBlank() }?.let(::setAction)
        field.uri?.takeIf { it.isNotBlank() }?.let { data = it.toUri() }
        if (!field.packageName.isNullOrBlank() && !field.className.isNullOrBlank()) {
            setClassName(field.packageName, field.className)
        } else if (!field.packageName.isNullOrBlank()) {
            `package` = field.packageName
        }
        if (field.flags >= 0) {
            addFlags(field.flags)
        }
        field.extras.forEach { extra ->
            when (extra.type.lowercase(Locale.ROOT)) {
                "string" -> putExtra(extra.name, extra.value)
                "int", "integer" -> extra.value.toIntOrNull()?.let { putExtra(extra.name, it) }
                "boolean", "bool" -> putExtra(
                    extra.name,
                    extra.value == "1" || extra.value.equals("true", ignoreCase = true),
                )

                "float" -> extra.value.toFloatOrNull()?.let { putExtra(extra.name, it) }
                "double", "number" -> extra.value.toDoubleOrNull()?.let { putExtra(extra.name, it) }
                else -> putExtra(extra.name, extra.value)
            }
        }
    }
    return intent
}

private fun formatColorInt(color: Int): String {
    return String.format(Locale.US, "#%08X", color)
}

private val TemplateOptionCardShape = RoundedCornerShape(18.dp)
private val TemplateOptionCardSelectedShadow = 10.dp

@Composable
private fun TemplateSectionHeader(
    modifier: Modifier = Modifier,
    title: String,
    summary: String? = null,
    insideMargin: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
) {
    BasicComponent(
        modifier = modifier.fillMaxWidth(),
        title = title,
        summary = summary?.takeIf { it.isNotBlank() },
        insideMargin = insideMargin,
    )
}

private enum class ImageOptionSelectionMode {
    None,
    Single,
    Multi,
}

private enum class ImagePreviewVariantTab {
    Light,
    Dark,
}

private data class AlignGraphicOption(
    val value: Int,
    val title: String,
    val icon: ImageVector,
)

@Composable
private fun TemplateImageOptionCard(
    business: String,
    templateSourcePath: String,
    imageValue: String,
    label: String,
    secondaryLabel: String?,
    selected: Boolean,
    selectionMode: ImageOptionSelectionMode,
    onClick: () -> Unit,
    darkImageValue: String? = null,
    previewVariant: ImagePreviewVariantTab = ImagePreviewVariantTab.Light,
) {
    val hasDarkVariant = darkImageValue.isNullOrBlank().not()
    val previewImageValue = if (hasDarkVariant && previewVariant == ImagePreviewVariantTab.Dark) {
        darkImageValue
    } else {
        imageValue
    }
    val selectedBorderColor = if (selected) {
        MiuixTheme.colorScheme.primary
    } else {
        MiuixTheme.colorScheme.outline.copy(alpha = 0.35f)
    }
    val interactionSource =
        remember(imageValue, darkImageValue, label) { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .shadow(
                elevation = if (pressed) TemplateOptionCardSelectedShadow else 0.dp,
                shape = TemplateOptionCardShape,
                clip = false,
            )
            .clip(TemplateOptionCardShape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = selectedBorderColor,
                shape = TemplateOptionCardShape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TemplatePreviewThumbnail(
                business = business,
                templateSourcePath = templateSourcePath,
                imageValue = previewImageValue,
                modifier = Modifier.height(76.dp),
            )
            Column(
                modifier = Modifier.padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = label)
                secondaryLabel?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            when (selectionMode) {
                ImageOptionSelectionMode.None -> Unit
                ImageOptionSelectionMode.Single -> Unit
                ImageOptionSelectionMode.Multi -> Checkbox(
                    state = if (selected) ToggleableState.On else ToggleableState.Off,
                    onClick = onClick,
                )
            }
        }
    }
}

@Composable
private fun TemplatePreviewThumbnail(
    business: String,
    templateSourcePath: String,
    imageValue: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberRearWidgetTemplatePreviewBitmap(
        business = business,
        templateSourcePath = templateSourcePath,
        imageValue = imageValue,
    )
    Box(
        modifier = modifier
            .width(112.dp)
            .aspectRatio(bitmap?.let {
                (it.width.toFloat() / it.height.coerceAtLeast(1).toFloat()).coerceIn(0.8f, 1.9f)
            } ?: 1.55f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = stringResource(R.string.rear_widget_card_template_preview_unavailable),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun FreeTextStringFieldEditor(
    title: String,
    summary: String? = null,
    initialValue: String,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    onValueChange: (String) -> Unit,
) {
    var value by remember(title, initialValue) { mutableStateOf(initialValue) }
    LaunchedEffect(initialValue) {
        value = initialValue
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TemplateSectionHeader(
            title = title,
            summary = summary,
            modifier = Modifier.padding(bottom = 2.dp),
            insideMargin = PaddingValues(vertical = 10.dp),
        )
        TextField(
            value = value,
            onValueChange = {
                value = it
                onValueChange(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = title,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
        )
    }
}

@Composable
private fun IntValueEditor(
    title: String,
    initialValue: Int,
    onValueChange: (Int) -> Unit,
) {
    var value by remember(title, initialValue) { mutableStateOf(initialValue.toString()) }
    LaunchedEffect(initialValue) {
        value = initialValue.toString()
    }
    FreeTextStringFieldEditor(
        title = title,
        initialValue = value,
        onValueChange = {
            value = it
            it.toIntOrNull()?.let(onValueChange)
        },
    )
}

@Composable
private fun DoubleValueEditor(
    title: String,
    initialValue: Double,
    onValueChange: (Double) -> Unit,
) {
    var value by remember(title, initialValue) { mutableStateOf(initialValue.toString()) }
    LaunchedEffect(initialValue) {
        value = initialValue.toString()
    }
    FreeTextStringFieldEditor(
        title = title,
        initialValue = value,
        onValueChange = {
            value = it
            it.toDoubleOrNull()?.let(onValueChange)
        },
    )
}

@Composable
private fun SingleChoicePreference(
    title: String,
    summary: String,
    options: List<String>,
    optionSummaries: List<String?> = List(options.size) { null },
    selectedValue: String,
    onSelected: (String) -> Unit,
) {
    val popupScope = rememberCoroutineScope()
    var showPopup by remember(title, selectedValue, options) { mutableStateOf(false) }
    val entries = remember(options, optionSummaries) {
        options.mapIndexed { index, option ->
            DropdownItem(
                title = option,
                summary = optionSummaries.getOrNull(index)?.takeIf { it.isBlank().not() },
            )
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = title,
            summary = summary,
            holdDownState = showPopup,
            onClick = { if (options.isNotEmpty()) showPopup = true },
        )

        OverlayListPopup(
            show = showPopup,
            popupModifier = Modifier.widthIn(min = 220.dp),
            popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
            alignment = PopupPositionProvider.Align.End,
            enableWindowDim = true,
            onDismissRequest = { showPopup = false },
            maxHeight = null,
            minWidth = 220.dp,
            renderInRootScaffold = true,
        ) {
            ListPopupColumn {
                options.forEachIndexed { index, option ->
                    DropdownImpl(
                        item = entries[index],
                        optionSize = options.size,
                        isSelected = option == selectedValue,
                        index = index,
                        dropdownColors = DropdownDefaults.dropdownColors(),
                        onSelectedIndexChange = {
                            showPopup = false
                            popupScope.launch {
                                withFrameNanos { }
                                onSelected(option)
                            }
                        },
                    )
                }
            }
        }
    }
}
