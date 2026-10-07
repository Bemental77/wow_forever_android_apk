package com.wowforever.powercontrol

import com.wowforever.powercontrol.drivers.PServerDriver
import com.wowforever.powercontrol.drivers.PerformanceDriver
import com.wowforever.wow.Wow
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Hard GPU max-frequency cap for the WoW session.
 *
 * On the Retroid Pocket 5 (SD865 / Adreno 650) the kernel exposes a 670 MHz bin above the stock
 * 587 MHz; WoW at 670 MHz hangs the GPU every few minutes, while capped it holds 60 fps with no
 * hangs. While a WoW session runs this caps kgsl max_pwrlevel to the fastest bin at or below
 * [WOW_GPU_MAX_MHZ], independent of the user's power-control setting, re-asserts it periodically
 * (the system performance-mode service may raise it again), and restores the previous value when
 * the session stops or goes to the background.
 *
 * Only works through [PServerDriver]; on any other driver it is a logged no-op.
 */
object WowGpuCap {
    private const val TAG = "WowGpuCap"

    /** Highest GPU frequency WoW may use. Stock SD865 max is 587; lower to 490 if hangs remain. */
    const val WOW_GPU_MAX_MHZ = 587L

    private const val REASSERT_INTERVAL_SEC = 10L
    private const val RESTORE_TIMEOUT_MS = 2000L

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "WowGpuCap").apply { isDaemon = true }
    }

    // Confined to the scheduler thread, except [reassertTask] which is only touched under the lock.
    private var driver: PServerDriver? = null
    private var targetSysfsLevel: Int = -1
    private var previousSysfsLevel: Int? = null
    private var reassertTask: ScheduledFuture<*>? = null

    /** True when [rootDir] is the WoW container (".../xuser-CUSTOM_GAME_2004"). */
    fun isWowContainer(rootDir: File?): Boolean =
        rootDir != null && rootDir.name.endsWith(Wow.APP_ID)

    /**
     * Apply the cap and start re-asserting it. Returns immediately; work runs on a background thread.
     */
    @Synchronized
    fun start(performanceDriver: PerformanceDriver) {
        reassertTask?.cancel(false)
        reassertTask = null

        val pserver = performanceDriver as? PServerDriver
        val supported = pserver != null && pserver.isDriverSupported() && pserver.isGpuSupported()
        Timber.tag(TAG).i(
            "Session start: supported=$supported (driver=${performanceDriver.javaClass.simpleName}, " +
                "pserver=${pserver?.isDriverSupported()}, gpu=${pserver?.isGpuSupported()})"
        )
        if (!supported || pserver == null) return

        try {
            scheduler.execute { safely("apply") { apply(pserver) } }
            reassertTask = scheduler.scheduleWithFixedDelay(
                { safely("reassert") { reassert() } },
                REASSERT_INTERVAL_SEC,
                REASSERT_INTERVAL_SEC,
                TimeUnit.SECONDS
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to schedule GPU cap")
        }
    }

    /**
     * Stop re-asserting and restore the previous max level. Blocks briefly (bounded) so the restore
     * lands before the power driver shuts its root worker down.
     */
    @Synchronized
    fun stop() {
        reassertTask?.cancel(false)
        reassertTask = null
        try {
            scheduler.submit { safely("restore") { restore() } }
                .get(RESTORE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "GPU cap restore did not finish in time")
        }
    }

    private fun apply(pserver: PServerDriver) {
        pserver.ensureExecutor()

        val freqsKHz = pserver.getAvailableGpuFrequencies().distinct().sortedDescending()
        val numLevels = pserver.getNumGpuPowerLevels()
        Timber.tag(TAG).i(
            "GPU levels: num_pwrlevels=$numLevels, freqs(MHz, level 0 first)=${freqsKHz.map { it / 1000 }}"
        )
        if (freqsKHz.isEmpty() || numLevels <= 0) {
            Timber.tag(TAG).w("GPU frequency table unreadable, cap not applied")
            return
        }

        // Level index i == i-th fastest frequency (kgsl pwrlevel table is ordered fastest first)
        val capKHz = WOW_GPU_MAX_MHZ * 1000
        var level = freqsKHz.indexOfFirst { it <= capKHz }
        if (level < 0) level = freqsKHz.lastIndex
        if (level == 0) {
            Timber.tag(TAG).i("Fastest bin ${freqsKHz[0] / 1000} MHz is already <= $WOW_GPU_MAX_MHZ MHz, no cap needed")
            return
        }
        if (level >= numLevels) {
            Timber.tag(TAG).w("Chosen level $level outside num_pwrlevels=$numLevels, cap not applied")
            return
        }

        val current = pserver.readGpuMaxPwrLevelSysfs()
        if (current == null) {
            Timber.tag(TAG).w("max_pwrlevel unreadable, cap not applied")
            return
        }

        driver = pserver
        targetSysfsLevel = level
        // A repeated start() without stop() must not record the cap itself as the previous value
        if (previousSysfsLevel == null) previousSysfsLevel = current
        pserver.setGpuCeiling(level, previousSysfsLevel)

        Timber.tag(TAG).i(
            "Chosen level $level = ${freqsKHz[level] / 1000} MHz (cap $WOW_GPU_MAX_MHZ MHz), " +
                "current max_pwrlevel=$current, previous=$previousSysfsLevel"
        )
        if (current < level) {
            val ok = pserver.writeGpuMaxPwrLevelSysfs(level)
            Timber.tag(TAG).i("Applied max_pwrlevel=$level: ${if (ok) "success" else "FAILED"}")
        } else {
            Timber.tag(TAG).i("max_pwrlevel=$current already at or below cap, ceiling armed")
        }
    }

    private fun reassert() {
        val pserver = driver ?: return
        val target = targetSysfsLevel
        if (target < 0) return
        pserver.ensureExecutor()
        val current = pserver.readGpuMaxPwrLevelSysfs() ?: return
        if (current < target) {
            val ok = pserver.writeGpuMaxPwrLevelSysfs(target)
            Timber.tag(TAG).w("max_pwrlevel was raised to $current, re-applied $target: ${if (ok) "success" else "FAILED"}")
        }
    }

    private fun restore() {
        val pserver = driver
        val previous = previousSysfsLevel
        driver = null
        targetSysfsLevel = -1
        previousSysfsLevel = null
        if (pserver == null) return

        pserver.clearGpuCeiling()
        if (previous == null) {
            Timber.tag(TAG).i("Restored: ceiling cleared (no previous level recorded)")
            return
        }
        pserver.ensureExecutor()
        val current = pserver.readGpuMaxPwrLevelSysfs()
        if (current == previous) {
            Timber.tag(TAG).i("Restored: max_pwrlevel already $previous")
            return
        }
        val ok = pserver.writeGpuMaxPwrLevelSysfs(previous)
        Timber.tag(TAG).i("Restored max_pwrlevel $current -> $previous: ${if (ok) "success" else "FAILED"}")
    }

    private inline fun safely(step: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "GPU cap $step failed")
        }
    }
}
