package io.github.commandertvis.huemanager

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() {
    // Match macOS title bar to system dark/light theme
    System.setProperty("apple.awt.application.appearance", "system")

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "hue-manager",
        ) {
            App()
        }
    }
}