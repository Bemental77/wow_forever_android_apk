package com.wowforever.workshop.compatibility

import com.wowforever.data.GameSource

object WorkshopCompatibilityRegistry {
    private val overrides: Map<Pair<GameSource, String>, WorkshopCompatibilityOverride> = listOf(
        STEAM_WorkshopOverride_331470,
    ).associate { keyedOverride ->
        (keyedOverride.gameSource to keyedOverride.gameId) to keyedOverride.override
    }

    private var overridesProvider: () -> Map<Pair<GameSource, String>, WorkshopCompatibilityOverride> = {
        overrides
    }

    fun get(source: GameSource, gameId: String): WorkshopCompatibilityOverride? =
        overridesProvider()[source to gameId]

    internal fun setOverridesProviderForTests(
        provider: (() -> Map<Pair<GameSource, String>, WorkshopCompatibilityOverride>)?,
    ) {
        overridesProvider = provider ?: { overrides }
    }
}
