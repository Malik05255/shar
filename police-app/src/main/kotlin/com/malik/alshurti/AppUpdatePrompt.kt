package com.malik.alshurti

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
                title = { Text("يتوفر تحديث جديد") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "الإصدار ${state.versionName} جاهز. سيتم تنزيله والتحقق منه ثم يفتح Android شاشة تثبيت التحديث فوق النسخة الحالية.",
                            fontWeight = FontWeight.Medium
                        )
                        if (state.releaseNotes.isNotBlank()) {
                            Text(state.releaseNotes.take(600))
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onUpdateNow) { Text("تحديث الآن") }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("لاحقًا") }
                }
            )
        }

        is AppUpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("جاري تحميل التحديث") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("الإصدار ${state.versionName} — ${state.progressPercent}%")
                        LinearProgressIndicator(
                            progress = { state.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("لا تغلق التطبيق حتى يكتمل التحقق من ملف التحديث.")
                    }
                },
                confirmButton = {}
            )
        }

        is AppUpdateState.PermissionRequired -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("السماح بتثبيت التحديث") },
                text = {
                    Text(
                        "Android يحتاج سماحًا مرة واحدة لتطبيق الشرطي بتثبيت ملف التحديث. بعد السماح ارجع للتطبيق وسيكمل تلقائيًا."
                    )
                },
                confirmButton = {
                    TextButton(onClick = onRetry) { Text("فتح الإعدادات") }
                }
            )
        }

        is AppUpdateState.Installing -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("التحديث جاهز للتثبيت") },
                text = {
                    Text("أكمل تأكيد Android لتثبيت الإصدار ${state.versionName} فوق النسخة الحالية.")
                },
                confirmButton = {}
            )
        }

        is AppUpdateState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("تعذر تحديث التطبيق") },
                text = { Text(state.message) },
                confirmButton = {
                    if (state.retryable) {
                        TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("إغلاق") }
                }
            )
        }
    }
}
