package com.shashi.rayneomagnify

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs Text-to-Speech helper.
 *
 * Calls the ElevenLabs REST API, writes the returned MP3 to a temp file,
 * and plays it back through [MediaPlayer].
 *
 * All network work must be called from a coroutine (suspend function).
 */
class ElevenLabsTTS(
    private val apiKey: String,
    private val voiceId: String,
    private val cacheDir: File
) {
    companion object {
        private const val TAG = "ElevenLabsTTS"
        private const val BASE_URL = "https://api.elevenlabs.io/v1/text-to-speech"
        private const val MODEL_ID = "eleven_multilingual_v2"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null

    /** Callback triggered when playback finishes (or is stopped). */
    var onPlaybackFinished: (() -> Unit)? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fetch TTS audio from ElevenLabs and play it.
     * Must be called from a suspend context (e.g. [kotlinx.coroutines.launch]).
     *
     * @return true if the audio was played successfully, false on any error.
     */
    suspend fun speak(text: String): Boolean {
        if (text.isBlank()) return false

        return withContext(Dispatchers.IO) {
            try {
                val audioBytes = fetchAudio(text) ?: return@withContext false
                val tmpFile = writeTempFile(audioBytes)
                withContext(Dispatchers.Main) {
                    playAudio(tmpFile)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "ElevenLabs TTS error", e)
                false
            }
        }
    }

    /** Stop any currently playing audio immediately. */
    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "stop() error", e)
        }
    }

    /** Release all resources. Call from onDestroy(). */
    fun shutdown() {
        stop()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** POST to ElevenLabs and return raw MP3 bytes, or null on failure. */
    private fun fetchAudio(text: String): ByteArray? {
        val json = JSONObject().apply {
            put("text", text)
            put("model_id", MODEL_ID)
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.75)
            })
        }.toString()

        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$BASE_URL/$voiceId")
            .addHeader("xi-api-key", apiKey)
            .addHeader("Accept", "audio/mpeg")
            .post(body)
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "ElevenLabs HTTP ${response.code}: ${response.body?.string()}")
                return null
            }
            response.body?.bytes()
        }
    }

    /** Write MP3 bytes to a temp file and return it. */
    private fun writeTempFile(bytes: ByteArray): File {
        val tmp = File(cacheDir, "elevenlabs_tts_${System.currentTimeMillis()}.mp3")
        FileOutputStream(tmp).use { it.write(bytes) }
        return tmp
    }

    /** Play audio from a file on the main thread. */
    private fun playAudio(file: File) {
        stop() // release any previous player

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                it.release()
                mediaPlayer = null
                file.delete()    // clean up temp file
                onPlaybackFinished?.invoke()
            }
            setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                mp.release()
                mediaPlayer = null
                file.delete()
                onPlaybackFinished?.invoke()
                true
            }
            prepare()
            start()
        }
        Log.d(TAG, "Playing ElevenLabs audio: ${file.name}")
    }
}
