package io.github.commandertvis.huemanager

import kotlinx.browser.window

class JsPlatform: Platform {
    override val name: String = "Web with Kotlin/JS"
    override val isWeb: Boolean = true
    override fun openUrl(url: String) {
        window.open(url, "_blank")
    }
}

actual fun getPlatform(): Platform = JsPlatform()