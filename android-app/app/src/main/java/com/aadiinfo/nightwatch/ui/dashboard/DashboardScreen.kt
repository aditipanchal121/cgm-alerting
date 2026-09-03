package com.aadiinfo.nightwatch.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.GlucoseReading
import com.aadiinfo.nightwatch.domain.model.Thresholds
import com.aadiinfo.nightwatch.domain.model.TrendDirection
import com.aadiinfo.nightwatch.ui.vmFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(patientRepository: PatientRepository, patientId: String) {
    val viewModel: DashboardViewModel =
        viewModel(factory = vmFactory { DashboardViewModel(patientRepository, patientId) })
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            state.patient?.displayName ?: "Vigil",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(24.dp))

        when {
            state.loading -> CircularProgressIndicator()
            state.reading == null -> Text(
                "No readings yet - the backend polls Gluroo every few minutes " +
                    "once credentials are saved.",
                style = MaterialTheme.typography.bodyMedium
            )
            else -> {
                ReadingCard(state.reading!!, state.thresholds)
                Spacer(Modifier.height(24.dp))
                Text("Last 24 hours", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                GlucoseTrendChart(state.trend, state.thresholds)
            }
        }
    }
}

@Composable
private fun GlucoseTrendChart(readings: List<GlucoseReading>, thresholds: Thresholds) {
    if (readings.size < 2) {
        Text(
            "Not enough readings yet to draw a trend graph.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    var selectedIndex by remember(readings) { mutableStateOf<Int?>(null) }

    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val selectionColor = MaterialTheme.colorScheme.onSurface

    val oldestMs = readings.first().dateMs
    val newestMs = readings.last().dateMs
    val fullSpanMs = (newestMs - oldestMs).coerceAtLeast(1L).toFloat()
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    // Pinch-to-zoom window, kept independent of `readings`' identity so a
    // poll cycle refreshing the data doesn't snap an inspected zoom back to
    // the full range. Stored as an offset+span relative to oldestMs, not
    // absolute epoch millis - epoch millis (~1.7e12) would lose several
    // seconds of precision once promoted to Float during the pinch math
    // below, where these deltas (at most a day, ~8.6e7) stay accurate to a
    // few milliseconds.
    var windowStartOffsetMs by remember { mutableStateOf(0f) }
    var windowSpanMs by remember { mutableStateOf(fullSpanMs) }
    val minSpanMs = 10 * 60 * 1000f

    val effectiveSpan = windowSpanMs.coerceIn(minSpanMs, fullSpanMs)
    val effectiveStartOffset = windowStartOffsetMs.coerceIn(0f, (fullSpanMs - effectiveSpan).coerceAtLeast(0f))
    val windowStartMs = oldestMs + effectiveStartOffset.toLong()
    val windowEndMs = windowStartMs + effectiveSpan.toLong()
    val isZoomed = effectiveSpan < fullSpanMs - 1f

    // Filtering only kicks in once zoomed - left as the untouched full list
    // otherwise, so the default view can never drop a reading to float
    // rounding at the window edges.
    val visibleReadings = if (isZoomed) readings.filter { it.dateMs in windowStartMs..windowEndMs } else readings
    val minValue = if (isZoomed && visibleReadings.isNotEmpty()) {
        min(40f, (visibleReadings.minOf { it.sgv } - 20).toFloat())
    } else {
        40f
    }
    val maxValue = max(300f, ((visibleReadings.maxOfOrNull { it.sgv } ?: 300) + 20).toFloat())

    fun nearestIndexFor(x: Float, width: Float): Int {
        val targetMs = windowStartMs + (x / width).coerceIn(0f, 1f) * effectiveSpan
        return readings.indices.minByOrNull { abs(readings[it].dateMs - targetMs) } ?: 0
    }

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
                        .pointerInput(readings) {
                            awaitEachGesture {
                                awaitFirstDown()
                                // Two fingers pinch/pan to zoom; a lone finger inspects
                                // a reading (unchanged from before). Tracked per-gesture
                                // so switching finger count mid-gesture re-anchors cleanly.
                                var lastSpanPx: Float? = null
                                var lastCenterPx: Float? = null
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break

                                    if (pressed.size >= 2) {
                                        selectedIndex = null
                                        val width = size.width.toFloat()
                                        val p1 = pressed[0].position.x
                                        val p2 = pressed[1].position.x
                                        val spanPx = abs(p1 - p2).coerceAtLeast(1f)
                                        val centerPx = (p1 + p2) / 2f

                                        if (lastSpanPx != null && lastCenterPx != null && width > 0f) {
                                            val scale = lastSpanPx!! / spanPx
                                            val anchorMs = windowStartOffsetMs + (centerPx / width) * windowSpanMs
                                            val newSpan = (windowSpanMs * scale).coerceIn(minSpanMs, fullSpanMs)
                                            var newStart = anchorMs - (centerPx / width) * newSpan
                                            newStart -= ((centerPx - lastCenterPx!!) / width) * newSpan
                                            windowSpanMs = newSpan
                                            windowStartOffsetMs = newStart.coerceIn(0f, (fullSpanMs - newSpan).coerceAtLeast(0f))
                                        }
                                        lastSpanPx = spanPx
                                        lastCenterPx = centerPx
                                        pressed.forEach { it.consume() }
                                    } else {
                                        lastSpanPx = null
                                        lastCenterPx = null
                                        val change = pressed[0]
                                        selectedIndex = nearestIndexFor(change.position.x, size.width.toFloat())
                                        change.consume()
                                    }
                                }
                                selectedIndex = null
                            }
                        }
                ) {
                    fun xFor(dateMs: Long) = (dateMs - windowStartMs) / effectiveSpan * size.width
                    fun yFor(sgv: Int) =
                        size.height - ((sgv - minValue) / (maxValue - minValue)).coerceIn(0f, 1f) * size.height

                    clipRect {
                        listOf(thresholds.lowMgdl, thresholds.highMgdl).forEach { threshold ->
                            val y = yFor(threshold)
                            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        }

                        if (visibleReadings.size >= 2) {
                            val path = Path()
                            visibleReadings.forEachIndexed { index, reading ->
                                val point = Offset(xFor(reading.dateMs), yFor(reading.sgv))
                                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                            }
                            drawPath(path, color = lineColor, style = Stroke(width = 3.dp.toPx()))
                        }

                        visibleReadings.forEach { reading ->
                            drawCircle(
                                color = glucoseColor(reading.sgv, thresholds),
                                radius = 3.dp.toPx(),
                                center = Offset(xFor(reading.dateMs), yFor(reading.sgv))
                            )
                        }

                        selectedIndex?.let { index ->
                            val reading = readings[index]
                            val x = xFor(reading.dateMs)
                            drawLine(
                                selectionColor,
                                Offset(x, 0f),
                                Offset(x, size.height),
                                strokeWidth = 1.dp.toPx()
                            )
                            drawCircle(
                                selectionColor,
                                radius = 6.dp.toPx(),
                                center = Offset(x, yFor(reading.sgv)),
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }

                selectedIndex?.let { index ->
                    val reading = readings[index]
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            "${reading.sgv} mg/dL · ${timeFormat.format(Date(reading.dateMs))}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium
                        )
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
            Text(timeFormat.format(Date(windowStartMs)), style = MaterialTheme.typography.labelSmall)
            Text(timeFormat.format(Date((windowStartMs + windowEndMs) / 2)), style = MaterialTheme.typography.labelSmall)
            Text(timeFormat.format(Date(windowEndMs)), style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Drag to inspect a reading. Pinch with two fingers to zoom.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isZoomed) {
                Text(
                    "Reset zoom",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        windowStartOffsetMs = 0f
                        windowSpanMs = fullSpanMs
                    }
                )
            }
        }
    }
}

