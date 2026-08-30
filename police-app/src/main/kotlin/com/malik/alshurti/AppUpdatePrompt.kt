package com.malik.alshurti

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Update UX intentionally contains no written copy. The main product is an audio/cinematic
 * experience; update state is expressed through familiar icons and a progress bar. Android's
 * package installer remains responsible for its own system confirmation UI.
 */
@Composable
fun AppUpdatePrompt(
    state: AppUpdateState,
    onUpdateNow: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        AppUpdateState.Idle,
        AppUpdateState.Checking -> Unit

        is AppUpdateState.Available -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = { Icon(Icons.Default.Download, contentDescription = null) },
                title = null,
                text = null,
                confirmButton = {
                    IconButton(onClick = onUpdateNow) {
                        Icon(Icons.Default.Download, contentDescription = null)
                    }
                },
                dismissButton = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
            )
        }

        is AppUpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                icon = { Icon(Icons.Default.Download, contentDescription = null) },
                title = null,
                text = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        LinearProgressIndicator(
                            progress = { state.progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        )
                    }
                },
                confirmButton = {}
            )
        }

        is AppUpdateState.PermissionRequired -> {
            AlertDialog(
                onDismissRequest = {},
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                title = null,
                text = null,
                confirmButton = {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    }
                }
            )
        }

        is AppUpdateState.Installing -> {
            AlertDialog(
                onDismissRequest = {},
                icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                title = null,
                text = null,
                confirmButton = {}
            )
        }

        is AppUpdateState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = { Icon(Icons.Default.Warning, contentDescription = null) },
                title = null,
                text = null,
                confirmButton = {
                    if (state.retryable) {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                        }
                    }
                },
                dismissButton = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
            )
        }
    }
}
