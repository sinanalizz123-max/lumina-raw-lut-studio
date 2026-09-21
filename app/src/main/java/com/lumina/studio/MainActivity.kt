package com.lumina.studio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.lumina.studio.core.data.datastore.SettingsRepository
import com.lumina.studio.core.data.store.StorageMigration
import com.lumina.studio.core.design.theme.LuminaTheme
import com.lumina.studio.core.util.IncomingImages
import com.lumina.studio.navigation.AppNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { StorageMigration.runIfNeeded(applicationContext) }
        }
        handleIncomingIntent(intent)
        enableEdgeToEdge()
        setContent {
            val repository = remember { SettingsRepository(applicationContext) }
            val theme by repository.theme.collectAsState(initial = "dark")
            LuminaTheme(darkPlus = theme == "dark_plus") {
                AppNav()
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
