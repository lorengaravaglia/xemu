package com.boxxy.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.io.File

@Composable
fun AdvancedSettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val accurateMemOrdering by settingsViewModel.accurateMemOrdering.collectAsState()
    val fastOrderedLoads by settingsViewModel.fastOrderedLoads.collectAsState()

    // Compute shader cache stats on composition
    val shaderDir = remember { File(context.filesDir, "shaders") }
    var shaderCount by remember { mutableIntStateOf(0) }
    var shaderSizeKb by remember { mutableLongStateOf(0L) }
    var cleared by remember { mutableStateOf(false) }

    LaunchedEffect(cleared) {
        shaderCount = shaderDir.listFiles()?.size ?: 0
        shaderSizeKb = shaderDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024
    }

    SettingsSubScreenScaffold("Advanced", navController) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(0.dp))

            SettingsSectionLabel("Compatibility")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    SettingsSwitchRow(
                        label = "Accurate memory ordering",
                        subtitle = "The Xbox has one CPU, so the strict store " +
                                   "ordering its games were written against has " +
                                   "nothing to observe it, and skipping it removes " +
                                   "most of the worst frame drops. Turn this on only " +
                                   "if you see flickering or corrupted graphics, or " +
                                   "hear crackling audio \u2014 it will cost " +
                                   "performance. Reloads translated code, so " +
                                   "expect a brief pause.",
                        checked = accurateMemOrdering,
                    ) { settingsViewModel.setAccurateMemOrdering(it) }
                }
            }

            SettingsSectionLabel("Performance")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    SettingsSwitchRow(
                        label = "Fast ordered loads",
                        subtitle = "Uses a newer ARM instruction that carries " +
                                   "memory ordering itself instead of adding a " +
                                   "separate barrier before every read. Roughly " +
                                   "halves the worst frame drops in busy scenes. " +
                                   "Turn it off if you see graphical corruption " +
                                   "or crashes. Reloads translated code, so " +
                                   "expect a brief pause.",
                        checked = fastOrderedLoads,
                    ) { settingsViewModel.setFastOrderedLoads(it) }
                }
            }

            SettingsSectionLabel("Shader Cache")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp),
                       verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (shaderCount > 0)
                            "$shaderCount shaders cached (${shaderSizeKb} KB)"
                        else
                            "No shaders cached yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            shaderDir.deleteRecursively()
                            shaderDir.mkdirs()
                            cleared = !cleared
                        },
                        enabled = shaderCount > 0,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                    ) {
                        Text("Clear Cache")
                    }
                }
            }

            SettingsSectionLabel("Build Info")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp),
                       verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BuildInfoRow("Package", context.packageName)
                    BuildInfoRow("Version", try {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
                    } catch (_: Exception) { "—" })
                    BuildInfoRow("ABI", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "—")
                    BuildInfoRow("Android", android.os.Build.VERSION.RELEASE)
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun BuildInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
