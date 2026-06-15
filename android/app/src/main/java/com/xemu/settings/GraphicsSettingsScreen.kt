package com.xemu.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun GraphicsSettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val renderer      by settingsViewModel.renderer.collectAsState()
    val driverEnabled by settingsViewModel.driverEnabled.collectAsState()
    val driverLabel   by settingsViewModel.driverLabel.collectAsState()

    val driverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val label = settingsViewModel.installDriver(context, it)
                Toast.makeText(context, "Driver installed: $label", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    SettingsSubScreenScaffold("Graphics", navController) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(0.dp))

            // Renderer
            SettingsSectionLabel("Renderer")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp),
                       verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Backend", style = MaterialTheme.typography.labelLarge)
                    Text("Takes effect on next launch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("VULKAN" to "Vulkan", "OPENGL" to "OpenGL").forEach { (key, label) ->
                            FilterChip(
                                selected = renderer == key,
                                onClick = { settingsViewModel.setRenderer(key) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            // Custom Vulkan driver
            SettingsSectionLabel("Custom Vulkan Driver")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp),
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Install a custom Vulkan driver (e.g. Mesa Turnip) from a ZIP package.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (driverLabel.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Installed: $driverLabel",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f))
                            Switch(
                                checked = driverEnabled,
                                onCheckedChange = { settingsViewModel.setDriverEnabled(it) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { driverPicker.launch(arrayOf("application/zip", "*/*")) }
                        ) {
                            Text(if (driverLabel.isEmpty()) "Install from ZIP" else "Replace")
                        }
                        if (driverLabel.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { settingsViewModel.clearDriver(context) },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                            ) { Text("Remove") }
                        }
                    }
                    Text(
                        "Takes effect on next launch. Requires Vulkan renderer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
