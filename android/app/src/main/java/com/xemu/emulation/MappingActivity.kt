package com.xemu.emulation

import android.view.InputDevice
import android.view.KeyEvent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xemu.settings.SettingsSectionLabel
import com.xemu.ui.theme.XemuTheme

/**
 * Controller remapping screen.
 *
 * Shows each Xbox button with its currently-assigned Android keycode.
 * Tap a row → the activity listens for the next physical gamepad button
 * press and assigns it.
 */
class MappingActivity : ComponentActivity() {

    private lateinit var mapping: ControllerMapping
    private val capturingFor = mutableStateOf<ControllerMapping.XboxButton?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mapping = ControllerMapping(this)
        setContent {
            XemuTheme {
                MappingScreen(
                    mapping = mapping,
                    capturingFor = capturingFor.value,
                    onStartCapture = { capturingFor.value = it },
                    onCancelCapture = { capturingFor.value = null },
                    onReset = { mapping.resetToDefaults(); mapping.save() },
                    onNavigateBack = { finish() },
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val capturing = capturingFor.value
        if (capturing != null && event.action == KeyEvent.ACTION_DOWN) {
            val isGamepad = (event.source and InputDevice.SOURCE_GAMEPAD != 0) ||
                            (event.source and InputDevice.SOURCE_DPAD != 0)
            if (isGamepad) {
                mapping.assignButton(event.keyCode, capturing)
                mapping.save()
                capturingFor.value = null
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MappingScreen(
    mapping: ControllerMapping,
    capturingFor: ControllerMapping.XboxButton?,
    onStartCapture: (ControllerMapping.XboxButton) -> Unit,
    onCancelCapture: () -> Unit,
    onReset: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    var showResetDialog by remember { mutableStateOf(false) }

    // Build reverse map: XboxButton → bound keycode label string
    // Recomputed on each recomposition (triggered by capturingFor changes or reset)
    val buttonLabels = remember(capturingFor, showResetDialog) {
        val reverse = mutableMapOf<ControllerMapping.XboxButton, MutableList<Int>>()
        mapping.allButtonMappings().forEach { (kc, btn) ->
            reverse.getOrPut(btn) { mutableListOf() }.add(kc)
        }
        ControllerMapping.XboxButton.values().associateWith { btn ->
            reverse[btn]?.joinToString(", ") { ControllerMapping.keycodeLabel(it) } ?: "(none)"
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Mappings") },
            text = { Text("Reset all button mappings to defaults?") },
            confirmButton = {
                TextButton(onClick = {
                    onReset()
                    showResetDialog = false
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Controller Mapping") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { showResetDialog = true }) {
                        Text("Reset")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            // Status / capture prompt
            item {
                val isCapturing = capturingFor != null
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (isCapturing)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    onClick = { if (isCapturing) onCancelCapture() },
                ) {
                    Text(
                        text = if (isCapturing)
                            "Press a button on your controller for: ${capturingFor!!.label}  •  Tap to cancel"
                        else
                            "Tap a row below, then press a button on your physical controller to assign it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCapturing)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            // Button mapping rows
            item { SettingsSectionLabel("Buttons") }
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        ControllerMapping.XboxButton.values().forEachIndexed { index, btn ->
                            val label = buttonLabels[btn] ?: "(none)"
                            val isBeingCaptured = capturingFor == btn
                            ButtonMappingRow(
                                buttonName = btn.label,
                                assignedLabel = label,
                                isCapturing = isBeingCaptured,
                                onClick = {
                                    if (isBeingCaptured) onCancelCapture() else onStartCapture(btn)
                                },
                            )
                            if (index < ControllerMapping.XboxButton.values().size - 1) {
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            // Axes info (read-only)
            item { SettingsSectionLabel("Axes (auto-detected)") }
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Standard axis layout is used automatically:\n" +
                               "Left stick → AXIS_X / AXIS_Y\n" +
                               "Right stick → AXIS_Z / AXIS_RZ\n" +
                               "Triggers → AXIS_LTRIGGER / AXIS_RTRIGGER",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun ButtonMappingRow(
    buttonName: String,
    assignedLabel: String,
    isCapturing: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (isCapturing)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        else
            MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buttonName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = assignedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (isCapturing)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
