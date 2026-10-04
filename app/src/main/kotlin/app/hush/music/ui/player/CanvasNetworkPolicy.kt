/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.player

import java.io.IOException

/**
 * The Wi-Fi-only canvas policy, shared with the canvas video download client.
 *
 * Resolving canvas artwork is gated where it happens (Player/Thumbnail compute
 * `allowNetwork` from the same inputs), but video downloads keep running in the
 * background after a resolve finishes. The cache client consults this policy so a
 * download started before Wi-Fi-only was enabled — or after Wi-Fi was lost — is
 * aborted instead of completing on a forbidden network.
 *
 * The composables that already observe the preference and the network push updates
 * here; the defaults preserve today's behavior before any observer has run.
 */
object CanvasNetworkPolicy {
    @Volatile
    private var wifiOnlyEnabled = false

    @Volatile
    private var wifiActive = true

    val allowed: Boolean
        get() = !wifiOnlyEnabled || wifiActive

    fun update(
        wifiOnly: Boolean,
        wifiActive: Boolean,
    ) {
        wifiOnlyEnabled = wifiOnly
        this.wifiActive = wifiActive
    }

    fun requireAllowed() {
        if (!allowed) {
            throw IOException("Canvas network access is disabled by the current policy")
        }
    }
}
