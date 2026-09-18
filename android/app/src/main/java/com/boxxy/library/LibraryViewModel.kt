package com.boxxy.library

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

private val GAME_EXTENSIONS = setOf("iso")
private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)

    private val _games = MutableStateFlow<List<GameEntry>>(emptyList())
    val games: StateFlow<List<GameEntry>> = _games

    private val _dirs = MutableStateFlow<List<Uri>>(emptyList())
    val dirs: StateFlow<List<Uri>> = _dirs

    /** Outcome of the last artwork lookup, for the settings screen to show. */
    sealed interface ArtStatus {
        data object Working : ArtStatus
        data class Ok(val message: String) : ArtStatus
        data class Problem(val message: String) : ArtStatus
    }

    private val _artStatus = MutableStateFlow<ArtStatus?>(null)
    val artStatus: StateFlow<ArtStatus?> = _artStatus

    /** Rows of cards shown at once; the grid scrolls sideways within them. */
    private val _rows = MutableStateFlow(prefs.getInt("grid_rows", 1))
    val rows: StateFlow<Int> = _rows

    fun setRows(n: Int) {
        _rows.value = n
        prefs.edit().putInt("grid_rows", n).apply()
    }

    init {
        loadDirs()
        rescan()
    }

    private fun loadDirs() {
        val json = prefs.getString("game_dirs", "[]") ?: "[]"
        val arr = JSONArray(json)
        _dirs.value = (0 until arr.length()).map { Uri.parse(arr.getString(it)) }
    }

    private fun saveDirs() {
        val arr = JSONArray()
        _dirs.value.forEach { arr.put(it.toString()) }
        prefs.edit().putString("game_dirs", arr.toString()).apply()
    }

    fun addDirectory(uri: Uri, steamGridDbKey: String = "") {
        if (_dirs.value.none { it == uri }) {
            _dirs.value = _dirs.value + uri
            saveDirs()
            rescan(steamGridDbKey)
        }
    }

    fun removeDirectory(uri: Uri) {
        _dirs.value = _dirs.value.filter { it != uri }
        saveDirs()
        rescan()
    }

    fun rescan(steamGridDbKey: String = "") {
        viewModelScope.launch(Dispatchers.IO) { doScan(steamGridDbKey) }
    }

    /**
     * The scan itself. Suspending rather than launching so callers that need to
     * report an outcome can wait for it to finish.
     */
    private suspend fun doScan(steamGridDbKey: String) {
        run {
            val ctx = getApplication<Application>()
            val found = mutableListOf<GameEntry>()
            for (dirUri in _dirs.value) {
                val dir = DocumentFile.fromTreeUri(ctx, dirUri) ?: continue
                if (!dir.canRead()) continue
                scanDirectory(dir, displayNameOverride = null, found)
            }
            found.sortBy { it.displayName.lowercase() }

            // Apply locally cached artwork immediately (no network round-trip)
            _games.value = found.map { game ->
                if (game.coverUri != null) game
                else {
                    val cached = ArtworkRepository.cachedFile(ctx, game.displayName)
                    if (cached.exists()) game.copy(coverUri = Uri.fromFile(cached)) else game
                }
            }

            // Read titles and art out of the discs themselves. This needs no
            // network and no key, so it is what fills the grid in practice.
            resolveDiscInfo(ctx)

            // Only then try the network, which can upgrade a 128x128 title
            // image to a proper cover if the user has supplied a key.
            if (steamGridDbKey.isNotEmpty()) {
                fetchMissingArt(ctx, steamGridDbKey)
            }
        }
    }

    /**
     * Discard downloaded art and look it up again, reporting what happened.
     * Art the user chose and art read from discs is kept — only network
     * results are refreshed.
     */
    fun refetchArt(steamGridDbKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _artStatus.value = ArtStatus.Working
            ArtworkRepository.clearNetworkArt(getApplication())
            doScan(steamGridDbKey)
        }
    }

    /** Checks the key on its own, without touching stored art. */
    fun testKey(steamGridDbKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _artStatus.value = ArtStatus.Working
            _artStatus.value = when (ArtworkRepository.validateKey(steamGridDbKey)) {
                is ArtworkRepository.ArtOutcome.KeyRejected ->
                    ArtStatus.Problem("That key was rejected. Check it on steamgriddb.com and paste it again.")
                is ArtworkRepository.ArtOutcome.Unreachable ->
                    ArtStatus.Problem("Could not reach steamgriddb.com. Check the network and try again.")
                is ArtworkRepository.ArtOutcome.NoKey ->
                    ArtStatus.Problem("Enter a key first.")
                else -> ArtStatus.Ok("Key accepted.")
            }
        }
    }

    fun clearArtStatus() { _artStatus.value = null }

    /**
     * Scans [dir] for game files. Also recurses one level into subdirectories,
     * using the subdirectory name as the game's display name (e.g. a folder
     * "Halo_Combat_Evolved/" containing a .xiso file appears as "Halo_Combat_Evolved").
     *
     * [displayNameOverride] is set when called for a subdirectory so the folder
     * name is used instead of the individual file's basename.
     */
    private fun scanDirectory(
        dir: DocumentFile,
        displayNameOverride: String?,
        found: MutableList<GameEntry>,
    ) {
        val files = dir.listFiles()
        val nameMap = files.associateBy { it.name?.lowercase() ?: "" }
        for (file in files) {
            val name = file.name ?: continue
            if (file.isDirectory && displayNameOverride == null) {
                // Recurse one level — use the subdirectory name as display name
                scanDirectory(file, displayNameOverride = name, found)
                continue
            }
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in GAME_EXTENSIONS) continue
            val base = name.substringBeforeLast('.')
            /*
             * Exact sidecar first. Failing that, a lone image in the folder is
             * almost certainly this game's cover — rips are commonly named
             * differently from the disc ("Halo_-_..._box_art.jpg" next to
             * "Halo - ....xiso.iso"), which the strict match always missed.
             */
            val coverUri = nameMap["$base.png"]?.uri
                ?: nameMap["$base.jpg"]?.uri
                ?: nameMap["$base.jpeg"]?.uri
                ?: files.singleOrNull { f ->
                    (f.name?.substringAfterLast('.', "")?.lowercase() ?: "") in IMAGE_EXTENSIONS
                }?.uri
            found += GameEntry(
                uri = file.uri,
                displayName = displayNameOverride ?: base,
                coverUri = coverUri,
            )
        }
    }

    /**
     * Parses each disc for its certificate title, title ID and embedded
     * `$$XTIMAGE`, caching the decoded image under the title ID. Entries are
     * updated one at a time so art appears as it is found rather than after
     * the slowest disc.
     *
     * Only reads a few KB per disc, but still off the main thread — these are
     * multi-GB files behind a content provider.
     */
    private fun resolveDiscInfo(ctx: Context) {
        for (game in _games.value) {
            val info = XisoReader.read(ctx, game.uri) ?: continue

            // A choice the user made explicitly outranks every other source.
            val custom = ArtworkRepository.customArtFor(ctx, info.titleId)
            var cover = if (custom != null) Uri.fromFile(custom) else game.coverUri
            var fromDisc = false
            if (cover == null) {
                // Network art is cached under the query used to fetch it, which
                // is the disc title — so it has to be re-checked here, once the
                // title is known, or a previous download is never reused.
                val netCache = ArtworkRepository.cachedFile(ctx, info.title)
                if (info.title.isNotEmpty() && netCache.exists()) {
                    cover = Uri.fromFile(netCache)
                }
            }
            if (cover == null) {
                val cacheFile = ArtworkRepository.discArtFile(ctx, info.titleId)
                val art = when {
                    cacheFile.exists() -> cacheFile
                    info.titleImage != null -> ArtworkRepository.saveBitmap(info.titleImage, cacheFile)
                    else -> null
                }
                if (art != null) {
                    cover = Uri.fromFile(art)
                    fromDisc = true
                }
            }

            _games.value = _games.value.map { g ->
                if (g.uri == game.uri) {
                    g.copy(
                        coverUri = cover,
                        discTitle = info.title.ifEmpty { null },
                        titleId = info.titleId,
                        coverIsDiscArt = fromDisc,
                    )
                } else g
            }
        }
    }

    /** Stores a user-picked image as [game]'s art. No-op if the disc had no title ID. */
    fun setCustomArt(game: GameEntry, src: Uri) {
        val titleId = game.titleId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val file = ArtworkRepository.importCustomArt(getApplication(), src, titleId) ?: return@launch
            _games.value = _games.value.map { g ->
                if (g.uri == game.uri) {
                    g.copy(coverUri = Uri.fromFile(file), coverIsDiscArt = false)
                } else g
            }
        }
    }

    /** Drops user-picked art for [game] and falls back to the other sources. */
    fun clearCustomArt(game: GameEntry) {
        val titleId = game.titleId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            ArtworkRepository.clearCustomArt(getApplication(), titleId)
            rescan()
        }
    }

    // ── Art fetching ──────────────────────────────────────────────────────────

    /**
     * Looks up art for everything still without a cover, then reports a single
     * summary. Individual failures are counted rather than announced, so one
     * unmatched title does not bury the result for the rest.
     */
    private suspend fun fetchMissingArt(ctx: Context, steamKey: String) {
        val missing = _games.value.filter { it.coverUri == null }
        if (missing.isEmpty()) {
            _artStatus.value = ArtStatus.Ok("Every game already has artwork.")
            return
        }

        var found = 0
        var unmatched = 0
        for (game in missing) {
            // Search with the disc's own title where we have it; a filename
            // like "Halo - Combat Evolved (USA) (Rev 2)" matches no real
            // catalogue entry.
            val query = game.discTitle ?: game.displayName
            when (val r = ArtworkRepository.fetchArt(ctx, query, steamKey)) {
                is ArtworkRepository.ArtOutcome.Found -> {
                    found++
                    _games.value = _games.value.map { g ->
                        if (g.uri == game.uri) {
                            g.copy(coverUri = Uri.fromFile(r.file), coverIsDiscArt = false)
                        } else g
                    }
                }
                is ArtworkRepository.ArtOutcome.NoMatch -> unmatched++
                // A bad key or a dead connection applies to every remaining
                // game, so stop rather than repeat the same failure N times.
                is ArtworkRepository.ArtOutcome.KeyRejected -> {
                    _artStatus.value = ArtStatus.Problem(
                        "That key was rejected. Check it on steamgriddb.com and paste it again.")
                    return
                }
                is ArtworkRepository.ArtOutcome.Unreachable -> {
                    _artStatus.value = ArtStatus.Problem(
                        "Could not reach steamgriddb.com. Check the network and try again.")
                    return
                }
                is ArtworkRepository.ArtOutcome.NoKey -> {
                    _artStatus.value = ArtStatus.Problem(
                        "No API key set, so only artwork from the discs is available.")
                    return
                }
            }
        }
        _artStatus.value = when {
            found == 0 -> ArtStatus.Problem(
                "No matches for $unmatched game${if (unmatched == 1) "" else "s"}. " +
                "Long-press a game to choose artwork yourself.")
            unmatched == 0 -> ArtStatus.Ok("Found artwork for $found.")
            else -> ArtStatus.Ok("Found artwork for $found; no match for $unmatched.")
        }
    }
}
