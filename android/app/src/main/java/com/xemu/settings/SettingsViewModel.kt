package com.xemu.settings

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("main_prefs", Context.MODE_PRIVATE)

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
