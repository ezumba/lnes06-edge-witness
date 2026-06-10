package com.exergynet.myapplication

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DLTNAudioEngine — raw, lightweight full-duplex PCM voice over a socket pair.
 *
 * No WebRTC, no native codecs — pure AudioRecord → socket → AudioTrack piping to
 * honour the "Minimum Logical Energy" aesthetic of LNES-06.
 *
 *   Capture : AudioRecord (16 kHz, 16-bit PCM, mono, VOICE_COMMUNICATION source)
 *             → frames pushed to the provided OutputStream.
 *   Playback: AudioTrack (STREAM_VOICE_CALL usage) ← frames from the provided
 *             InputStream.
 *   AEC     : AcousticEchoCanceler (+ NoiseSuppressor) engaged on the capture
 *             session when the device supports it, to kill feedback loops.
 *
 * Wire format matches the rest of DLTN: [Int frameLen][frameLen bytes] per frame.
 *
 * Voice is intended for WiFi-Aware (Phase C) sockets only — BLE MTU/bandwidth
 * cannot sustain continuous audio. The caller (DLTNCallEngine) enforces that gate.
 */
@SuppressLint("MissingPermission")
class DLTNAudioEngine {

    private val TAG = "DLTNAudioEngine"

    private val active = AtomicBoolean(false)
    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var audioRecord: AudioRecord? = null
    private var audioTrack:  AudioTrack?  = null
    private var aec: AcousticEchoCanceler? = null
    private var ns:  NoiseSuppressor?      = null

    private var onClosed: (() -> Unit)? = null
    private val closedNotified = AtomicBoolean(false)

    private val isMuted = AtomicBoolean(false)
    private var audioManager: AudioManager? = null

    /**
     * Begin full-duplex streaming over the supplied socket streams. Idempotent.
     * [onClosed] fires once if a stream drops on its own (e.g. the remote peer
     * hangs up / the socket closes) while still active — letting the caller tear
     * the call down. It does NOT fire on a deliberate [stop].
     */
    fun start(context: Context, input: InputStream, output: OutputStream, onClosed: (() -> Unit)? = null) {
        if (active.getAndSet(true)) return
        this.onClosed = onClosed
        closedNotified.set(false)
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Log.i(TAG, "[AUDIO] Engine start — ${SAMPLE_RATE}Hz mono PCM full-duplex")
        scope.launch { captureLoop(output) }
        scope.launch { playbackLoop(input) }
    }

    private fun notifyClosedIfDropped() {
        if (active.get() && closedNotified.compareAndSet(false, true)) {
            onClosed?.invoke()
        }
    }

    /** Stop streaming and release the microphone/speaker hardware immediately. */
    fun stop() {
        if (!active.getAndSet(false)) return
        releaseRecord()
        releaseTrack()
        audioManager?.isSpeakerphoneOn = false // Turn off speaker on stop
        Log.i(TAG, "[AUDIO] Engine stopped — mic + speaker released")
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
        Log.i(TAG, "[AUDIO] Mute set to $muted")
    }

    fun setSpeakerphoneOn(on: Boolean) {
        audioManager?.isSpeakerphoneOn = on
        Log.i(TAG, "[AUDIO] Speakerphone set to $on")
    }

    // ── Capture: mic → socket ────────────────────────────────────────────────
    private suspend fun captureLoop(output: OutputStream) = withContext(Dispatchers.IO) {
        val minBuf  = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val bufSize = maxOf(minBuf, DLTNConstants.VOICE_BUFFER_BYTES)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL_IN, ENCODING, bufSize,
        )
        audioRecord = rec
        engageEffects(rec.audioSessionId)
        rec.startRecording()

        val out = DataOutputStream(output)
        val buf = ByteArray(DLTNConstants.VOICE_BUFFER_BYTES)
        val muteBuf = ByteArray(DLTNConstants.VOICE_BUFFER_BYTES) // A silent buffer
        try {
            while (active.get()) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0) {
                    if (isMuted.get()) {
                        out.writeInt(read)
                        out.write(muteBuf, 0, read)
                    } else {
                        out.writeInt(read)
                        out.write(buf, 0, read)
                    }
                    out.flush()
                } else if (read < 0) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "[AUDIO] TX stream ended: ${e.message}")
        } finally {
            releaseRecord()
            notifyClosedIfDropped()
        }
    }

    // ── Playback: socket → speaker ───────────────────────────────────────────
    private suspend fun playbackLoop(input: InputStream) = withContext(Dispatchers.IO) {
        val minBuf  = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        val bufSize = maxOf(minBuf, DLTNConstants.VOICE_BUFFER_BYTES * 4)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)   // routes to STREAM_VOICE_CALL
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        track.play()

        val inp = DataInputStream(input)
        try {
            while (active.get()) {
                val len = inp.readInt()
                if (len <= 0 || len > MAX_FRAME_BYTES) break
                val buf = ByteArray(len)
                inp.readFully(buf)
                track.write(buf, 0, len)
            }
        } catch (e: Exception) {
            Log.d(TAG, "[AUDIO] RX stream ended: ${e.message}")
        } finally {
            releaseTrack()
            notifyClosedIfDropped()
        }
    }

    // ── Acoustic effects ─────────────────────────────────────────────────────
    private fun engageEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                Log.i(TAG, "[AUDIO] AEC engaged=${aec?.enabled == true}")
            } else {
                Log.d(TAG, "[AUDIO] AEC not available on this device")
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[AUDIO] Acoustic effects unavailable: ${e.message}")
        }
    }

    // ── Release helpers ──────────────────────────────────────────────────────
    private fun releaseRecord() {
        try { aec?.release() } catch (_: Exception) {} ; aec = null
        try { ns?.release()  } catch (_: Exception) {} ; ns  = null
        audioRecord?.let { r ->
            try { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() } catch (_: Exception) {}
            try { r.release() } catch (_: Exception) {}
        }
        audioRecord = null
    }

    private fun releaseTrack() {
        audioTrack?.let { t ->
            try { if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop() } catch (_: Exception) {}
            try { t.release() } catch (_: Exception) {}
        }
        audioTrack = null
    }

    companion object {
        private const val SAMPLE_RATE     = DLTNConstants.VOICE_SAMPLE_RATE_HZ   // 16000
        private const val CHANNEL_IN      = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT     = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING        = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_FRAME_BYTES = 4096
    }
}
