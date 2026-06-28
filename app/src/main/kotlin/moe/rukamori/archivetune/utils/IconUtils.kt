/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import timber.log.Timber

object IconUtils {
    private const val DYNAMIC_ALIAS_CLASS = "MainActivityAlias"
    private const val STATIC_ALIAS_CLASS = "MainActivityStatic"

    private fun dynamicAlias(context: Context): ComponentName =
        ComponentName(context.packageName, "${context.packageName}.$DYNAMIC_ALIAS_CLASS")

    private fun staticAlias(context: Context): ComponentName =
        ComponentName(context.packageName, "${context.packageName}.$STATIC_ALIAS_CLASS")

    fun isDynamicIconEnabled(context: Context): Boolean {
        val state = context.packageManager.getComponentEnabledSetting(dynamicAlias(context))
        return when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> true
        }
    }

    fun setIcon(
        context: Context,
        dynamicEnabled: Boolean,
    ) {
        val packageManager = context.packageManager
        val dynamic = dynamicAlias(context)
        val static = staticAlias(context)
        val enabledState =
            if (dynamicEnabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        val disabledState =
            if (dynamicEnabled) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PackageManager.DONT_KILL_APP or PackageManager.SYNCHRONOUS
            } else {
                PackageManager.DONT_KILL_APP
            }

        runCatching {
            packageManager.setComponentEnabledSetting(dynamic, enabledState, flags)
            packageManager.setComponentEnabledSetting(static, disabledState, flags)
            requestLauncherRefresh(context)
            Timber.i(
                "Updated launcher icon alias: dynamic=%s (%s enabled=%s), static=%s (enabled=%s)",
                dynamicEnabled,
                dynamic.flattenToShortString(),
                packageManager.getComponentEnabledSetting(dynamic),
                static.flattenToShortString(),
                packageManager.getComponentEnabledSetting(static),
            )
        }.onFailure { error ->
            Timber.e(error, "Failed to update launcher icon alias")
            reportException(error)
        }
    }

    private fun requestLauncherRefresh(context: Context) {
        val packageName = context.packageName
        runCatching {
            context.sendBroadcast(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(packageName)
                },
            )
        }
    }
}
