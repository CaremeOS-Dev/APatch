package com.zcg.apatch

import android.os.Parcelable
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import dalvik.annotation.optimization.FastNative
import kotlinx.parcelize.Parcelize

object Natives {
    init {
        System.loadLibrary("apjni")
    }

    @Immutable
    @Parcelize
    @Keep
    data class Profile(
        var uid: Int = 0,
        var toUid: Int = 0,
        var scontext: String = APApplication.DEFAULT_SCONTEXT,
    ) : Parcelable

    @Keep
    class KPMCtlRes {
        var rc: Long = 0
        var outMsg: String? = null

        constructor()

        constructor(rc: Long, outMsg: String?) {
            this.rc = rc
            this.outMsg = outMsg
        }
    }


    @FastNative
    private external fun nativeSu(superKey: String, toUid: Int, scontext: String?): Long

    fun su(toUid: Int, scontext: String?): Boolean {
        return nativeSu(APApplication.superKey, toUid, scontext) == 0L
    }

    fun su(): Boolean {
        return su(0, "")
    }

    @FastNative
    external fun nativeReady(superKey: String): Boolean

    @FastNative
    private external fun nativeSuPath(superKey: String): String

    fun suPath(): String {
        return nativeSuPath(APApplication.superKey)
    }

    @FastNative
    private external fun nativeSuUids(superKey: String): IntArray

    fun suUids(): IntArray {
        return nativeSuUids(APApplication.superKey)
    }

    @FastNative
    private external fun nativeKernelPatchVersion(superKey: String): Long
    fun kernelPatchVersion(): Long {
        return nativeKernelPatchVersion(APApplication.superKey)
    }

    @FastNative
    private external fun nativeKernelPatchBuildTime(superKey: String): String
    fun kernelPatchBuildTime(): String {
        return nativeKernelPatchBuildTime(APApplication.superKey)
    }

    private external fun nativeLoadKernelPatchModule(
        superKey: String, modulePath: String, args: String
    ): Long

    fun loadKernelPatchModule(modulePath: String, args: String): Long {
        return nativeLoadKernelPatchModule(APApplication.superKey, modulePath, args)
    }

    private external fun nativeUnloadKernelPatchModule(superKey: String, moduleName: String): Long
    fun unloadKernelPatchModule(moduleName: String): Long {
        return nativeUnloadKernelPatchModule(APApplication.superKey, moduleName)
    }

    @FastNative
    private external fun nativeKernelPatchModuleNum(superKey: String): Long

    fun kernelPatchModuleNum(): Long {
        return nativeKernelPatchModuleNum(APApplication.superKey)
    }

    @FastNative
    private external fun nativeKernelPatchModuleList(superKey: String): String
    fun kernelPatchModuleList(): String {
        return nativeKernelPatchModuleList(APApplication.superKey)
    }

    @FastNative
    private external fun nativeKernelPatchModuleInfo(superKey: String, moduleName: String): String
    fun kernelPatchModuleInfo(moduleName: String): String {
        return nativeKernelPatchModuleInfo(APApplication.superKey, moduleName)
    }

    private external fun nativeControlKernelPatchModule(
        superKey: String, modName: String, jctlargs: String
    ): KPMCtlRes

    fun kernelPatchModuleControl(moduleName: String, controlArg: String): KPMCtlRes {
        return nativeControlKernelPatchModule(APApplication.superKey, moduleName, controlArg)
    }

    @FastNative
    private external fun nativeGrantSu(
        superKey: String, uid: Int, toUid: Int, scontext: String?
    ): Long

    fun grantSu(uid: Int, toUid: Int, scontext: String?): Long {
        return nativeGrantSu(APApplication.superKey, uid, toUid, scontext)
    }

    @FastNative
    private external fun nativeRevokeSu(superKey: String, uid: Int): Long
    fun revokeSu(uid: Int): Long {
        return nativeRevokeSu(APApplication.superKey, uid)
    }

