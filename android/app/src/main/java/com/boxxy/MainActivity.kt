package com.boxxy

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.boxxy.library.GameLibraryScreen
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.boxxy.emulation.EmulationActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.boxxy.library.LibraryViewModel
import com.boxxy.onboarding.SetupScreen
import com.boxxy.settings.*
import com.boxxy.ui.theme.XemuTheme

class MainActivity : ComponentActivity() {

    private val settingsViewModel: SettingsViewModel by viewModels()
    private val libraryViewModel: LibraryViewModel by viewModels()

    /*
     * Scripted launch, for automated measurement:
     *
     *   adb shell am start -n com.boxxy/.MainActivity --ez autolaunch true
     *   adb shell am start -n com.boxxy/.MainActivity --ez autolaunch true \
     *       --es game halo
     *
     * EmulationActivity is not exported, so it cannot be started from the
     * shell directly and should stay that way; MainActivity is the launcher
     * activity and already exported, so the hook lives here instead.  Without
     * this, every benchmark run needs a human to tap through the library,
     * which is both slow and a source of timing variation.
     *
     * "game" optionally picks a title by substring; otherwise the first entry
     * in the library is used.
     */
    private fun maybeAutoLaunch() {
        if (!intent.getBooleanExtra("autolaunch", false)) {
            return
        }
        val wanted = intent.getStringExtra("game")

        lifecycleScope.launch {
            val games = libraryViewModel.games.first { it.isNotEmpty() }
            val game = wanted?.let { w ->
                games.firstOrNull {
                    it.displayName.contains(w, ignoreCase = true) ||
                    it.uri.toString().contains(w, ignoreCase = true)
                }
            } ?: games.first()

            val mcpx = settingsViewModel.mcpxUri.value?.toString()
            val bios = settingsViewModel.biosUri.value?.toString()
            val hdd  = settingsViewModel.hddUri.value?.toString()
            if (mcpx == null || bios == null || hdd == null) {
                android.util.Log.w("xemu-android",
                    "autolaunch: system files not configured")
                return@launch
            }

            val useCustomDriver = settingsViewModel.driverEnabled.value &&
                settingsViewModel.driverDir.value.isNotEmpty() &&
                settingsViewModel.driverName.value.isNotEmpty()

            android.util.Log.i("xemu-android",
                "autolaunch: starting ${game.displayName}")
            startActivity(Intent(this@MainActivity, EmulationActivity::class.java).apply {
                putExtra("mcpx", mcpx)
                putExtra("bios", bios)
                putExtra("hdd", hdd)
                putExtra("iso", game.uri.toString())
                putExtra("renderer", settingsViewModel.renderer.value)
                putExtra("driverDir",
                    if (useCustomDriver) settingsViewModel.driverDir.value else "")
                putExtra("driverName",
                    if (useCustomDriver) settingsViewModel.driverName.value else "")
            })
        }
    }

    /**
     * Start the game a running emulator handed over when switching games
     * (EmulationActivity.switchToGame).  Its process must be gone first: QEMU
     * cannot start twice in one, and a new start while it lingers would be
     * routed straight back into it.
     */
    private fun maybeRelaunchGame(from: Intent) {
        @Suppress("DEPRECATION")
        val next = from.getParcelableExtra<Intent>(
            com.boxxy.emulation.EXTRA_RELAUNCH_GAME) ?: return
        val pid = from.getIntExtra(com.boxxy.emulation.EXTRA_RELAUNCH_WAIT_PID, 0)
        from.removeExtra(com.boxxy.emulation.EXTRA_RELAUNCH_GAME)
        val am = getSystemService(android.app.ActivityManager::class.java)
        lifecycleScope.launch {
            for (i in 0 until 50) {
                val alive = am.runningAppProcesses?.any { it.pid == pid } == true
                if (!alive) break
                kotlinx.coroutines.delay(100)
            }
            startActivity(next)
            /*
             * A frontend's game: this activity was only the handoff, so step
             * aside.  Exit from the game removes its task only when the game
             * is the task's root, and should land back in the frontend, not
             * in this library.
             */
            if (next.getBooleanExtra(com.boxxy.emulation.GameLaunch.EXTRA_EXTERNAL, false)) {
                finish()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeRelaunchGame(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRelaunchGame(intent)
        maybeAutoLaunch()
        enableEdgeToEdge()
        // Dark theme — force light (white) status bar icons
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = false
        setContent {
            XemuTheme {
                val navController = rememberNavController()
                val startDest = if (settingsViewModel.isSetupComplete) "library" else "setup"
                NavHost(navController = navController, startDestination = startDest) {
                    composable("setup") {
                        SetupScreen(navController, settingsViewModel)
                    }
                    composable("library") {
                        GameLibraryScreen(navController, libraryViewModel, settingsViewModel)
                    }
                    composable("settings") {
                        SettingsScreen(navController)
                    }
                    composable("settings/system") {
                        SystemSettingsScreen(navController, settingsViewModel, libraryViewModel)
                    }
                    composable("settings/graphics") {
                        GraphicsSettingsScreen(navController, settingsViewModel)
                    }
                    composable("settings/controls") {
                        ControlsSettingsScreen(navController, settingsViewModel)
                    }
                    composable("settings/audio") {
                        AudioSettingsScreen(navController)
                    }
                    composable("settings/overlay") {
                        OverlaySettingsScreen(navController, settingsViewModel)
                    }
                    composable("settings/advanced") {
                        AdvancedSettingsScreen(navController, settingsViewModel)
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Copies a content URI to a file in internal storage and returns the
         * absolute path. Used for small system files (MCPX, BIOS).
         * NOTE: Do NOT use this for large files (HDD, ISO) — use
         * openFileDescriptorPath() instead.
         */
        fun getRealFilePath(context: Context, uriString: String, fileName: String): String {
            val uri = Uri.parse(uriString)
            val file = java.io.File(context.filesDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Failed to open input stream for $uriString")
            return file.absolutePath
        }

        /**
         * Opens a content URI as a read-only file descriptor and returns the
         * /proc/self/fd/<n> path so QEMU can open it directly.
         * The returned ParcelFileDescriptor MUST be kept open for the lifetime
         * of emulation — close it in onDestroy().
         */
        fun openFileDescriptorPath(
            context: Context,
            uriString: String
        ): Pair<android.os.ParcelFileDescriptor, String> {
            val uri = Uri.parse(uriString)
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw Exception("Failed to open file descriptor for $uriString")
            return Pair(pfd, "/proc/self/fd/${pfd.fd}")
        }
    }
}
