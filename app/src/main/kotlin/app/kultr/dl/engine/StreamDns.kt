package app.kultr.dl.engine

import java.net.Inet4Address
import java.net.InetAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * YouTube stream links are bound to the address that asked for them
 * ("ip=" in the link). If the player then connects over the other family
 * (IPv6 instead of IPv4, or back), YouTube answers 403. This makes the
 * player connect to each stream host over the family its link names.
 */
object StreamDns : Dns {
    private val ipv4ByHost = ConcurrentHashMap<String, Boolean>()

    fun pin(url: String) {
        val host = url.toHttpUrlOrNull()?.host ?: return
        val ip = Regex("[?&]ip=([^&]+)").find(url)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?: return
        ipv4ByHost[host] = !ip.contains(':')
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        val ipv4 = ipv4ByHost[hostname] ?: return all
        return all.filter { (it is Inet4Address) == ipv4 }.ifEmpty { all }
    }
}
