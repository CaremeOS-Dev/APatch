package com.zcg.apatch.su

import com.zcg.apatch.APApplication
import com.zcg.apatch.util.getRootShell

/**
 * su 授权守护进程（apd su-prompt）的启动/停止。
 *
 * 为什么是守护进程而不是 App 里的前台服务：确认框需要**在任何界面之上**弹出。
 * 从 Android 10 起后台启动 Activity 会被拦，前台服务也不例外；而 root 进程执行
 * `am start` 不受这条限制——Magisk 的 magiskd 就是这么做的。
 *
 * 进程由 `execApd` 通过 root shell 拉起，与 APatch 其它 apd 命令走同一条路径。
 */
object SuPromptDaemon {
    private const val TAG = "SuPromptDaemon"
    private const val MARKER = "apd su-prompt"

    /** 守护进程是否在跑（用 pgrep 判断，不依赖 pid 文件）。 */
    fun isRunning(): Boolean = try {
        val out = getRootShell().newJob()
            .add("pgrep -f \"$MARKER\" >/dev/null 2>&1 && echo up || echo down")
            .to(ArrayList(), null).exec().out.joinToString("")
        out.contains("up")
    } catch (_: Throwable) {
        false
    }

    /** 重启：先杀旧的，再以 root 身份后台拉起一个新的。 */
    fun restart(context: android.content.Context): Boolean = try {
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
