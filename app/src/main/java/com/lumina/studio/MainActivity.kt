package com.lumina.studio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.lumina.studio.core.data.datastore.SettingsRepository
import com.lumina.studio.core.data.local.DatabaseGate
import com.lumina.studio.core.data.store.StorageMigration
import com.lumina.studio.core.design.theme.LuminaTheme
import com.lumina.studio.core.util.IncomingImages
import com.lumina.studio.navigation.AppNav
import com.lumina.studio.ui.screens.DatabaseRecoveryScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) {
            // Startup gate: no Room-dependent work (StorageMigration, AppNav,
            // project ViewModels) runs until the database gate reports Ready.
            DatabaseGate.initialize(applicationContext)
            if (DatabaseGate.state.value is DatabaseGate.State.Ready) {
                runCatching { StorageMigration.runIfNeeded(applicationContext) }
            }
        }
        handleIncomingIntent(intent)
        enableEdgeToEdge()
        setContent {
            val repository = remember { SettingsRepository(applicationContext) }
            val theme by repository.theme.collectAsState(initial = "dark")
            val dbState by DatabaseGate.state.collectAsState()
            LuminaTheme(darkPlus = theme == "dark_plus") {
                when (dbState) {
                    is DatabaseGate.State.Ready -> AppNav()
                    is DatabaseGate.State.RecoveryRequired -> DatabaseRecoveryScreen(
                        onRetry = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                DatabaseGate.retry(applicationContext)
                            }
                        },
                        onFinish = { finish() }
                    )
                    is DatabaseGate.State.Checking -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Text("Checking project database…")
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uris = IncomingImages.extractUris(intent)
        if (uris.isEmpty()) return
        // Single now; SEND_MULTIPLE imports the first image (batch lands later).
        IncomingImages.emitAll(uris)
    }
}
