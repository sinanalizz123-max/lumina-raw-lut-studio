package com.lumina.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.lumina.studio.core.data.datastore.SettingsRepository
import com.lumina.studio.core.design.theme.LuminaTheme
import com.lumina.studio.navigation.AppNav

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val repository = remember { SettingsRepository(applicationContext) }
            val theme by repository.theme.collectAsState(initial = "dark")
            LuminaTheme(darkPlus = theme == "dark_plus") {
                AppNav()
            }
        }
    }
}
