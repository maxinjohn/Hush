package app.hush.music.ui.screens.equalizer.axion

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hush.music.R
import app.hush.music.constants.EqualizerBassBoostEnabledKey
import app.hush.music.constants.EqualizerBassBoostStrengthKey
import app.hush.music.constants.EqualizerVirtualizerEnabledKey
import app.hush.music.constants.EqualizerVirtualizerStrengthKey
import app.hush.music.eq.data.SavedEQProfile
import app.hush.music.utils.rememberPreference
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AxionEqScreen(
    onBackClick: () -> Unit,
    viewModel: AxionEqViewModel,
) {
    val enabled by viewModel.enabled.collectAsState()
    val bandGains by viewModel.bandGains.collectAsState()
    val mode by viewModel.mode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.equalizer)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Text("<", style = MaterialTheme.typography.titleMedium)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Enable toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.eq_enable_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.eq_enable_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { viewModel.setEnabled(it) },
                )
            }

            // Frequency response curve
            EqFrequencyResponseCurve(
                bandGains = bandGains,
                enabled = enabled,
                onBandChange = { band, value ->
                    viewModel.setBandGain(band, value, applyOnly = true)
                },
                onBandChangeFinished = { viewModel.commitCurrentGains() },
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            // Mode switcher
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            ) {
                ToggleButton(
                    checked = mode == 0,
                    onCheckedChange = { viewModel.setMode(0) },
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                    shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                ) {
                    Text(stringResource(R.string.eq_simple))
                }
                ToggleButton(
                    checked = mode == 1,
                    onCheckedChange = { viewModel.setMode(1) },
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                    shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                ) {
                    Text(stringResource(R.string.eq_advanced))
                }
            }

            // Content body
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    fadeIn().togetherWith(fadeOut()).using(SizeTransform(clip = false))
                },
                label = "eqMode",
            ) { currentMode ->
                val isDirty by viewModel.isDirty.collectAsState()
                var showSaveDialog by remember { mutableStateOf(false) }

                if (showSaveDialog) {
                    SavePresetDialog(
                        onDismiss = { showSaveDialog = false },
                        onSave = { name ->
                            viewModel.saveCustomProfile(name)
                            showSaveDialog = false
                        },
                    )
                }

                when (currentMode) {
                    0 -> SimpleEqMode(
                        bandGains = bandGains,
                        enabled = enabled,
                        viewModel = viewModel,
                        isDirty = isDirty,
                        onSaveClick = { showSaveDialog = true },
                    )
                    else -> AdvancedEqMode(
                        bandGains = bandGains,
                        enabled = enabled,
                        onBandChange = { band, value ->
                            viewModel.setBandGain(band, value, applyOnly = true)
                        },
                        onBandChangeFinished = { viewModel.commitCurrentGains() },
                        onReset = { viewModel.reset() },
                    )
                }
            }

            // Virtualizer effect
            EqEffectSection(
                title = stringResource(R.string.eq_virtualizer),
                description = stringResource(R.string.eq_virtualizer_desc),
                enabledKey = EqualizerVirtualizerEnabledKey,
                strengthKey = EqualizerVirtualizerStrengthKey,
            )

            // Bass Boost effect
            EqEffectSection(
                title = stringResource(R.string.eq_bass_boost),
                description = stringResource(R.string.eq_bass_boost_desc),
                enabledKey = EqualizerBassBoostEnabledKey,
                strengthKey = EqualizerBassBoostStrengthKey,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun EqFrequencyResponseCurve(
    bandGains: FloatArray,
    enabled: Boolean,
    onBandChange: (Int, Float) -> Unit,
    onBandChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    val bandFrequencies = remember { doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0) }
    val logFrequencies = remember { bandFrequencies.map { log10(it) } }

    val minLogF = log10(20.0)
    val maxLogF = log10(20000.0)
    val logRange = maxLogF - minLogF

    var activeNodeIndex by remember { mutableStateOf(-1) }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
    )

    fun getCumulativeY(bandIndex: Int, width: Float, height: Float, centerY: Float): Float {
        val logF = logFrequencies[bandIndex]
        val sigma = 0.28
        var totalGainDb = 0.0
        for (i in bandGains.indices) {
            val gainDb = bandGains[i] / 50.0
            val dist = logF - logFrequencies[i]
            val influence = exp(-(dist * dist) / (2.0 * sigma * sigma))
            totalGainDb += gainDb * influence
        }
        val normalizedGain = (totalGainDb / 15.0).coerceIn(-1.0, 1.0)
        return centerY - (normalizedGain * centerY * 0.8f).toFloat()
    }

    Box(modifier = modifier.fillMaxWidth().height(160.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(enabled, bandGains) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            val nodeXPositions = logFrequencies.map { logFreq ->
                                val pct = (logFreq - minLogF) / logRange
                                (pct * size.width).toFloat()
                            }
                            val nodeYPositions = bandGains.indices.map { idx ->
                                getCumulativeY(idx, size.width.toFloat(), size.height.toFloat(), size.height.toFloat() / 2f)
                            }

                            var closestIdx = -1
                            var minDistance = Float.MAX_VALUE
                            val maxTouchRadius = 40.dp.toPx()

                            for (i in nodeXPositions.indices) {
                                val dx = offset.x - nodeXPositions[i]
                                val dy = offset.y - nodeYPositions[i]
                                val distance = hypot(dx, dy)
                                if (distance < maxTouchRadius && distance < minDistance) {
                                    minDistance = distance
                                    closestIdx = i
                                }
                            }
                            activeNodeIndex = closestIdx
                        },
                        onDragEnd = {
                            activeNodeIndex = -1
                            onBandChangeFinished()
                        },
                        onDragCancel = {
                            activeNodeIndex = -1
                            onBandChangeFinished()
                        },
                        onDrag = { change, _ ->
                            if (activeNodeIndex >= 0) {
                                change.consume()
                                val centerY = size.height / 2f
                                val currentY = change.position.y
                                val normY = (centerY - currentY) / (centerY * 0.8f)
                                val newGainDb = (normY * 15.0).coerceIn(-12.0, 12.0)
                                val newGainFloat = (newGainDb * 50.0).toFloat()
                                onBandChange(activeNodeIndex, newGainFloat)
                            }
                        },
                    )
                }
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        ) {
            val width = size.width
            val height = size.height
            val centerY = height / 2f
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

            // Grid lines
            drawLine(
                color = outlineColor.copy(alpha = 0.3f),
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 1f,
            )
            drawLine(
                color = outlineColor.copy(alpha = 0.15f),
                start = Offset(0f, centerY - centerY * 0.8f),
                end = Offset(width, centerY - centerY * 0.8f),
                strokeWidth = 1f,
                pathEffect = dashEffect,
            )
            drawLine(
                color = outlineColor.copy(alpha = 0.15f),
                start = Offset(0f, centerY + centerY * 0.8f),
                end = Offset(width, centerY + centerY * 0.8f),
                strokeWidth = 1f,
                pathEffect = dashEffect,
            )

            // dB labels
            drawText(
                textMeasurer = textMeasurer,
                text = "+12 dB",
                style = labelStyle,
                topLeft = Offset(4.dp.toPx(), centerY - centerY * 0.8f - 6.dp.toPx()),
            )
            drawText(
                textMeasurer = textMeasurer,
                text = "0 dB",
                style = labelStyle,
                topLeft = Offset(4.dp.toPx(), centerY - 6.dp.toPx()),
            )
            drawText(
                textMeasurer = textMeasurer,
                text = "-12 dB",
                style = labelStyle,
                topLeft = Offset(4.dp.toPx(), centerY + centerY * 0.8f - 6.dp.toPx()),
            )

            // Vertical grid lines
            val gridFreqs = listOf(100.0, 1000.0, 10000.0)
            gridFreqs.forEach { freq ->
                val logVal = log10(freq)
                val pct = (logVal - minLogF) / logRange
                val x = (pct * width).toFloat()
                drawLine(
                    color = outlineColor.copy(alpha = 0.18f),
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = 1f,
                    pathEffect = dashEffect,
                )
            }

            // Plot response curve
            if (enabled) {
                val path = Path()
                val points = 120
                val sigma = 0.28

                for (p in 0..points) {
                    val pct = p.toFloat() / points
                    val logF = minLogF + pct * logRange

                    var totalGainDb = 0.0
                    for (i in bandGains.indices) {
                        val gainDb = bandGains[i] / 50.0
                        val dist = logF - logFrequencies[i]
                        val influence = exp(-(dist * dist) / (2.0 * sigma * sigma))
                        totalGainDb += gainDb * influence
                    }

                    val normalizedGain = (totalGainDb / 15.0).coerceIn(-1.0, 1.0)
                    val y = centerY - (normalizedGain * centerY * 0.8f).toFloat()
                    val x = pct * width

                    if (p == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                // Filled gradient area
                val fillPath = Path().apply {
                    addPath(path)
                    lineTo(width, centerY)
                    lineTo(0f, centerY)
                    close()
                }
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(primaryColor.copy(alpha = 0.15f), Color.Transparent),
                        startY = 0f,
                        endY = height,
                    ),
                )

                // Glow line
                drawPath(
                    path = path,
                    color = primaryColor.copy(alpha = 0.25f),
                    style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round),
                )
                // Core line
                drawPath(
                    path = path,
                    color = primaryColor,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                )

                // Control nodes
                for (i in bandGains.indices) {
                    val pctX = (logFrequencies[i] - minLogF) / logRange
                    val x = (pctX * width).toFloat()
                    val y = getCumulativeY(i, width, height, centerY)
                    val isActive = activeNodeIndex == i

                    // Vertical guide
                    drawLine(
                        color = primaryColor.copy(alpha = if (isActive) 0.6f else 0.15f),
                        start = Offset(x, y),
                        end = Offset(x, centerY),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f),
                    )

                    // Outer glow
                    drawCircle(
                        color = primaryColor.copy(alpha = if (isActive) 0.4f else 0.18f),
                        radius = if (isActive) 16.dp.toPx() else 10.dp.toPx(),
                        center = Offset(x, y),
                    )
                    // White border ring
                    drawCircle(
                        color = Color.White,
                        radius = 6.dp.toPx(),
                        center = Offset(x, y),
                    )
                    // Core dot
                    drawCircle(
                        color = primaryColor,
                        radius = 4.5.dp.toPx(),
                        center = Offset(x, y),
                    )
                }
            }
        }

        // Frequency labels at bottom
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val shortLabels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
            shortLabels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SimpleBandControl(
    label: String,
    description: String,
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${if (value > 0) "+" else ""}${value.toInt()}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = -10f..10f,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            ),
        )
    }
}

