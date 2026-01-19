package io.github.commandertvis.huemanager

import kotlinx.browser.window

class WasmPlatform: Platform {
    override val name: String = "Web with Kotlin/Wasm"
    override val isWeb: Boolean = true
    override fun openUrl(url: String) {
        window.open(url, "_blank")
    }
}

actual fun getPlatform(): Platform = WasmPlatform()