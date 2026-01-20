package io.github.commandertvis.huemanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
    override val isWeb: Boolean = false
    override fun openUrl(url: String) {
        try {
            val context = getAndroidApplicationContext()
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
    override fun copyToClipboard(text: String) {
        try {
            val context = getAndroidApplicationContext()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("MCP Configuration", text)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            println("Failed to copy to clipboard on Android: ${e.message}")
            e.printStackTrace()
        }
    }
}

actual fun getPlatform(): Platform = AndroidPlatform()