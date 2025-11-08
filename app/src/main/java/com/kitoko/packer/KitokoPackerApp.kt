package com.kitoko.packer

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class KitokoPackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ensureFirebase()
    }

    private fun ensureFirebase() {
        try {
            val app = FirebaseApp.initializeApp(this)
            if (app == null && FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(
                    this,
                    FirebaseOptions.Builder()
                        .setApplicationId("1:000000000000:android:kitokopacker")
                        .setProjectId("kitoko-packer-demo")
                        .setApiKey("demo-api-key")
                        .build()
                )
            }
        } catch (_: IllegalStateException) {
            // Already initialized.
        }
    }
}
