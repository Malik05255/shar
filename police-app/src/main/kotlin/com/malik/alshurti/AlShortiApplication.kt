package com.malik.alshurti

import android.app.Application
import ai.runanywhere.proto.v1.SDKEnvironment
import com.runanywhere.sdk.core.onnx.ONNX
import com.runanywhere.sdk.public.RunAnywhere
import kotlinx.coroutines.runBlocking

/** Initializes only the small local runtime; model downloads remain explicit user actions. */
class AlShortiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        runBlocking {
            ONNX.register()
        }
        RunAnywhere.initialize(
            context = this,
            environment = SDKEnvironment.SDK_ENVIRONMENT_DEVELOPMENT
        )
    }
}
