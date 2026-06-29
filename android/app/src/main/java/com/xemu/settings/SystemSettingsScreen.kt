package com.xemu.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.xemu.library.LibraryViewModel

@Composable
fun SystemSettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel,
    libraryViewModel: LibraryViewModel,
) {
    val context = LocalContext.current
    val mcpx by settingsViewModel.mcpxUri.collectAsState()
    val bios by settingsViewModel.biosUri.collectAsState()
    val hdd  by settingsViewModel.hddUri.collectAsState()
    val dirs by libraryViewModel.dirs.collectAsState()
    val steamGridDbKey by settingsViewModel.steamGridDbKey.collectAsState()

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

    SettingsSubScreenScaffold("System", navController) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Text("System Files", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            item { FilePicker("MCPX Boot ROM", mcpx) { pickMcpx.launch(arrayOf("*/*")) } }
            item { FilePicker("Xbox BIOS", bios) { pickBios.launch(arrayOf("*/*")) } }
            item { FilePicker("Hard Drive Image", hdd) { pickHdd.launch(arrayOf("*/*")) } }

            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Game Directories", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = { dirPicker.launch(null) }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add directory")
                    }
                }
            }

            if (dirs.isEmpty()) {
                item {
                    Text(
                        "No directories added yet. Tap + to add a folder containing game ISOs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            } else {
                items(dirs, key = { it.toString() }) { dir ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                dir.lastPathSegment ?: dir.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { libraryViewModel.removeDirectory(dir) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // Box Art
            item {
                Spacer(Modifier.height(8.dp))
                SettingsSectionLabel("Box Art")
            }
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Art is fetched automatically when games are scanned. " +
                            "Get a free API key at steamgriddb.com → Profile → Preferences → API.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        var keyVisible by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = steamGridDbKey,
                            onValueChange = { settingsViewModel.setSteamGridDbKey(it) },
                            label = { Text("SteamGridDB API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (keyVisible) VisualTransformation.None
                                                   else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { keyVisible = !keyVisible }) {
                                    Icon(
                                        imageVector = if (keyVisible) Icons.Outlined.VisibilityOff
                                                      else Icons.Outlined.Visibility,
                                        contentDescription = if (keyVisible) "Hide key" else "Show key",
                                    )
                                }
                            },
                        )
                        OutlinedButton(
                            onClick = { libraryViewModel.refetchArt(steamGridDbKey) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = steamGridDbKey.isNotEmpty(),
                        ) {
                            Text("Re-fetch All Art")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilePicker(label: String, uri: android.net.Uri?, onPick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
            OutlinedButton(onClick = onPick) {
                Text(if (uri != null) "Change" else "Select")
            }
        }
    }
}
