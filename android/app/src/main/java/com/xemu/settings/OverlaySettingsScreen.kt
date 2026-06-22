package com.xemu.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun OverlaySettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel,
) {
    val overlayPosition      by settingsViewModel.overlayPosition.collectAsState()
    val overlayShowFps       by settingsViewModel.overlayShowFps.collectAsState()
    val overlayShowFrametime by settingsViewModel.overlayShowFrametime.collectAsState()
    val overlayShowMemory    by settingsViewModel.overlayShowMemory.collectAsState()
    val overlayShowShaders   by settingsViewModel.overlayShowShaders.collectAsState()

    SettingsSubScreenScaffold("Overlay", navController) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(0.dp))

            SettingsSectionLabel("Position")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    val positions = listOf(
                        "TOP_LEFT"     to "Top Left",
                        "TOP_RIGHT"    to "Top Right",
                        "BOTTOM_LEFT"  to "Bottom Left",
                        "BOTTOM_RIGHT" to "Bottom Right",
                    )
                    SettingsDropdown(
                        value = positions.first { it.first == overlayPosition }.second,
                        options = positions.map { it.second },
                        onSelect = { label ->
                            settingsViewModel.setOverlayPosition(
                                positions.first { it.second == label }.first
                            )
                        },
                    )
                }
            }

            SettingsSectionLabel("Metrics")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    SettingsSwitchRow("FPS", checked = overlayShowFps) {
                        settingsViewModel.setOverlayShowFps(it)
                    }
                    HorizontalDivider()
                    SettingsSwitchRow("Frame Time",
                        subtitle = "Worst swap interval in the last second",
                        checked = overlayShowFrametime) {
                        settingsViewModel.setOverlayShowFrametime(it)
                    }
                    HorizontalDivider()
                    SettingsSwitchRow("Memory Usage",
                        subtitle = "Process RSS from /proc/self/status",
                        checked = overlayShowMemory) {
                        settingsViewModel.setOverlayShowMemory(it)
                    }
                    HorizontalDivider()
                    SettingsSwitchRow("Shaders Compiled",
                        subtitle = "Running total this session",
                        checked = overlayShowShaders) {
                        settingsViewModel.setOverlayShowShaders(it)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
