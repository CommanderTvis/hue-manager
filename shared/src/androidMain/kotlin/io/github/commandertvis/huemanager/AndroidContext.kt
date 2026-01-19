package io.github.commandertvis.huemanager

import android.content.Context

private lateinit var applicationContext: Context

fun initializeAndroidContext(context: Context) {
    applicationContext = context.applicationContext
}

fun getAndroidApplicationContext(): Context = applicationContext
