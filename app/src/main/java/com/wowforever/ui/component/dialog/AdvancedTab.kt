package com.wowforever.ui.component.dialog

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.wowforever.R
import com.wowforever.ui.component.settings.SettingsCPUList
import com.wowforever.ui.component.settings.SettingsListDropdown
import com.wowforever.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup

@Composable
fun AdvancedTabContent(state: ContainerConfigState) {
    val config = state.config.value

    SettingsGroup() {
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.startup_selection)) },
            value = config.startupSelection.toInt().takeIf { it in state.getStartupSelectionOptions().indices } ?: 1,
            items = state.getStartupSelectionOptions(),
            onItemSelected = {
                state.config.value = config.copy(startupSelection = it.toByte())
            },
        )
        SettingsCPUList(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.processor_affinity)) },
            value = config.cpuList,
            onValueChange = {
                state.config.value = config.copy(cpuList = it)
            },
        )
        SettingsCPUList(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.processor_affinity_32bit)) },
            value = config.cpuListWoW64,
            onValueChange = { state.config.value = config.copy(cpuListWoW64 = it) },
        )
    }
}
