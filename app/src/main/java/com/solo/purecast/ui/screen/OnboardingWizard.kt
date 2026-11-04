package com.solo.purecast.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.solo.purecast.util.PermissionHelper

/**
 * 💡 PureCast 电视专属开机权限与自检引导向导 (OnboardingWizard)
 *
 * 遵循原则：
 * 1. 10-Foot 遥控器专属交互体验，严禁混用移动端 Compose 组件，全部交互组件采用 androidx.tv.material3.Button。
 * 2. 郑重展示“零录音硬件调用承诺”，消除用户对电视串流应用申请录音权限的隐私顾虑。
 * 3. 支持生命周期感知：当用户通过遥控器跳转系统设置页并返回时，自动重新自检并更新四项状态。
 * 4. 健壮的容错机制：针对部分电视盒子阉割系统设置界面的情况，由 PermissionHelper 施加 try-catch 保护。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OnboardingWizard(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 当前权限自检状态
    var permissionStatus by remember {
        mutableStateOf(PermissionHelper.checkAllPermissions(context))
    }

    // 💡 监听页面 Resume 事件，从系统设置返回时自动静默刷新权限快照
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionStatus = PermissionHelper.checkAllPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = 48.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .width(960.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF141620))
                .border(BorderStroke(1.dp, Color(0xFF2C3042)), RoundedCornerShape(20.dp))
                .padding(28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 标题区域
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "PureCast 电视系统自检与开箱权限向导",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    Text(
                        text = "为了保障 USB DAC 无损独占与局域网后台被动唤醒，建议完成以下核心授权：",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8E8E93)
                    )
                }

                // 整体状态指示胶囊
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (permissionStatus.isAllGranted) Color(0xFF1E3A2B) else Color(0xFF3A2B18))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (permissionStatus.isAllGranted) "✓ 核心环境已就绪" else "待优化项已标记",
                        color = if (permissionStatus.isAllGranted) Color(0xFF30D158) else Color(0xFFFF9F0A),
                        fontSize = 12.sp
                    )
                }
            }

            // 💡 核心亮点：零录音硬件调用承诺横幅
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0F2236))
                    .border(BorderStroke(1.dp, Color(0xFF0A84FF).copy(alpha = 0.4f)), RoundedCornerShape(12.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "🔒 零录音硬件调用承诺 (Zero-AudioRecord Privacy Guarantee)",
                    color = Color(0xFF5AC8FA),
                    fontSize = 13.sp,
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    text = "PureCast 纯粹作为局域网高保真音频渲染器与 USB 转盘。全工程严禁且绝无任何 AudioRecord 硬件录音 API 调用，绝不收录任何麦克风环境声音。申请录音权限仅用于满足 Android 系统内核对 USB 声卡设备免弹窗静默接管的合规要求。",
                    color = Color(0xFFBAC7D5),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }

            // 四项核心自检卡片
            PermissionItemCard(
                title = "1. USB 声卡静默接管 (RECORD_AUDIO)",
                description = "允许电视在冷开机或待机唤醒后，自动且静默接管外接 Hi-Fi DAC，杜绝频繁弹出系统 USB 授权确认框。",
                isGranted = permissionStatus.isRecordAudioGranted,
                grantButtonText = "前往授权",
                onGrantClick = { PermissionHelper.openAppDetailsSettings(context) }
            )

            PermissionItemCard(
                title = "2. 悬浮窗与后台唤醒 (SYSTEM_ALERT_WINDOW)",
                description = "突破 Android 后台 Activity 启动限制 (BAL)。收到手机端 AirPlay / UPnP 推流时，立即自动唤醒电视大屏播放界面。",
                isGranted = permissionStatus.isOverlayGranted,
                grantButtonText = "开启悬浮窗",
                onGrantClick = { PermissionHelper.openOverlaySettings(context) }
            )

            PermissionItemCard(
                title = "3. 待机电池白名单 (IGNORE_BATTERY_OPTIMIZATIONS)",
                description = "防止电视盒子休眠或熄屏待机时强制杀死后台 UPnP 发现与高码率推流服务，杜绝断流与掉线。",
                isGranted = permissionStatus.isBatteryOptimizationIgnored,
                grantButtonText = "加入白名单",
                onGrantClick = { PermissionHelper.openBatteryOptimizationSettings(context) }
            )

            PermissionItemCard(
                title = "4. 防权限自动回收 (Auto-Revoke Whitelist)",
                description = "防止电视长期闲置后系统自动重置已授予的 USB 与网络权限，确保随时投屏即刻出声。",
                isGranted = permissionStatus.isAutoRevokeWhitelisted,
                grantButtonText = "查看设置",
                onGrantClick = { PermissionHelper.openAutoRevokeSettings(context) }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 底部操作按钮区域
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 主操作按钮：完成并进入
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    scale = ButtonDefaults.scale(scale = 1.0f, focusedScale = 1.05f),
                    colors = ButtonDefaults.colors(
                        containerColor = Color(0xFF0A84FF),
                        focusedContainerColor = Color.White,
                        contentColor = Color.White,
                        focusedContentColor = Color.Black
                    )
                ) {
                    Text(
                        text = if (permissionStatus.isAllGranted) "完成自检，进入播放器" else "已知晓，暂不设置直接进入",
                        fontSize = 14.sp
                    )
                }

                // 重新刷新检测
                Button(
                    onClick = {
                        permissionStatus = PermissionHelper.checkAllPermissions(context)
                    },
                    scale = ButtonDefaults.scale(scale = 1.0f, focusedScale = 1.05f),
                    colors = ButtonDefaults.colors(
                        containerColor = Color(0xFF2C3042),
                        focusedContainerColor = Color.White,
                        contentColor = Color.White,
                        focusedContentColor = Color.Black
                    )
                ) {
                    Text(text = "🔄 重新自检", fontSize = 14.sp)
                }
            }
        }
    }
}

/**
 * 单项权限自检卡片组件
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PermissionItemCard(
    title: String,
    description: String,
    isGranted: Boolean,
    grantButtonText: String,
    onGrantClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E212E))
            .border(
                BorderStroke(
                    1.dp,
                    if (isGranted) Color(0xFF283A30) else Color(0xFF3F3526)
                ),
                RoundedCornerShape(10.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.sp,
                    style = MaterialTheme.typography.titleSmall
                )
                // 状态指示
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isGranted) Color(0xFF1B4D2E) else Color(0xFF4A3816))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isGranted) "已授权" else "待配置",
                        color = if (isGranted) Color(0xFF30D158) else Color(0xFFFF9F0A),
                        fontSize = 10.sp
                    )
                }
            }
            Text(
                text = description,
                color = Color(0xFF8E8E93),
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // 操作按钮
        Button(
            onClick = onGrantClick,
            scale = ButtonDefaults.scale(scale = 1.0f, focusedScale = 1.08f),
            colors = ButtonDefaults.colors(
                containerColor = if (isGranted) Color(0xFF2A2E3D) else Color(0xFF3E4357),
                focusedContainerColor = Color.White,
                contentColor = if (isGranted) Color(0xFF8E8E93) else Color.White,
                focusedContentColor = Color.Black
            )
        ) {
            Text(
                text = if (isGranted) "已就绪" else grantButtonText,
                fontSize = 12.sp
            )
        }
    }
}
