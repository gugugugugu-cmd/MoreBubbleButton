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
    var positionMode by remember { mutableIntStateOf(ModuleSettings.getPositionMode(ctx)) }
    var sliderX by remember { mutableFloatStateOf(ModuleSettings.getPosX(ctx).toFloat()) }
    var sliderY by remember { mutableFloatStateOf(ModuleSettings.getPosY(ctx).toFloat()) }
    var bubbleWidth by remember { mutableFloatStateOf(ModuleSettings.getBubbleWidthPercent(ctx).toFloat()) }
    var bubbleHeight by remember { mutableFloatStateOf(ModuleSettings.getBubbleHeightPercent(ctx).toFloat()) }

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

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "位置微调",
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
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "按钮显示行",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = positionMode == 0,
                        onClick = {
                            positionMode = 0
                            ModuleSettings.setPositionMode(ctx, 0)
                            try { MoreBubbleHookModule.applyPositionFromSettings(ctx) } catch (_: Throwable) {}
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        label = { Text("跟随原按钮") }
                    )
                    SegmentedButton(
                        selected = positionMode == 1,
                        onClick = {
                            positionMode = 1
                            ModuleSettings.setPositionMode(ctx, 1)
                            try { MoreBubbleHookModule.applyPositionFromSettings(ctx) } catch (_: Throwable) {}
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        label = { Text("第二行") }
                    )
                }

                if (positionMode == 1) {
                    Spacer(modifier = Modifier.height(16.dp))
                    FineTuneSlider(
                        title = "X 轴（← 左 | 右 →）",
                        value = sliderX,
                        onValueChange = { sliderX = it },
                        onCommit = {
                            val x = sliderX.toInt()
                            ModuleSettings.setPosX(ctx, x)
                            try { MoreBubbleHookModule.applyPositionFromSettings(ctx) } catch (_: Throwable) {}
                        },
                        onStep = { delta ->
                            sliderX = (sliderX + delta).coerceIn(0f, 100f)
                            ModuleSettings.setPosX(ctx, sliderX.toInt())
                            try { MoreBubbleHookModule.applyPositionFromSettings(ctx) } catch (_: Throwable) {}
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    FineTuneSlider(
                        title = "Y 轴（↑ 上 | 下 ↓）",
                        value = sliderY,
                        onValueChange = { sliderY = it },
                        onCommit = {
                            val y = sliderY.toInt()
                            ModuleSettings.setPosY(ctx, y)
                            try { MoreBubbleHookModule.applyPositionFromSettings(ctx) } catch (_: Throwable) {}
                        },
                        onStep = { delta ->
                            sliderY = (sliderY + delta).coerceIn(0f, 100f)
                            ModuleSettings.setPosY(ctx, sliderY.toInt())
                            try { MoreBubbleHookModule.applyPositionFromSettings(ctx) } catch (_: Throwable) {}
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = {
                            sliderX = 50f
                            sliderY = 50f
                            ModuleSettings.setPosX(ctx, 50)
                            ModuleSettings.setPosY(ctx, 50)
                            try { MoreBubbleHookModule.applyPositionFromSettings(ctx) } catch (_: Throwable) {}
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("恢复默认 X/Y 位置")
                    }
                } else {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "当前跟随 Pixel Launcher 原底部按钮位置；选择“第二行”后可使用 X/Y 精调。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Android 17 气泡小窗大小",
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
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "调整系统气泡窗口的宽度和高度；100% 为系统默认。改动立即保存，重启 SystemUI 后生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                FineTuneSlider(
                    title = "宽度",
                    value = bubbleWidth,
                    onValueChange = { bubbleWidth = it },
                    onCommit = {
                        ModuleSettings.setBubbleWidthPercent(ctx, bubbleWidth.toInt())
                    },
                    onStep = { delta ->
                        bubbleWidth = (bubbleWidth + delta).coerceIn(50f, 150f)
                        ModuleSettings.setBubbleWidthPercent(ctx, bubbleWidth.toInt())
                    },
                    valueRange = 50f..150f,
                    steps = 99,
                    suffix = "%"
                )
                Spacer(modifier = Modifier.height(8.dp))
                FineTuneSlider(
                    title = "高度",
                    value = bubbleHeight,
                    onValueChange = { bubbleHeight = it },
                    onCommit = {
                        ModuleSettings.setBubbleHeightPercent(ctx, bubbleHeight.toInt())
                    },
                    onStep = { delta ->
                        bubbleHeight = (bubbleHeight + delta).coerceIn(50f, 150f)
                        ModuleSettings.setBubbleHeightPercent(ctx, bubbleHeight.toInt())
                    },
                    valueRange = 50f..150f,
                    steps = 99,
                    suffix = "%"
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        bubbleWidth = 100f
                        bubbleHeight = 100f
                        ModuleSettings.setBubbleWidthPercent(ctx, 100)
                        ModuleSettings.setBubbleHeightPercent(ctx, 100)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("恢复系统默认大小")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "killall com.google.android.apps.nexuslauncher com.android.launcher3 com.android.systemui 2>/dev/null; sleep 1"))
                        p.waitFor()
                    } catch (_: Throwable) {}
                }, 300)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("重启启动器 + 系统界面")
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
private fun FineTuneSlider(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onCommit: () -> Unit,
    onStep: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    steps: Int = 99,
    suffix: String = "%"
) {
    Text(
        text = "$title: ${value.toInt()}$suffix",
        style = MaterialTheme.typography.bodyMedium
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalIconButton(onClick = { onStep(-1f) }) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onCommit,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        )
        FilledTonalIconButton(onClick = { onStep(1f) }) {
            Text("+", style = MaterialTheme.typography.titleLarge)
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
