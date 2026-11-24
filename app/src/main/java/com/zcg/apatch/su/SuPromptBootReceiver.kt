package com.zcg.apatch.su

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zcg.apatch.APApplication
import com.zcg.apatch.Natives

/**
 * 开机后恢复 su 弹窗。
 *
 * 两件事都必须重做，因为内核里的 `su_prompt` 开关是**内存状态**，重启即清零：
 *
 *  1. 用 supercall 把 `su_prompt` / `su_prompt_timeout` 重新打开；
 *  2. 拉起授权守护进程 `apd su-prompt`。
 *
 * 之前这里只启动了前台服务，内核开关没恢复 —— 结果开机后所有非白名单 `su`
 * 都走「不重定向 → 文件不存在」，表现为 `su: not found` 且不弹窗。
 */
class SuPromptBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val prefs = APApplication.sharedPreferences ?: return
        if (!prefs.getBoolean(KEY_SU_PROMPT_ENABLED, false)) return

        val app = context.applicationContext
        SuPromptDaemon.restore(app)
    }

    companion object {
        const val KEY_SU_PROMPT_ENABLED = "su_prompt_enabled"
    }
}
