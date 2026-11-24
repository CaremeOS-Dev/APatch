package com.zcg.apatch.su

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zcg.apatch.Natives

/**
 * su 请求确认弹窗。
 *
 * 由 SuRequestService 通过 full-screen intent 拉起（或通知点击）。
 * 三档结果：
 *   - 拒绝         → SU_DECISION_DENY，内核不提权
 *   - 仅本次       → SU_DECISION_ALLOW，只唤醒本次 execve，不写任何持久化
 *   - 本次会话     → 允许并写入内存缓存，同 uid 后续请求自动放行
 *   - 总是允许     → 允许并写内核 su 白名单 + package_config（等价于旧版永久授权）
 */
class SuPromptActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PID = "pid"
        const val EXTRA_UID = "uid"
        const val EXTRA_SCONTEXT = "scontext"
        const val EXTRA_COMM = "comm"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 让锁屏/后台也能显示（full-screen intent 语义）
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

        val pid = intent.getIntExtra(EXTRA_PID, -1)
        val uid = intent.getIntExtra(EXTRA_UID, -1)
        val scontext = intent.getStringExtra(EXTRA_SCONTEXT) ?: ""
        val comm = intent.getStringExtra(EXTRA_COMM) ?: ""

        // pid == -1 是守护通知的占位点击，无实际请求可裁决
        if (pid < 0) {
            finish()
            return
        }

        val label = SuDecisionStore.labelForUid(this, uid)
        val packageName = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: ""

        setContent {
            MaterialTheme {
                AlertDialog(
                    onDismissRequest = {
                        // 点击外部 / 返回键：默认拒绝，避免内核线程一直挂起
                        SuDecisionStore.deny(pid)
                        finish()
                    },
                    title = {
                        Text(text = "请求 ROOT 权限", fontWeight = FontWeight.Bold)
                    },
                    text = {
                        Column {
                            Text(text = "$label 请求获取 root 权限")
                            Spacer(Modifier.height(8.dp))
                            Text(text = "进程: $comm", style = MaterialTheme.typography.bodySmall)
                            Text(text = "PID: $pid  UID: $uid", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(4.dp))
                            Text(text = "SELinux: $scontext", style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    confirmButton = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = {
                                    SuDecisionStore.deny(pid)
                                    finish()
                                }) {
                                    Text("拒绝")
                                }
                                TextButton(onClick = {
                                    SuDecisionStore.allowOnce(pid)
                                    finish()
                                }) {
                                    Text("仅本次")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = {
                                    SuDecisionStore.markSession(uid)
                                    SuDecisionStore.allowOnce(pid)
                                    finish()
                                }) {
                                    Text("本次会话")
                                }
                                TextButton(onClick = {
                                    SuDecisionStore.allowAlways(pid, uid, scontext, packageName)
                                    finish()
                                }) {
                                    Text("总是允许")
                                }
                            }
                        }
                    },
                    dismissButton = {}
                )
            }
        }
    }

    override fun onBackPressed() {
        // 返回键等同拒绝，确保内核等待方一定能被唤醒
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid >= 0) SuDecisionStore.deny(pid)
        super.onBackPressed()
    }

    override fun finish() {
        super.finish()
        // 弹窗_activity 属于 EXCLUDE_FROM_RECENTS，结束即回到后台
    }
}
