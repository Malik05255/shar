package com.malik.alshurti

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RawRes
import kotlin.math.absoluteValue
import kotlin.math.max

/**
 * Texture-backed, muted cinematic clip player.
 *
 * Continuous clips are pre-built as forward+reverse cycles, so MediaPlayer can loop them without
 * exposing the generated clip's hard end -> beginning cut. Physical actions remain one-shot and
 * hold their final rendered frame until the next action arrives.
 */
class CinematicClipView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private data class ClipConfig(
        @param:RawRes val resId: Int,
        val randomizeStart: Boolean,
        val seed: Long,
        val continuous: Boolean
    )

    private var config: ClipConfig? = null
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
        @RawRes resId: Int,
        randomizeStart: Boolean,
        seed: Long,
        continuous: Boolean
    ) {
        val next = ClipConfig(resId, randomizeStart, seed, continuous)
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
            player.setDataSource(
                context,
                Uri.parse("android.resource://${context.packageName}/${active.resId}")
            )
            player.isLooping = active.continuous
            player.setVolume(0f, 0f)

            player.setOnVideoSizeChangedListener { _, width, height ->
                videoWidth = width
                videoHeight = height
                applyCenterCropTransform()
            }

            player.setOnPreparedListener { prepared ->
                runCatching {
                    val durationMs = prepared.duration.toLong().coerceAtLeast(0L)
                    if (active.randomizeStart && durationMs > 2_200L) {
                        val maxStartMs = minOf(
                            (durationMs * 0.25f).toLong(),
                            (durationMs - 1_800L).coerceAtLeast(0L)
                        )
                        val raw = (active.seed xor (active.resId.toLong() shl 17)).absoluteValue
                        val offset = if (maxStartMs > 0L) raw % (maxStartMs + 1L) else 0L
                        prepared.setOnSeekCompleteListener { seeked ->
                            runCatching { seeked.start() }
                        }
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
                    if (alpha < 1f) animate().alpha(1f).setDuration(120L).start()
                }
                false
            }

            player.setOnCompletionListener { completed ->
                runCatching { completed.setOnSeekCompleteListener(null) }
            }

            player.setOnErrorListener { _, _, _ ->
                if (!hasRenderedFrame) alpha = 0f
                releasePlayer()
                true
            }

            player.prepareAsync()
        }.onFailure {
            if (!hasRenderedFrame) alpha = 0f
            releasePlayer()
        }
    }

    private fun applyCenterCropTransform() {
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) return

        val sourceScaleX = width.toFloat() / videoWidth.toFloat()
        val sourceScaleY = height.toFloat() / videoHeight.toFloat()
        val uniformScale = max(sourceScaleX, sourceScaleY)
        val correctionX = uniformScale / sourceScaleX
        val correctionY = uniformScale / sourceScaleY

        val matrix = Matrix().apply {
            setScale(correctionX, correctionY, width / 2f, height / 2f)
        }
        setTransform(matrix)
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
