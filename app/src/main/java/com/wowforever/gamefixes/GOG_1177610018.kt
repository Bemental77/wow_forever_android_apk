package com.wowforever.gamefixes

import com.wowforever.data.GameSource

/**
 * Of Orcs and Men (GOG)
 */
val GOG_Fix_1177610018: KeyedGameFix = KeyedLaunchArgFix(
    gameSource = GameSource.GOG,
    gameId = "1177610018",
    launchArgs = "-lang=eng",
)

