package com.xemu

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.xemu.emulation.EmulationActivity

class MainActivity : AppCompatActivity() {

    private var mcpxUri: Uri? = null
    private var biosUri: Uri? = null
    private var hddUri: Uri? = null
    private var isoUri: Uri? = null  // optional — null = boot to dashboard

    private lateinit var statusText: TextView
    private lateinit var btnIso: Button

    private val prefs by lazy { getSharedPreferences("main_prefs", Context.MODE_PRIVATE) }

    private fun saveUris() {
        prefs.edit()
            .putString("mcpx_uri", mcpxUri?.toString())
            .putString("bios_uri", biosUri?.toString())
            .putString("hdd_uri",  hddUri?.toString())
            .putString("iso_uri",  isoUri?.toString())
            .apply()
    }

    private fun loadUris() {
        mcpxUri = prefs.getString("mcpx_uri", null)?.let { Uri.parse(it) }
        biosUri = prefs.getString("bios_uri", null)?.let { Uri.parse(it) }
        hddUri  = prefs.getString("hdd_uri",  null)?.let { Uri.parse(it) }
        isoUri  = prefs.getString("iso_uri",  null)?.let { Uri.parse(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadUris()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
        }

        statusText = TextView(this).apply {
            text = "Status: Please select system files"
            setPadding(0, 0, 0, 32)
        }
        layout.addView(statusText)

        layout.addView(Button(this).apply {
            text = "Select MCPX Boot ROM"
            setOnClickListener { pickMcpx.launch(arrayOf("*/*")) }
        })
        layout.addView(Button(this).apply {
            text = "Select Xbox BIOS (Flash)"
            setOnClickListener { pickBios.launch(arrayOf("*/*")) }
        })
        layout.addView(Button(this).apply {
            text = "Select Hard Drive Image"
            setOnClickListener { pickHdd.launch(arrayOf("*/*")) }
        })

        btnIso = Button(this).apply {
            text = "Select Game Disc (ISO) — optional"
            setOnClickListener { pickIso.launch(arrayOf("*/*")) }
        }
        layout.addView(btnIso)

        layout.addView(Button(this).apply {
            text = "Clear Disc (boot to dashboard)"
            setOnClickListener {
                isoUri = null; saveUris()
                btnIso.text = "Select Game Disc (ISO) — optional"
                updateStatus()
            }
        })

        layout.addView(Button(this).apply {
            text = "START EMULATION"
            setPadding(0, 64, 0, 0)
            setOnClickListener {
                if (mcpxUri != null && biosUri != null && hddUri != null) {
                    val intent = Intent(this@MainActivity, EmulationActivity::class.java).apply {
                        putExtra("mcpx", mcpxUri.toString())
                        putExtra("bios", biosUri.toString())
                        putExtra("hdd", hddUri.toString())
                        putExtra("iso", isoUri?.toString() ?: "")
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this@MainActivity, "Please select MCPX, BIOS, and HDD first!", Toast.LENGTH_SHORT).show()
                }
            }
        })

        setContentView(layout)

        // Restore ISO button label if a disc was previously selected
        isoUri?.let { uri ->
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
            btnIso.text = "Disc: $name"
        }
        updateStatus()
    }

    private val pickMcpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            mcpxUri = it; saveUris(); updateStatus()
        }
    }

    private val pickBios = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            biosUri = it; saveUris(); updateStatus()
        }
    }

    private val pickHdd = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            hddUri = it; saveUris(); updateStatus()
        }
    }

    private val pickIso = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            isoUri = it; saveUris()
            val name = it.lastPathSegment?.substringAfterLast('/') ?: it.toString()
            btnIso.text = "Disc: $name"
            updateStatus()
        }
    }

    private fun updateStatus() {
        val mcpx = if (mcpxUri != null) "OK" else "Missing"
        val bios = if (biosUri != null) "OK" else "Missing"
        val hdd  = if (hddUri  != null) "OK" else "Missing"
        val iso  = if (isoUri  != null) "Set" else "None (dashboard)"
        statusText.text = "MCPX: $mcpx | BIOS: $bios | HDD: $hdd | Disc: $iso"
    }

    companion object {
        /**
         * Copies a content URI to a file in internal storage and returns the
         * absolute path.  Used for small system files (MCPX, BIOS).
         * NOTE: Do NOT use this for large files (HDD, ISO) — use
         * getFileDescriptorPath() instead.
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
