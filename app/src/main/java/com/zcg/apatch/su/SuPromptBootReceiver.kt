package com.zcg.apatch.su

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zcg.apatch.APApplication

/**
 * 开机后恢复 su 弹窗。
 *
 * 两件事都必须重做，因为内核里的 `su_prompt` 开关是**内存状态**，重启即清零：
 *
 *  1. 用 supercall 把 `su_prompt` / `su_prompt_timeout` 重新打开；
 *  2. 拉起授权守护进程 `apd su-prompt`。
 *
 * 开机广播触发时 root 往往还没就绪（apd 尚在启动、su 还不可用），所以这里必须重试，
 * 否则「开关开着但内核标志是 0」会让所有非白名单 su 直接 not found。
 * `APApplication` 启动时也会做同样的事，两条路径互为兜底。
 */
class SuPromptBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return

        val prefs = APApplication.sharedPreferences ?: return
        if (!prefs.getBoolean(KEY_SU_PROMPT_ENABLED, false)) return

        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                repeat(10) { attempt ->
                    val ok = runCatching { SuPromptDaemon.restore(app) }.getOrDefault(false)
                    Log.d("SuPromptBoot", "attempt ${attempt + 1}: ok=$ok")
                    if (ok) return@Thread
                    Thread.sleep(6_000)
                }
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true; name = "su-prompt-boot" }.start()
    }

    companion object {
        const val KEY_SU_PROMPT_ENABLED = "su_prompt_enabled"
    }
}
