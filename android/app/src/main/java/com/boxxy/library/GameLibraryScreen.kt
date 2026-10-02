package com.boxxy.library

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import com.boxxy.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.boxxy.emulation.EmulationActivity
import com.boxxy.emulation.EXTRA_QUICK_RESUME
import com.boxxy.emulation.GameLaunch
import com.boxxy.emulation.QuickResume
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.boxxy.settings.SettingsViewModel

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
    val rows by libraryViewModel.rows.collectAsState()

    // Long-pressed game awaiting an artwork decision.
    var artTarget by remember { mutableStateOf<GameEntry?>(null) }

    /* Bumped whenever the library returns to the front, so resume points the
     * emulator process just wrote show up without a rescan. */
    var resumeRefresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val artPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = artTarget
        artTarget = null
        if (uri != null && target != null) libraryViewModel.setCustomArt(target, uri)
    }
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
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { libraryViewModel.setRows(if (rows == 1) 2 else 1) }) {
                        Icon(
                            if (rows == 1) Icons.Default.GridView else Icons.Default.ViewAgenda,
                            contentDescription = if (rows == 1) "Show two rows" else "Show one row",
                        )
                    }
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
            /*
             * Rows are fixed and the grid scrolls sideways, so the row count is
             * a direct size control: one row gives large cards, two fits more on
             * screen at half the height. A vertical grid could not do this — its
             * card size follows the column count, which is the wrong axis on a
             * landscape handheld.
             */
            /*
             * One row of cards at full viewport height is overbearing, so that
             * case is capped short of filling it and centred. Two rows already
             * halve the card height and need no cap.
             */
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
            LazyHorizontalGrid(
                rows = GridCells.Fixed(rows),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(if (rows == 1) 0.78f else 1f),
                // Extra room on the trailing edge so the last card can scroll
                // clear of the floating action button instead of under it.
                contentPadding = PaddingValues(
                    start = 12.dp, end = 88.dp, top = 8.dp, bottom = 8.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(games, key = { it.uri.toString() }) { game ->
                    /* Re-read when the library comes back to the front: the
                     * emulator process writes the marker on exit. */
                    val resumeAt = remember(game.uri, resumeRefresh) {
                        QuickResume.savedAt(
                            context, QuickResume.gameIdFor(game.uri.toString()))
                    }
                    GameCard(
                        game = game,
                        onClick = { launchGame(context, game, settingsViewModel) },
                        onLongClick = { artTarget = game },
                        onResume = resumeAt?.let {
                            { launchGame(context, game, settingsViewModel,
                                         quickResume = true) }
                        },
                    )
                }
            }
            }
        }
    }


    artTarget?.let { game ->
        val resumeAt = QuickResume.savedAt(
            context, QuickResume.gameIdFor(game.uri.toString()))
        ArtworkDialog(
            game = game,
            resumeAt = resumeAt,
            onQuickResume = {
                artTarget = null
                launchGame(context, game, settingsViewModel, quickResume = true)
            },
            onChoose = { artPicker.launch(arrayOf("image/*")) },
            onReset = {
                libraryViewModel.clearCustomArt(game)
                artTarget = null
            },
            onDismiss = { artTarget = null },
        )
    }
}

/**
 * Artwork options for a long-pressed game. Only offered once the disc has been
 * read — art is stored against the title ID, so there is nowhere to put it
 * until that is known.
 */
@Composable
private fun ArtworkDialog(
    game: GameEntry,
    resumeAt: Long?,
    onQuickResume: () -> Unit,
    onChoose: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(game.displayName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                /* The alternate way in: a normal tap boots the game. */
                if (resumeAt != null) {
                    FilledTonalButton(
                        onClick = onQuickResume,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Quick resume \u00b7 " +
                             android.text.format.DateUtils.getRelativeTimeSpanString(
                                 resumeAt).toString())
                    }
                }
                Text(
                    if (game.titleId == null)
                        "This disc could not be read, so artwork cannot be stored for it."
                    else
                        "Choose your own image, or go back to the artwork found on the disc.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onChoose, enabled = game.titleId != null) {
                Text("Choose artwork…")
            }
        },
        dismissButton = {
            TextButton(onClick = onReset, enabled = game.titleId != null) {
                Text("Use disc artwork")
            }
        },
    )
}

private fun launchGame(
    context: android.content.Context,
    game: GameEntry,
    settings: SettingsViewModel,
    quickResume: Boolean = false,
) {
    val intent = GameLaunch.intentFor(context, game.uri.toString(), quickResume) ?: return
    context.startActivity(intent)
}
