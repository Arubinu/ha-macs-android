package fr.pergens.hamacs.ha

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.net.Inet6Address

/** Serveur Home Assistant annoncé sur le réseau local. */
data class HaServer(val name: String, val url: String)

/**
 * Recherche des serveurs Home Assistant sur le réseau local : HA s'annonce en mDNS (`_home-assistant._tcp`), avec son
 * adresse interne dans les attributs TXT (`internal_url`, `base_url`) et le nom de la maison (`location_name`).
 */
class HaDiscovery(context: Context, private val onFound: (HaServer) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = synchronized(pending) { pending.addLast(info); next() }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "découverte : $code") }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        }
        listener = l
        runCatching { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { Log.w(TAG, "découverte", it); listener = null }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        synchronized(pending) { pending.clear() }
    }

    /** Une résolution à la fois (l'ancienne API refuse les résolutions simultanées). */
    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving || listener == null) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) = done()
            override fun onServiceResolved(info: NsdServiceInfo) {
                val attrs = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
                val host = info.host?.let { if (it is Inet6Address) "[${it.hostAddress?.substringBefore('%')}]" else it.hostAddress }
                val url = listOf(attrs["internal_url"], attrs["base_url"]).firstOrNull { !it.isNullOrBlank() && it != "None" }
                    ?: host?.let { "http://$it:${info.port}" }
                if (url != null) onFound(HaServer(attrs["location_name"]?.takeIf { it.isNotBlank() } ?: info.serviceName, url.trimEnd('/')))
                done()
            }

            private fun done() = synchronized(pending) { resolving = false; next() }
        })
    }

    companion object {
        private const val TAG = "HaDiscovery"
        private const val TYPE = "_home-assistant._tcp."
    }
}

/** Serveurs trouvés tant que l'écran est affiché. */
@Composable
fun rememberHaServers(): List<HaServer> {
    val context = LocalContext.current
    val found = remember { mutableStateListOf<HaServer>() }
    DisposableEffect(Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val d = HaDiscovery(context.applicationContext) { s -> main.post { if (found.none { it.url == s.url }) found.add(s) } }
        d.start()
        onDispose { d.stop() }
    }
    return found
}
