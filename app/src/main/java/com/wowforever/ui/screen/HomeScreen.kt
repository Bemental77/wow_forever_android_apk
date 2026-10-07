package com.wowforever.ui.screen

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wowforever.ui.enums.HomeDestination
import com.wowforever.ui.model.HomeViewModel
import com.wowforever.ui.screen.downloads.HomeDownloadsScreen
import com.wowforever.ui.screen.library.HomeLibraryScreen
import com.wowforever.ui.theme.PluviaTheme

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onChat: (Long) -> Unit,
    onClickExit: () -> Unit,
    onClickPlay: (String, Boolean) -> Unit,
    onTestGraphics: (String) -> Unit,
    onPlayWithDiagnostics: (String) -> Unit,
    onAiDebugRun: (String) -> Unit,
    onLogout: () -> Unit,
    onNavigateRoute: (String) -> Unit,
    onGoOnline: () -> Unit,
    isOffline: Boolean = false,
    isSteamConnected: Boolean = false,
) {
    val homeState by viewModel.homeState.collectAsStateWithLifecycle()

    // Pressing back while logged in, confirm we want to close the app.
    BackHandler {
        if (homeState.currentDestination != HomeDestination.Library) {
            viewModel.onDestination(HomeDestination.Library)
        } else {
            onClickExit()
        }
    }

    when (homeState.currentDestination) {
        HomeDestination.Library -> HomeLibraryScreen(
            onClickPlay = onClickPlay,
            onTestGraphics = onTestGraphics,
            onPlayWithDiagnostics = onPlayWithDiagnostics,
            onAiDebugRun = onAiDebugRun,
            onNavigateRoute = onNavigateRoute,
            onLogout = onLogout,
            onGoOnline = onGoOnline,
            onDownloadsClick = { viewModel.onDestination(HomeDestination.Downloads) },
            isOffline = isOffline,
            isSteamConnected = isSteamConnected,
        )
        HomeDestination.Downloads -> HomeDownloadsScreen(
            onBack = { viewModel.onDestination(HomeDestination.Library) },
            onClickPlay = onClickPlay,
            onTestGraphics = onTestGraphics,
            onPlayWithDiagnostics = onPlayWithDiagnostics,
            onAiDebugRun = onAiDebugRun,
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    device = "spec:width=1080px,height=1920px,dpi=440,orientation=landscape",
)
@Composable
private fun Preview_HomeScreenContent() {
    PluviaTheme {
        var destination: HomeDestination by remember {
            mutableStateOf(HomeDestination.Library)
        }
        HomeScreen(
            onChat = {},
            onClickPlay = { _, _ -> },
            onTestGraphics = { },
            onPlayWithDiagnostics = { },
            onAiDebugRun = { },
            onLogout = {},
            onNavigateRoute = {},
            onClickExit = {},
            onGoOnline = {},
        )
    }
}
