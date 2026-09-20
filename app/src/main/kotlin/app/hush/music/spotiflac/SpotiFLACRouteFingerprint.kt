/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.preferences.core.Preferences
import app.hush.music.constants.ProxyEnabledKey
import app.hush.music.constants.ProxyHostKey
import app.hush.music.constants.ProxyPortKey
import app.hush.music.constants.ProxyTypeKey
import app.hush.music.utils.PreferenceStore
import java.net.Inet4Address

/**
 * The route the app reaches the gateway *from*, as one comparable string.
 *
 * A block is the gateway refusing an *address*, and the app can never see the address itself - only
 * the two things that decide it: which exit the traffic leaves through (Hush's proxy setting) and
 * which network carries it (Wi-Fi, mobile data, a VPN). Those are what this records, and they are
 * recorded *with* the block rather than checked once, because a route can change with nothing in the
 * app being touched.
 *
 * Measured on the reporting device, that is the difference between a working setup and a stuck one:
 * the same install the gateway refused with `{"retry_after":75358}` was served normally the moment a
 * VPN came up. Nothing noticed, so the block kept suppressing playback for the ~21 hours it had left,
 * against an address the device was no longer using - and the only way out was for the user to find
 * "Try again now".
 *
 * What is deliberately *not* in here is the network's handle or the device's address: a handle is
 * reassigned across restarts, so a fingerprint built from one would differ on every launch and the
 * app would re-ask a blocking gateway at every start - the one thing a block must never provoke.
 * Transports and the VPN flag stay put for as long as the route does, so a difference in the
 * fingerprint means the traffic really is leaving by another way.
 */
internal object SpotiFLACRouteFingerprint {

    /** The route in use, read live. */
    fun current(context: Context): String =
        of(
            proxyEnabled = PreferenceStore.get(ProxyEnabledKey),
            proxyType = PreferenceStore.get(ProxyTypeKey),
            proxyHost = PreferenceStore.get(ProxyHostKey),
            proxyPort = PreferenceStore.get(ProxyPortKey),
            network = networkTag(context),
        )

    /**
     * The route in use plus the identity of the network carrying it, read live.
     *
     * [current] is deliberately coarse - the transports and the proxy, nothing more - because it is
     * *stored with a block*, and a fingerprint that differed on every launch would turn every app
     * start into a fresh ask at a gateway that had already said no. That coarse fingerprint is also
     * blind to the commonest way a route moves on a phone: two Wi-Fi networks are both
     * `exit=direct net=wifi`, so switching between them changes nothing [current] can see, and the
     * watch had nothing to react to.
     *
     * This is the pair for that half of the job: the durable fingerprint plus which network is
     * actually carrying the traffic now, with the *settling* flag kept as a separate half. That split
     * is not cosmetic - see [liveAddress] and [addressChanged] - because the two halves answer two
     * different questions and one of them is allowed to retire what the gateway said.
     */
    fun live(context: Context): String = liveOf(liveAddress(context), networkValidated(context))

    /**
     * The route in use plus the identity of the network carrying it: everything that decides the
     * **address** the gateway sees, and nothing else.
     *
     * This is the half a remembered answer is about. A block is the gateway refusing an address, so
     * when the address is known to have changed the answer stops being about anything - which is the
     * rule [addressChanged] exists to state, and the one the app uses to stop sitting on a wait the
     * device has already left. It is deliberately free of the settling flag: a network revalidating
     * does not change the address, and treating it as one would throw away a block that is still true.
     */
    fun liveAddress(context: Context): String = liveAddressOf(current(context), networkIdentity(context))

    /**
     * The two halves put together. Pure, so the property this exists for can be pinned without a
     * device: two routes the durable fingerprint cannot tell apart are still told apart here.
     */
    internal fun liveAddressOf(
        route: String,
        identity: String,
    ): String = "$route id=$identity"

    /** [liveAddress] plus whether the network has finished validating. Pure, likewise. */
    internal fun liveOf(
        address: String,
        validated: Boolean,
    ): String = "$address validated=$validated"

    /**
     * Whether the address a stored answer was earned on is known to be a different one now.
     *
     * The same shape as [stillDescribes], and deliberately so - both are asked for the same reason, by
     * the same rule, so a block cannot be retired by one and kept by the other. A record with no address
     * on it counts as "not different": it was written before addresses were kept, and treating unknown
     * provenance as a move is how a client ends up asking a blocking gateway at every launch.
     */
    fun addressChanged(
        recorded: String?,
        current: String,
    ): Boolean = !recorded.isNullOrBlank() && recorded != current

