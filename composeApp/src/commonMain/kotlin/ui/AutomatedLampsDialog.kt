package io.github.commandertvis.huemanager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.commandertvis.huemanager.models.Lamp
import io.github.commandertvis.huemanager.models.LampSchedule

enum class LampControlMode(val label: String) {
    CYCLE("Day/night"), SCHEDULE("Individual"), MANUAL("Manual")
}

@Composable
fun LampControlModeSelector(mode: LampControlMode, enabled: Boolean, hasSchedule: Boolean = true,
    onSelect: (LampControlMode) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LampControlMode.entries.forEach { option ->
            val available = enabled && (option != LampControlMode.SCHEDULE || hasSchedule)
            val interactions = remember { MutableInteractionSource() }
            val hovered by interactions.collectIsHoveredAsState()
            val pressed by interactions.collectIsPressedAsState()
            val focused by interactions.collectIsFocusedAsState()
            val selected = mode == option
            val colors = MaterialTheme.colorScheme
            val base = if (selected) colors.secondaryContainer else colors.surface
            val background = if (available && (hovered || pressed || focused)) {
                colors.onSurface.copy(alpha = if (pressed) 0.12f else 0.08f).compositeOver(base)
            } else base
            Surface(
                modifier = Modifier.selectable(selected = selected, enabled = available, role = Role.RadioButton,
                    interactionSource = interactions, indication = null, onClick = { onSelect(option) }),
                shape = RoundedCornerShape(8.dp),
                color = background,
                contentColor = (if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
                    .let { if (available) it else it.copy(alpha = 0.38f) },
                border = if (selected) null else BorderStroke(1.dp, colors.outlineVariant),
            ) {
                Text(option.label, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
fun AutomatedLampsDialog(
    lamps: List<Lamp>,
    excludedLampIds: Set<String>,
    schedules: List<LampSchedule>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (Set<String>, List<LampSchedule>) -> Unit,
) {
    var pendingExcluded by remember { mutableStateOf(excludedLampIds) }
    var pendingSchedules by remember { mutableStateOf(schedules) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Lamp automation") },
        text = {
            val scrollState = rememberScrollState()
            val containerColor = AlertDialogDefaults.containerColor
            Box(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(scrollState).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Choose what controls each lamp. Manual leaves it to you or other apps. Create an individual schedule with the clock on the lamp card.",
                        style = MaterialTheme.typography.bodySmall)
                    if (lamps.isEmpty()) Text("No lamps available.")
                    lamps.forEach { lamp ->
                        val schedule = pendingSchedules.find { it.lampId == lamp.id }
                        val mode = when {
                            schedule?.enabled == true -> LampControlMode.SCHEDULE
                            lamp.id in pendingExcluded -> LampControlMode.MANUAL
                            else -> LampControlMode.CYCLE
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(lamp.name, style = MaterialTheme.typography.titleMedium)
                            Text(when (mode) {
                                LampControlMode.CYCLE -> "Controlled by day/night cycle"
                                LampControlMode.SCHEDULE -> "Controlled by individual schedule"
                                LampControlMode.MANUAL -> "No automation"
                            }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LampControlModeSelector(mode, enabled = !saving, hasSchedule = schedule != null) { option ->
                                pendingExcluded = if (option == LampControlMode.MANUAL) pendingExcluded + lamp.id else pendingExcluded - lamp.id
                                pendingSchedules = pendingSchedules.map {
                                    if (it.lampId == lamp.id) it.copy(enabled = option == LampControlMode.SCHEDULE) else it
                                }
                            }
                        }
                    }
                }
                if (scrollState.canScrollBackward) Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(24.dp)
                    .background(Brush.verticalGradient(listOf(containerColor, containerColor.copy(alpha = 0f)))))
                if (scrollState.canScrollForward) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp)
                    .background(Brush.verticalGradient(listOf(containerColor.copy(alpha = 0f), containerColor))))
                Box(Modifier.matchParentSize()) {
                    VerticalScrollbarCompat(scrollState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = { onSave(pendingExcluded, pendingSchedules) }) {
                Text(if (saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
