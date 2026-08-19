package com.xemu.settings

import com.xemu.NativeInterface

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("main_prefs", Context.MODE_PRIVATE)

    // Separate encrypted store for sensitive values (API keys, credentials).
    // Uses Android Keystore-backed AES-256-GCM; only SettingsViewModel reads/writes this file.
    private val securePrefs = EncryptedSharedPreferences.create(
        app,
        "secure_prefs",
        MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _mcpxUri = MutableStateFlow(prefs.getString("mcpx_uri", null)?.let { Uri.parse(it) })
    val mcpxUri: StateFlow<Uri?> = _mcpxUri

    private val _biosUri = MutableStateFlow(prefs.getString("bios_uri", null)?.let { Uri.parse(it) })
    val biosUri: StateFlow<Uri?> = _biosUri

    private val _hddUri = MutableStateFlow(prefs.getString("hdd_uri", null)?.let { Uri.parse(it) })
    val hddUri: StateFlow<Uri?> = _hddUri

    // "VULKAN" or "OPENGL"; default is VULKAN (best for Adreno)
    private val _renderer = MutableStateFlow(prefs.getString("renderer", "VULKAN") ?: "VULKAN")
    val renderer: StateFlow<String> = _renderer

    val isSetupComplete: Boolean
        get() = _mcpxUri.value != null && _biosUri.value != null && _hddUri.value != null

    fun setMcpx(uri: Uri) {
        _mcpxUri.value = uri
        prefs.edit().putString("mcpx_uri", uri.toString()).apply()
    }

    fun setBios(uri: Uri) {
        _biosUri.value = uri
        prefs.edit().putString("bios_uri", uri.toString()).apply()
    }

    fun setHdd(uri: Uri) {
        _hddUri.value = uri
        prefs.edit().putString("hdd_uri", uri.toString()).apply()
    }

    fun setRenderer(value: String) {
        _renderer.value = value
        prefs.edit().putString("renderer", value).apply()
    }

    // ── Box art ───────────────────────────────────────────────────────────────

    /** API key from https://www.steamgriddb.com/profile/preferences */
    private val _steamGridDbKey = MutableStateFlow(securePrefs.getString("steamgriddb_key", "") ?: "")
    val steamGridDbKey: StateFlow<String> = _steamGridDbKey

    fun setSteamGridDbKey(value: String) {
        _steamGridDbKey.value = value
        securePrefs.edit().putString("steamgriddb_key", value).apply()
    }

    // TODO: ScreenScraper (screenscraper.fr) — username + password, system ID 15 for Xbox
    // TODO: ScreenScraper credentials should also use securePrefs

    // ── Display settings ──────────────────────────────────────────────────────

    /** 0=Native, 1=Auto, 2=4:3, 3=16:9 (default) */
    private val _aspectRatio = MutableStateFlow(prefs.getInt("aspect_ratio", 3))
    val aspectRatio: StateFlow<Int> = _aspectRatio

    /** 1=1× (native), 2=2×, 3=3× */
    private val _surfaceScale = MutableStateFlow(prefs.getInt("surface_scale", 1))
    val surfaceScale: StateFlow<Int> = _surfaceScale

    /** false=Linear (default, smooth), true=Nearest (sharp/pixel-art) */
    private val _filterNearest = MutableStateFlow(prefs.getBoolean("filter_nearest", false))
    val filterNearest: StateFlow<Boolean> = _filterNearest

    fun setAspectRatio(value: Int) {
        _aspectRatio.value = value
        prefs.edit().putInt("aspect_ratio", value).apply()
    }

    fun setSurfaceScale(value: Int) {
        _surfaceScale.value = value
        prefs.edit().putInt("surface_scale", value).apply()
    }

    fun setFilterNearest(value: Boolean) {
        _filterNearest.value = value
        prefs.edit().putBoolean("filter_nearest", value).apply()
    }

    // ── Audio ─────────────────────────────────────────────────────────────────

    private val _hrtf = MutableStateFlow(prefs.getBoolean("audio_hrtf", false))
    val hrtf: StateFlow<Boolean> = _hrtf

    /** Applies immediately: the APU re-reads this every audio frame. */
    fun setHrtf(value: Boolean) {
        _hrtf.value = value
        prefs.edit().putBoolean("audio_hrtf", value).apply()
        NativeInterface.setHrtf(value)
    }

    private val _voiceWorkers = MutableStateFlow(prefs.getInt("audio_voice_workers", 2))
    val voiceWorkers: StateFlow<Int> = _voiceWorkers

    /** Read once when the APU starts, so this only takes effect on next launch. */
    fun setVoiceWorkers(value: Int) {
        _voiceWorkers.value = value
        prefs.edit().putInt("audio_voice_workers", value).apply()
    }

    // ── Custom Vulkan driver (adrenotools) ────────────────────────────────────

    private val _driverEnabled = MutableStateFlow(prefs.getBoolean("driver_enabled", false))
    val driverEnabled: StateFlow<Boolean> = _driverEnabled

    private val _driverDir = MutableStateFlow(prefs.getString("driver_dir", "") ?: "")
    val driverDir: StateFlow<String> = _driverDir

    private val _driverName = MutableStateFlow(prefs.getString("driver_name", "") ?: "")
    val driverName: StateFlow<String> = _driverName

    /** Display label for the installed driver (parsed from meta.json), or empty string. */
    private val _driverLabel = MutableStateFlow(prefs.getString("driver_label", "") ?: "")
    val driverLabel: StateFlow<String> = _driverLabel

    fun setDriverEnabled(enabled: Boolean) {
        _driverEnabled.value = enabled
        prefs.edit().putBoolean("driver_enabled", enabled).apply()
    }

    /**
     * Extract a driver ZIP, parse meta.json, and persist driver path/name.
     * The ZIP must contain a meta.json with at least a "libraryName" field and
     * one or more .so files. Extraction target: filesDir/driver/.
     *
     * Returns the driver display name on success, or throws on failure.
     */
    fun installDriver(context: Context, zipUri: Uri): String {
        val destDir = File(context.filesDir, "driver").also {
            it.deleteRecursively()
            it.mkdirs()
        }

        var metaJson: String? = null

        context.contentResolver.openInputStream(zipUri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = File(entry.name).name  // strip any path prefix
                    if (name == "meta.json") {
                        metaJson = zip.readBytes().toString(Charsets.UTF_8)
                    } else if (name.endsWith(".so")) {
                        File(destDir, name).outputStream().use { out ->
                            zip.copyTo(out)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error("Cannot open ZIP")

        val meta = JSONObject(metaJson ?: error("meta.json not found in ZIP"))
        val libName = meta.getString("libraryName")
        val label = meta.optString("name", libName)

        require(File(destDir, libName).exists()) {
            "Driver library '$libName' not found in ZIP"
        }

        val dirPath = destDir.absolutePath + "/"
        _driverDir.value = dirPath
        _driverName.value = libName
        _driverLabel.value = label
        _driverEnabled.value = true
        prefs.edit()
            .putString("driver_dir", dirPath)
            .putString("driver_name", libName)
            .putString("driver_label", label)
            .putBoolean("driver_enabled", true)
            .apply()

        return label
    }

    // ── Controller overlay visibility ─────────────────────────────────────────

    /** "AUTO", "ALWAYS_SHOW", or "ALWAYS_HIDE" */
    private val _overlayMode = MutableStateFlow(
        prefs.getString("overlay_mode", "AUTO") ?: "AUTO"
    )
    val overlayMode: StateFlow<String> = _overlayMode

    fun setOverlayMode(value: String) {
        _overlayMode.value = value
        prefs.edit().putString("overlay_mode", value).apply()
    }

    // ── Performance overlay (advanced) ────────────────────────────────────────

    /** Corner where the overlay is anchored: "TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT" */
    private val _overlayPosition = MutableStateFlow(
        prefs.getString("overlay_position", "TOP_LEFT") ?: "TOP_LEFT"
    )
    val overlayPosition: StateFlow<String> = _overlayPosition

    private val _overlayShowFps = MutableStateFlow(prefs.getBoolean("overlay_show_fps", true))
    val overlayShowFps: StateFlow<Boolean> = _overlayShowFps

    private val _overlayShowFrametime = MutableStateFlow(prefs.getBoolean("overlay_show_frametime", false))
    val overlayShowFrametime: StateFlow<Boolean> = _overlayShowFrametime

    private val _overlayShowMemory = MutableStateFlow(prefs.getBoolean("overlay_show_memory", false))
    val overlayShowMemory: StateFlow<Boolean> = _overlayShowMemory

    private val _overlayShowShaders = MutableStateFlow(prefs.getBoolean("overlay_show_shaders", false))
    val overlayShowShaders: StateFlow<Boolean> = _overlayShowShaders

    fun setOverlayPosition(value: String) {
        _overlayPosition.value = value
        prefs.edit().putString("overlay_position", value).apply()
    }
    fun setOverlayShowFps(value: Boolean) {
        _overlayShowFps.value = value
        prefs.edit().putBoolean("overlay_show_fps", value).apply()
    }
    fun setOverlayShowFrametime(value: Boolean) {
        _overlayShowFrametime.value = value
        prefs.edit().putBoolean("overlay_show_frametime", value).apply()
    }
    fun setOverlayShowMemory(value: Boolean) {
        _overlayShowMemory.value = value
        prefs.edit().putBoolean("overlay_show_memory", value).apply()
    }
    fun setOverlayShowShaders(value: Boolean) {
        _overlayShowShaders.value = value
        prefs.edit().putBoolean("overlay_show_shaders", value).apply()
    }

    /** Remove the installed custom driver and revert to the system Vulkan loader. */
    fun clearDriver(context: Context) {
        File(context.filesDir, "driver").deleteRecursively()
        _driverDir.value = ""
        _driverName.value = ""
        _driverLabel.value = ""
        _driverEnabled.value = false
        prefs.edit()
            .remove("driver_dir")
            .remove("driver_name")
            .remove("driver_label")
            .putBoolean("driver_enabled", false)
            .apply()
    }
}
