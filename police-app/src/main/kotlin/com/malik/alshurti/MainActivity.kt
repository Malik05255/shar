package com.malik.alshurti

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import com.malik.alshurti.livev2.LivePoliceV2Screen

/**
 * V2 entry point.
 *
 * The legacy update/cinematic stack is deliberately not mounted. This branch exists to prove the
 * new audio-to-audio call architecture on a physical phone without any legacy state machine or
 * updater changing what the user is actually testing.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                LivePoliceV2Screen()
            }
        }
    }
}
