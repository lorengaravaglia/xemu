package com.boxxy.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController

@Composable
fun AudioSettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val voiceWorkers by settingsViewModel.voiceWorkers.collectAsState()

    SettingsSubScreenScaffold("Audio", navController) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // (HRTF 3D audio moved to Advanced settings.)

            // Voice Processing Threads
            SettingsSectionLabel("Voice Processing Threads")
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "How many threads process audio voices. Does not change how " +
                        "the audio sounds, only how the work is spread across cores. " +
                        "0 processes them on the audio thread with no locking " +
                        "overhead. Takes effect on next launch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val workerOptions = listOf(
                        0 to "0 (synchronous)",
                        1 to "1",
                        2 to "2 (default)",
                        3 to "3",
                        4 to "4",
                    )
                    SettingsDropdown(
                        value = workerOptions.first { it.first == voiceWorkers }.second,
                        options = workerOptions.map { it.second },
                        onSelect = { label ->
                            settingsViewModel.setVoiceWorkers(
                                workerOptions.first { it.second == label }.first
                            )
                        },
                    )
                }
            }
        }
    }
}
