package io.github.commandertvis.huemanager

import kotlin.js.ExperimentalWasmJsInterop

private external fun open(url: String, target: String)

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("(text) => navigator.clipboard.writeText(text)")
private external fun writeToClipboard(text: String)

class WasmPlatform: Platform {
    override val name: String = "Web with Kotlin/Wasm"
    override val isWeb: Boolean = true
    override fun openUrl(url: String) {
        open(url, "_blank")
    }
    override fun copyToClipboard(text: String) {
        writeToClipboard(text)
    }
}

actual fun getPlatform(): Platform = WasmPlatform()
