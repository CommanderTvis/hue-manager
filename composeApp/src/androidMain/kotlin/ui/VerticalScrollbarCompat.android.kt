package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun VerticalScrollbarCompat(scrollState: ScrollState, modifier: Modifier) {
    // Android handles scrollbars natively via scrollable modifiers
}
