@file:OptIn(ExperimentalComposeUiApi::class)

package io.github.commandertvis.huemanager

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

fun main() {
    val body = document.body ?: return
    ComposeViewport(body) {
        App()
    }
}
