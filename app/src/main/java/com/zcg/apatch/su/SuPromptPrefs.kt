package com.zcg.apatch.su

import android.content.Context
import androidx.core.content.edit

/**
 * su 弹窗的本地偏好：
 *
 *  - timeoutAllows：倒计时结束后自动「允许」还是「拒绝」（默认拒绝）；
 *  - timeoutSeconds：倒计时长度，5 或 10 秒（默认 10，但内核只等 10s，
 *    所以实际弹窗用的倒计时是 min(该值, 9)）。
 *
 * 这些只影响"没人应答时怎么办"；用户主动点允许/拒绝走的是内核侧的白名单/
 * 黑名单固化，与这里无关。
 */
object SuPromptPrefs {
    private const val PREFS = "su_prompt"
    private const val KEY_TIMEOUT_ALLOW = "timeout_allow"
    private const val KEY_TIMEOUT_SECONDS = "timeout_seconds"

    /** 内核 su_prompt_timeout 的镜像：0=拒绝，1=允许。 */
    fun timeoutAllows(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_TIMEOUT_ALLOW, false)

    fun setTimeoutAllows(context: Context, allow: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_TIMEOUT_ALLOW, allow) }
    }

    fun timeoutSeconds(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_TIMEOUT_SECONDS, 10)

    fun setTimeoutSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putInt(KEY_TIMEOUT_SECONDS, seconds) }
    }

    /** 弹窗里真正使用的倒计时：必须短于内核的 10s 等待。 */
    fun countdownSeconds(context: Context): Int =
        timeoutSeconds(context).coerceIn(3, 9)
}
