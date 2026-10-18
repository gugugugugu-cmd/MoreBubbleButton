package com.floatwindow.morebubblebutton.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.floatwindow.morebubblebutton.ModuleSettings
import com.floatwindow.morebubblebutton.MoreBubbleHookModule

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scrollState = rememberScrollState()

    var menuEnabled by remember { mutableStateOf(ModuleSettings.isMenuEnabled(ctx)) }
    var actionBarEnabled by remember { mutableStateOf(ModuleSettings.isActionBarEnabled(ctx)) }
    var systemUiBubbleEnabled by remember { mutableStateOf(ModuleSettings.isSystemUiBubbleEnabled(ctx)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = "消息气泡设置",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Text(
            text = "功能开关",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column {
                SwitchPreferenceRow(
                    title = "任务卡片菜单",
                    subtitle = "在多任务界面点击 app 图标弹出的菜单中显示「消息气泡」",
                    checked = menuEnabled,
                    onCheckedChange = {
                        menuEnabled = it
                        ModuleSettings.setMenuEnabled(ctx, it)
                    }
                )
                HorizontalDivider()
                SwitchPreferenceRow(
                    title = "底部操作栏",
                    subtitle = "在多任务界面底部显示「消息气泡」按钮",
                    checked = actionBarEnabled,
                    onCheckedChange = {
                        actionBarEnabled = it
                        ModuleSettings.setActionBarEnabled(ctx, it)
                    }
                )
                HorizontalDivider()
                SwitchPreferenceRow(
                    title = "通知横幅气泡",
                    subtitle = "所有应用通知横幅右下角显示气泡图标",
                    checked = systemUiBubbleEnabled,
                    onCheckedChange = {
                        systemUiBubbleEnabled = it
                        ModuleSettings.setSystemUiBubbleEnabled(ctx, it)
                    }
                )
            }
        }

}

@Composable
private fun SwitchPreferenceRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
