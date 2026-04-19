package com.xemu

import android.app.Application

class XemuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
       // Global initialization (logging, settings paths, etc.) can go here
    }

    companion object {
        init {
            try {
                System.loadLibrary("slirp")
                System.loadLibrary("xemu")
            } catch (e: Throwable) {
                android.util.Log.e("xemu-app", "Failed to load native libraries", e)
            }
        }
    }
}
