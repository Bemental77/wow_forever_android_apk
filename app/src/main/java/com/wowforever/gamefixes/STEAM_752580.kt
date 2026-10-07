package com.wowforever.gamefixes

import com.wowforever.data.GameSource

val STEAM_Fix_752580: KeyedGameFix = KeyedIniFileFix(
    gameSource = GameSource.STEAM,
    gameId = "752580",
    relativePath = "Settings.ini",
    defaultValues = linkedMapOf(
        "Music" to "0",
        "SoundFX" to "1",
        "Speech" to "1",
    ),
)