@Composable
private fun SimpleEqMode(
    bandGains: FloatArray,
    enabled: Boolean,
    viewModel: AxionEqViewModel,
    isDirty: Boolean,
    onSaveClick: () -> Unit,
) {
    var bass by remember { mutableFloatStateOf(0f) }
    var mid by remember { mutableFloatStateOf(0f) }
    var treble by remember { mutableFloatStateOf(0f) }

    fun syncFromBands() {
        bass = bandGains[1] / 50f
        mid = (bandGains[4] + bandGains[5]) / 2f / 50f
        treble = bandGains[8] / 50f
    }

    fun applyTriangle(applyOnly: Boolean = false) {
        val bv = (bass * 50f).coerceIn(-600f, 600f)
        val mv = (mid * 50f).coerceIn(-600f, 600f)
        val tv = (treble * 50f).coerceIn(-600f, 600f)
        val newGains = FloatArray(10)

        // Smooth, organic transition across the spectrum
        newGains[0] = bv * 1.1f
        newGains[1] = bv * 1.0f
        newGains[2] = bv * 0.7f + mv * 0.3f
        newGains[3] = bv * 0.2f + mv * 0.8f
        newGains[4] = mv * 1.0f
        newGains[5] = mv * 1.0f
        newGains[6] = mv * 0.8f + tv * 0.2f
        newGains[7] = mv * 0.3f + tv * 0.7f
        newGains[8] = tv * 1.0f
        newGains[9] = tv * 1.15f

        viewModel.setBandsGains(newGains, fromUser = true, applyOnly = applyOnly)
    }

    LaunchedEffect(bandGains) { syncFromBands() }

    val presets = listOf(
        R.string.eq_preset_flat to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        R.string.eq_preset_acoustic to floatArrayOf(-50f, 50f, 120f, 50f, -50f, 100f, 150f, 220f, 180f, 100f),
        R.string.eq_preset_bass_boost to floatArrayOf(500f, 400f, 250f, 100f, 0f, -50f, 0f, 100f, 200f, 300f),
        R.string.eq_preset_pop to floatArrayOf(-150f, 0f, 100f, 180f, 250f, 220f, 150f, 80f, -50f, -120f),
        R.string.eq_preset_rock to floatArrayOf(300f, 220f, 150f, 50f, -100f, 120f, 200f, 250f, 320f, 380f),
        R.string.eq_preset_jazz to floatArrayOf(150f, 100f, 60f, 140f, 200f, 180f, 120f, 180f, 220f, 200f),
        R.string.eq_preset_electronic to floatArrayOf(350f, 280f, 120f, -50f, -150f, 50f, 180f, 300f, 400f, 500f),
        R.string.eq_preset_voice to floatArrayOf(-250f, -150f, 0f, 200f, 400f, 380f, 200f, 120f, 0f, -120f),
        R.string.eq_preset_spatial to floatArrayOf(200f, 100f, 0f, -150f, -200f, -100f, 50f, 150f, 300f, 400f),
        R.string.eq_preset_clarity to floatArrayOf(100f, 50f, -80f, -150f, -50f, 150f, 250f, 200f, 150f, 100f),
    )

    val customProfiles by viewModel.customProfiles.collectAsState()
    var showManageDialog by remember { mutableStateOf(false) }

    if (showManageDialog) {
        ManagePresetsDialog(
            customProfiles = customProfiles,
            onDismiss = { showManageDialog = false },
            onDeleteSelected = { ids -> viewModel.deleteProfiles(ids); showManageDialog = false },
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SimpleBandControl(
            label = stringResource(R.string.eq_label_bass),
            description = stringResource(R.string.eq_desc_bass),
            value = bass,
            enabled = enabled,
            onValueChange = { bass = it; applyTriangle(applyOnly = true) },
            onValueChangeFinished = { viewModel.commitCurrentGains() },
        )

        SimpleBandControl(
            label = stringResource(R.string.eq_label_mid),
            description = stringResource(R.string.eq_desc_mid),
            value = mid,
            enabled = enabled,
            onValueChange = { mid = it; applyTriangle(applyOnly = true) },
            onValueChangeFinished = { viewModel.commitCurrentGains() },
        )

        SimpleBandControl(
            label = stringResource(R.string.eq_label_treble),
            description = stringResource(R.string.eq_desc_treble),
            value = treble,
            enabled = enabled,
            onValueChange = { treble = it; applyTriangle(applyOnly = true) },
            onValueChangeFinished = { viewModel.commitCurrentGains() },
        )

        // Save button when dirty
        AnimatedVisibility(
            visible = isDirty && enabled,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            OutlinedButton(
                onClick = onSaveClick,
                modifier = Modifier.padding(bottom = 8.dp),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = stringResource(R.string.eq_save), style = MaterialTheme.typography.labelLarge)
            }
        }

        // Custom profiles
        if (customProfiles.isNotEmpty()) {
            PresetSection(
                title = stringResource(R.string.eq_label_custom),
                presetNames = customProfiles.map { it.name },
                enabled = enabled,
                viewModel = viewModel,
                bandGains = bandGains,
                onEditClick = { showManageDialog = true },
            )
        }

        // Built-in presets
        presets.chunked(4).forEach { chunk ->
            PresetSection(
                title = if (presets.first() in chunk) stringResource(R.string.eq_label_presets) else "",
                presets = chunk.map { it.first },
                presetValues = chunk.map { it.second },
                enabled = enabled,
                viewModel = viewModel,
                bandGains = bandGains,
            )
        }
    }
}

