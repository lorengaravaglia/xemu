package com.boxxy

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.widget.Toast
import com.boxxy.emulation.GameLaunch

/**
 * Entry point for frontends such as ES-DE: ACTION_VIEW with the disc image as
 * a content URI (ES-DE's %ROMSAF%), with read access granted on the intent.
 *
 * A trampoline rather than an exported EmulationActivity.  EmulationActivity
 * takes the system files as extras too, and must not accept those from other
 * apps; here only the disc comes from outside, and the rest is filled in from
 * Boxxy's own settings exactly as the library does it.
 *
 * Three details matter on a dual-screen handheld and with frontends:
 *  - The game opens on the main display.  ES-DE on the Thor runs on the lower
 *    screen, and an activity starts on the display it was launched from.
 *  - The disc's read grant belongs to this activity's intent, so it is passed
 *    on (data + FLAG_GRANT_READ_URI_PERMISSION) to the activity that opens it.
 *  - Exit returns to the frontend, not to Boxxy's library (EXTRA_EXTERNAL).
 *
 * Never shown: Theme.NoDisplay, finishing before onResume.
 */
class ExternalLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            Log.w("xemu-android", "external launch: no disc in the intent")
            finish()
            return
        }

        val launch = GameLaunch.intentFor(this, uri.toString())
        if (launch == null) {
            Toast.makeText(this, "Set up Boxxy's system files first",
                           Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        launch.data = uri
        launch.putExtra(GameLaunch.EXTRA_EXTERNAL, true)
        launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK)

        val options = ActivityOptions.makeBasic()
            .setLaunchDisplayId(Display.DEFAULT_DISPLAY)
        Log.i("xemu-android", "external launch: $uri")
        try {
            startActivity(launch, options.toBundle())
        } catch (e: SecurityException) {
            Log.w("xemu-android", "external launch: cannot open the disc", e)
            Toast.makeText(this, "Boxxy was not given access to that disc",
                           Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
