package com.aadiinfo.nightwatch.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aadiinfo.nightwatch.data.repository.PatientRepository
import com.aadiinfo.nightwatch.domain.model.AlertEvent
import com.aadiinfo.nightwatch.domain.model.Severity
import com.aadiinfo.nightwatch.ui.vmFactory
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(patientRepository: PatientRepository, patientId: String) {
    val viewModel: HistoryViewModel =
        viewModel(factory = vmFactory { HistoryViewModel(patientRepository, patientId) })
    val alerts by viewModel.alerts.collectAsState()

    if (alerts.isEmpty()) {
        Text(
            "No alerts yet.",
            modifier = Modifier.fillMaxSize().padding(24.dp)
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(alerts, key = { it.id }) { alert ->
            AlertRow(alert, onAcknowledge = { viewModel.acknowledge(alert.id) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun AlertRow(alert: AlertEvent, onAcknowledge: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                alert.type.name.replace('_', ' '),
                color = severityColor(alert.severity),
                style = MaterialTheme.typography.titleSmall
            )
            Text(alert.message, style = MaterialTheme.typography.bodyMedium)
            Text(
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(alert.timestamp)),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (!alert.acknowledged) {
            TextButton(onClick = onAcknowledge) { Text("Ack") }
        }
    }
}

private fun severityColor(severity: Severity): Color = when (severity) {
    Severity.CRITICAL -> Color(0xFFD32F2F)
    Severity.WARNING -> Color(0xFFF9A825)
    Severity.INFO -> Color(0xFF616161)
}