@Composable
private fun AdvancedEqMode(
    bandGains: FloatArray,
    enabled: Boolean,
    onBandChange: (Int, Float) -> Unit,
    onBandChangeFinished: () -> Unit,
    onReset: () -> Unit,
) {
    val bandLabels = arrayOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (band in 0..9) {
                EqBandSlider(
                    label = bandLabels[band],
                    value = bandGains[band],
                    enabled = enabled,
                    onValueChange = { onBandChange(band, it) },
                    onValueChangeFinished = onBandChangeFinished,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            OutlinedButton(onClick = onReset) {
                Icon(Icons.Rounded.Replay, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.eq_reset))
            }
        }
    }
}

@Composable
private fun EqBandSlider(
    label: String,
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(
        modifier = Modifier.width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "%.1f".format(value / 10f),
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier.height(200.dp),
            contentAlignment = Alignment.Center,
        ) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = -600f..600f,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                ),
                modifier = Modifier
                    .width(180.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(
                            constraints.copy(
                                minWidth = constraints.minHeight,
                                maxWidth = constraints.maxHeight,
                            ),
                        )
                        layout(placeable.height, placeable.width) {
                            placeable.place(
                                -placeable.width / 2 + placeable.height / 2,
                                placeable.width / 2 - placeable.height / 2,
                            )
                        }
                    }
                    .graphicsLayer { rotationZ = -90f },
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetSection(
    title: String,
    presets: List<Int> = emptyList(),
    presetValues: List<FloatArray> = emptyList(),
    presetNames: List<String>? = null,
    enabled: Boolean,
    viewModel: AxionEqViewModel,
    bandGains: FloatArray,
    onEditClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
                if (onEditClick != null && enabled) {
                    IconButton(
                        onClick = onEditClick,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            verticalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            if (presetNames != null) {
                // Custom profiles
                presetNames.forEachIndexed { index, name ->
                    val isSelected = remember(bandGains) { false } // Custom profiles not checked by band matching
                    ToggleButton(
                        checked = isSelected,
                        onCheckedChange = { },
                        enabled = enabled,
                        modifier = Modifier.semantics { role = Role.RadioButton },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(text = name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            } else {
                // Built-in presets
                presets.forEachIndexed { index, nameRes ->
                    val bands = presetValues.getOrNull(index) ?: return@forEachIndexed
                    val isSelected = remember(bandGains, bands) {
                        bandGains.size == bands.size && bandGains.zip(bands).all { (g, b) -> abs(g - b) < 10f }
                    }
                    ToggleButton(
                        checked = isSelected,
                        onCheckedChange = { if (enabled) viewModel.setBandsGains(bands) },
                        enabled = enabled,
                        shapes = when {
                            index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            index == presets.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                    ) {
                        Text(text = stringResource(nameRes), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun SavePresetDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.eq_save_dialog_title),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.eq_save_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            OutlinedButton(
                onClick = { if (name.isNotBlank()) onSave(name) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.eq_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun EqEffectSection(
    title: String,
    description: String,
    enabledKey: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
    strengthKey: androidx.datastore.preferences.core.Preferences.Key<Int>,
) {
    val (isEnabled, onEnabledChange) = rememberPreference(enabledKey, defaultValue = false)
    val (strength, onStrengthChange) = rememberPreference(strengthKey, defaultValue = 0)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
            modifier = Modifier.padding(vertical = 8.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isEnabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onEnabledChange,
            )
        }

        if (isEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.eq_effect_strength),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = strength.toFloat(),
                    onValueChange = { onStrengthChange(it.toInt()) },
                    valueRange = 0f..1000f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    ),
                )
                Text(
                    text = "${(strength / 10)}%",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(40.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun ManagePresetsDialog(
    customProfiles: List<SavedEQProfile>,
    onDismiss: () -> Unit,
    onDeleteSelected: (List<String>) -> Unit,
) {
    val selectedIds = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.eq_manage_presets), fontWeight = FontWeight.Bold) },
        text = {
            if (customProfiles.isEmpty()) {
                Text(
                    stringResource(R.string.eq_no_custom_presets),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(customProfiles) { profile ->
                        val isSelected = profile.id in selectedIds
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    if (isSelected) selectedIds.remove(profile.id)
                                    else selectedIds.add(profile.id)
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    if (it) selectedIds.add(profile.id)
                                    else selectedIds.remove(profile.id)
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (selectedIds.isNotEmpty()) {
                OutlinedButton(
                    onClick = { onDeleteSelected(selectedIds.toList()) },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.eq_delete_selected))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
