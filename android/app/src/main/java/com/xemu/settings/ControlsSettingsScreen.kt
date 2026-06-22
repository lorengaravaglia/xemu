package com.xemu.settings

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.xemu.emulation.MappingActivity

@Composable
fun ControlsSettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val overlayMode by settingsViewModel.overlayMode.collectAsState()

    SettingsSubScreenScaffold("Controls", navController) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(0.dp))

            // Button mapping
            SettingsSectionLabel("Controller")
            Card(
                onClick = { context.startActivity(Intent(context, MappingActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Gamepad, contentDescription = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Button Mapping", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Remap physical controller buttons",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Touchscreen overlay visibility
            SettingsSectionLabel("Touchscreen Overlay")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp),
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Controls when the on-screen gamepad overlay is shown during emulation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val modeOptions = listOf(
                        "AUTO"         to "Auto (hide when physical controller connected)",
                        "ALWAYS_SHOW"  to "Always Show",
                        "ALWAYS_HIDE"  to "Always Hide",
                    )
                    SettingsDropdown(
                        value = modeOptions.first { it.first == overlayMode }.second,
                        options = modeOptions.map { it.second },
                        onSelect = { label ->
                            settingsViewModel.setOverlayMode(
                                modeOptions.first { it.second == label }.first
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
