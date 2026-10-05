package io.github.commandertvis.huemanager

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import java.awt.GraphicsEnvironment
import java.awt.Toolkit

fun main() {
    val isMac = System.getProperty("os.name").startsWith("Mac")
    // Match macOS title bar to system dark/light theme
    System.setProperty("apple.awt.application.appearance", "system")

    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(screen)
    val availableWidth = screen.bounds.width - insets.left - insets.right
    val availableHeight = screen.bounds.height - insets.top - insets.bottom

    application {
        val windowState = rememberWindowState(
            position = WindowPosition.Aligned(Alignment.Center),
            size = DpSize((availableWidth * 0.85f).dp, (availableHeight * 0.85f).dp),
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = "hue-manager",
            state = windowState,
        ) {
            SideEffect {
                window.minimumSize = java.awt.Dimension(360, 400)
                if (isMac) {
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                }
            }
            App(compactWindowHeader = isMac, desktop = true, onTitleBarDoubleClick = {
                windowState.placement = if (windowState.placement == WindowPlacement.Maximized)
                    WindowPlacement.Floating else WindowPlacement.Maximized
            })
        }
    }
}
