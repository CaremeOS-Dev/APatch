package com.zcg.apatch.su

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay

/**
 * su 请求确认弹窗 —— Material 3 Expressive 风格，适配 320x360 dp @ 320dpi。
 *
 * 视觉：
 *   - 整屏半透明 + 背景模糊（API 31+ 用 window blur，低版本退化为遮罩）；
 *   - 卡片 28dp 圆角、渐变表面、超大字号标题层级；
 *   - 主次按钮用「填充胶囊 vs 文字按钮」区分，而不是描边；
 *   - 颜色取自 M3 Expressive 的 primary/tertiary 调。
 *
 * 320x360 是这块表的实际可用高度，所以布局用 8dp 网格、字号 9~17sp、
 * 上下 padding 合计不超过 24dp，避免在小屏上被裁掉按钮。
 *
 * 交互：内核最多等 SU_PROMPT_TIMEOUT_MS(10s)，倒计时抢在它前面到 0；
 * 到 0 按设置里的「超时动作」自动裁决，且【不记住】。记住只发生在用户主动
 * 点「允许/拒绝」且「记住选择」打开时。
 */
class SuPromptActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PID = "pid"
        const val EXTRA_UID = "uid"
        const val EXTRA_SCONTEXT = "scontext"
        const val EXTRA_COMM = "comm"

        /** 内核侧记录最多存活 10s，留 1s 余量回执。 */
        internal const val COUNTDOWN_SECONDS = 9
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setBackgroundBlurRadius(72)
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
        window.setDimAmount(0.45f)

        val pid = intent.getIntExtra(EXTRA_PID, -1)
        val uid = intent.getIntExtra(EXTRA_UID, -1)
        val scontext = intent.getStringExtra(EXTRA_SCONTEXT) ?: ""
        val comm = intent.getStringExtra(EXTRA_COMM) ?: ""

        if (pid < 0) {
            finish()
            return
        }

        val label = SuDecisionStore.labelForUid(this, uid)
        val pkg = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: ""
        val icon = loadIcon(pkg)
        val timeoutAllows = SuPromptPrefs.timeoutAllows(this)
        val countdown = SuPromptPrefs.countdownSeconds(this)

        setContent {
            MaterialTheme {
                // 160x180dp 的屏上 Material 默认密度会把按钮挤出屏幕
                com.zcg.apatch.ui.theme.CompactDensity {
                    SuPromptCard(
                    label = label,
                    pkg = pkg,
                    comm = comm,
                    pid = pid,
                    uid = uid,
                    icon = icon,
                    timeoutAllows = timeoutAllows,
                    countdownSeconds = countdown,
                    onAllow = { remember ->
                        if (remember) SuDecisionStore.allowAlways(pid, uid, scontext, pkg)
                        else SuDecisionStore.allowOnce(pid)
                        finish()
                    },
                    onDeny = { remember ->
                        if (remember) SuDecisionStore.deny(pid) else SuDecisionStore.denyOnce(pid)
                        finish()
                    },
                    onTimeout = {
                        if (timeoutAllows) SuDecisionStore.allowOnce(pid)
                        else SuDecisionStore.denyOnce(pid)
                        finish()
                    }
                )
                }
            }
        }
    }

    private fun loadIcon(pkg: String): Drawable? = try {
        if (pkg.isEmpty()) null else packageManager.getApplicationIcon(pkg)
    } catch (_: Exception) {
        null
    }

    override fun onBackPressed() {
        // 返回键 = 只拒绝这一次，避免误触永久拉黑
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid >= 0) SuDecisionStore.denyOnce(pid)
        super.onBackPressed()
    }
}

@Composable
private fun SuPromptCard(
    label: String,
    pkg: String,
    comm: String,
    pid: Int,
    uid: Int,
    icon: Drawable?,
    timeoutAllows: Boolean,
    countdownSeconds: Int,
    onAllow: (remember: Boolean) -> Unit,
    onDeny: (remember: Boolean) -> Unit,
    onTimeout: () -> Unit,
) {
    var remaining by remember { mutableIntStateOf(countdownSeconds) }
    var remember by remember { mutableStateOf(true) }

    LaunchedEffect(pid) {
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
        onTimeout()
    }

    val surface = remember {
        Brush.verticalGradient(listOf(ComposeColor(0xF21C1B1F), ComposeColor(0xF229282D)))
    }
    val primary = ComposeColor(0xFFD0BCFF)
    val onPrimary = ComposeColor(0xFF381E72)
    val tertiary = ComposeColor(0xFFFFB4AB)
    val textHigh = ComposeColor(0xFFF4EFF4)
    val textLow = ComposeColor(0xA6CAC4D0)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor(0x66000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(28.dp))
                .background(surface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- 头部：图标 + 应用名 -------------------------------------------------
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ComposeColor(0x33FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                val bmp = remember(icon) { icon?.toBitmap(96, 96)?.asImageBitmap() }
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(30.dp)
                    )
                } else {
                    Text("?", fontSize = 18.sp, color = primary, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = textHigh,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = if (pkg.isNotEmpty()) pkg else "uid=$uid",
                fontSize = 10.sp,
                color = textLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(8.dp))
            Text(
                text = "请求超级用户权限",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = ComposeColor(0xFFE6E1E5)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append("PID $pid")
                    if (comm.isNotBlank()) append(" · $comm")
                },
                fontSize = 9.sp,
                color = textLow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // ---- 倒计时 ------------------------------------------------------------
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ComposeColor(0x33FFFFFF))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(remaining / countdownSeconds.toFloat())
                        .height(3.dp)
                        .background(if (remaining <= 3) tertiary else primary)
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = "$remaining 秒后自动" + if (timeoutAllows) "允许" else "拒绝",
                fontSize = 9.sp,
                color = textLow
            )

            // ---- 记住选择 ----------------------------------------------------------
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "记住我的选择",
                    fontSize = 11.sp,
                    color = ComposeColor(0xFFCAC4D0)
                )
                Switch(
                    checked = remember,
                    onCheckedChange = { remember = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = onPrimary,
                        checkedTrackColor = primary
                    ),
                    modifier = Modifier.size(width = 44.dp, height = 24.dp)
                )
            }

            // ---- 按钮 --------------------------------------------------------------
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = { onDeny(remember) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(contentColor = tertiary)
                ) {
                    Text("拒绝", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Button(
                    onClick = { onAllow(remember) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primary,
                        contentColor = onPrimary
                    )
                ) {
                    Text("允许", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
