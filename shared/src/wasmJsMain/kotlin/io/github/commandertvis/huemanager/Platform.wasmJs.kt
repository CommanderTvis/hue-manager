package io.github.commandertvis.huemanager

class WasmPlatform: Platform {
    override val name: String = "Web with Kotlin/Wasm"
    override val isWeb: Boolean = true
}

actual fun getPlatform(): Platform = WasmPlatform()