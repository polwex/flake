package one.yago.sorchat.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool

/**
 * Short sound effects (synthesized by tools/make_sounds.py). UI sounds respect silent/vibrate
 * mode; call sounds go with the call audio.
 */
class Sounds(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val ui = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(attributes(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION))
        .build()
    private val send = ui.load(context, R.raw.sound_send, 1)
    private val receive = ui.load(context, R.raw.sound_receive, 1)

    private val call = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(attributes(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING))
        .build()
    private val connected = call.load(context, R.raw.sound_call_connected, 1)
    private val ended = call.load(context, R.raw.sound_call_ended, 1)
    private val ringback = call.load(context, R.raw.sound_ringback, 1)
    private var ringbackStream = 0

    fun messageSent() = playUi(send, 0.5f)
    fun messageReceived() = playUi(receive, 0.6f)

    fun callConnected() {
        call.play(connected, 0.7f, 0.7f, 1, 0, 1f)
    }

    fun callEnded() {
        call.play(ended, 0.7f, 0.7f, 1, 0, 1f)
    }

    /** Plays (looping) while our outgoing call rings on the other side. */
    fun startRingback() {
        stopRingback()
        ringbackStream = call.play(ringback, 0.5f, 0.5f, 1, -1, 1f)
    }

    fun stopRingback() {
        if (ringbackStream != 0) call.stop(ringbackStream)
        ringbackStream = 0
    }

    private fun playUi(sound: Int, volume: Float) {
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) ui.play(sound, volume, volume, 1, 0, 1f)
    }

    private fun attributes(usage: Int) = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}
