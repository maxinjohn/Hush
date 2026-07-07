/* SPDX-License-Identifier: GPL-3.0-only */
/*
 * Hush — Ported from Echo Music
 * Device-based JioSaavn server routing for better connectivity.
 * Original: github.com/EchoMusicApp/Echo-Music
 */

package app.hush.music.jiosaavn

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.math.abs

/**
 * Routes JioSaavn API requests to the best available server based on
 * device identity and remote server configuration. Falls back to alternative
 * servers automatically on failure.
 */
object DeviceRouter {
    private const val TAG = "DeviceRouter"
    private const val PREFS_NAME = "hush_saavn_router"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_CACHED_SERVERS = "cached_saavn_servers"
    private val REMOTE_CONFIG_URLS = listOf(
        "https://echomusic.fun/saavn.json",
        "https://raw.githubusercontent.com/anomalyco/hush-config/main/saavn.json",
    )
    private const val API_BASE_FALLBACK = "https://www.jiosaavn.com/api.php"

    @Volatile
    private var activeServers = emptyList<String>()

    @Volatile
    private var deviceId: String? = null

    @Volatile
    private var assignedServerIndex: Int = 0

    @Volatile
    private var currentSessionServerIndex: Int = 0

    @Volatile
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val id = prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            newId
        }
        deviceId = id

        val cachedServersJson = prefs.getString(KEY_CACHED_SERVERS, null)
        if (cachedServersJson != null) {
            try {
                val config = JSONObject(cachedServersJson)
                val serversArray = config.optJSONArray("servers")
                val servers = mutableListOf<String>()
                if (serversArray != null) {
                    for (i in 0 until serversArray.length()) {
                        servers.add(serversArray.getString(i))
                    }
                }
                if (servers.isNotEmpty()) {
                    activeServers = servers
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse cached servers", e)
            }
        }

        assignServer()
        isInitialized = true

        // Fetch remote server config in background
        Thread {
            for (configUrl in REMOTE_CONFIG_URLS) {
                try {
                    val url = URL(configUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.connect()

                    if (connection.responseCode in 200..299) {
                        val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonObject = JSONObject(jsonText)
                        val serversArray = jsonObject.optJSONArray("servers")
                        val fetchedServers = mutableListOf<String>()
                        if (serversArray != null) {
                            for (i in 0 until serversArray.length()) {
                                fetchedServers.add(serversArray.getString(i))
                            }
                        }
                        if (fetchedServers.isNotEmpty()) {
                            activeServers = fetchedServers
                            assignServer()
                            val configToSave = JSONObject()
                            configToSave.put("version", jsonObject.optInt("version", 1))
                            configToSave.put("servers", JSONArray(fetchedServers))
                            prefs.edit().putString(KEY_CACHED_SERVERS, configToSave.toString()).apply()
                            Log.i(TAG, "Updated server list: ${fetchedServers.size} servers")
                        }
                    }
                    connection.disconnect()
                    return@Thread
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch remote server config from $configUrl", e)
                }
            }
        }.start()
    }

    private fun assignServer() {
        val id = deviceId ?: return
        if (activeServers.isEmpty()) return
        assignedServerIndex = abs(id.hashCode()) % activeServers.size
        currentSessionServerIndex = assignedServerIndex
    }

    /**
     * Returns the server URL for the current session's assigned server.
     * Falls back to the default API_BASE if not initialized or no servers available.
     */
    fun getCurrentServer(): String {
        if (!isInitialized || activeServers.isEmpty()) {
            Log.w(TAG, "DeviceRouter not initialized or no servers — using API_BASE")
            return API_BASE_FALLBACK
        }
        return activeServers[currentSessionServerIndex]
    }

    /**
     * Whether at least one server is available.
     */
    fun hasServers(): Boolean {
        if (!isInitialized) return false
        return activeServers.isNotEmpty()
    }

    /**
     * Cycles to the next server in the list for this session.
     * Used when the current server returns errors (5xx).
     */
    fun fallbackToNextServer() {
        if (!isInitialized || activeServers.isEmpty()) return
        currentSessionServerIndex = (currentSessionServerIndex + 1) % activeServers.size
        Log.i(TAG, "Falling back to server index $currentSessionServerIndex/${activeServers.size}")
    }

    /**
     * Returns the current device ID for debugging/UI purposes.
     */
    fun getDeviceId(): String {
        if (!isInitialized) return "uninitialized"
        return deviceId ?: "uninitialized"
    }

    /**
     * Returns the number of available servers.
     */
    fun serverCount(): Int = activeServers.size
}
