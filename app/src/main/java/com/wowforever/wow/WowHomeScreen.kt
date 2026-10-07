package com.wowforever.wow

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wowforever.ui.component.dialog.state.MessageDialogState
import com.wowforever.ui.model.MainViewModel
import com.wowforever.utils.ContainerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber

private sealed class WowPhase {
    data object Checking : WowPhase()
    data object Setup : WowPhase()
    data class Ready(val wowInstalled: Boolean, val bnetInstalled: Boolean, val bnetLoggedIn: Boolean = false) : WowPhase() {
        /** Via Battle.net (token, no password) once it has logged in; otherwise WoW directly. */
        val play get() = if (bnetInstalled && bnetLoggedIn) WowTarget.PLAY_VIA_BNET else WowTarget.PLAY
    }
}

@Composable
fun WowHomeScreen(
    viewModel: MainViewModel,
    setMessageDialogState: (MessageDialogState) -> Unit,
    onOpenContainerSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val launcher = remember(ctx) { WowLauncher(ctx, viewModel, setMessageDialogState) }
    var phase by remember { mutableStateOf<WowPhase>(WowPhase.Checking) }
    var refresh by remember { mutableIntStateOf(0) }
    val pending by WowLaunchBus.pending.collectAsState()

    BackHandler { (ctx as? Activity)?.finish() }

    var runInstallerAfterSetup by remember { mutableStateOf(false) }
    // Compose-visible mirror of WowLaunchBus.autoLaunchDone
    var autoDone by remember { mutableStateOf(WowLaunchBus.autoLaunchDone) }
    fun markAutoDone() {
        WowLaunchBus.autoLaunchDone = true
        autoDone = true
    }

    LaunchedEffect(refresh) {
        val detected = withContext(Dispatchers.IO) { detect(ctx) }
        phase = detected
        if (runInstallerAfterSetup) {
            runInstallerAfterSetup = false
            // Next step after setup: Battle.net installer (imagefs + FEXCore download happen in preLaunchApp).
            if (detected is WowPhase.Ready && !detected.bnetInstalled) launcher.launch(WowTarget.SETUP_BNET)
        }
    }

    // An explicit shortcut target wins over the auto-launch countdown.
    LaunchedEffect(pending, phase) {
        val t = pending ?: return@LaunchedEffect
        val p = phase as? WowPhase.Ready ?: return@LaunchedEffect
        WowLaunchBus.pending.value = null
        markAutoDone()
        if (t == WowTarget.PLAY && !p.wowInstalled) return@LaunchedEffect
        if (t != WowTarget.MENU) launcher.launch(t, finishOnExit = true)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val p = phase) {
            WowPhase.Checking -> Centered { CircularProgressIndicator() }

            WowPhase.Setup -> WowSetupScreen(
                onDone = {
                    markAutoDone()
                    runInstallerAfterSetup = true
                    phase = WowPhase.Checking
                    refresh++
                },
            )

            is WowPhase.Ready -> {
                if (p.wowInstalled && !autoDone && pending == null) {
                    Countdown(
                        seconds = 3,
                        onCancel = { markAutoDone() },
                        onFinish = {
                            markAutoDone()
                            launcher.launch(p.play, finishOnExit = true)
                        },
                    )
                } else {
                    var driver by remember { mutableStateOf(Wow.currentDriver()) }
                    var gfxApi by remember { mutableStateOf(Wow.currentGraphicsApi()) }
                    val items = buildList<Pair<String, () -> Unit>> {
                        if (p.wowInstalled) {
                            val label = if (p.play == WowTarget.PLAY_VIA_BNET) "Play WoW (auto-login via Battle.net)" else "Play WoW"
                            add(label to { launcher.launch(p.play) })
                            if (p.play == WowTarget.PLAY_VIA_BNET) {
                                add("Play WoW directly (asks for password)" to { launcher.launch(WowTarget.PLAY) })
                            } else if (p.bnetInstalled) {
                                add("Play via Battle.net (auto-login after one Battle.net login)" to { launcher.launch(WowTarget.PLAY_VIA_BNET) })
                            }
                        }
                        if (p.bnetInstalled) {
                            add("Open Battle.net (install / update / login)" to { launcher.launch(WowTarget.BNET) })
                            add("Open Battle.net (safe: --disable-gpu)" to { launcher.launch(WowTarget.BNET_SAFE) })
                        } else {
                            add("Install Battle.net" to { launcher.launch(WowTarget.SETUP_BNET) })
                        }
                        add("Graphics driver: $driver (switch)" to { driver = launcher.toggleDriver() })
                        add("Graphics API: ${gfxApi.uppercase()} (switch)" to { gfxApi = launcher.toggleGraphicsApi() })
                        add("Container settings" to onOpenContainerSettings)
                        add("App settings" to onOpenAppSettings)
                        add("Re-run setup" to { phase = WowPhase.Setup })
                        add("Exit" to { (ctx as? Activity)?.finish(); Unit })
                    }
                    WowMenu(
                        status = when {
                            p.wowInstalled -> "World of Warcraft is installed."
                            p.bnetInstalled -> "Battle.net is installed. Log in and install WoW Classic (beta) to the default location."
                            else -> "Next step: install Battle.net."
                        },
                        items = items,
                    )
                }
            }
        }
    }
}

