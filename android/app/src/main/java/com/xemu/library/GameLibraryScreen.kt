package com.xemu.library

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.xemu.emulation.EmulationActivity
import com.xemu.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameLibraryScreen(
    navController: NavController,
    libraryViewModel: LibraryViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val games by libraryViewModel.games.collectAsState()
    val context = LocalContext.current

    val steamGridDbKey by settingsViewModel.steamGridDbKey.collectAsState()
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            libraryViewModel.addDirectory(it, steamGridDbKey)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("xemu") },
                actions = {
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { dirPicker.launch(null) }) {
                Icon(Icons.Default.Add, contentDescription = "Add games folder")
            }
        }
    ) { padding ->
        if (games.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No games found.\nTap + to add a games folder.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 72.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(games, key = { it.uri.toString() }) { game ->
                    GameCard(game = game) {
                        launchGame(context, game, settingsViewModel)
                    }
                }
            }
        }
    }
}

private fun launchGame(
    context: android.content.Context,
    game: GameEntry,
    settings: SettingsViewModel,
) {
    val mcpx = settings.mcpxUri.value?.toString() ?: return
    val bios = settings.biosUri.value?.toString() ?: return
    val hdd  = settings.hddUri.value?.toString()  ?: return
    val useCustomDriver = settings.driverEnabled.value &&
        settings.driverDir.value.isNotEmpty() &&
        settings.driverName.value.isNotEmpty()
    val intent = Intent(context, EmulationActivity::class.java).apply {
        putExtra("mcpx",       mcpx)
        putExtra("bios",       bios)
        putExtra("hdd",        hdd)
        putExtra("iso",        game.uri.toString())
        putExtra("renderer",   settings.renderer.value)
        putExtra("driverDir",  if (useCustomDriver) settings.driverDir.value else "")
        putExtra("driverName", if (useCustomDriver) settings.driverName.value else "")
    }
    context.startActivity(intent)
}
