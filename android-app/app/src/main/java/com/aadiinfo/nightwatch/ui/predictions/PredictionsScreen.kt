package com.aadiinfo.nightwatch.ui.predictions

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.GlucoseReading
import com.aadiinfo.nightwatch.domain.model.Thresholds
import com.aadiinfo.nightwatch.ui.vmFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private val PREDICTOR_COLORS = listOf(
    Color(0xFF1E88E5), // blue
    Color(0xFF8E24AA), // purple
    Color(0xFF00897B), // teal
    Color(0xFFF4511E) // deep orange
)

@Composable
fun PredictionsScreen(patientRepository: PatientRepository, patientId: String, uid: String) {
    val viewModel: PredictionsViewModel =
        viewModel(factory = vmFactory { PredictionsViewModel(patientRepository, patientId, uid) })
    val state by viewModel.uiState.collectAsState()

    // LazyColumn rather than a plain Column+verticalScroll: it's the same
    // scrolling container HistoryScreen already uses for its list, and
    // keeping scrolling item-based (header/chart as one item, each
    // predictor card as its own) avoids relying on a single tall Column to
    // report the right scrollable height as content is added below the fold.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        item {
            Text("Predictions", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Beta - on-device forecasting experiments, recomputed automatically whenever " +
                    "a new reading arrives (about every 5 minutes, matching the backend's poll " +
                    "cycle). These don't drive alerts; the backend's own predictor does that " +
                    "independently.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }

        when {
            state.loading -> item { CircularProgressIndicator() }
            state.recentReadings.size < 2 -> item {
                Text(
                    "Not enough recent readings yet to run predictions.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            else -> {
                item {
                    PredictionChart(state.recentReadings, state.outputs, state.thresholds)
                    Spacer(Modifier.height(8.dp))
                }
                items(state.outputs) { output ->
                    PredictorCard(output)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PredictionChart(
    readings: List<GlucoseReading>,
    outputs: List<PredictorOutput>,
    thresholds: Thresholds
) {
    val actualColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val nowColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    val lastReading = readings.last()
    val horizonMs = 30 * 60 * 1000L
    val oldestMs = readings.first().dateMs
    val newestMs = lastReading.dateMs + horizonMs
    val spanMs = (newestMs - oldestMs).coerceAtLeast(1L).toFloat()

    val minValue = 40f
    val maxReadingSgv = readings.maxOf { it.sgv }.toFloat()
    val maxProjected = outputs.mapNotNull { it.result.projectedValue }.maxOfOrNull { it.toFloat() } ?: maxReadingSgv
    val maxValue = max(300f, max(maxReadingSgv, maxProjected) + 20f)

    Column {
        Row {
            Column(
                modifier = Modifier
                    .height(160.dp)
                    .width(36.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${maxValue.roundToInt()}", style = MaterialTheme.typography.labelSmall)
                Text("${((maxValue + minValue) / 2).roundToInt()}", style = MaterialTheme.typography.labelSmall)
                Text("${minValue.roundToInt()}", style = MaterialTheme.typography.labelSmall)
            }

            Box(modifier = Modifier.weight(1f)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                ) {
                    fun xFor(ms: Long) = (ms - oldestMs) / spanMs * size.width
                    fun yFor(value: Float) =
                        size.height - ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f) * size.height

                    val thresholdY = yFor(thresholds.lowMgdl.toFloat())
                    drawLine(gridColor, Offset(0f, thresholdY), Offset(size.width, thresholdY), strokeWidth = 1.dp.toPx())

                    val nowX = xFor(lastReading.dateMs)
                    drawLine(nowColor, Offset(nowX, 0f), Offset(nowX, size.height), strokeWidth = 1.dp.toPx())

                    val path = Path()
                    readings.forEachIndexed { index, reading ->
                        val point = Offset(xFor(reading.dateMs), yFor(reading.sgv.toFloat()))
                        if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                    }
                    drawPath(path, color = actualColor, style = Stroke(width = 3.dp.toPx()))
                    readings.forEach { reading ->
                        drawCircle(
                            color = actualColor,
                            radius = 2.5.dp.toPx(),
                            center = Offset(xFor(reading.dateMs), yFor(reading.sgv.toFloat()))
                        )
                    }

                    val start = Offset(xFor(lastReading.dateMs), yFor(lastReading.sgv.toFloat()))
                    outputs.forEachIndexed { index, output ->
                        val projectedValue = output.result.projectedValue ?: return@forEachIndexed
                        val color = PREDICTOR_COLORS[index % PREDICTOR_COLORS.size]
                        val end = Offset(
                            xFor(lastReading.dateMs + horizonMs),
                            yFor(projectedValue.toFloat())
                        )
                        drawLine(
                            color,
                            start,
                            end,
                            strokeWidth = 3.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                        )
                        drawCircle(color, radius = 4.dp.toPx(), center = end)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(timeFormat.format(Date(oldestMs)), style = MaterialTheme.typography.labelSmall)
            Text("now", style = MaterialTheme.typography.labelSmall)
            Text(timeFormat.format(Date(newestMs)), style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(8.dp))
        // FlowRow rather than a plain Row: with 4 predictors now (after
        // Quadratic moved out on its own), longer names like "Direction-aware
        // (windowed)" no longer reliably fit on one line - this wraps to a
        // second line instead of overflowing/clipping past the screen edge.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            outputs.forEachIndexed { index, output ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(PREDICTOR_COLORS[index % PREDICTOR_COLORS.size], CircleShape)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(output.predictorName, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun PredictorCard(output: PredictorOutput) {
    // A dialog rather than inline text: the earlier press-and-hold-to-reveal
    // design broke down for cards near the bottom of the list, since the
    // description could render past the visible viewport with no way to
    // scroll to it (releasing the hold to scroll immediately hid it again).
    // A dialog renders as its own overlay independent of this list's scroll
    // position, so it's never cut off regardless of which card triggered it.
    var showDescription by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    output.predictorName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { showDescription = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "About ${output.predictorName}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val projectedValue = output.result.projectedValue
            if (projectedValue != null) {
                Text(
                    "Projected in 30 min: ${projectedValue.roundToInt()} mg/dL",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(2.dp))
                val minutesToThreshold = output.result.minutesToThreshold
                Text(
                    if (minutesToThreshold != null) {
                        "Projected to cross the threshold in ~${minutesToThreshold.roundToInt()} min"
                    } else {
                        "No threshold crossing predicted within 30 min"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            output.result.note?.let {
                if (projectedValue != null) Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showDescription) {
        AlertDialog(
            onDismissRequest = { showDescription = false },
            confirmButton = {
                TextButton(onClick = { showDescription = false }) { Text("Close") }
            },
            title = { Text(output.predictorName) },
            text = { Text(output.description) }
        )
    }
}
