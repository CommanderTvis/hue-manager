package io.github.commandertvis.huemanager

external fun open(url: String, target: String)

class WasmPlatform: Platform {
    override val name: String = "Web with Kotlin/Wasm"
    override val isWeb: Boolean = true
    override fun openUrl(url: String) {
        open(url, "_blank")
    }
}

actual fun getPlatform(): Platform = WasmPlatform()