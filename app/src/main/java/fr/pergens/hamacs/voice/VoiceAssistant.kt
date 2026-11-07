package fr.pergens.hamacs.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import fr.pergens.hamacs.R
import fr.pergens.hamacs.config.ConfigRepository
import fr.pergens.hamacs.ha.HaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.time.Instant
import java.util.UUID
import kotlin.math.sqrt

enum class VoiceState { Idle, Listening, Thinking, Error }

data class Turn(val id: String, val heard: String, val reply: String, val error: String, val ts: String)

/**
 * Satellite vocal « full audio » : le micro est diffusé en continu (PCM 16 kHz) vers la
 * passerelle (gateway/voice_gateway.py) qui transmet l'audio brut à LFM3-Audio et exécute
 * les outils Home Assistant. Aucun STT/TTS côté tablette ; la réponse s'affiche dans les
 * bulles du M.A.C.S. Si la passerelle renvoie de l'audio, il est joué tel quel.
 */
class VoiceAssistant(
    private val context: Context,
    private val http: OkHttpClient,
    @Suppress("unused") private val ha: HaClient,
    private val config: ConfigRepository,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns.asStateFlow()

    /** Humeur de la réponse en cours (happy / sad…), affichée par le M.A.C.S. pendant qu'il parle. */
    private val _emotion = MutableStateFlow<String?>(null)
    val emotion: StateFlow<String?> = _emotion.asStateFlow()
    private var emotionJob: Job? = null

    private fun showEmotion(e: String?) {
        emotionJob?.cancel()
        _emotion.value = e?.takeIf { it != "neutral" }
        emotionJob = scope.launch {
            delay(5_000)
            _emotion.value = null
        }
    }

    /** Fourni par l'activité pour demander la permission micro. */
    var requestPermission: (() -> Unit)? = null

    private var socket: WebSocket? = null
    private var socketUrl: String? = null
    private var socketOpen = false
    private var recordJob: Job? = null
    private var currentTurn: String? = null
    private var player: AudioTrack? = null
    private var playerRate = 0

    /**
     * Satellite Home Assistant (Wyoming) : prioritaire quand Home Assistant y est connecté ; sinon la passerelle.
     */
    val wyoming = WyomingSatellite(context, scope, { fr.pergens.hamacs.App.instance.link.deviceName }) {
        fr.pergens.hamacs.App.instance.config.settings.value.haUrl
    }
    /** Micro ouvert en continu pour le mot d'activation (détecté par Home Assistant). */
    private var wakeJob: Job? = null
    /** Lecture de la synthèse : une seule écriture à la fois dans l'AudioTrack (sinon plantage natif). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val audioQueue = Dispatchers.IO.limitedParallelism(1)
    /** Trames de synthèse vocale écrites depuis le dernier audio-start (fin de lecture estimée). */
    private var ttsFrames = 0L

    init {
        wyoming.onEvent = { scope.launch(Dispatchers.Main.immediate) { onWyoming(it) } }
        wyoming.onRunning = { running -> scope.launch(Dispatchers.Main.immediate) { onWyomingRunning(running) } }
    }

    fun toggleListening() {
        when (_state.value) {
            VoiceState.Listening -> stopListening()
            VoiceState.Thinking -> Unit
            else -> startListening()
        }
    }

    private fun ensureSocket(): WebSocket? {
        val url = config.settings.value.voiceUrl.trim()
        if (url.isBlank()) return null
        if (socket != null && socketOpen && socketUrl == url) return socket
        socket?.cancel()
        socketUrl = url
        socketOpen = false
        socket = http.newWebSocket(Request.Builder().url(url).build(), Listener())
        return socket
    }

    /** Connexion anticipée : supprime le coût de la poignée de main au moment de parler. */
    fun warmUp() {
        if (!wyoming.enabled.value) ensureSocket()
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermission?.invoke()
            return
        }
        // Système choisi dans les réglages : Home Assistant (satellite Wyoming) ou la passerelle.
        if (wyoming.enabled.value) {
            if (wyoming.ready) startWyoming() else fail(context.getString(R.string.voice_ha_not_connected))
            return
        }
        val ws = ensureSocket() ?: run {
            fail(context.getString(R.string.voice_no_gateway))
            return
        }
        stopPlayback()
        val id = UUID.randomUUID().toString()
        currentTurn = id
        upsert(id) { it.copy(heard = "…") }
        _state.value = VoiceState.Listening
        ws.send(buildJsonObject {
            put("type", "start")
            put("id", id)
            put("sample_rate", SAMPLE_RATE)
            put("format", "pcm_s16le")
            put("language", java.util.Locale.getDefault().language)
        }.toString())

        recordJob = scope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME_BYTES * 8),
            )
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.enabled = true }
            if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true }
            val frame = ByteArray(FRAME_BYTES)
            val vad = EndOfSpeech()
            try {
                rec.startRecording()
                while (isActive && _state.value == VoiceState.Listening) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    ws.send(frame.toByteString(0, n))
                    when (vad.feed(frame, n)) {
                        EndOfSpeech.Result.Done -> break
                        EndOfSpeech.Result.NoSpeech -> {
                            ws.send(buildJsonObject { put("type", "cancel") }.toString())
                            _turns.value = _turns.value.filterNot { it.id == id }
                            _state.value = VoiceState.Idle
                            return@launch
                        }
                        EndOfSpeech.Result.Continue -> Unit
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Micro: ${e.message}")
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
            if (_state.value == VoiceState.Listening) {
                ws.send(buildJsonObject { put("type", "end") }.toString())
                _state.value = VoiceState.Thinking
                // Garde-fou si la passerelle ne répond jamais.
                delay(30_000)
                if (_state.value == VoiceState.Thinking && currentTurn == id) fail(context.getString(R.string.voice_no_answer))
            }
        }
    }

    fun stopListening() {
        if (_state.value == VoiceState.Listening) {
            if (!wyoming.enabled.value) socket?.send(buildJsonObject { put("type", "end") }.toString())
            _state.value = VoiceState.Thinking
        }
    }

    // --------------------------------------------------------------------------------------------------------
    // Satellite Wyoming
    // --------------------------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME_BYTES * 8),
        )
        if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.enabled = true }
        if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true }
        return rec
    }

    /** Appui sur le M.A.C.S. : pipeline Assist depuis l'écoute, audio envoyé jusqu'à la fin de la phrase. */
    private fun startWyoming() {
        stopPlayback()
        val id = UUID.randomUUID().toString()
        currentTurn = id
        upsert(id) { it.copy(heard = "…") }
        _state.value = VoiceState.Listening
        // Micro déjà ouvert (mot d'activation) : on relance seulement le pipeline à partir de l'écoute.
        if (wakeJob?.isActive == true) { wyoming.runPipeline(fromWake = false); return }
        wyoming.runPipeline(fromWake = false)
        wyoming.audioStart()
        recordJob = scope.launch(Dispatchers.IO) {
            val rec = openMic()
            val frame = ByteArray(FRAME_BYTES)
            val vad = EndOfSpeech()
            var noSpeech = false
            try {
                rec.startRecording()
                // Home Assistant détecte la fin de phrase (voice-stopped / transcript) ; la détection locale sert de
                // garde-fou si rien ne vient.
                while (isActive && _state.value == VoiceState.Listening) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) continue
                    if (!wyoming.audioChunk(frame, n)) break
                    when (vad.feed(frame, n)) {
                        EndOfSpeech.Result.NoSpeech -> { noSpeech = true; break }
                        else -> Unit
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Micro: ${e.message}")
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
            wyoming.audioStop()
            if (noSpeech && _state.value == VoiceState.Listening) {
                _turns.value = _turns.value.filterNot { it.id == id }
                _state.value = VoiceState.Idle
                return@launch
            }
            if (_state.value == VoiceState.Listening) _state.value = VoiceState.Thinking
            delay(30_000)
            if (_state.value == VoiceState.Thinking && currentTurn == id) fail(context.getString(R.string.voice_no_answer))
        }
    }

    /** Satellite démarré : micro ouvert en continu si le mot d'activation est activé. */
    @SuppressLint("MissingPermission")
    private fun onWyomingRunning(running: Boolean) {
        wakeJob?.cancel()
        wakeJob = null
        if (!running || !wyoming.wakeWord.value) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        wakeJob = scope.launch(Dispatchers.IO) {
            wyoming.runPipeline(fromWake = true)
            wyoming.audioStart()
            val rec = openMic()
            val frame = ByteArray(FRAME_BYTES)
            try {
                rec.startRecording()
                while (isActive && wyoming.ready) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n > 0 && !wyoming.audioChunk(frame, n)) break
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                wyoming.audioStop()
            }
        }
    }

    private fun onWyoming(e: WyomingSatellite.Event) {
        when (e.type) {
            // Mot d'activation entendu par Home Assistant : nouvelle bulle, écoute.
            "detection" -> {
                stopPlayback()
                val id = UUID.randomUUID().toString()
                currentTurn = id
                upsert(id) { it.copy(heard = "…") }
                _state.value = VoiceState.Listening
            }
            "voice-stopped" -> if (_state.value == VoiceState.Listening) _state.value = VoiceState.Thinking
            "transcript" -> {
                currentTurn?.let { id -> upsert(id) { it.copy(heard = WyomingSatellite.text(e)) } }
                if (_state.value == VoiceState.Listening) _state.value = VoiceState.Thinking
            }
            "synthesize" -> currentTurn?.let { id -> upsert(id) { it.copy(reply = WyomingSatellite.text(e)) } }
            "audio-start" -> scope.launch(audioQueue) { ttsFrames = 0; track(WyomingSatellite.int(e, "rate", 22050)) }
            "audio-chunk" -> e.payload?.let { pcm ->
                scope.launch(audioQueue) {
                    val t = track(WyomingSatellite.int(e, "rate", playerRate.takeIf { it > 0 } ?: 22050))
                    ttsFrames += pcm.size / WyomingSatellite.int(e, "width", 2).coerceAtLeast(1)
                    t.write(pcm, 0, pcm.size)
                }
            }
            "audio-stop" -> scope.launch(audioQueue) {
                // Fin de la synthèse : Home Assistant attend « played » une fois le son joué.
                val left = player?.let { ((ttsFrames - it.playbackHeadPosition.toLong()).coerceAtLeast(0) * 1000 / playerRate.coerceAtLeast(1)) } ?: 0
                delay(left + 150)
                wyoming.send("played")
                if (_state.value == VoiceState.Thinking) _state.value = VoiceState.Idle
            }
            "error" -> fail(WyomingSatellite.text(e).ifBlank { context.getString(R.string.voice_no_answer) })
        }
        // Réponse reçue sans audio (TTS absent du pipeline) : retour au repos.
        if (e.type == "synthesize" && _state.value == VoiceState.Listening) _state.value = VoiceState.Thinking
    }

    private fun fail(message: String) {
        currentTurn?.let { id -> upsert(id) { it.copy(error = message) } }
        _state.value = VoiceState.Error
        scope.launch {
            delay(2500)
            if (_state.value == VoiceState.Error) _state.value = VoiceState.Idle
        }
    }

    private fun upsert(id: String, change: (Turn) -> Turn) {
        val list = _turns.value
        val existing = list.firstOrNull { it.id == id } ?: Turn(id, "", "", "", Instant.now().toString())
        _turns.value = (listOf(change(existing)) + list.filterNot { it.id == id }).take(MAX_TURNS)
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket == socket) socketOpen = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val id = msg["id"]?.jsonPrimitive?.contentOrNull ?: currentTurn ?: return
            when (msg["type"]?.jsonPrimitive?.contentOrNull) {
                "heard" -> upsert(id) { it.copy(heard = msg.text()) }
                "reply_delta" -> upsert(id) { it.copy(reply = it.reply + msg.text()) }
                "reply" -> upsert(id) { it.copy(reply = msg.text()) }
                "audio" -> playPcm(msg)
                "emotion" -> showEmotion(msg["emotion"]?.jsonPrimitive?.contentOrNull)
                "error" -> fail(msg.text())
                "done" -> if (id == currentTurn && _state.value != VoiceState.Listening) _state.value = VoiceState.Idle
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket != socket) return
            socketOpen = false
            socket = null
            if (_state.value == VoiceState.Listening || _state.value == VoiceState.Thinking) fail(context.getString(R.string.voice_unreachable))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket == socket) {
                socketOpen = false
                socket = null
            }
        }
    }

    private fun JsonObject.text() = this["text"]?.jsonPrimitive?.contentOrNull ?: ""

    /** Lecture en flux d'audio PCM 16 bits renvoyé par la passerelle (si le modèle en produit). */
    private fun playPcm(msg: JsonObject) {
        val data = msg["data"]?.jsonPrimitive?.contentOrNull ?: return
        val rate = msg["sample_rate"]?.jsonPrimitive?.intOrNull ?: 24_000
        val pcm = Base64.decode(data, Base64.DEFAULT)
        scope.launch(audioQueue) { track(rate).write(pcm, 0, pcm.size) }
    }

    /** Piste de lecture PCM 16 bits mono à [rate] Hz (recréée si la fréquence change). */
    private fun track(rate: Int): AudioTrack =
        player?.takeIf { playerRate == rate } ?: AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
            .build().also {
                player?.release()
                player = it
                playerRate = rate
                it.play()
            }

    private fun stopPlayback() {
        scope.launch(audioQueue) { player?.runCatching { pause(); flush(); play() } }
    }

    /**
     * Détection simple de fin de parole par énergie : on attend de la voix, puis
     * [SILENCE_MS] de silence. Le seuil s'adapte au bruit ambiant des premières trames.
     */
    private class EndOfSpeech {
        enum class Result { Continue, Done, NoSpeech }

        private var elapsed = 0
        private var speechMs = 0
        private var silenceMs = 0
        private var noise = 0.0
        private var calibrated = 0

        fun feed(buf: ByteArray, n: Int): Result {
            var sum = 0.0
            var i = 0
            while (i + 1 < n) {
                val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble()
                sum += s * s
                i += 2
            }
            val rms = sqrt(sum / (n / 2).coerceAtLeast(1))
            val ms = n / 2 * 1000 / SAMPLE_RATE
            elapsed += ms
            if (calibrated < 5) {
                noise = (noise * calibrated + rms) / (calibrated + 1)
                calibrated++
            }
            val threshold = maxOf(noise * 2.5, 500.0)
            if (rms > threshold) {
                speechMs += ms
                silenceMs = 0
            } else if (speechMs > 0) {
                silenceMs += ms
                noise = noise * 0.95 + rms * 0.05
            }
            return when {
                speechMs >= MIN_SPEECH_MS && silenceMs >= SILENCE_MS -> Result.Done
                speechMs < MIN_SPEECH_MS && elapsed >= NO_SPEECH_TIMEOUT_MS -> Result.NoSpeech
                elapsed >= MAX_UTTERANCE_MS -> Result.Done
                else -> Result.Continue
            }
        }
    }

    companion object {
        private const val TAG = "VoiceAssistant"
        const val SAMPLE_RATE = 16_000
        /** 20 ms de PCM 16 bits mono. */
        const val FRAME_BYTES = SAMPLE_RATE / 50 * 2
        /** Silence marquant la fin de phrase : chaque ms ici s'ajoute au délai de réponse. */
        private const val SILENCE_MS = 500
        private const val MIN_SPEECH_MS = 200
        private const val NO_SPEECH_TIMEOUT_MS = 6_000
        private const val MAX_UTTERANCE_MS = 12_000
        private const val MAX_TURNS = 4
    }
}
