package com.malik.alshurti

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private lateinit var appUpdateManager: AppUpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appUpdateManager = AppUpdateManager(this)

        setContent {
            val updateState = appUpdateManager.state.collectAsStateWithLifecycle().value

            MaterialTheme {
                PoliceCallScreen()
                AppUpdatePrompt(
                    state = updateState,
                    onUpdateNow = appUpdateManager::startUpdate,
                    onRetry = appUpdateManager::retry,
                    onDismiss = appUpdateManager::dismissForThisSession
                )
            }
        }

        // Every real app launch checks GitHub Releases once. Network/check failures stay silent so
        // the child can always use the core call experience even when GitHub is unavailable.
        appUpdateManager.checkForUpdates()
    }

    override fun onResume() {
        super.onResume()
        if (::appUpdateManager.isInitialized) {
            appUpdateManager.onActivityResumed()
        }
    }

    override fun onDestroy() {
        if (::appUpdateManager.isInitialized) {
            appUpdateManager.release()
        }
        super.onDestroy()
    }
}
