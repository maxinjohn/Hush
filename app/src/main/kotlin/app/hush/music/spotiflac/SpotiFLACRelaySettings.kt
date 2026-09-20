/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import app.hush.music.constants.ProxyEnabledKey
import app.hush.music.constants.ProxyHostKey
import app.hush.music.constants.ProxyPasswordKey
import app.hush.music.constants.ProxyPortKey
import app.hush.music.constants.ProxyTypeKey
import app.hush.music.constants.ProxyUsernameKey
import app.hush.music.constants.SpotiFLACRelayUrlKey
import app.hush.music.utils.PreferenceStore
import app.hush.music.utils.ProxyUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * The exit the relay calls leave through, taken from Hush's own proxy setting.
 *
 * Held as the resolved [Proxy] plus the credentials the Internet screen collected, rather than as a
 * second set of fields: the app already has one place that says how it reaches the network, and a
 * SpotiFLAC-only proxy would be a second answer to the same question. That screen's own
 * `ProxyUtils.createProxyOrNull` is what builds the proxy here too, so "enabled", "host", "port" and
 * "type" can only mean one thing in this app.
 *
 * @param username credentials the proxy asks for, or null.
 * @param password paired with [username]; the two are only applied together.
 */
data class RelayEgress(
    val proxy: Proxy,
    val username: String? = null,
    val password: String? = null,
) {
    val hasCredentials: Boolean get() = !username.isNullOrBlank()

    /** How a settings row says it. Never includes the credentials. */
    fun describe(): String {
        val address = proxy.address() as? InetSocketAddress
        val kind = if (proxy.type() == Proxy.Type.SOCKS) "SOCKS" else "HTTP"
        val where = address?.let { "${it.hostString}:${it.port}" } ?: "?"
        return "$kind $where" + if (hasCredentials) " (with credentials)" else ""
    }
}

/** Where the gateway session is reached, and through what. */
data class SpotiFLACRelayEndpoint(
    val baseUrl: String,
    val egress: RelayEgress?,
) {
    val isDefaultBaseUrl: Boolean get() = baseUrl == SpotiFLACRelayStore.DEFAULT_BASE_URL

    val isDirect: Boolean get() = egress == null

    /** One line for a settings row, naming only what the app is actually going to use. */
    fun describe(): String =
        if (egress == null) {
            "Direct connection"
        } else {
            "Via the proxy in Internet settings: ${egress.describe()}"
        }
}

/**
 * The relay address every one of Hush's own gateway calls goes to - and only that.
 *
 * ## Why this exists
 *
 * The gateway refuses a client by its *address*: measured on the reporting device,
 * `{"error":"Temporarily blocked. Please try again later.","retry_after":75358}` came back for
 * `/health` and `/session/refresh` alike, with no Cloudflare challenge header, and the
 * same install on a VPN was served normally. Nothing the app can do clears that, and a verification
 * cannot help - the challenge endpoint answers inside the same block. What a user *can* do is arrive
 * from somewhere else, which is Hush's own proxy on the Internet screen: it changes the address every
 * request leaves from, and it is the only place that is set - there is no SpotiFLAC-only proxy, and
 * no relay address to configure, because both were a second answer to a question the app already
 * asks once.
 *
 * ## What it covers, and what it does not
 *
 * This routes the gateway session Hush holds - bootstrap, challenge, grant exchange, renewal, the
 * health probe, and the source test that reports on it. It does **not** reach the provider downloads,
 * which run inside the engine: its transports are built in Go (`sharedTransport`,
 * `extensionAPITransport`) with no proxy and no environment lookup, and each extension's relay host is
 * validated against its own declared `permissions.network`, so a self-hosted relay cannot be forced
 * on it from here either. A download therefore follows the device's own route, which is the honest
 * thing for the screen to say.
 */
object SpotiFLACRelayStore {

    /** The built-in relay, and what an unset address means. */
    const val DEFAULT_BASE_URL = SpotiFLACInstallIdentity.BASE_URL

