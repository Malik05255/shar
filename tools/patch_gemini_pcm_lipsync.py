from pathlib import Path


def must_replace(source: str, old: str, new: str, label: str) -> str:
    if old not in source:
        raise SystemExit(f"missing expected block: {label}")
    return source.replace(old, new, 1)


voice = Path("police-app/src/main/kotlin/com/malik/alshurti/voice/SaudiHumanVoice.kt")
text = voice.read_text()

text = must_replace(
    text,
    "import com.malik.alshurti.BuildConfig\n",
    "import com.malik.alshurti.BuildConfig\nimport com.malik.alshurti.neural.PcmSpeechEnergy\n",
    "energy import",
)
text = must_replace(
    text,
    "        fun onSpeechCursor(fraction: Float)\n        fun onSpeechFinished()\n",
    "        fun onSpeechCursor(fraction: Float)\n        fun onSpeechFrame(fraction: Float, energy: Float) = onSpeechCursor(fraction)\n        fun onSpeechFinished()\n",
    "callback frame",
)

old_stream = '''            var offset = 0
            while (offset < pcm.size && ticket == generation.get() && !released && audioTrack === track) {
                val length = minOf(STREAM_CHUNK_BYTES, pcm.size - offset)
                val written = track.write(pcm, offset, length, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) throw IllegalStateException("تعذر إرسال الصوت للسماعة.")
                offset += written

                // OEM watchdog: after enough PCM has been queued, playbackHeadPosition must advance.
                if (offset >= minOf(pcm.size, START_WATCHDOG_BYTES) && track.playbackHeadPosition == 0) {
                    Thread.sleep(START_WATCHDOG_MS)
                    if (track.playbackHeadPosition == 0) {
                        throw IllegalStateException("مسار AudioTrack لم يبدأ على هذا الجهاز.")
                    }
                }
            }
            if (ticket != generation.get() || released || audioTrack !== track) return

            dispatch { callbacks.onSpeechStarted(durationMs) }
            val deadline = System.currentTimeMillis() + durationMs + PLAYBACK_GRACE_MS
            while (ticket == generation.get() && !released && audioTrack === track) {
                val played = track.playbackHeadPosition.toLong().coerceAtLeast(0L)
                val fraction = (played.toDouble() / totalFrames.toDouble()).toFloat().coerceIn(0f, 1f)
                dispatch { callbacks.onSpeechCursor(fraction) }
                if (played >= totalFrames - 2L) break
                if (System.currentTimeMillis() >= deadline) break
                Thread.sleep(CURSOR_INTERVAL_MS)
            }
            if (ticket == generation.get() && !released && audioTrack === track) {
                dispatch {
                    callbacks.onSpeechCursor(1f)
                    callbacks.onSpeechFinished()
                }
            }
'''

new_stream = '''            val floatPcm = pcm16ToFloat(pcm)
            val calibration = PcmSpeechEnergy.calibrate(floatPcm, clip.sampleRate)
            var offset = 0
            var startedReported = false
            while (offset < pcm.size && ticket == generation.get() && !released && audioTrack === track) {
                val length = minOf(STREAM_CHUNK_BYTES, pcm.size - offset)
                val written = track.write(pcm, offset, length, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) throw IllegalStateException("تعذر إرسال الصوت للسماعة.")
                offset += written

                if (!startedReported && offset >= minOf(pcm.size, START_WATCHDOG_BYTES)) {
                    if (track.playbackHeadPosition == 0) Thread.sleep(START_WATCHDOG_MS)
                    if (track.playbackHeadPosition == 0) {
                        throw IllegalStateException("مسار AudioTrack لم يبدأ على هذا الجهاز.")
                    }
                    startedReported = true
                    dispatch { callbacks.onSpeechStarted(durationMs) }
                }

                if (startedReported) {
                    val played = track.playbackHeadPosition.toLong().coerceAtLeast(0L)
                    val fraction = (played.toDouble() / totalFrames.toDouble()).toFloat().coerceIn(0f, 1f)
                    val energy = PcmSpeechEnergy.normalizedAt(floatPcm, clip.sampleRate, fraction, calibration)
                    dispatch { callbacks.onSpeechFrame(fraction, energy) }
                }
            }
            if (ticket != generation.get() || released || audioTrack !== track) return
            if (!startedReported) {
                Thread.sleep(START_WATCHDOG_MS)
                if (track.playbackHeadPosition == 0) throw IllegalStateException("مسار AudioTrack لم يبدأ على هذا الجهاز.")
                dispatch { callbacks.onSpeechStarted(durationMs) }
            }

            val deadline = System.currentTimeMillis() + durationMs + PLAYBACK_GRACE_MS
            while (ticket == generation.get() && !released && audioTrack === track) {
                val played = track.playbackHeadPosition.toLong().coerceAtLeast(0L)
                val fraction = (played.toDouble() / totalFrames.toDouble()).toFloat().coerceIn(0f, 1f)
                val energy = PcmSpeechEnergy.normalizedAt(floatPcm, clip.sampleRate, fraction, calibration)
                dispatch { callbacks.onSpeechFrame(fraction, energy) }
                if (played >= totalFrames - 2L) break
                if (System.currentTimeMillis() >= deadline) break
                Thread.sleep(CURSOR_INTERVAL_MS)
            }
            if (ticket == generation.get() && !released && audioTrack === track) {
                dispatch {
                    callbacks.onSpeechFrame(1f, 0f)
                    callbacks.onSpeechFinished()
                }
            }
'''
text = must_replace(text, old_stream, new_stream, "stream playback")