private fun detect(ctx: android.content.Context): WowPhase = try {
    if (!Wow.isContainerReady(ctx)) {
        WowPhase.Setup
    } else {
        val c = ContainerUtils.getContainer(ctx, Wow.APP_ID)
        val bnet = Wow.isBnetInstalled(c)
        WowPhase.Ready(wowInstalled = Wow.isWowInstalled(c), bnetInstalled = bnet, bnetLoggedIn = bnet && WowBnet.isLoggedIn(c))
    }
} catch (e: Exception) {
    Timber.tag("WowHome").e(e, "detect failed")
    WowPhase.Setup
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun Countdown(seconds: Int, onCancel: () -> Unit, onFinish: () -> Unit) {
    var left by remember { mutableIntStateOf(seconds) }
    var cancelled by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        while (left > 0) {
            delay(1000)
            if (cancelled) return@LaunchedEffect
            left--
        }
        if (!cancelled) onFinish()
    }
    val cancel = {
        if (!cancelled) {
            cancelled = true
            onCancel()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) cancel(); true }
            .focusable()
            .clickable { cancel() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Starting World of Warcraft… $left", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text("Tap or press any button for the menu", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun WowMenu(status: String, items: List<Pair<String, () -> Unit>>) {
    val first = remember { FocusRequester() }
    LaunchedEffect(items.size) { runCatching { first.requestFocus() } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("WoW Forever", style = MaterialTheme.typography.headlineMedium)
        Text(status, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        items.forEachIndexed { i, (label, action) ->
            val mod = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .let { if (i == 0) it.focusRequester(first) else it }
            if (i == 0) {
                Button(onClick = action, modifier = mod) { Text(label, modifier = Modifier.padding(vertical = 6.dp)) }
            } else {
                FilledTonalButton(onClick = action, modifier = mod) { Text(label, modifier = Modifier.padding(vertical = 6.dp)) }
            }
        }
    }
}

@Composable
private fun WowSetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var message by remember { mutableStateOf("Preparing…") }
    var fraction by remember { mutableFloatStateOf(-1f) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                WowSetup(ctx) { m, f ->
                    message = m
                    fraction = f
                }.run()
            }
        }
        result.onSuccess { onDone() }
            .onFailure {
                Timber.tag("WowSetup").e(it, "Setup failed")
                error = it.message ?: it.toString()
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Setting up WoW Forever", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        val err = error
        if (err == null) {
            Text(message, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            if (fraction in 0f..1f) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth())
            }
        } else {
            Text("Setup failed:\n$err", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Button(onClick = { attempt++ }, modifier = Modifier.focusRequester(focus)) { Text("Retry") }
        }
    }
}
