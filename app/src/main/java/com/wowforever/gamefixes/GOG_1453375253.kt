package com.wowforever.gamefixes

import com.wowforever.data.GameSource

/**
 * Stardew Valley (GOG)
 */
val GOG_Fix_1453375253: KeyedGameFix = KeyedWineEnvVarFix(
    gameSource = GameSource.GOG,
    gameId = "1453375253",
    envVarsToSet = mapOf(
        "WINEDLLOVERRIDES" to "icu=n",
    ),
)
