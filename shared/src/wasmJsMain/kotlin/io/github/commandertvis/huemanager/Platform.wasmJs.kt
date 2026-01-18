package io.github.commandertvis.huemanager

external fun openUrlInNewTab(url: String)

class WasmPlatform: Platform {
    override val name: String = "Web with Kotlin/Wasm"
    override val isWeb: Boolean = true
    override fun openUrl(url: String) {
        openUrlInNewTab(url)
    }
}

actual fun getPlatform(): Platform = WasmPlatform()