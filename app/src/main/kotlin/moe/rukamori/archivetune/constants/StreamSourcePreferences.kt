/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.constants

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey

val StreamSourceWebRemixKey = booleanPreferencesKey("streamSourceWebRemix")
val StreamSourceTVHTML5Key = booleanPreferencesKey("streamSourceTVHTML5")
val StreamSourceVisionOSKey = booleanPreferencesKey("streamSourceVisionOS")
val StreamSourceAndroidVRKey = booleanPreferencesKey("streamSourceAndroidVR")
val StreamSourceIOSKey = booleanPreferencesKey("streamSourceIOS")
val StreamSourceWebCreatorKey = booleanPreferencesKey("streamSourceWebCreator")
val StreamSourceAndroidCreatorKey = booleanPreferencesKey("streamSourceAndroidCreator")

object StreamSourcePreferences {
    /** Client names disabled in Settings → Stream sources. */
    fun disabledClientNames(prefs: Preferences): Set<String> =
        buildSet {
            if (prefs[StreamSourceWebRemixKey] == false) add("WEB_REMIX")
            if (prefs[StreamSourceTVHTML5Key] == false) add("TVHTML5")
            if (prefs[StreamSourceVisionOSKey] == false) add("VISIONOS")
            if (prefs[StreamSourceAndroidVRKey] == false) add("ANDROID_VR")
            if (prefs[StreamSourceIOSKey] == false) add("IOS")
            if (prefs[StreamSourceWebCreatorKey] == false) add("WEB_CREATOR")
            if (prefs[StreamSourceAndroidCreatorKey] == false) add("ANDROID_CREATOR")
        }

    fun disabledClientNames(
        webRemix: Boolean,
        tvHtml5: Boolean,
        visionOs: Boolean,
        androidVr: Boolean,
        ios: Boolean,
        webCreator: Boolean,
        androidCreator: Boolean,
    ): Set<String> =
        buildSet {
            if (!webRemix) add("WEB_REMIX")
            if (!tvHtml5) add("TVHTML5")
            if (!visionOs) add("VISIONOS")
            if (!androidVr) add("ANDROID_VR")
            if (!ios) add("IOS")
            if (!webCreator) add("WEB_CREATOR")
            if (!androidCreator) add("ANDROID_CREATOR")
        }

    /** Collapse versioned client names into the families shown in Stream sources settings. */
    fun normalizeClientFamily(clientName: String): String =
        when {
            clientName == "ANDROID_VR_NO_AUTH" || clientName.startsWith("ANDROID_VR") -> "ANDROID_VR"
            clientName.startsWith("TVHTML5") -> "TVHTML5"
            clientName == "IOS_MUSIC" || clientName == "IPADOS" -> "IOS"
            else -> clientName
        }

    val toggleableFamilies =
        setOf(
            "WEB_REMIX",
            "TVHTML5",
            "VISIONOS",
            "ANDROID_VR",
            "IOS",
            "WEB_CREATOR",
            "ANDROID_CREATOR",
        )
}
