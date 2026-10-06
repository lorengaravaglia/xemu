package com.boxxy.library

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewCarousel
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
import com.boxxy.emulation.GameSettings
import com.boxxy.emulation.QuickResume
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.focus.onFocusChanged
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

    /* ── Background art and selection ─────────────────────────────────── */
    val backgroundArt by libraryViewModel.backgroundArt.collectAsState()
    val selectedUri by libraryViewModel.selectedUri.collectAsState()
    val selectedGame = games.firstOrNull { it.uri == selectedUri } ?: games.firstOrNull()
    /* Showcase needs the background; without it, it is just one row. */
    val showcase = rows == 0 && backgroundArt
    val gridRows = if (rows == 2) 2 else 1

    /* Lower-screen list in dual-screen mode; re-checked when the library
     * returns to the front, in case the setting changed. */
    val dualDisplay = remember(resumeRefresh) { LibraryPresentation.companionDisplay(context) }
    var started by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) started = true
            if (e == Lifecycle.Event.ON_STOP) started = false
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    /*
     * The selected game is the card with controller focus, or else the one
     * nearest the middle of the row -- so scrolling by touch moves the
     * background along with it.
     */
    val gridState = rememberLazyGridState()
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val centreKey by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            /* At either end of the list the end card is the natural pick;
             * the centre card is only right while scrolled in between. */
            if (gridState.firstVisibleItemIndex == 0 &&
                gridState.firstVisibleItemScrollOffset == 0) {
                return@derivedStateOf info.visibleItemsInfo.firstOrNull()?.key as? String
            }
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo
                .minByOrNull { kotlin.math.abs(it.offset.x + it.size.width / 2 - mid) }
                ?.key as? String
        }
    }
    /* Heroes and logos arrive in the background once games are known. */
    LaunchedEffect(steamGridDbKey, games.size) {
        libraryViewModel.fetchBackgrounds(steamGridDbKey)
    }
    LaunchedEffect(focusedKey, centreKey, games.size) {
        (focusedKey ?: centreKey ?: games.firstOrNull()?.uri?.toString())
            ?.let { libraryViewModel.select(android.net.Uri.parse(it)) }
    }

    val cards: @Composable (Modifier) -> Unit = { mod ->
        LazyHorizontalGrid(
            state = gridState,
            rows = GridCells.Fixed(gridRows),
            modifier = mod,
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
                Box(Modifier.onFocusChanged {
                    if (it.hasFocus) focusedKey = game.uri.toString()
                    else if (focusedKey == game.uri.toString()) focusedKey = null
                }) {
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

    /* Dual screen: the cards go to the lower screen's window. */
    val bottomContent = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val activity = context as? androidx.activity.ComponentActivity
    val useDual = dualDisplay != null && activity != null && games.isNotEmpty()
    SideEffect {
        bottomContent.value = if (useDual) {
            {
                /* A 1240x1080 touchscreen: one row at full height would show
                 * two cards, so it is kept to about three-quarters. */
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    cards(Modifier.fillMaxWidth().fillMaxHeight(if (gridRows == 1) 0.72f else 1f))
                }
            }
        } else null
    }
    DisposableEffect(useDual, started, dualDisplay?.displayId) {
        var p: LibraryPresentation? = null
        if (useDual && started) {
            p = LibraryPresentation(activity!!, dualDisplay!!, bottomContent)
            try {
                p.show()
                LibraryPresentation.showing = p
            } catch (e: android.view.WindowManager.InvalidDisplayException) {
                p = null
            }
        }
        onDispose {
            p?.dismiss()
            if (LibraryPresentation.showing === p) LibraryPresentation.showing = null
        }
    }

    Box(Modifier.fillMaxSize()) {
    if ((backgroundArt || useDual) && games.isNotEmpty()) {
        GameShowcase(
            game = selectedGame,
            modifier = Modifier.fillMaxSize(),
            dim = if (showcase || useDual) 0f else 0.55f,
            footer = if (useDual) { g -> ShowcaseDetails(g, resumeRefresh) } else null,
        )
    }
    Scaffold(
        containerColor = if ((backgroundArt || useDual) && games.isNotEmpty())
            androidx.compose.ui.graphics.Color.Transparent
        else MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = if ((backgroundArt || useDual) && games.isNotEmpty())
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent)
                else TopAppBarDefaults.topAppBarColors(),
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    /* Showcase -> one row -> two rows. */
                    IconButton(onClick = {
                        libraryViewModel.setRows(when (rows) { 0 -> 1; 1 -> 2; else -> 0 })
                    }) {
                        Icon(
                            when (rows) {
                                0 -> Icons.Default.ViewCarousel
                                1 -> Icons.Default.GridView
                                else -> Icons.Default.ViewAgenda
                            },
                            contentDescription = when (rows) {
                                0 -> "Showcase view"; 1 -> "One row"; else -> "Two rows"
                            },
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
        } else if (!useDual) {
            /*
             * Rows are fixed and the grid scrolls sideways, so the row count is
             * a direct size control.  One row at full height is overbearing,
             * so it is capped and centred; Showcase keeps a short row along
             * the bottom so the background art above it is seen.
             */
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = if (showcase) Alignment.BottomCenter else Alignment.Center,
            ) {
                cards(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(when {
                            showcase -> 0.46f
                            gridRows == 1 -> 0.78f
                            else -> 1f
                        })
                )
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
            onGameSettings = {
                artTarget = null
                val key = GameSettings.keyFor(game.titleId, game.uri.toString())
                navController.navigate("game_settings/$key?name=" +
                                       android.net.Uri.encode(game.displayName))
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
    onGameSettings: () -> Unit,
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
                OutlinedButton(
                    onClick = onGameSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Game settings")
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

/** Dual screen: what the top screen shows under the selected game's hero. */
@Composable
private fun ShowcaseDetails(game: GameEntry, refresh: Int) {
    val context = LocalContext.current
    val resumeAt = remember(game.uri, refresh) {
        QuickResume.savedAt(context, QuickResume.gameIdFor(game.uri.toString()))
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        /* Without a logo the name already stands in its place above. */
        if (game.logoUri != null) {
            Text(game.displayName, style = MaterialTheme.typography.headlineMedium,
                 color = androidx.compose.ui.graphics.Color.White)
        }
        if (resumeAt != null) {
            Text("Quick resume available \u00b7 saved " +
                 android.text.format.DateUtils.getRelativeTimeSpanString(resumeAt),
                 style = MaterialTheme.typography.bodyLarge,
                 color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.8f))
        }
        Text("Choose a game on the lower screen. Long-press for options.",
             style = MaterialTheme.typography.bodyMedium,
             color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f))
    }
}
