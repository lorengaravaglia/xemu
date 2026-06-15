package com.xemu.settings

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.xemu.library.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel,
    libraryViewModel: LibraryViewModel,
) {
    val context = LocalContext.current
    val mcpx               by settingsViewModel.mcpxUri.collectAsState()
    val bios               by settingsViewModel.biosUri.collectAsState()
    val hdd                by settingsViewModel.hddUri.collectAsState()
    val dirs               by libraryViewModel.dirs.collectAsState()
    val renderer           by settingsViewModel.renderer.collectAsState()
    val driverEnabled      by settingsViewModel.driverEnabled.collectAsState()
    val driverLabel        by settingsViewModel.driverLabel.collectAsState()
    val overlayPosition    by settingsViewModel.overlayPosition.collectAsState()
    val overlayShowFps     by settingsViewModel.overlayShowFps.collectAsState()
    val overlayShowFrametime by settingsViewModel.overlayShowFrametime.collectAsState()
    val overlayShowMemory  by settingsViewModel.overlayShowMemory.collectAsState()
    val overlayShowShaders by settingsViewModel.overlayShowShaders.collectAsState()

    val pickMcpx = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settingsViewModel.setMcpx(it)
        }
    }
    val pickBios = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settingsViewModel.setBios(it)
        }
    }
    val pickHdd = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settingsViewModel.setHdd(it)
        }
    }
    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            libraryViewModel.addDirectory(it)
        }
    }
    val driverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                val label = settingsViewModel.installDriver(context, it)
                Toast.makeText(context, "Driver installed: $label", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to install driver: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Text("System Files", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            item {
                SettingsFilePicker("MCPX Boot ROM", mcpx) { pickMcpx.launch(arrayOf("*/*")) }
            }
            item {
                SettingsFilePicker("Xbox BIOS (Flash)", bios) { pickBios.launch(arrayOf("*/*")) }
            }
            item {
                SettingsFilePicker("Hard Drive Image", hdd) { pickHdd.launch(arrayOf("*/*")) }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Graphics", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp),
                           verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Renderer", style = MaterialTheme.typography.labelLarge)
                        Text("Takes effect on next launch",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("VULKAN", "OPENGL").forEach { option ->
                                FilterChip(
                                    selected = renderer == option,
                                    onClick = { settingsViewModel.setRenderer(option) },
                                    label = { Text(option.lowercase().replaceFirstChar { it.uppercase() }) },
                                )
                            }
                        }
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Custom Vulkan Driver", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp),
                           verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Load a custom Vulkan driver (e.g. Mesa Turnip) via a driver ZIP.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (driverLabel.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Installed: $driverLabel",
                                        style = MaterialTheme.typography.labelLarge)
                                }
                                Switch(
                                    checked = driverEnabled,
                                    onCheckedChange = { settingsViewModel.setDriverEnabled(it) },
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { driverPicker.launch(arrayOf("application/zip", "*/*")) }) {
                                Text(if (driverLabel.isEmpty()) "Install from ZIP" else "Replace")
                            }
                            if (driverLabel.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = { settingsViewModel.clearDriver(context) },
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
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
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("Advanced", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp),
                           verticalArrangement = Arrangement.spacedBy(12.dp)) {

                        // Overlay position
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Overlay Position", style = MaterialTheme.typography.labelLarge)
                            val positions = listOf(
                                "TOP_LEFT" to "Top Left",
                                "TOP_RIGHT" to "Top Right",
                                "BOTTOM_LEFT" to "Bottom Left",
                                "BOTTOM_RIGHT" to "Bottom Right",
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()) {
                                positions.take(2).forEach { (key, label) ->
                                    FilterChip(
                                        selected = overlayPosition == key,
                                        onClick = { settingsViewModel.setOverlayPosition(key) },
                                        label = { Text(label) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()) {
                                positions.drop(2).forEach { (key, label) ->
                                    FilterChip(
                                        selected = overlayPosition == key,
                                        onClick = { settingsViewModel.setOverlayPosition(key) },
                                        label = { Text(label) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }

                        HorizontalDivider()

                        // Overlay metrics toggles
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Show in Overlay", style = MaterialTheme.typography.labelLarge)
                            OverlayToggleRow("FPS", overlayShowFps) {
                                settingsViewModel.setOverlayShowFps(it)
                            }
                            OverlayToggleRow("Frame Time", overlayShowFrametime) {
                                settingsViewModel.setOverlayShowFrametime(it)
                            }
                            OverlayToggleRow("Memory Usage", overlayShowMemory) {
                                settingsViewModel.setOverlayShowMemory(it)
                            }
                            OverlayToggleRow("Shaders Compiled", overlayShowShaders) {
                                settingsViewModel.setOverlayShowShaders(it)
                            }
                        }
                    }
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Game Directories", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { dirPicker.launch(null) }) {
                        Icon(Icons.Default.Add, contentDescription = "Add directory")
                    }
                }
            }

            if (dirs.isEmpty()) {
                item {
                    Text(
                        "No game directories added yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                items(dirs, key = { it.toString() }) { dir ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                dir.lastPathSegment ?: dir.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { libraryViewModel.removeDirectory(dir) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsFilePicker(label: String, uri: android.net.Uri?, onPick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(
                    uri?.lastPathSegment?.substringAfterLast('/') ?: "Not set",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uri != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(onClick = onPick) { Text(if (uri != null) "Change" else "Select") }
        }
    }
}
