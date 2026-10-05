package com.boxxy.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.boxxy.emulation.GameSettings

/**
 * Per-game settings (A4).  Each row is "Default" -- the global setting,
 * named so it is clear what that means today -- or an override that applies
 * to this game only.  Takes effect the next time the game starts.
 */
@Composable
fun GameSettingsScreen(navController: NavController, gameKey: String, gameName: String) {
    val context = LocalContext.current
    /* Bumped on every change so the rows re-read their stored values. */
    var version by remember { mutableIntStateOf(0) }
    val global = remember { GameSettings.main(context) }

    SettingsSubScreenScaffold(gameName, navController) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Settings for this game only. “Default” follows your " +
                "global settings. Changes apply the next time the game starts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            GameSettings.SPECS.groupBy { it.section }.forEach { (section, specs) ->
                SettingsSectionLabel(section)
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp),
                           verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        specs.forEach { spec ->
                            key(spec.prefKey, version) {
                                val current = GameSettings.override(context, gameKey, spec.prefKey)
                                val globalLabel = spec.choices
                                    .firstOrNull { it.first == spec.global(global) }?.second ?: "?"
                                val defaultLabel = "Default ($globalLabel)"
                                val options = listOf(null to defaultLabel) + spec.choices
                                Text(spec.label, style = MaterialTheme.typography.bodyMedium)
                                SettingsDropdown(
                                    value = options.firstOrNull { it.first == current }?.second
                                        ?: defaultLabel,
                                    options = options.map { it.second },
                                    onSelect = { label ->
                                        val v = options.first { it.second == label }.first
                                        GameSettings.setOverride(context, gameKey, spec.prefKey, v)
                                        version++
                                    },
                                )
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { GameSettings.clearAll(context, gameKey); version++ },
                enabled = remember(version) { GameSettings.overrideCount(context, gameKey) > 0 },
            ) { Text("Reset to defaults") }
            Spacer(Modifier.height(8.dp))
        }
    }
}