    /**
     * A relay address as the app will use it, or null when [raw] cannot be one.
     *
     * HTTPS only, for the same reason the *runtime* requires it of an extension's own
     * `signedSession.baseUrl`: these requests carry a session id and secret, so a self-hosted relay
     * reached over cleartext would put them on the wire. A bare host with no scheme is accepted as
     * https, because that is what a person types.
     */
    fun normalizeBaseUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.trim().orEmpty()
        if (host.isEmpty()) return null
        val port = uri.port
        val authority = if (port > 0) "$host:$port" else host
        val path = uri.path.orEmpty().trimEnd('/')
        return "https://$authority$path"
    }

    /**
     * The proxy Hush's Internet screen has enabled, or null when it has not.
     *
     * Read on every use rather than cached, so switching the proxy on takes effect on the next relay
     * call instead of at the next app start - which matters most for the one case this exists for,
     * where somebody has just enabled a proxy precisely because the current route is refused.
     */
    fun egress(): RelayEgress? {
        if (PreferenceStore.get(ProxyEnabledKey) != true) return null
        val type =
            Proxy.Type.values().firstOrNull {
                it.name.equals(PreferenceStore.get(ProxyTypeKey)?.trim(), ignoreCase = true)
            } ?: Proxy.Type.HTTP
        // The same builder the Internet screen uses, so an incomplete proxy is absent here for the
        // same reason it is absent there, instead of becoming a client that fails every request.
        val proxy =
            ProxyUtils.createProxyOrNull(
                type = type,
                host = PreferenceStore.get(ProxyHostKey),
                port = PreferenceStore.get(ProxyPortKey),
            ) ?: return null
        val username = PreferenceStore.get(ProxyUsernameKey)?.trim()?.takeIf { it.isNotEmpty() }
        val password = PreferenceStore.get(ProxyPasswordKey)?.takeIf { it.isNotEmpty() }
        return RelayEgress(proxy = proxy, username = username, password = password)
    }

    /** What the app will actually use: the configured relay address, through the configured exit. */
    fun endpoint(): SpotiFLACRelayEndpoint =
        endpointOf(baseUrl = PreferenceStore.get(SpotiFLACRelayUrlKey))

    /**
     * The endpoint a stored address describes.
     *
     * A stored address is still honoured - an install that was pointed at a relay of its own while
     * that was a setting keeps working - but nothing offers to change it any more: what a blocked
     * client needs is a different *exit*, which Hush's proxy setting provides, and a second way to
     * say where the gateway lives was a second answer to a question already answered.
     */
    fun endpointOf(baseUrl: String?): SpotiFLACRelayEndpoint =
        SpotiFLACRelayEndpoint(
            baseUrl = normalizeBaseUrl(baseUrl) ?: DEFAULT_BASE_URL,
            egress = egress(),
        )

    /** The relay client, routed through Hush's proxy when one is enabled. */
    fun okHttp(
        endpoint: SpotiFLACRelayEndpoint,
        readTimeoutMs: Long,
        connectTimeoutMs: Long = 15_000L,
    ): OkHttpClient {
        val builder =
            OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .callTimeout(readTimeoutMs + connectTimeoutMs, TimeUnit.MILLISECONDS)
        endpoint.egress?.let { egress ->
            builder.proxy(egress.proxy)
            if (egress.hasCredentials) {
                builder.proxyAuthenticator { _, response ->
                    response.request.newBuilder()
                        .header(
                            "Proxy-Authorization",
                            Credentials.basic(egress.username.orEmpty(), egress.password.orEmpty()),
                        )
                        .build()
                }
            }
        }
        return builder.build()
    }

    private var cachedKtor: Pair<SpotiFLACRelayEndpoint, HttpClient>? = null

    /**
     * The same routing for Ktor's client, which is what the gateway session itself is spoken over.
     *
     * Cached against the endpoint it was built for, because a Ktor client owns a thread pool and
     * rebuilding one per request would cost more than the calls it makes - and cached *by* it, so a
     * changed proxy takes effect on the next call instead of at the next app start.
     */
    fun ktor(endpoint: SpotiFLACRelayEndpoint): HttpClient {
        cachedKtor?.let { (cached, client) -> if (cached == endpoint) return client }
        val client =
            HttpClient(OkHttp) {
                engine {
                    config {
                        endpoint.egress?.let { egress ->
                            proxy(egress.proxy)
                            if (egress.hasCredentials) {
                                proxyAuthenticator { _, response ->
                                    response.request.newBuilder()
                                        .header(
                                            "Proxy-Authorization",
                                            Credentials.basic(
                                                egress.username.orEmpty(),
                                                egress.password.orEmpty(),
                                            ),
                                        )
                                        .build()
                                }
                            }
                        }
                    }
                }
            }
        cachedKtor = endpoint to client
        return client
    }
}
