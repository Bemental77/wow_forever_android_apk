package com.wowforever.gamefixes

import com.wowforever.data.GameSource

/**
 * The Elder Scrolls IV: Oblivion (Steam)
 */
val STEAM_Fix_22330: KeyedGameFix = KeyedRegistryKeyFix(
    gameSource = GameSource.STEAM,
    gameId = "22330",
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\Oblivion",
    defaultValues = mapOf(
        "Installed Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
