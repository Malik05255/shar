package com.malik.alshurti

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import kotlin.math.absoluteValue
import kotlin.math.max

/**
 * Texture-backed muted cinematic player for exact-quality local-cache or CDN sources.
 *
 * A completed clip never owns scene timing. Completion is reported to the stage immediately so the
 * living-office director can move to a different continuation instead of exposing a frozen frame.
 */
class CinematicClipView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private data class ClipConfig(
        val source: String,
        val randomizeStart: Boolean,
        val seed: Long
    )

    private var config: ClipConfig? = null
    private var completionCallback: (() -> Unit)? = null
    private var mediaPlayer: MediaPlayer? = null
    private var mediaSurface: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var hasRenderedFrame = false

    init {
        surfaceTextureListener = this
        isOpaque = false
        alpha = 0f
    }

    fun bind(
        source: String,
        randomizeStart: Boolean,
        seed: Long,
        onCompletion: (() -> Unit)? = null
    ) {
        completionCallback = onCompletion
        val next = ClipConfig(source, randomizeStart, seed)
        if (config == next && mediaPlayer != null) return
        config = next
        if (isAvailable) startConfiguredClip()
    }

    private fun startConfiguredClip() {
        val active = config ?: return
        val texture = surfaceTexture ?: return

        releasePlayer()
        if (!hasRenderedFrame) alpha = 0f

        runCatching {
            val surface = Surface(texture)
            mediaSurface = surface

            val player = MediaPlayer().also { mediaPlayer = it }
            player.setSurface(surface)
            player.setDataSource(context, Uri.parse(active.source))
            player.isLooping = false
            // Picture and dialogue are deliberately decoupled. Recorded Foley and the selected
            // speech engine own audio; old baked clip audio is never allowed to double the scene.
            player.setVolume(0f, 0f)

            player.setOnVideoSizeChangedListener { _, width, height ->
                videoWidth = width
                videoHeight = height
                applyCenterCropTransform()
            }

            player.setOnPreparedListener { prepared ->
                runCatching {
                    val durationMs = prepared.duration.toLong().coerceAtLeast(0L)
                    if (active.randomizeStart && durationMs > 2_800L) {
                        // Reusable talk/idle footage can safely start across most of the take. Keep
                        // at least the final 1.8 s so MediaPlayer still has time to establish motion
                        // before handing off to the next beat.
                        val maxStartMs = minOf(
                            (durationMs * 0.62f).toLong(),
                            (durationMs - 1_800L).coerceAtLeast(0L)
                        )
                        val raw = (active.seed xor (active.source.hashCode().toLong() shl 17)).absoluteValue
                        val offset = if (maxStartMs > 0L) raw % (maxStartMs + 1L) else 0L
                        prepared.setOnSeekCompleteListener { seeked -> runCatching { seeked.start() } }
                        prepared.seekTo(offset, MediaPlayer.SEEK_CLOSEST_SYNC)
                    } else {
                        prepared.start()
                    }
                }.onFailure {
                    if (!hasRenderedFrame) alpha = 0f
                }
            }

            player.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    hasRenderedFrame = true
                    animate().cancel()
                    if (alpha < 1f) animate().alpha(1f).setDuration(90L).start()
                }
                false
            }

            player.setOnCompletionListener { completed ->
                runCatching { completed.setOnSeekCompleteListener(null) }
                post { completionCallback?.invoke() }
            }

            player.setOnErrorListener { _, _, _ ->
                if (!hasRenderedFrame) alpha = 0f
                releasePlayer()
                post { completionCallback?.invoke() }
                true
            }

            player.prepareAsync()
        }.onFailure {
            if (!hasRenderedFrame) alpha = 0f
            releasePlayer()
            post { completionCallback?.invoke() }
        }
    }

    private fun applyCenterCropTransform() {
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return

        val sourceScaleX = width.toFloat() / videoWidth.toFloat()
        val sourceScaleY = height.toFloat() / videoHeight.toFloat()
        val uniformScale = max(sourceScaleX, sourceScaleY)
        val correctionX = uniformScale / sourceScaleX
        val correctionY = uniformScale / sourceScaleY

        setTransform(Matrix().apply {
            setScale(correctionX, correctionY, width / 2f, height / 2f)
        })
    }

    fun releasePlayback() {
        animate().cancel()
        releasePlayer()
        hasRenderedFrame = false
        alpha = 0f
    }

    private fun releasePlayer() {
        runCatching { mediaPlayer?.setOnSeekCompleteListener(null) }
        runCatching { mediaPlayer?.setOnPreparedListener(null) }
        runCatching { mediaPlayer?.setOnInfoListener(null) }
        runCatching { mediaPlayer?.setOnCompletionListener(null) }
        runCatching { mediaPlayer?.setOnErrorListener(null) }
        runCatching { mediaPlayer?.setOnVideoSizeChangedListener(null) }
        runCatching { mediaPlayer?.setSurface(null) }
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.reset() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { mediaSurface?.release() }
        mediaSurface = null
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        startConfiguredClip()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        applyCenterCropTransform()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releasePlayback()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        releasePlayback()
        super.onDetachedFromWindow()
    }
}
