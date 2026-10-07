package com.wowforever.gamefixes

import com.wowforever.data.GameSource

/**
 * Look Outside (Steam)
 */
val STEAM_Fix_3373660: KeyedGameFix = KeyedLaunchArgFix(
    gameSource = GameSource.STEAM,
    gameId = "3373660",
    launchArgs = "--no-sandbox",
)

