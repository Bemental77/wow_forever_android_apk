package com.wowforever.utils

import android.content.Context
import com.wowforever.R
import com.wowforever.data.GameSource
import com.wowforever.utils.launchdependencies.BionicDefaultProtonDependency
import com.wowforever.utils.launchdependencies.EpicOverlayDependency
import com.wowforever.utils.launchdependencies.GogScriptInterpreterDependency
import com.wowforever.utils.launchdependencies.LaunchDependencyCallbacks
import com.wowforever.utils.launchdependencies.LaunchDependency
import com.wowforever.utils.launchdependencies.BionicSteamAssetsDependency
import com.winlator.container.Container

const val LOADING_PROGRESS_UNKNOWN: Float = -1f

/**
 * Ensures all dependencies required to launch a container are downloaded and installed.
 * Reports progress via the given callbacks.
 * [gameSource] and [gameId] are extracted once by the caller (e.g. PluviaMain) and passed down.
 */
class LaunchDependencies {
    companion object {
        private val launchDependencies: List<LaunchDependency> = listOf(
            BionicDefaultProtonDependency,
            GogScriptInterpreterDependency,
            EpicOverlayDependency,
            BionicSteamAssetsDependency,
        )

        private var dependenciesProvider: () -> List<LaunchDependency> = { launchDependencies }
    }

    fun getLaunchDependencies(container: Container, gameSource: GameSource, gameId: Int): List<LaunchDependency> =
        dependenciesProvider().filter { it.appliesTo(container, gameSource, gameId) }

    suspend fun ensureLaunchDependencies(
        context: Context,
        container: Container,
        gameSource: GameSource,
        gameId: Int,
        setLoadingMessage: (String) -> Unit,
        setLoadingProgress: (Float) -> Unit,
    ) {
        val callbacks = LaunchDependencyCallbacks(setLoadingMessage, setLoadingProgress)
        try {
            for (dep in getLaunchDependencies(container, gameSource, gameId)) {
                if (!dep.isSatisfied(context, container, gameSource, gameId)) {
                    setLoadingMessage(dep.getLoadingMessage(context, container, gameSource, gameId))
                    dep.install(context, container, callbacks, gameSource, gameId)
                }
            }
        } finally {
            setLoadingMessage(context.getString(R.string.main_loading))
            setLoadingProgress(1f)
        }
    }

    /**
     * Test-only hook to override the launch dependency provider.
     * Not intended for production code paths.
     *
     * @param provider Dependency provider for tests; pass null to restore the default provider.
     */
    internal fun setDependenciesProviderForTests(provider: (() -> List<LaunchDependency>)?) {
        dependenciesProvider = provider ?: { launchDependencies }
    }
}