    /**
     * The identity of the network in use, as far as the app can see it.
     *
     * The handle and the IPv4 address, not the IPv6 set: a device rotates its temporary IPv6
     * addresses while the route it is really on stays the same, and reading those would turn ordinary
     * churn into "the route moved" - the one thing a probe must not be spent on. IPv4 is assigned per
     * network and stays put for as long as the network does, which is exactly the lifetime being
     * compared.
     */
    private fun networkIdentity(context: Context): String =
        runCatching {
            val manager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return "unknown"
            val active = manager.activeNetwork ?: return TAG_NONE
            val ipv4 =
                manager.getLinkProperties(active)
                    ?.linkAddresses
                    ?.firstOrNull { it.address is Inet4Address }
                    ?.address
                    ?.hostAddress
                    .orEmpty()
            "$active|$ipv4"
        }.getOrDefault("unknown")

    /**
     * Whether the network has finished validating.
     *
     * Kept out of the address because it is not one: a network that is still coming up is the same
     * address as the same network a moment later, and only a changed *address* retires what the gateway
     * said. What it is for is the other question - a route worth asking about twice. A network that has
     * just come up cannot reach anything, so a probe made then comes back with no answer, and nothing
     * else would ask again; this flag changing is the event that does.
     */
    private fun networkValidated(context: Context): Boolean =
        runCatching {
            val manager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return false
            val active = manager.activeNetwork ?: return false
            manager.getNetworkCapabilities(active)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }.getOrDefault(false)

    /**
     * The fingerprint a stored set of preferences describes, for the proxy half only.
     *
     * Exposed because a proxy change is not something the platform reports: it is a preference, and
     * the only way to notice it is to watch the preference. The watch compares this against itself,
     * so the network half being absent here is deliberate - it would otherwise fire on every
     * network event as well, and the two halves are observed separately.
     */
    fun ofPreferences(prefs: Preferences): String =
        of(
            proxyEnabled = prefs[ProxyEnabledKey],
            proxyType = prefs[ProxyTypeKey],
            proxyHost = prefs[ProxyHostKey],
            proxyPort = prefs[ProxyPortKey],
            network = null,
        )

    /** Pure, so the rule can be pinned without a device or a network. */
    fun of(
        proxyEnabled: Boolean?,
        proxyType: String?,
        proxyHost: String?,
        proxyPort: Int?,
        network: String?,
    ): String {
        val exit =
            if (proxyEnabled != true) {
                "direct"
            } else {
                val type = proxyType?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "http"
                val host = proxyHost?.trim()?.takeIf { it.isNotEmpty() } ?: "?"
                val port = proxyPort?.takeIf { it in 1..65535 }
                "$type://$host:${port ?: "?"}"
            }
        return "exit=$exit net=${network?.takeIf { it.isNotBlank() } ?: "unknown"}"
    }

    /**
     * Whether a fingerprint describes a route that carries no traffic at all.
     *
     * Asked before anything is sent: a device that has just left Wi-Fi and has not found mobile data
     * yet has no network, and a request made from there can only fail - a failure the app would then
     * have to tell apart from the gateway refusing it. The next event, the network coming up, is the
     * one worth asking about.
     */
    fun carriesNoNetwork(route: String): Boolean = route.endsWith("net=$TAG_NONE")

    /**
     * Whether a route recorded with a block still describes the route in use.
     *
     * A missing record counts as "still describes it". A block written before this existed, or one
     * written while the network could not be read, is still evidence the gateway gave - and treating
     * unknown provenance as "different route" would turn every one of them into a fresh ask at a
     * gateway that had already said no, which is how a block gets extended rather than cleared.
     */
    fun stillDescribes(
        recorded: String?,
        current: String,
    ): Boolean = recorded.isNullOrBlank() || recorded == current

    /**
     * Which transports are carrying this device's traffic.
     *
     * Transports rather than the network's identity, for the reason in the class comment. A VPN is
     * the case this exists for - it is what changes the address the gateway sees without the user
     * touching Hush - and it arrives here as an added transport on the active network, which is
     * exactly how `NetworkCapabilities` reports it.
     */
    private fun networkTag(context: Context): String? =
        runCatching {
            val manager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return null
            val active = manager.activeNetwork ?: return TAG_NONE
            val capabilities = manager.getNetworkCapabilities(active) ?: return "unknown"
            TRANSPORTS
                .filter { (_, carries) -> carries(capabilities) }
                .joinToString("+") { (name, _) -> name }
                .ifEmpty { "none" }
        }.getOrNull()

    /** The network tag for a device with no active network at all. */
    private const val TAG_NONE = "none"

    /**
     * Ordered, so the same set of transports always renders the same way - a fingerprint that
     * reordered itself would look like a route change.
     */
    private val TRANSPORTS: List<Pair<String, (NetworkCapabilities) -> Boolean>> =
        listOf(
            "vpn" to { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
            "wifi" to { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
            "cell" to { it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
            "ethernet" to { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
            "bluetooth" to { it.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) },
        )
}
