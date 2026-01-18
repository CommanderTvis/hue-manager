package io.github.commandertvis.huemanager

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
    override val isWeb: Boolean = false
    override fun openUrl(url: String) {
        // Implementation for Android might require a Context, which we don't have here.
        // For now, we'll leave it empty or handle it differently if needed.
    }
}

actual fun getPlatform(): Platform = AndroidPlatform()