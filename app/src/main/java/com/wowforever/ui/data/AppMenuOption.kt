package com.wowforever.ui.data

import com.wowforever.ui.enums.AppOptionMenuType

data class AppMenuOption(
    val optionType: AppOptionMenuType,
    val onClick: () -> Unit,
)
