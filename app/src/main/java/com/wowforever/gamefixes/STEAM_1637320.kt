package com.wowforever.gamefixes

import com.wowforever.data.GameSource

/**
 * Dome Keeper (Steam)
 */
val STEAM_Fix_1637320: KeyedGameFix = KeyedLaunchArgFix(
    gameSource = GameSource.STEAM,
    gameId = "1637320",
    launchArgs = "--rendering-driver vulkan",
)

