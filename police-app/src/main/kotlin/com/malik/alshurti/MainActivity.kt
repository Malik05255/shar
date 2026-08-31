package com.malik.alshurti

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private lateinit var appUpdateManager: AppUpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        appUpdateManager = AppUpdateManager(this)

        setContent {
            val updateState = appUpdateManager.state.collectAsStateWithLifecycle().value

            // A confirmed newer stable release is mandatory: start the delta transfer immediately.
            // Idle/Checking never render a window, so normal launches remain visually untouched.
            LaunchedEffect(updateState) {
                if (updateState is AppUpdateState.Available) {
                    appUpdateManager.startUpdate()
                }
            }

            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    PoliceCallScreen()
                    AppUpdatePrompt(
                        state = updateState,
                        onRetry = appUpdateManager::retry
                    )
                }
            }
        }

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
