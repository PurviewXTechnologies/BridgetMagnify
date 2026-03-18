package com.shashi.rayneomagnify

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ElevenLabs Speech-to-Text helper.
 *
 * Records 16-bit PCM mono audio at 16 kHz using [AudioRecord], writes a
 * valid WAV file, then POSTs it to the ElevenLabs STT endpoint.
 *
 * Usage:
 *  1. Call [startRecording] to open the mic.
 *  2. Call [stopAndTranscribe] (suspend) to stop + send to API → returns text.
 *  3. Call [cancel] to abandon without transcribing.
 *
 * Requires RECORD_AUDIO permission (already in AndroidManifest.xml).
 */
class ElevenLabsSTT(
    private val apiKey: String,
    private val cacheDir: File
) {
    companion object {
        private const val TAG         = "ElevenLabsSTT"
        private const val STT_URL     = "https://api.elevenlabs.io/v1/speech-to-text"
        private const val MODEL_ID    = "scribe_v1"
        private const val SAMPLE_RATE = 16_000
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread?  = null
    private val isRecording               = AtomicBoolean(false)
    private var outputFile: File?         = null

    // ── Public API ────────────────────────────────────────────────────────────

    /** Open the microphone and start capturing audio in a background thread. */
    fun startRecording() {
        if (isRecording.get()) return

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "AudioRecord.getMinBufferSize() failed — device may not support this config")
            return
        }

        @Suppress("MissingPermission")
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 4
        )

        // Guard: AudioRecord can be in an uninitialized state if RECORD_AUDIO
        // permission was not granted or if the hardware is unavailable.
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize — RECORD_AUDIO permission may not be granted")
            audioRecord?.release()
            audioRecord = null
            return
        }

        val tmp = File(cacheDir, "qa_rec_${System.currentTimeMillis()}.wav")
        outputFile = tmp
        isRecording.set(true)
        audioRecord?.startRecording()

        recordingThread = Thread {
            try {
                FileOutputStream(tmp).use { fos ->
                    // Reserve space for the 44-byte WAV header; filled in after recording
                    fos.write(ByteArray(44))
                    val buf = ByteArray(minBuf)
                    while (isRecording.get()) {
                        val n = audioRecord?.read(buf, 0, buf.size) ?: 0
                        if (n > 0) fos.write(buf, 0, n)
                    }
                }
                patchWavHeader(tmp)
            } catch (e: Exception) {
                Log.e(TAG, "Recording thread error", e)
            }
        }.also { it.start() }

        Log.d(TAG, "Recording started → ${tmp.name}")
    }

    /**
     * Stop the microphone, upload the WAV, and return the transcribed text.
     * Returns null on failure. Deletes the temp file automatically.
     * Must be called from a coroutine (suspend).
     */
    suspend fun stopAndTranscribe(): String? {
        if (!isRecording.get()) return null

        isRecording.set(false)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordingThread?.join()
        recordingThread = null

        val file = outputFile ?: return null
        outputFile = null

        return withContext(Dispatchers.IO) {
            var result: String? = null
            try {
                result = transcribe(file)
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
            } finally {
                file.delete()
            }
            result
        }
    }

    /** Cancel an in-progress recording without sending to the API. */
    fun cancel() {
        isRecording.set(false)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord      = null
        recordingThread  = null
        outputFile?.delete()
        outputFile = null
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun transcribe(file: File): String? {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model_id", MODEL_ID)
            .build()

        val request = Request.Builder()
            .url(STT_URL)
            .addHeader("xi-api-key", apiKey)
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        return response.use { resp ->
            val raw = resp.body?.string()
            if (!resp.isSuccessful) {
                Log.e(TAG, "STT HTTP ${resp.code}: $raw")
                return@use null
            }
            raw?.let { JSONObject(it).optString("text").takeIf { t -> t.isNotEmpty() } }
        }
    }

    /**
     * Patch the WAV header placeholder written at the start of recording.
     * Uses little-endian byte order as required by the RIFF/WAV spec.
     */
    private fun patchWavHeader(file: File) {
        val total     = file.length().toInt()
        val dataSize  = total - 44

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            // RIFF chunk
            raf.write("RIFF".toByteArray())
            raf.write(le32(total - 8))
            raf.write("WAVE".toByteArray())
            // fmt sub-chunk
            raf.write("fmt ".toByteArray())
            raf.write(le32(16))             // sub-chunk size for PCM
            raf.write(le16(1))              // AudioFormat: PCM
            raf.write(le16(1))              // num channels: mono
            raf.write(le32(SAMPLE_RATE))
            raf.write(le32(SAMPLE_RATE * 2)) // byte rate = sr * channels * (bits/8)
            raf.write(le16(2))              // block align = channels * (bits/8)
            raf.write(le16(16))             // bits per sample
            // data sub-chunk
            raf.write("data".toByteArray())
            raf.write(le32(dataSize))
        }
    }

    private fun le32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun le16(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
}
