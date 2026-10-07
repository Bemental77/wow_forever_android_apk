package com.wowforever.gamefixes

import com.wowforever.data.GameSource

/**
 * Stardew Valley (Steam)
 */
val STEAM_Fix_413150: KeyedGameFix = KeyedWineEnvVarFix(
    gameSource = GameSource.STEAM,
    gameId = "413150",
    envVarsToSet = mapOf(
        "WINEDLLOVERRIDES" to "icu=n",
    ),
)
