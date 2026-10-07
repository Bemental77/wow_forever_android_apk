package com.wowforever.gamefixes

import com.wowforever.data.GameSource

/**
 * Portal (Steam)
 */
val STEAM_Fix_400: KeyedGameFix = KeyedLaunchArgFix(
    gameSource = GameSource.STEAM,
    gameId = "400",
    launchArgs = "-game portal",
)

