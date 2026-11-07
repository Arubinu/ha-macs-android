package fr.pergens.hamacs.net

import android.content.Context
import fr.pergens.hamacs.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Appareils du réseau qui contactent un service de la tablette (lecteur DLNA, satellite Wyoming) : le serveur Home
 * Assistant de l'app est toujours accepté ; les autres sont retenus dans une liste (en attente, approuvés, bloqués)
 * que l'on gère dans les réglages. [Mode.Open] (DLNA seulement) : tout le réseau sauf les appareils bloqués.
 * Prefs propres à la tablette (non exportées).
 */
class PeerGuard(
    ctx: Context,
    file: String,
    private val scope: CoroutineScope,
    private val haUrl: () -> String,
    /** Mode choisi par défaut (le satellite vocal reste toujours en [Mode.Approved]). */
    private val defaultMode: Mode = Mode.Approved,
) {
    enum class State { Pending, Allowed, Blocked }
    enum class Mode { Open, Approved }

    data class Peer(
        val ip: String, val state: State, val label: String, val first: Long, val last: Long,
        /** Serveur Home Assistant de l'app (accepté d'office). */
        val ha: Boolean = false,
    )

    private val prefs = ctx.getSharedPreferences(file, Context.MODE_PRIVATE)
    private val map = ConcurrentHashMap<String, Peer>()
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    /** Appareils connus, les plus récents d'abord. */
    val peers: StateFlow<List<Peer>> = _peers

    private val _mode = MutableStateFlow(runCatching { Mode.valueOf(prefs.getString("mode", null) ?: defaultMode.name) }.getOrDefault(defaultMode))
    val mode: StateFlow<Mode> = _mode

    @Volatile private var haIps: Set<String> = emptySet()
    @Volatile private var haIpsAt = 0L
    @Volatile private var haIpsFor = ""
    @Volatile private var savedAt = 0L

    init {
        runCatching {
            (Json.parseToJsonElement(prefs.getString("peers", null) ?: "[]") as JsonArray).forEach { e ->
                val o = e.jsonObject
                val ip = o["ip"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                map[ip] = Peer(
                    ip, runCatching { State.valueOf(o["state"]!!.jsonPrimitive.content) }.getOrDefault(State.Pending),
                    o["label"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["first"]?.jsonPrimitive?.longOrNull ?: 0L,
                    o["last"]?.jsonPrimitive?.longOrNull ?: 0L, o["ha"]?.jsonPrimitive?.contentOrNull == "true",
                )
            }
        }
        publish()
    }

    fun setMode(m: Mode) {
        prefs.edit().putString("mode", m.name).apply()
        _mode.value = m
    }

    /**
     * L'appareil [ip] peut-il utiliser le service ? Le note dans la liste au passage ([label] : description, ex.
     * User-Agent). À appeler hors du fil principal (résolution du nom du serveur Home Assistant).
     */
    fun check(ip: String?, label: String? = null, loopback: Boolean = false): Boolean {
        if (ip == null) return false
        // Versions de débogage : la tablette elle-même (essais par `adb forward`).
        if (BuildConfig.DEBUG && loopback) return true
        val now = System.currentTimeMillis()
        val ha = ip in haIps()
        val old = map[ip]
        val state = when {
            ha -> State.Allowed
            old != null -> old.state
            else -> State.Pending
        }
        val peer = Peer(ip, state, label?.take(120)?.takeIf { it.isNotBlank() } ?: old?.label.orEmpty(), old?.first ?: now, now, ha)
        map[ip] = peer
        // Enregistré pour un nouvel appareil ou un changement ; sinon (heure de dernière visite) au plus toutes les minutes.
        if (old == null || old.state != peer.state || old.label != peer.label || old.ha != ha || now - savedAt > 60_000) save()
        if (old == null && peer.label.isEmpty() && !ha) resolveName(ip)
        return when (state) {
            State.Allowed -> true
            State.Blocked -> false
            State.Pending -> _mode.value == Mode.Open
        }
    }

    fun set(ip: String, state: State) {
        map[ip]?.let { map[ip] = it.copy(state = state); save() }
    }

    fun remove(ip: String) {
        if (map.remove(ip) != null) save()
    }

    /** Nom de l'appareil sur le réseau (DNS inverse), s'il en a un. */
    private fun resolveName(ip: String) {
        scope.launch(Dispatchers.IO) {
            val name = runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()?.takeIf { it != ip } ?: return@launch
            map[ip]?.let { if (it.label.isEmpty()) { map[ip] = it.copy(label = name); save() } }
        }
    }

    /** Adresses du serveur Home Assistant de l'app (résolues au plus toutes les 5 minutes). */
    private fun haIps(): Set<String> {
        val url = haUrl()
        val now = System.currentTimeMillis()
        if (url != haIpsFor || now - haIpsAt > 5 * 60_000) {
            haIpsFor = url
            haIpsAt = now
            val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()
            haIps = host?.let { h -> runCatching { InetAddress.getAllByName(h).mapNotNull { it.hostAddress }.toSet() }.getOrNull() }.orEmpty()
        }
        return haIps
    }

    private fun save() {
        savedAt = System.currentTimeMillis()
        val arr = JsonArray(map.values.map { p ->
            buildJsonObject {
                put("ip", p.ip); put("state", p.state.name); put("label", p.label); put("first", p.first); put("last", p.last)
                if (p.ha) put("ha", true)
            }
        })
        prefs.edit().putString("peers", arr.toString()).apply()
        publish()
    }

    private fun publish() {
        _peers.value = map.values.sortedWith(compareBy<Peer> { it.state != State.Pending }.thenByDescending { it.last })
    }
}
