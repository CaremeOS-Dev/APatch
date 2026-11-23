package com.zcg.apatch.su

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.zcg.apatch.Natives

/**
 * 常驻前台服务：轮询内核中的待决 su 请求，并为每个新请求弹出确认窗口。
 *
 * 内核侧把所有非白名单的 su execve 挂起在 interruptible wait 上（60s 超时），
 * 本服务负责把它取出来交给用户裁决；用户点击后通过 Natives.suDecision 回执，
 * 唤醒被挂起的线程。服务不运行时内核会走 60s 超时后拒绝，等价于默认拒绝。
 */
class SuRequestService : Service() {
    companion object {
        private const val TAG = "SuRequestService"
        private const val CHANNEL_ID = "su_prompt"
        private const val NOTIFICATION_ID = 1158
        private const val POLL_INTERVAL_MS = 400L
        const val ACTION_START = "com.zcg.apatch.su.START"
        const val ACTION_STOP = "com.zcg.apatch.su.STOP"

        fun start(context: Context) {
            val intent = Intent(context, SuRequestService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SuRequestService::class.java).setAction(ACTION_STOP)
            context.stopService(intent)
        }
    }

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private var running = false

    /** 已经弹过窗的 pid 集合，避免同一请求被重复提示。 */
    private val promptedPids = HashSet<Int>()

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val requests = Natives.suPromptPoll(32)
                for (req in requests) {
                    onRequest(req)
                }
                // 清理已经消失的 pid 记录，避免无界增长
                if (promptedPids.size > 256) promptedPids.clear()
            } catch (t: Throwable) {
                Log.e(TAG, "poll failed", t)
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun onRequest(req: Natives.SuRequest) {
        // 会话内已允许：直接回执，不再打扰用户
        if (SuDecisionStore.isSessionAllowed(req.uid)) {
            Log.d(TAG, "session-allowed uid=${req.uid} pid=${req.pid}, auto grant")
            Natives.suDecision(req.pid, true)
            return
        }

        if (promptedPids.contains(req.pid)) return
        promptedPids.add(req.pid)

        val intent = Intent(this, SuPromptActivity::class.java).apply {
            putExtra(SuPromptActivity.EXTRA_PID, req.pid)
            putExtra(SuPromptActivity.EXTRA_UID, req.uid)
            putExtra(SuPromptActivity.EXTRA_SCONTEXT, req.scontext)
            putExtra(SuPromptActivity.EXTRA_COMM, req.comm)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(this, req.pid, intent, piFlags)
        val label = SuDecisionStore.labelForUid(this, req.uid)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SU 请求")
            .setContentText("$label (${req.comm}) 请求 root 权限")
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true) // Android 10+ 后台启动 Activity 的关键
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(req.pid, notification)
        } catch (t: Throwable) {
            // 通知权限被拒等场景：退化为直接拉起 Activity
            Log.w(TAG, "notify failed, launch directly", t)
            try {
                startActivity(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "launch prompt failed", e)
                // 连弹窗都起不来：默认拒绝，避免把用户晾在内核等待里
                SuDecisionStore.deny(req.pid)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        handlerThread = HandlerThread("su-prompt-poll").also { it.start() }
        handler = Handler(handlerThread.looper)
        SuDecisionStore.clear()
        Log.i(TAG, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        if (!running) {
            running = true
            handler.post(pollRunnable)
            Log.i(TAG, "polling started")
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID, "SU 授权请求", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "其他应用请求 root 权限时的确认弹窗"
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val open = Intent(this, SuPromptActivity::class.java).apply {
            putExtra(SuPromptActivity.EXTRA_PID, -1)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SU 授权守护")
            .setContentText("正在监听 root 权限请求")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(pollRunnable)
        handlerThread.quitSafely()
        SuDecisionStore.clear()
        Log.i(TAG, "service destroyed")
        super.onDestroy()
    }
}
