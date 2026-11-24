package com.zcg.apatch.su

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zcg.apatch.APApplication

/**
 * 开机后恢复 su 轮询服务。
 *
 * 只有当 su 弹窗功能被用户开启（SharedPreferences 开关）且 KernelPatch 已
 * 就绪时才启动，避免在未 patch 的设备上常驻一个无用的前台服务。
 */
class SuPromptBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val prefs = APApplication.sharedPreferences
        if (prefs == null || !prefs.getBoolean(KEY_SU_PROMPT_ENABLED, false)) return

        SuRequestService.start(context.applicationContext)
    }

    companion object {
        const val KEY_SU_PROMPT_ENABLED = "su_prompt_enabled"
    }
}
