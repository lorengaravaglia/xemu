package com.xemu.onboarding

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.xemu.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(navController: NavController, settingsViewModel: SettingsViewModel) {
    val context = LocalContext.current
    val mcpx by settingsViewModel.mcpxUri.collectAsState()
    val bios by settingsViewModel.biosUri.collectAsState()
    val hdd  by settingsViewModel.hddUri.collectAsState()

    val pickMcpx = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settingsViewModel.setMcpx(it)
        }
    }
    val pickBios = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settingsViewModel.setBios(it)
        }
    }
    val pickHdd = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            settingsViewModel.setHdd(it)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("First-time Setup") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Select the required Xbox system files to begin.",
                style = MaterialTheme.typography.bodyLarge,
            )

            SystemFilePicker(
                label = "MCPX Boot ROM",
                uri = mcpx,
                onPick = { pickMcpx.launch(arrayOf("*/*")) },
            )
            SystemFilePicker(
                label = "Xbox BIOS (Flash)",
                uri = bios,
                onPick = { pickBios.launch(arrayOf("*/*")) },
            )
            SystemFilePicker(
                label = "Hard Drive Image (.qcow2)",
                uri = hdd,
                onPick = { pickHdd.launch(arrayOf("*/*")) },
            )

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { navController.navigate("library") { popUpTo("setup") { inclusive = true } } },
                enabled = mcpx != null && bios != null && hdd != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Continue")
            }
        }
    }
}

@Composable
private fun SystemFilePicker(label: String, uri: android.net.Uri?, onPick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                if (uri != null) {
                    Text(
                        uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text("Not set", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            Button(onClick = onPick) { Text(if (uri != null) "Change" else "Select") }
        }
    }
}