    @FastNative
    private external fun nativeSetUidExclude(superKey: String, uid: Int, exclude: Int): Int
    fun setUidExclude(uid: Int, exclude: Int): Int {
        return nativeSetUidExclude(APApplication.superKey, uid, exclude)
    }

    @FastNative
    private external fun nativeGetUidExclude(superKey: String, uid: Int): Int
    fun isUidExcluded(uid: Int): Int {
        return nativeGetUidExclude(APApplication.superKey, uid)
    }

    @FastNative
    private external fun nativeSuProfile(superKey: String, uid: Int): Profile
    fun suProfile(uid: Int): Profile {
        return nativeSuProfile(APApplication.superKey, uid)
    }

    @FastNative
    private external fun nativeResetSuPath(superKey: String, path: String): Boolean
    fun resetSuPath(path: String): Boolean {
        return nativeResetSuPath(APApplication.superKey, path)
    }

    @FastNative
    private external fun nativeControlFeature(superKey: String, featureName: String, state: Int): Long
    fun controlFeature(featureName: String, enable: Boolean): Long {
        return nativeControlFeature(APApplication.superKey, featureName, if (enable) 1 else 0)
    }

    /** Query form: state = -1 asks the kernel for the current value (or status). */
    fun controlFeature(featureName: String, state: Int): Long {
        return nativeControlFeature(APApplication.superKey, featureName, state)
    }

    // ---- SU prompt (弹窗确认) ----

    /** 一条待裁决的 su 请求，字段与内核 struct su_request 逐项对应。 */
    @Immutable
    @Parcelize
    @Keep
    data class SuRequest(
        val uid: Int = 0,
        val toUid: Int = 0,
        val pid: Int = 0,
        val stamp: Long = 0,
        val decision: Int = 0,
        val scontext: String = "",
        val comm: String = "",
    ) : Parcelable

    /** 拉取当前所有挂起的 su 请求。仅受信管理器/持有 superkey 者可用。 */
    external fun nativeSuPromptPoll(superKey: String, max: Int): Array<SuRequest>
    fun suPromptPoll(max: Int = 32): Array<SuRequest> {
        return nativeSuPromptPoll(APApplication.superKey, max)
    }

    /** 裁决一条请求。 */
    @FastNative
    private external fun nativeSuDecision(superKey: String, pid: Int, decision: Int): Long

    /** 旧调用点：只裁决本次，不写白名单/黑名单。 */
    fun suDecision(pid: Int, allow: Boolean): Long {
        return nativeSuDecision(
            APApplication.superKey, pid,
            if (allow) SuDecision.ALLOW_ONCE else SuDecision.DENY_ONCE
        )
    }

    /** 带"是否记住"的裁决：ALLOW/DENY 会固化到内核白名单/黑名单。 */
    fun suDecision(pid: Int, decision: Int): Long {
        return nativeSuDecision(APApplication.superKey, pid, decision)
    }

    /** 与 kernel/patch/include/uapi/scdefs.h 的 SU_DECISION_* 一一对应。 */
    object SuDecision {
        const val PENDING = 0
        const val ALLOW = 1
        const val ALLOW_ONCE = 2
        const val DENY = -1
        const val TIMEOUT = -2
        const val DENY_ONCE = -3
    }

    /** 被用户拒绝过、已固化的 uid 列表（内核 kstorage group 3）。 */
    @FastNative
    private external fun nativeSuDenyList(superKey: String): IntArray
    fun suDenyList(): IntArray = nativeSuDenyList(APApplication.superKey)

    /** 解除某个 uid 的黑名单，下次 su 会重新弹窗。 */
    @FastNative
    private external fun nativeSuDenyRemove(superKey: String, uid: Int): Long
    fun suDenyRemove(uid: Int): Long = nativeSuDenyRemove(APApplication.superKey, uid)
}
