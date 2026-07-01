/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.utils

import androidx.navigation.NavController
import app.hush.music.ui.screens.Screens

fun NavController.backToMain() {
    val mainRoutes =
        listOf(
            Screens.ROUTE_HOME,
            Screens.ROUTE_SEARCH,
            Screens.ROUTE_LIBRARY,
        )

    while (previousBackStackEntry != null &&
        currentBackStackEntry?.destination?.route !in mainRoutes
    ) {
        popBackStack()
    }
}
