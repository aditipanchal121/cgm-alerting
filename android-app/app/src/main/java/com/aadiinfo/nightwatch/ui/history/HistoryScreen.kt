package com.aadiinfo.nightwatch.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.AlertEvent
import com.aadiinfo.nightwatch.ui.theme.AlertColors
import com.aadiinfo.nightwatch.ui.vmFactory
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Read-only alert log - no acknowledge step, since nothing in the app ever
 * did anything differently based on it being toggled, and it's implicitly
 * bounded to the last 3 days by cleanupOldAlerts (see backend/README.md),
 * so it doubles as a running log rather than something that needs to be
 * triaged/cleared. Grouped by day since 3 days' worth in one flat list
 * makes it hard to tell where one day ends and the next begins. */
@Composable
fun HistoryScreen(patientRepository: PatientRepository, patientId: String, uid: String) {
    val viewModel: HistoryViewModel =
        viewModel(factory = vmFactory { HistoryViewModel(patientRepository, patientId, uid) })
    val alerts by viewModel.alerts.collectAsState()

    if (alerts.isEmpty()) {
        Text(
            "No alerts yet - based on your own alert thresholds (Settings tab).",
            modifier = Modifier.fillMaxSize().padding(24.dp)
        )
        return
    }

    // Grouping key is a plain locale-stable date string (not the display
    // format below) so two alerts on the same calendar day always group
    // together regardless of locale.
    val dayKeyFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val dayLabelFormat = remember { DateFormat.getDateInstance(DateFormat.FULL) }
    val timeFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val groupedAlerts = remember(alerts) {
        alerts.groupBy { dayKeyFormat.format(Date(it.timestamp)) }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        groupedAlerts.forEach { (dayKey, dayAlerts) ->
            item(key = dayKey) {
                Text(
                    dayLabelFormat.format(Date(dayAlerts.first().timestamp)),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            }
            items(dayAlerts, key = { it.id }) { alert ->
                AlertRow(alert, timeFormat)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun AlertRow(alert: AlertEvent, timeFormat: DateFormat) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            alert.type.name.replace('_', ' '),
            color = AlertColors.forAlertType(alert.type, alert.severity),
            style = MaterialTheme.typography.titleSmall
        )
        Text(alert.message, style = MaterialTheme.typography.bodyMedium)
        Text(timeFormat.format(Date(alert.timestamp)), style = MaterialTheme.typography.bodySmall)
    }
}
