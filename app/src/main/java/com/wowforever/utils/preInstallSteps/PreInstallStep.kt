package com.wowforever.utils

import com.wowforever.data.GameSource
import com.wowforever.enums.Marker
import com.winlator.container.Container
import java.io.File

interface PreInstallStep {
    val marker: Marker

    fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean

    fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String?
}
