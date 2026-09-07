package com.aadiinfo.nightwatch.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.GlucoseReading
import com.aadiinfo.nightwatch.domain.model.Thresholds
import com.aadiinfo.nightwatch.domain.model.TreatmentEvent
import com.aadiinfo.nightwatch.domain.model.TrendDirection
import com.aadiinfo.nightwatch.ui.theme.AlertColors
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
fun DashboardScreen(patientRepository: PatientRepository, patientId: String, uid: String) {
    val viewModel: DashboardViewModel =
        viewModel(factory = vmFactory { DashboardViewModel(patientRepository, patientId, uid) })
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
                "No readings yet.",
                style = MaterialTheme.typography.bodyMedium
            )
            else -> {
                ReadingCard(state.reading!!, state.thresholds)
                Spacer(Modifier.height(24.dp))
                Text("Last 24 hours", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                GlucoseTrendChart(state.trend, state.thresholds, state.treatments)
                state.percentTimeAtHighRate?.let { percent ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Moving fast (≥ 3 mg/dL/min): ${percent.roundToInt()}% of last 24h",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun GlucoseTrendChart(readings: List<GlucoseReading>, thresholds: Thresholds, treatments: List<TreatmentEvent>) {
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
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val selectionColor = MaterialTheme.colorScheme.onSurface

    // Captured once (not re-read every recomposition) so the graph's right
    // edge reflects when the dashboard was opened, not just the last actual
    // reading - otherwise a long gap since the last reading quietly shrinks
    // the axis to end at that old time instead of visibly leaving blank
    // space up to now, which reads as "the graph is current" when it isn't.
    val nowMs = remember { System.currentTimeMillis() }
    val oldestMs = readings.first().dateMs
    val newestMs = max(readings.last().dateMs, nowMs)
    val fullSpanMs = (newestMs - oldestMs).coerceAtLeast(1L).toFloat()
    // Includes the date, not just the time - a 24h window almost always
    // spans two calendar days, and a bare time on the left edge (e.g.
    // "6:19 PM") reads as if it's from today, making the graph look like it
    // runs backwards once you realize it's actually yesterday's.
    val timeFormat = remember { SimpleDateFormat("M/d, h:mm a", Locale.getDefault()) }

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
    // rounding at the window edges. Includes one reading just outside each
    // edge of the window (if one exists), so the segment connecting into/
    // out of the window is computed correctly and simply clipped at the
    // canvas edge below - a strict inside-only filter left the nearest
    // in-window point as a dead end mid-chart whenever the real next
    // reading sat outside the zoomed range, which is the common case once
    // the window is narrower than the ~5-minute gap between readings.
    val visibleReadings = if (isZoomed) {
        var startIdx = readings.indexOfFirst { it.dateMs >= windowStartMs }
        var endIdx = readings.indexOfLast { it.dateMs <= windowEndMs }
        if (startIdx == -1) startIdx = readings.lastIndex
        if (endIdx == -1) endIdx = 0
        startIdx = (startIdx - 1).coerceIn(0, readings.lastIndex)
        endIdx = (endIdx + 1).coerceIn(0, readings.lastIndex)
        if (startIdx <= endIdx) readings.subList(startIdx, endIdx + 1) else emptyList()
    } else {
        readings
    }
    val minValue = if (isZoomed && visibleReadings.isNotEmpty()) {
        min(40f, (visibleReadings.minOf { it.sgv } - 20).toFloat())
    } else {
        40f
    }
    val maxValue = max(300f, ((visibleReadings.maxOfOrNull { it.sgv } ?: 300) + 20).toFloat())

    // Insulin markers in a fixed strip near the top, carbs near the bottom -
    // deliberately not mapped to glucose value like the reading dots are,
    // so they read as a separate timeline of events rather than competing
    // with the glucose line for vertical position.
    val visibleBoluses = treatments.filter { t ->
        (t.insulin ?: 0.0) > 0.0 && t.mills in windowStartMs..windowEndMs
    }
    val visibleCarbs = treatments.filter { t ->
        (t.carbs ?: 0.0) > 0.0 && t.mills in windowStartMs..windowEndMs
    }
    val insulinMarkerColor = Color(0xFF7B1FA2) // purple
    val carbMarkerColor = Color(0xFFFFA000) // amber

    fun nearestIndexFor(x: Float, width: Float): Int {
        val targetMs = windowStartMs + (x / width).coerceIn(0f, 1f) * effectiveSpan
        return readings.indices.minByOrNull { abs(readings[it].dateMs - targetMs) } ?: 0
    }

    Column {
        // Explicit height on the row itself (not just its children) pins the
        // whole graph area to a hard 160dp regardless of what either child
        // measures to - the fix for zooming shifting the labels/rows below
        // it downward, which happened because nothing forced the row's own
        // cross-axis size to stay fixed independent of its children.
        Row(modifier = Modifier.height(160.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(36.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${maxValue.roundToInt()}", style = MaterialTheme.typography.labelSmall)
                Text("${((maxValue + minValue) / 2).roundToInt()}", style = MaterialTheme.typography.labelSmall)
                Text("${minValue.roundToInt()}", style = MaterialTheme.typography.labelSmall)
            }

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
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
                        // Plain reference gridlines for scale - one per Y-axis
                        // label (max/mid/min) and one per time label below
                        // (start/mid/end) - distinct from the colored low/high
                        // threshold lines, which mark alert boundaries, not
                        // just scale.
                        listOf(maxValue, (maxValue + minValue) / 2f, minValue).forEach { value ->
                            val y = yFor(value.roundToInt())
                            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        }
                        listOf(windowStartMs, (windowStartMs + windowEndMs) / 2, windowEndMs).forEach { ms ->
                            val x = xFor(ms)
                            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                        }

                        val lowY = yFor(thresholds.lowMgdl)
                        drawLine(
                            AlertColors.Low.copy(alpha = 0.4f),
                            Offset(0f, lowY),
                            Offset(size.width, lowY),
                            strokeWidth = 1.dp.toPx()
                        )
                        val highY = yFor(thresholds.highMgdl)
                        drawLine(
                            AlertColors.High.copy(alpha = 0.4f),
                            Offset(0f, highY),
                            Offset(size.width, highY),
                            strokeWidth = 1.dp.toPx()
                        )

                        val markerRadius = 2.5.dp.toPx()
                        val markerInset = 8.dp.toPx()
                        visibleBoluses.forEach { bolus ->
                            drawCircle(
                                color = insulinMarkerColor,
                                radius = markerRadius,
                                center = Offset(xFor(bolus.mills), markerInset)
                            )
                        }
                        visibleCarbs.forEach { carb ->
                            drawCircle(
                                color = carbMarkerColor,
                                radius = markerRadius,
                                center = Offset(xFor(carb.mills), size.height - markerInset)
                            )
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
                .padding(start = 40.dp)
        ) {
            // Single-line + ellipsis, not wrapping - these three now carry a
            // date too (see timeFormat above), and a wrapped second line here
            // would push the row below it down, the same class of layout
            // shift already fixed for the graph area itself.
            Text(
                timeFormat.format(Date(windowStartMs)),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                timeFormat.format(Date((windowStartMs + windowEndMs) / 2)),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Text(
                timeFormat.format(Date(windowEndMs)),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }

        if (visibleBoluses.isNotEmpty() || visibleCarbs.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (visibleBoluses.isNotEmpty()) {
                    MarkerLegendItem(insulinMarkerColor, "Insulin")
                }
                if (visibleCarbs.isNotEmpty()) {
                    MarkerLegendItem(carbMarkerColor, "Carbs")
                }
            }
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
            // Always present (not just when zoomed) so this row's height never
            // changes based on zoom state - only visible/clickable when
            // zoomed, via alpha rather than removing it from composition.
            Text(
                "Reset zoom",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .alpha(if (isZoomed) 1f else 0f)
                    .clickable(enabled = isZoomed) {
                        windowStartOffsetMs = 0f
                        windowSpanMs = fullSpanMs
                    }
            )
        }
    }
}

@Composable
private fun ReadingCard(reading: GlucoseReading, thresholds: Thresholds) {
    val ageMinutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - reading.dateMs)
    val isStale = ageMinutes >= thresholds.staleMinutes

    if (isStale) {
        // Never shown as if it's current once stale - a disconnected/missing
        // CGM sensor should read as "no reading available", not silently
        // keep displaying the last real number as though it's still live.
        Text(
            "---",
            fontSize = 48.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Sensor may be disconnected.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        val color = glucoseColor(reading.sgv, thresholds)
        val trend = TrendDirection.fromNightscout(reading.direction)
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
    }

    // Above "Updated" and visually heavier than plain body text - prominent
    // enough to read at a glance, but clearly secondary to the glucose
    // number/arrow above (72sp/40sp) rather than competing with it.
    Spacer(Modifier.height(16.dp))
    val iob = reading.iob
    if (iob != null) {
        Text(
            "IOB: ${"%.2f".format(iob)}u",
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (iob >= thresholds.iobThreshold) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface
        )
        if (reading.iobUnreliable) {
            Text(
                "May be unreliable data from Gluroo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    } else {
        // Null (not zero) means no source has reported IOB at all - either
        // Gluroo's own feed doesn't carry it and no phone is reporting the
        // pump's own notification, or a phone was reporting but the pump
        // disconnected/expired and the report went stale - see
        // EXTERNAL_IOB_FRESHNESS_MS. Surfacing this explicitly instead of
        // just omitting the row is the point: a caretaker seeing nothing
        // here can't tell "not tracked" from "screen forgot to load."
        Text(
            "IOB not available",
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(Modifier.height(16.dp))
    Text(
        "Updated ${formatAgo(ageMinutes)}",
        style = MaterialTheme.typography.bodyMedium,
        color = if (ageMinutes >= thresholds.staleMinutes) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(24.dp))
    Text(
        "Range: ${thresholds.lowMgdl}-${thresholds.highMgdl} mg/dL " +
            "(urgent below ${thresholds.urgentLowMgdl}, above ${thresholds.urgentHighMgdl})",
        style = MaterialTheme.typography.bodySmall
    )
}

private fun glucoseColor(sgv: Int, thresholds: Thresholds): Color = AlertColors.forGlucoseZone(sgv, thresholds)

@Composable
private fun MarkerLegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun formatAgo(ageMinutes: Long): String = when {
    ageMinutes <= 1 -> "just now"
    ageMinutes < 60 -> "$ageMinutes min ago"
    ageMinutes < 24 * 60 -> {
        val hours = ageMinutes / 60
        val minutes = ageMinutes % 60
        if (minutes == 0L) "$hours hr ago" else "$hours hr $minutes min ago"
    }
    else -> {
        val days = ageMinutes / (24 * 60)
        val hours = (ageMinutes % (24 * 60)) / 60
        if (hours == 0L) "$days d ago" else "$days d $hours hr ago"
    }
}
