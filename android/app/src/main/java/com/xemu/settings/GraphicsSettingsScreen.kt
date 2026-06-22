package com.xemu.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    val aspectRatio   by settingsViewModel.aspectRatio.collectAsState()
    val surfaceScale  by settingsViewModel.surfaceScale.collectAsState()
    val filterNearest by settingsViewModel.filterNearest.collectAsState()

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

            // Aspect Ratio
            SettingsSectionLabel("Aspect Ratio")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp),
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val aspectOptions = listOf(
                        0 to "Native (640×480 integer scale)",
                        1 to "Auto (stretch)",
                        2 to "4:3 (pillarbox / letterbox)",
                        3 to "16:9 (stretch to fill)",
                    )
                    SettingsDropdown(
                        value = aspectOptions.first { it.first == aspectRatio }.second,
                        options = aspectOptions.map { it.second },
                        onSelect = { label ->
                            settingsViewModel.setAspectRatio(
                                aspectOptions.first { it.second == label }.first
                            )
                        },
                    )
                }
            }

            // Internal Resolution Scale
            SettingsSectionLabel("Internal Resolution Scale")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp),
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Multiplies NV2A surface render resolution. Takes effect on next launch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val scaleOptions = listOf(1 to "1× (Native)", 2 to "2×", 3 to "3×")
                    SettingsDropdown(
                        value = scaleOptions.first { it.first == surfaceScale }.second,
                        options = scaleOptions.map { it.second },
                        onSelect = { label ->
                            settingsViewModel.setSurfaceScale(
                                scaleOptions.first { it.second == label }.first
                            )
                        },
                    )
                }
            }

            // Filter Method
            SettingsSectionLabel("Filter Method")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    SettingsSwitchRow(
                        label = "Nearest Neighbour",
                        subtitle = "Sharp pixel scaling instead of smooth interpolation",
                        checked = filterNearest,
                    ) { settingsViewModel.setFilterNearest(it) }
                }
            }

            // Renderer
            SettingsSectionLabel("Renderer")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp),
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Backend", style = MaterialTheme.typography.labelLarge)
                    Text("Takes effect on next launch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val rendererOptions = listOf("VULKAN" to "Vulkan", "OPENGL" to "OpenGL")
                    SettingsDropdown(
                        value = rendererOptions.first { it.first == renderer }.second,
                        options = rendererOptions.map { it.second },
                        onSelect = { label ->
                            settingsViewModel.setRenderer(
                                rendererOptions.first { it.second == label }.first
                            )
                        },
                    )
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
