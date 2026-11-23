package com.zcg.apatch.su

import android.content.Context
import android.content.pm.PackageManager
import com.zcg.apatch.APApplication
import com.zcg.apatch.Natives
import com.zcg.apatch.util.PkgConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话授权缓存：仅存活于进程内存，不写内核白名单、不写 package_config。
 *
 * 这样"仅本次 / 本次会话"的授权不会污染内核 su 列表——内核白名单是
 * refresh_ap_package_list() 的事实源，一旦写进去就成了永久授权。
 */
object SuDecisionStore {
    private const val TAG = "SuDecisionStore"

    /** 会话内已允许的 uid → 首次允许的时间戳。 */
    private val sessionAllowed = ConcurrentHashMap<Int, Long>()

    /** 某个 uid 是否处于"本次会话已允许"状态。 */
    fun isSessionAllowed(uid: Int): Boolean = sessionAllowed.containsKey(uid)

    /** 记录会话内允许；fn 由调用方决定何时真正回执内核。 */
    fun markSession(uid: Int) {
        sessionAllowed.putIfAbsent(uid, System.currentTimeMillis())
    }

    /** 撤销某 uid 的会话授权。 */
    fun revokeSession(uid: Int) {
        sessionAllowed.remove(uid)
    }

    fun clear() = sessionAllowed.clear()

    /** 把 pid 对应的待决请求裁决为拒绝。 */
    fun deny(pid: Int) {
        Natives.suDecision(pid, false)
    }

    /** 把 pid 对应的待决请求裁决为允许（本次）。 */
    fun allowOnce(pid: Int) {
        Natives.suDecision(pid, true)
    }

    /**
     * 永久授权：同时写内核 su 白名单与 package_config CSV。
     * 两者必须一起做，否则 uid-listener / 开机重建会用 CSV 覆盖内核状态。
     */
    fun allowAlways(pid: Int, uid: Int, scontext: String, packageName: String) {
        val sctx = if (scontext.isNotBlank()) scontext else APApplication.MAGISK_SCONTEXT
        Natives.grantSu(uid, 0, sctx)
        Natives.setUidExclude(uid, 0)
        val config = PkgConfig.Config(
            pkg = packageName,
            exclude = 0,
            allow = 1,
            profile = Natives.Profile(uid = uid, toUid = 0, scontext = sctx)
        )
        PkgConfig.changeConfig(config)
        // 回执内核，唤醒挂起的 execve
        Natives.suDecision(pid, true)
    }

    /** 仅供 UI 展示：由 uid 反查一个可读的应用标签。 */
    fun labelForUid(context: Context, uid: Int): String {
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid) ?: return "uid=$uid"
        val first = packages.firstOrNull() ?: return "uid=$uid"
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(first, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            first
        }
    }
}
