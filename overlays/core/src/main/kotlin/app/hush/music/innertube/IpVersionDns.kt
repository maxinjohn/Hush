/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.innertube

import app.hush.music.innertube.models.IpVersion
import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

fun Dns.withIpVersionPreference(ipVersion: IpVersion): Dns {
    if (ipVersion == IpVersion.AUTO) return this
    return object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = this@withIpVersionPreference.lookup(hostname)
            return when (ipVersion) {
                IpVersion.IPV4 -> addresses.filterIsInstance<Inet4Address>().ifEmpty { addresses }
                IpVersion.IPV6 -> addresses.filterIsInstance<Inet6Address>().ifEmpty { addresses }
                IpVersion.AUTO -> addresses
            }
        }
    }
}
