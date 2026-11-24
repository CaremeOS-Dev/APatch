package com.zcg.apatch.su

import android.content.Context
import com.zcg.apatch.APApplication
import com.zcg.apatch.Natives
import com.zcg.apatch.util.getRootShell

/**
 * su 授权守护进程（`apd su-prompt`）——**唯一的**请求轮询者与弹窗发起者。
 *
 * 架构刻意只有一条链路，和 Magisk 一样：
 *
 * ```
 * 内核：非白名单 su → 写 PENDING → 挂住 execve
 * apd su-prompt：轮询 → am start SuPromptActivity（root 进程，不受后台启动限制）
 * Activity：用户点选 → suDecision
 * 内核：放行 / 拒绝
 * ```
 *
 * App 侧不轮询、不起前台服务：多一条链路只会让「谁在弹窗」变得不确定。
 * App 只负责（a）开关内核 flag，（b）启停这个守护进程。
 */
object SuPromptDaemon {
    private const val TAG = "SuPromptDaemon"
    private const val MARKER = "apd su-prompt"

    /** 守护进程是否在跑。 */
    fun isRunning(): Boolean = try {
        val out = getRootShell().newJob()
            .add("pgrep -f \"$MARKER\" >/dev/null 2>&1 && echo up || echo down")
            .to(ArrayList(), null).exec().out.joinToString("")
        out.contains("up")
    } catch (_: Throwable) {
        false
    }

    /**
     * 恢复内核状态并确保守护进程在跑。开机 / App 启动 / 开关打开都走这里：
     * 内核的 su_prompt 标志是内存态，每次开机都得重新写一遍。
     */
    fun restore(context: Context): Boolean {
        val app = context.applicationContext
        val prefs = APApplication.sharedPreferences ?: return false
        if (!prefs.getBoolean(SuPromptBootReceiver.KEY_SU_PROMPT_ENABLED, false)) return false

        return try {
            Natives.controlFeature("su_prompt", true)
            Natives.controlFeature("su_prompt_timeout", SuPromptPrefs.timeoutAllows(app))
            restart(app)
        } catch (t: Throwable) {
            false
        }
    }

    /** 重启：先杀旧的，再以 root 身份后台拉起一个新的。 */
    fun restart(context: Context): Boolean = try {
        val pkg = context.packageName
        val key = APApplication.superKey
        getRootShell().newJob().add(
            "pkill -f \"$MARKER\" 2>/dev/null; " +
                "nohup ${APApplication.APD_PATH} --superkey '$key' su-prompt --package $pkg " +
                ">/dev/null 2>&1 &",
            "sleep 1",
            "pgrep -f \"$MARKER\" >/dev/null 2>&1 && echo up || echo down"
        ).to(ArrayList(), null).exec().out.joinToString("").contains("up")
    } catch (_: Throwable) {
        false
    }

    fun stop(): Boolean = try {
        getRootShell().newJob().add("pkill -f \"$MARKER\" 2>/dev/null; true")
            .to(ArrayList(), null).exec()
        true
    } catch (_: Throwable) {
        false
    }
}
