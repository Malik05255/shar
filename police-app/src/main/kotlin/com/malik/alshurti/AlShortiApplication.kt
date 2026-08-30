package com.malik.alshurti

import android.app.Application
import ai.runanywhere.proto.v1.SDKEnvironment
import com.runanywhere.sdk.core.onnx.ONNX
import com.runanywhere.sdk.public.RunAnywhere

/** Initializes the on-device speech runtime once for the whole application. */
class AlShortiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ONNX.register()
        RunAnywhere.initialize(
            context = this,
            environment = SDKEnvironment.SDK_ENVIRONMENT_DEVELOPMENT
        )
    }
}
