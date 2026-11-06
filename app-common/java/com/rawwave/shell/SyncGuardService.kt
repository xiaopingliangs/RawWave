package com.rawwave.shell

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import com.rawwave.core.WorkBudget
import com.rawwave.core.LeaseScope
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded sync lease. Never started for an idle page; never sticky or self-restarting. */
class SyncGuardService : Service() {
    class Lease(initialDeadline: Long, val cancelWork: () -> Unit,
                val ready: () -> Unit, val ended: (String) -> Unit) {
        val id = UUID.randomUUID().toString()
        val closed = AtomicBoolean(false)
        @Volatile var deadline: Long = initialDeadline
            internal set
        @Volatile internal var generation: Long = 0L
        @Volatile var service: SyncGuardService? = null
        /** Move the same lease later. Never revives a closed lease or shortens its deadline. */
        fun extendBy(ms: Long): Boolean {
            if (ms <= 0L) return false
            return service?.extend(this, ms) ?: false
        }
    }
    companion object {
        private val leases = java.util.concurrent.ConcurrentHashMap<String, Lease>()
        /**
         * [leaseMs] is chosen by the caller before the service starts. Diagnostic work keeps the
         * short default; raw-activity work starts with the initial activity window and calls
         * [Lease.extendBy] only after packaging/HELLO proves the archive is large enough.
         * A repeated command never renews an existing lease.
         */
        fun open(context: Context, leaseMs: Long = WorkBudget.SYNC_LEASE_MS,
                 cancel: () -> Unit, ready: () -> Unit, ended: (String) -> Unit): Lease {
            val lease = Lease(SystemClock.elapsedRealtime() + leaseMs.coerceAtLeast(1L), cancel, ready, ended)
            leases[lease.id] = lease
            try {
                val power = context.getSystemService(PowerManager::class.java)
                (context.applicationContext as ProbeApplication).journal?.event("SYNC_GUARD_REQUESTED", mapOf(
                    "lease" to lease.id, "deadlineMs" to lease.deadline,
                    "batteryOptimizationExempt" to power.isIgnoringBatteryOptimizations(context.packageName),
                    "deviceIdleMode" to power.isDeviceIdleMode, "interactive" to power.isInteractive))
            } catch (_: Exception) {}
            try { context.startForegroundService(Intent(context, SyncGuardService::class.java).putExtra("lease", lease.id)) }
            catch (error: Exception) {
                leases.remove(lease.id); lease.closed.set(true)
                try { (context.applicationContext as ProbeApplication).journal?.fault("SYNC_GUARD_REQUEST_REJECTED", error) } catch (_: Exception) {}
                throw error
            }
            return lease
        }
        fun close(lease: Lease?) {
            if (lease == null || !lease.closed.compareAndSet(false, true)) return
            leases.remove(lease.id)
            lease.service?.release(lease, "OWNER_CLOSED", notify = false)
        }
        private fun budgetText(ms: Long): String {
            val seconds=(ms.coerceAtLeast(1L)+999L)/1000L
            return if(seconds>=120L) "约 ${(seconds+59L)/60L} 分钟" else "$seconds 秒"
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private var timer = Executors.newSingleThreadScheduledExecutor()
    private var timerTask: ScheduledFuture<*>? = null
    private val scope = LeaseScope<Lease>()
    private val extensionLock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getStringExtra("lease")?.let { leases[it] }
        if (requested == null || requested.closed.get() || SystemClock.elapsedRealtime() >= requested.deadline) {
            if (requested != null && requested.closed.compareAndSet(false, true)) {
                leases.remove(requested.id)
                try { requested.cancelWork() } catch (_: Exception) {}
                main.post { requested.ended("START_DEADLINE_EXPIRED") }
            }
            if (scope.current() == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val current = scope.current()
        if (current != null) {
            if (current !== requested && requested.closed.compareAndSet(false, true)) {
                leases.remove(requested.id)
                try { requested.cancelWork() } catch (_: Exception) {}
                main.post { requested.ended("SERVICE_BUSY") }
            }
            return START_NOT_STICKY // Repeated commands never renew a lease.
        }
        scope.acquire(requested) {
        if (timer.isShutdown) timer = Executors.newSingleThreadScheduledExecutor()
        requested.service = this
        try {
            if (requested.closed.get() || SystemClock.elapsedRealtime() >= requested.deadline) {
                release(requested, "START_DEADLINE_EXPIRED", notify = true)
                return@acquire
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("sync", "限时数据同步", NotificationManager.IMPORTANCE_LOW))
            val remaining = (requested.deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1)
            startForeground(17, notification(remaining), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RawWave:bounded-sync").apply {
                setReferenceCounted(false); acquire(remaining + WorkBudget.LOCK_CLEANUP_MARGIN_MS)
            }
            timerTask = timer.schedule({
                if (!requested.closed.get() && requested.generation == 0L)
                    release(requested, "SYNC_DEADLINE", notify = true)
            }, remaining, TimeUnit.MILLISECONDS)
            evidence("SYNC_GUARD_ACQUIRED", requested)
            if (!requested.closed.get()) requested.ready()
        } catch (error: Exception) {
            try { (application as ProbeApplication).journal?.fault("SYNC_GUARD_START_FAILED", error) } catch (_: Exception) {}
            release(requested, "SYNC_GUARD_START_FAILED", notify = true)
        }
        }
        return START_NOT_STICKY
    }
    /**
     * Extend the current lease without opening a second service or reviving a closed one.
     * The timer and wake lock are rescheduled under one lock so a deadline cannot be lost
     * between cancel and replace.
     */
    internal fun extend(owner: Lease, extraMs: Long): Boolean {
        if (extraMs <= 0L || owner.closed.get() || owner.service !== this) return false
        synchronized(extensionLock) {
            if (owner.closed.get() || owner.service !== this) return false
            val now = SystemClock.elapsedRealtime()
            val target = now + extraMs.coerceAtLeast(1L)
            if (target <= owner.deadline) return false
            val oldDeadline = owner.deadline
            val oldGeneration = owner.generation
            val remaining = (target - now).coerceAtLeast(1L)
            owner.deadline = target
            owner.generation = oldGeneration + 1L
            val generation = owner.generation
            val replacement = try {
                timer.schedule({
                    if (!owner.closed.get() && owner.generation == generation)
                        release(owner, "SYNC_DEADLINE", notify = true)
                }, remaining, TimeUnit.MILLISECONDS)
            } catch (error: Exception) {
                owner.deadline = oldDeadline
                owner.generation = oldGeneration
                try { (application as ProbeApplication).journal?.fault("SYNC_GUARD_EXTENSION_FAILED", error) } catch (_: Exception) {}
                if (SystemClock.elapsedRealtime() >= oldDeadline) release(owner, "SYNC_DEADLINE", notify = true)
                return false
            }
            val previous = timerTask
            timerTask = replacement
            previous?.cancel(false)
            try {
                wakeLock?.let {
                    if (it.isHeld) it.release()
                    it.acquire(remaining + WorkBudget.LOCK_CLEANUP_MARGIN_MS)
                }
            } catch (_: Exception) {}
            // Update the foreground notification through startForeground rather than
            // NotificationManager.notify: the app intentionally does not request
            // POST_NOTIFICATIONS, while a foreground-service notification is owned by
            // the service and does not need that runtime permission.
            try { startForeground(17, notification(remaining), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) } catch (_: Exception) {}
            evidence("SYNC_GUARD_EXTENDED", owner)
            return true
        }
    }
    private fun notification(remainingMs: Long): Notification {
        val remaining = remainingMs.coerceAtLeast(1L)
        return Notification.Builder(this, "sync").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("原波正在同步")
            .setContentText("完成立即停止；本轮最多${budgetText(remaining)}，失败自动重试一次")
            .setOngoing(true).build()
    }
    private fun evidence(event: String, owner: Lease) {
        try { (application as ProbeApplication).journal?.event(event, mapOf(
            "lease" to owner.id, "deadlineMs" to owner.deadline,
            "elapsedRealtimeMs" to SystemClock.elapsedRealtime(), "wakeLockHeld" to (wakeLock?.isHeld == true),
            "batteryOptimizationExempt" to getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
            "noAutomaticRetry" to false, "boundedAutomaticRetry" to true)) } catch (_: Exception) {}
    }
    private fun release(owner: Lease?, reason: String, notify: Boolean) {
        if(owner == null) return
        scope.release(owner) {
        synchronized(extensionLock) {
            owner.closed.set(true)
            leases.remove(owner.id)
            timerTask?.cancel(false)
            timerTask = null
            timer.shutdownNow()
        }
        // Cancel actual work before releasing its execution protection. Never wait for UI callbacks.
        try { owner.cancelWork() } catch (_: Exception) {}
        synchronized(extensionLock) {
            try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
            wakeLock = null
            owner.service = null
        }
        evidence("SYNC_GUARD_RELEASED_$reason", owner)
        try { stopForeground(STOP_FOREGROUND_REMOVE) } finally {
            stopSelf()
            if (notify) main.post { owner.ended(reason) }
        }
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) { release(scope.current(), "SYSTEM_TIMEOUT", notify = true) }
    override fun onDestroy() { release(scope.current(), "SERVICE_DESTROYED", notify = true); timer.shutdownNow(); super.onDestroy() }
}
