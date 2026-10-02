package one.yago.sorchat.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Records voice notes as Opus in an Ogg container, mono at 24 kbit/s (~180 KB per minute). */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt = 0L

    fun start(file: File) {
        cancel()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(24_000)
            r.setOutputFile(file)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            throw e
        }
        recorder = r
        output = file
        startedAt = SystemClock.elapsedRealtime()
    }

    /** Stops recording. Returns the file and its duration, or null if it was too short to keep. */
    fun stop(): Pair<File, Long>? {
        val r = recorder ?: return null
        val file = output!!
        val duration = SystemClock.elapsedRealtime() - startedAt
        recorder = null
        output = null
        val ok = try {
            r.stop()
            true
        } catch (_: RuntimeException) {
            false // stop() throws if nothing usable was recorded yet
        } finally {
            r.release()
        }
        if (!ok || duration < MIN_DURATION_MS) {
            file.delete()
            return null
        }
        return file to duration
    }

    fun cancel() {
        stop()?.first?.delete()
    }

    private companion object {
        const val MIN_DURATION_MS = 500L
    }
}

data class Playback(val messageId: String, val positionMs: Long, val playing: Boolean)

/** Plays one voice note at a time. */
class VoicePlayer(private val scope: CoroutineScope) {
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    private val _state = MutableStateFlow<Playback?>(null)
    val state: StateFlow<Playback?> = _state.asStateFlow()

    fun toggle(message: ChatMessage) {
        val path = message.localPath ?: return
        val current = _state.value
        if (current?.messageId == message.id) {
            if (current.playing) pause() else resume()
            return
        }
        stop()
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(path)
            setOnCompletionListener { stop() }
            prepare()
            start()
        }
        _state.value = Playback(message.id, 0, playing = true)
        startTicker()
    }

    fun stop() {
        ticker?.cancel()
        player?.release()
        player = null
        _state.value = null
    }

    private fun pause() {
        player?.pause()
        ticker?.cancel()
        _state.update { it?.copy(playing = false) }
    }

    private fun resume() {
        player?.start()
        _state.update { it?.copy(playing = true) }
        startTicker()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                player?.let { p -> _state.update { it?.copy(positionMs = p.currentPosition.toLong()) } }
                delay(100)
            }
        }
    }
}
