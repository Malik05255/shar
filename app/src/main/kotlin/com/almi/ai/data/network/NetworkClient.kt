package com.almi.ai.data.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkClient @Inject constructor() {
    private val client: HttpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = TIMEOUT_MS
                connectTimeoutMillis = TIMEOUT_MS
                socketTimeoutMillis = TIMEOUT_MS
            }
        }
    }

    operator fun invoke(): HttpClient = client

    companion object {
        private const val TIMEOUT_MS = 5 * 60 * 1000L
    }
}