@Composable
private fun ReadingCard(reading: GlucoseReading, thresholds: Thresholds) {
    val color = glucoseColor(reading.sgv, thresholds)
    val trend = TrendDirection.fromNightscout(reading.direction)
    val ageMinutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - reading.dateMs)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${reading.sgv}",
            fontSize = 72.sp,
            color = color
        )
        Text(
            " ${trend.arrow}",
            fontSize = 40.sp,
            color = color
        )
    }
    Text("mg/dL", style = MaterialTheme.typography.bodyMedium)

    Spacer(Modifier.height(16.dp))
    Text(
        if (ageMinutes <= 1) "Updated just now" else "Updated $ageMinutes min ago",
        style = MaterialTheme.typography.bodyMedium,
        color = if (ageMinutes >= thresholds.staleMinutes) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    )

    reading.iob?.let { iob ->
        Spacer(Modifier.height(8.dp))
        Text(
            "IOB: ${"%.2f".format(iob)}u",
            style = MaterialTheme.typography.bodyMedium,
            color = if (iob >= thresholds.iobThreshold) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (reading.iobUnreliable) {
            Text(
                "This dropped abruptly from a much higher value - may be unreliable data from Gluroo rather than a true zero.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    Text(
        "Range: ${thresholds.lowMgdl}-${thresholds.highMgdl} mg/dL " +
            "(urgent below ${thresholds.urgentLowMgdl}, above ${thresholds.urgentHighMgdl})",
        style = MaterialTheme.typography.bodySmall
    )
}

private fun glucoseColor(sgv: Int, thresholds: Thresholds): Color = when {
    sgv <= thresholds.urgentLowMgdl || sgv >= thresholds.urgentHighMgdl -> Color(0xFFD32F2F)
    sgv <= thresholds.lowMgdl || sgv >= thresholds.highMgdl -> Color(0xFFF9A825)
    else -> Color(0xFF2E7D32)
}
