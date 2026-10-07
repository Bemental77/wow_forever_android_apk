package com.wowforever.ui.screen.xserver

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wowforever.R
import com.wowforever.ui.component.QuickMenuAction

/** In-session drawer for WoW: Keyboard, Touch controls, Exit to home. No other launcher UI. */
@Composable
fun WowQuickMenu(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onItemSelected: (Int) -> Boolean,
    activeToggleIds: Set<Int>,
    onAnimationComplete: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = isVisible
    LaunchedEffect(visibleState.currentState, visibleState.isIdle) {
        if (visibleState.isIdle) onAnimationComplete(visibleState.currentState)
    }
    BackHandler(enabled = isVisible) { onDismiss() }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(visible = isVisible, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visibleState = visibleState,
            enter = slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(200)),
            exit = slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(150)),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Surface(
                modifier = Modifier.width(300.dp).fillMaxHeight(),
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 24.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().statusBarsPadding().padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.wow_menu_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 24.dp, bottom = 12.dp),
                    )
                    val imeVisible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    WowMenuRow(Icons.Default.Keyboard, stringResource(R.string.wow_menu_keyboard), imeVisible) {
                        if (imeVisible) {
                            onDismiss()
                            WowSessionInput.hideKeyboard()
                        } else if (onItemSelected(QuickMenuAction.KEYBOARD)) {
                            onDismiss()
                        }
                    }
                    WowMenuRow(
                        Icons.Default.TouchApp,
                        stringResource(R.string.wow_menu_touch_controls),
                        QuickMenuAction.INPUT_CONTROLS in activeToggleIds,
                    ) {
                        if (onItemSelected(QuickMenuAction.INPUT_CONTROLS)) onDismiss()
                    }
                    Spacer(Modifier.weight(1f))
                    WowMenuRow(Icons.AutoMirrored.Filled.ExitToApp, stringResource(R.string.wow_menu_exit), null) {
                        if (onItemSelected(QuickMenuAction.EXIT_GAME)) onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun WowMenuRow(icon: ImageVector, label: String, on: Boolean?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp).weight(1f))
        if (on != null) {
            Text(
                stringResource(if (on) R.string.wow_menu_on else R.string.wow_menu_off),
                style = MaterialTheme.typography.labelLarge,
                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
