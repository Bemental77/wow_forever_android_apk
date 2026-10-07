package com.wowforever.gamefixes

import com.wowforever.data.GameSource

/**
 * Whisk Demo (Steam)
 */
val STEAM_Fix_4320000: KeyedGameFix = KeyedBionicSteamClientFix(
    gameSource = GameSource.STEAM,
    gameId = "4320000",
)
