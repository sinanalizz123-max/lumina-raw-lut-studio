package com.lumina.studio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumina.studio.core.data.local.DatabaseFailure
import com.lumina.studio.core.data.local.DatabaseGate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseRecoveryScreen(
    onRetry: () -> Unit,
    onFinish: () -> Unit
) {
    val state by DatabaseGate.state.collectAsState()
    val info = (state as? DatabaseGate.State.RecoveryRequired)?.info
    val busy = state is DatabaseGate.State.Checking

    Scaffold(
        topBar = { TopAppBar(title = { Text("Projects need recovery") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Checking project database…")
            }
            Text(
                "Your projects were not deleted.",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Lumina could not open its project database, so editing is paused. " +
                    "The existing database was left untouched.",
                style = MaterialTheme.typography.bodyMedium
            )
            if (info != null) {
                Text(reasonText(info.failure), style = MaterialTheme.typography.titleSmall)
                if (!info.message.isNullOrBlank()) {
                    Text(
                        "Technical detail: ${info.message}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "Recovery bundle: ${info.forensicPath ?: "not created"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(onClick = onRetry, enabled = !busy) {
                Text("Retry")
            }
            OutlinedButton(onClick = onFinish, enabled = !busy) {
                Text("Close app")
            }
        }
    }
}

private fun reasonText(failure: DatabaseFailure): String = when (failure) {
    DatabaseFailure.CORRUPT -> "The project database looks corrupt."
    DatabaseFailure.MIGRATION -> "The project database could not be upgraded."
    DatabaseFailure.LOCKED -> "The project database is busy."
    DatabaseFailure.IO -> "The project database could not be read from storage."
    DatabaseFailure.UNKNOWN -> "The project database failed for an unknown reason."
}
