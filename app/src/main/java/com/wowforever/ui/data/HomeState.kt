package com.wowforever.ui.data

import com.wowforever.PrefManager
import com.wowforever.ui.enums.HomeDestination

data class HomeState(
    val currentDestination: HomeDestination = PrefManager.startScreen,
    val confirmExit: Boolean = false,
)
