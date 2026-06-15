package com.xemu.library

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

private val GAME_EXTENSIONS = setOf("iso")

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)

    private val _games = MutableStateFlow<List<GameEntry>>(emptyList())
    val games: StateFlow<List<GameEntry>> = _games

    private val _dirs = MutableStateFlow<List<Uri>>(emptyList())
    val dirs: StateFlow<List<Uri>> = _dirs

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

    fun addDirectory(uri: Uri) {
        if (_dirs.value.none { it == uri }) {
            _dirs.value = _dirs.value + uri
            saveDirs()
            rescan()
        }
    }

    fun removeDirectory(uri: Uri) {
        _dirs.value = _dirs.value.filter { it != uri }
        saveDirs()
        rescan()
    }

    fun rescan() {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val found = mutableListOf<GameEntry>()
            for (dirUri in _dirs.value) {
                val dir = DocumentFile.fromTreeUri(ctx, dirUri) ?: continue
                if (!dir.canRead()) continue
                scanDirectory(dir, displayNameOverride = null, found)
            }
            found.sortBy { it.displayName.lowercase() }
            _games.value = found
        }
    }

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
            val coverUri = nameMap["$base.png"]?.uri
                ?: nameMap["$base.jpg"]?.uri
                ?: nameMap["$base.jpeg"]?.uri
            found += GameEntry(
                uri = file.uri,
                displayName = displayNameOverride ?: base,
                coverUri = coverUri,
            )
        }
    }
}
