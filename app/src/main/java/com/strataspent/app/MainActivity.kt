package com.strataspent.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.strataspent.app.navigation.StrataNavGraph
import com.strataspent.app.ui.theme.StrataSpentTheme

class MainActivity : AppCompatActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* user decides */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Opt into the modern edge-to-edge layout BEFORE super.onCreate so
        // the Activity's window decorations come up already configured for it.
        // This also replaces the deprecated Window.statusBarColor / navigationBarColor
        // setters that the platform removed in API 35.
        enableEdgeToEdge()
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        val locator = (application as StrataSpentApplication).locator

        setContent {
            StrataSpentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    StrataNavGraph(locator)
                }
            }
        }

        splash.setKeepOnScreenCondition { false }
        maybeRequestNotificationPermission()
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            if (checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(perm)
            }
        }
    }
}
