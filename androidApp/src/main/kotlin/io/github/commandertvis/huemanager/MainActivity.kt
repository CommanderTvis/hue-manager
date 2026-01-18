package io.github.commandertvis.huemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.commandertvis.huemanager.storage.initializeServerUrlStorage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        initializeServerUrlStorage(this)

        setContent {
            App()
        }
    }
}
