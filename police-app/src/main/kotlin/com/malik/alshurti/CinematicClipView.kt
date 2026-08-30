package com.malik.alshurti

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RawRes
import kotlin.math.absoluteValue

/**
 * Texture-backed, muted cinematic clip player.
 *
 * Unlike VideoView/SurfaceView this view can stay transparent until the first decoded video frame
 * is actually rendered. The master cinematic frame remains visible underneath, eliminating the
 * black flash that otherwise exposes transitions between AI motion clips.
 */
class CinematicClipView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private data class ClipConfig(
        @RawRes val resId: Int,
        val looping: Boolean,
        val seed: Long
    )

    private var config: ClipConfig? = null
    private var mediaPlayer: MediaPlayer? = null
    private var mediaSurface: Surface? = null

    init {
        surfaceTextureListener = this
        isOpaque = false
        alpha = 0f
    }

    fun bind(@RawRes resId: Int, looping: Boolean, seed: Long) {
        val next = ClipConfig(resId, looping, seed)
        if (config == next && mediaPlayer != null) return
        config = next
        if (isAvailable) startConfiguredClip()
    }

    private fun startConfiguredClip() {
        val active = config ?: return
        val texture = surfaceTexture ?: return

        releasePlayer()
        alpha = 0f

        runCatching {
            val surface = Surface(texture)
            mediaSurface = surface

            val player = MediaPlayer().also { mediaPlayer = it }
            player.setSurface(surface)
            player.setDataSource(
                context,
                Uri.parse("android.resource://${context.packageName}/${active.resId}")
            )
            player.isLooping = active.looping
            player.setVolume(0f, 0f)

            player.setOnPreparedListener { prepared ->
                runCatching {
                    if (active.looping && prepared.duration > 1_000) {
                        val usableMs = (prepared.duration - 650).coerceAtLeast(1)
                        val offset = (
                            (active.seed xor (active.resId.toLong() shl 17)).absoluteValue %
                                usableMs.toLong()
                            )

                        // Tiny visual tempo variance makes repeated idle/talk loops less mechanical.
                        val speedBucket = ((active.seed ushr 7).absoluteValue % 7).toInt()
                        val speed = 0.97f + speedBucket * 0.01f
                        prepared.playbackParams = prepared.playbackParams.setSpeed(speed)
                        prepared.setOnSeekCompleteListener { seeked ->
                            runCatching { seeked.start() }.onFailure { alpha = 0f }
                        }
                        prepared.seekTo(offset, MediaPlayer.SEEK_CLOSEST_SYNC)
                    } else {
                        prepared.start()
                    }
                }.onFailure {
                    alpha = 0f
                }
            }

            player.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    animate().cancel()
                    animate().alpha(1f).setDuration(170L).start()
                }
                false
            }

            player.setOnErrorListener { _, _, _ ->
                animate().cancel()
                alpha = 0f
                releasePlayer()
                true
            }

            player.prepareAsync()
        }.onFailure {
            alpha = 0f
            releasePlayer()
        }
    }

    fun releasePlayback() {
        animate().cancel()
        alpha = 0f
        releasePlayer()
    }

    private fun releasePlayer() {
        runCatching { mediaPlayer?.setOnSeekCompleteListener(null) }
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

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

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