text = must_replace(
    text,
    '        val wavFile = File(appContext.cacheDir, "alshorti-fallback-${role.name.lowercase()}-$ticket.wav")\n',
    '        val floatPcm = pcm16ToFloat(pcm)\n        val calibration = PcmSpeechEnergy.calibrate(floatPcm, clip.sampleRate)\n        val wavFile = File(appContext.cacheDir, "alshorti-fallback-${role.name.lowercase()}-$ticket.wav")\n',
    "fallback calibration",
)
text = must_replace(
    text,
    "                dispatch { callbacks.onSpeechCursor(fraction) }\n",
    "                val energy = PcmSpeechEnergy.normalizedAt(floatPcm, clip.sampleRate, fraction, calibration)\n                dispatch { callbacks.onSpeechFrame(fraction, energy) }\n",
    "fallback cursor",
)
text = must_replace(
    text,
    "                    callbacks.onSpeechCursor(1f)\n                    callbacks.onSpeechFinished()\n",
    "                    callbacks.onSpeechFrame(1f, 0f)\n                    callbacks.onSpeechFinished()\n",
    "fallback final frame",
)

helper = '''    private fun pcm16ToFloat(pcm: ByteArray): FloatArray {
        val samples = FloatArray(pcm.size / 2)
        var byteIndex = 0
        var sampleIndex = 0
        while (byteIndex + 1 < pcm.size) {
            val low = pcm[byteIndex].toInt() and 0xff
            val high = pcm[byteIndex + 1].toInt()
            val signed = ((high shl 8) or low).toShort().toInt()
            samples[sampleIndex++] = (signed / 32768f).coerceIn(-1f, 1f)
            byteIndex += 2
        }
        return samples
    }

'''
text = must_replace(
    text,
    "    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {\n",
    helper + "    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {\n",
    "pcm helper",
)
voice.write_text(text)

engine = Path("police-app/src/main/kotlin/com/malik/alshurti/PoliceVoiceEngine.kt")
text = engine.read_text()
text = must_replace(
    text,
    '''            override fun onSpeechCursor(fraction: Float) {
                if (activeSpeechBackend == SpeechBackend.CLOUD) handleSpeechCursor(fraction)
            }
''',
    '''            override fun onSpeechCursor(fraction: Float) {
                if (activeSpeechBackend == SpeechBackend.CLOUD) handleSpeechCursor(fraction)
            }

            override fun onSpeechFrame(fraction: Float, energy: Float) {
                if (activeSpeechBackend == SpeechBackend.CLOUD) handleEnergySpeechFrame(fraction, energy)
            }
''',
    "cloud frame callback",
)
text = must_replace(
    text,
    '''    private fun handleLocalSpeechFrame(fraction: Float, energy: Float) {
        localLipEnergy = energy.coerceIn(0f, 1f)
        localLipVoiced = PcmSpeechEnergy.isVoiced(localLipEnergy, localLipVoiced)
        val viseme = if (localLipVoiced) visemeAtFraction(spokenText, fraction) else MouthViseme.REST
        if (viseme != lastViseme) {
            lastViseme = viseme
            dispatchViseme(viseme)
        }
    }
''',
    '''    private fun handleLocalSpeechFrame(fraction: Float, energy: Float) {
        handleEnergySpeechFrame(fraction, energy)
    }

    private fun handleEnergySpeechFrame(fraction: Float, energy: Float) {
        localLipEnergy = PcmSpeechEnergy.smooth(localLipEnergy, energy.coerceIn(0f, 1f))
        localLipVoiced = PcmSpeechEnergy.isVoiced(localLipEnergy, localLipVoiced)
        val viseme = if (localLipVoiced) visemeAtFraction(spokenText, fraction) else MouthViseme.REST
        if (viseme != lastViseme) {
            lastViseme = viseme
            dispatchViseme(viseme)
        }
    }
''',
    "energy handler",
)
engine.write_text(text)
