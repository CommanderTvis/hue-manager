package io.github.commandertvis.huemanager

import android.content.Intent
import android.net.Uri
import android.os.Build
import io.github.commandertvis.huemanager.storage.getApplicationContext

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
    override val isWeb: Boolean = false
    override fun openUrl(url: String) {
        try {
            val context = getApplicationContext()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                // Add FLAG_ACTIVITY_NEW_TASK since we're starting from application context
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            println("Failed to open URL on Android: ${e.message}")
            e.printStackTrace()
        }
    }
}

actual fun getPlatform(): Platform = AndroidPlatform()