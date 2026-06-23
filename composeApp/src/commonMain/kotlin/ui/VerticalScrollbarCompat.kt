package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun VerticalScrollbarCompat(scrollState: ScrollState, modifier: Modifier = Modifier)
