package com.anderson.onedrivesync.ui.nav

/**
 * Every destination of the app. [ONBOARDING] and [WIZARD] are full-screen: they hide the bottom
 * navigation bar.
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val PAIRS = "pairs"
    const val CONFLICTS = "conflicts"
    const val PENDING = "pending"
    const val LOG = "log"
    const val SETTINGS = "settings"
    const val WIZARD = "wizard"

    val bottomBarRoutes: Set<String> = setOf(PAIRS, CONFLICTS, PENDING, LOG, SETTINGS)
}
