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
 * Buttons: tap a row → press a single button to assign.
 * Functions: tap a row → hold your desired combo, release all buttons to assign.
 */
class MappingActivity : ComponentActivity() {

    private lateinit var mapping: ControllerMapping

    // Single-button capture (Xbox buttons)
    private val capturingFor = mutableStateOf<ControllerMapping.XboxButton?>(null)

    // Multi-button capture (hotkey functions)
    private val capturingForHotkey = mutableStateOf<ControllerMapping.HotkeyFunction?>(null)
    private val heldHotkeyKeycodes = mutableSetOf<Int>()    // currently held during capture
    private val capturedHotkeyKeycodes = mutableSetOf<Int>() // all pressed since capture started

    // Drives recomposition of hotkey label rows
    private val hotkeyLabels = mutableStateOf(emptyMap<ControllerMapping.HotkeyFunction, String>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mapping = ControllerMapping(this)
        hotkeyLabels.value = mapping.hotkeyLabelMap()
        setContent {
            XemuTheme {
                MappingScreen(
                    mapping = mapping,
                    capturingFor = capturingFor.value,
                    capturingForHotkey = capturingForHotkey.value,
                    hotkeyLabels = hotkeyLabels.value,
                    onStartCapture = { capturingFor.value = it; capturingForHotkey.value = null },
                    onCancelCapture = { capturingFor.value = null },
                    onStartHotkeyCapture = {
                        capturingForHotkey.value = it
                        capturingFor.value = null
                        heldHotkeyKeycodes.clear()
                        capturedHotkeyKeycodes.clear()
                    },
                    onCancelHotkeyCapture = {
                        capturingForHotkey.value = null
                        heldHotkeyKeycodes.clear()
                        capturedHotkeyKeycodes.clear()
                    },
                    onClearHotkey = { fn ->
                        mapping.clearHotkey(fn)
                        mapping.saveHotkeys()
                        hotkeyLabels.value = mapping.hotkeyLabelMap()
                    },
                    onReset = { mapping.resetToDefaults(); mapping.save() },
                    onNavigateBack = { finish() },
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isGamepad = (event.source and InputDevice.SOURCE_GAMEPAD != 0) ||
                        (event.source and InputDevice.SOURCE_DPAD != 0)
        if (!isGamepad) return super.dispatchKeyEvent(event)

        // Single-button capture
        val capturingBtn = capturingFor.value
        if (capturingBtn != null && event.action == KeyEvent.ACTION_DOWN) {
            mapping.assignButton(event.keyCode, capturingBtn)
            mapping.save()
            capturingFor.value = null
            return true
        }

        // Multi-button combo capture
        val capturingFn = capturingForHotkey.value
        if (capturingFn != null) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    heldHotkeyKeycodes.add(event.keyCode)
                    capturedHotkeyKeycodes.add(event.keyCode)
                }
                KeyEvent.ACTION_UP -> {
                    heldHotkeyKeycodes.remove(event.keyCode)
                    if (heldHotkeyKeycodes.isEmpty() && capturedHotkeyKeycodes.isNotEmpty()) {
                        mapping.assignHotkey(capturingFn, capturedHotkeyKeycodes.toSet())
                        mapping.saveHotkeys()
                        hotkeyLabels.value = mapping.hotkeyLabelMap()
                        capturingForHotkey.value = null
                        capturedHotkeyKeycodes.clear()
                    }
                }
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MappingScreen(
    mapping: ControllerMapping,
    capturingFor: ControllerMapping.XboxButton?,
    capturingForHotkey: ControllerMapping.HotkeyFunction?,
    hotkeyLabels: Map<ControllerMapping.HotkeyFunction, String>,
    onStartCapture: (ControllerMapping.XboxButton) -> Unit,
    onCancelCapture: () -> Unit,
    onStartHotkeyCapture: (ControllerMapping.HotkeyFunction) -> Unit,
    onCancelHotkeyCapture: () -> Unit,
    onClearHotkey: (ControllerMapping.HotkeyFunction) -> Unit,
    onReset: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    var showResetDialog by remember { mutableStateOf(false) }

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
                TextButton(onClick = { onReset(); showResetDialog = false }) { Text("Reset") }
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
                    TextButton(onClick = { showResetDialog = true }) { Text("Reset") }
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
                val isCapturing = capturingFor != null || capturingForHotkey != null
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (isCapturing)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    onClick = { if (capturingFor != null) onCancelCapture()
                                else if (capturingForHotkey != null) onCancelHotkeyCapture() },
                ) {
                    Text(
                        text = when {
                            capturingFor != null ->
                                "Press a button on your controller for: ${capturingFor.label}  •  Tap to cancel"
                            capturingForHotkey != null ->
                                "Hold your combo for: ${capturingForHotkey.label}\nRelease all buttons to assign  •  Tap to cancel"
                            else ->
                                "Buttons: tap a row, then press a button.\n" +
                                "Functions: tap a row, hold your combo, then release all buttons."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCapturing) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
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
                            MappingRow(
                                name = btn.label,
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

            // Hotkey function rows
            item { SettingsSectionLabel("Functions") }
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        ControllerMapping.HotkeyFunction.values().forEachIndexed { index, fn ->
                            val label = hotkeyLabels[fn] ?: "(not set)"
                            val isBeingCaptured = capturingForHotkey == fn
                            MappingRow(
                                name = fn.label,
                                assignedLabel = label,
                                isCapturing = isBeingCaptured,
                                showClear = label != "(not set)",
                                onClick = {
                                    if (isBeingCaptured) onCancelHotkeyCapture()
                                    else onStartHotkeyCapture(fn)
                                },
                                onClear = { onClearHotkey(fn) },
                            )
                            if (index < ControllerMapping.HotkeyFunction.values().size - 1) {
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
private fun MappingRow(
    name: String,
    assignedLabel: String,
    isCapturing: Boolean,
    showClear: Boolean = false,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
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
                .padding(start = 16.dp, end = if (showClear) 4.dp else 16.dp, top = 14.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = assignedLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (isCapturing) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showClear && onClear != null) {
                TextButton(
                    onClick = onClear,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("Clear", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
