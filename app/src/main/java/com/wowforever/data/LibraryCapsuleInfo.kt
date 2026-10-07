package com.wowforever.data

import com.wowforever.enums.Language
import kotlinx.serialization.Serializable

@Serializable
data class LibraryCapsuleInfo(
    val image: Map<Language, String> = emptyMap(),
    val image2x: Map<Language, String> = emptyMap(),
)
