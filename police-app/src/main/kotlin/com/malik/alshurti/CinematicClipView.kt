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
 * Quality rules:
 * - The 16:9 AI master/video is always center-cropped with one uniform scale. It is never stretched
 *   to the phone viewport, so a portrait device cannot make the dog wider/thinner than the still.
 * - A completed generated clip holds its final rendered frame. It never hard-loops back to frame 0.
 * - Rebinding to the next action keeps the current TextureView/frame visible while the next decoder
 *   prepares; the master still is used only for the very first frame or an actual media failure.
 */
class CinematicClipView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private data class ClipConfig(
        @RawRes val resId: Int,
        val randomizeStart: Boolean,
        val seed: Long
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

    fun bind(@RawRes resId: Int, randomizeStart: Boolean, seed: Long) {
        val next = ClipConfig(resId, randomizeStart, seed)
        if (config == next && mediaPlayer != null) return
        config = next
        if (isAvailable) startConfiguredClip()
    }

    private fun startConfiguredClip() {
        val active = config ?: return
        val texture = surfaceTexture ?: return

        // Keep the last successfully rendered frame visible while the next state prepares. This is
        // what prevents the scene from visibly snapping back to the master/beginning between clips.
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
            player.isLooping = false
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
                        // Start from a different quiet portion each time this state is entered, but
                        // never from the final 1.2 s so enough natural movement remains to play.
                        val usableMs = (durationMs - 1_200L).coerceAtLeast(1L)
                        val raw = (active.seed xor (active.resId.toLong() shl 17)).absoluteValue
                        val offset = raw % usableMs
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
                    if (alpha < 1f) animate().alpha(1f).setDuration(140L).start()
                }
                false
            }

            // Do not loop generated actions. The final frame remains on the TextureView until the
            // state actually changes; this removes the obvious end -> beginning replay artifact.
            player.setOnCompletionListener { completed ->
                runCatching { completed.setOnSeekCompleteListener(null) }
            }

            player.setOnErrorListener { _, _, _ ->
                // If we already have a good rendered frame, preserve it rather than flashing the
                // master image. On first-load failure, fall back to the master underneath.
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

    /**
     * TextureView normally stretches video independently on X/Y. Correct that distortion by
     * applying the same center-crop geometry Compose's ContentScale.Crop uses for the master image.
     */
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
