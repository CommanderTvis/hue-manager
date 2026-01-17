package io.github.commandertvis.huemanager

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "hue-manager",
    ) {
        App()
    }
}