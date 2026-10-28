package com.rawwave.shell

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import com.rawwave.core.WorkBudget
import com.rawwave.core.LeaseScope
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded sync lease. Never started for an idle page; never sticky or self-restarting. */
class SyncGuardService : Service() {
    class Lease(val deadline: Long, val cancelWork: () -> Unit,
                val ready: () -> Unit, val ended: (String) -> Unit) {
        val id = UUID.randomUUID().toString()
        val closed = AtomicBoolean(false)
        @Volatile var service: SyncGuardService? = null
    }
    companion object {
        private val leases = java.util.concurrent.ConcurrentHashMap<String, Lease>()
        fun open(context: Context, cancel: () -> Unit, ready: () -> Unit, ended: (String) -> Unit): Lease {
            val lease = Lease(SystemClock.elapsedRealtime() + WorkBudget.SYNC_LEASE_MS, cancel, ready, ended)
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
    }
    private val main = Handler(Looper.getMainLooper())
    private var timer = Executors.newSingleThreadScheduledExecutor()
    private val scope = LeaseScope<Lease>()
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
            val notification = Notification.Builder(this, "sync").setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("原波正在同步")
                .setContentText("完成立即停止；本轮最多80秒，失败不自动重试")
                .setOngoing(true).build()
            startForeground(17, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            val remaining = (requested.deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RawWave:bounded-sync").apply {
                setReferenceCounted(false); acquire(remaining + WorkBudget.LOCK_CLEANUP_MARGIN_MS)
            }
            timer.schedule({ release(requested, "SYNC_DEADLINE", notify = true) }, remaining, TimeUnit.MILLISECONDS)
            evidence("SYNC_GUARD_ACQUIRED", requested)
            if (!requested.closed.get()) requested.ready()
        } catch (error: Exception) {
            try { (application as ProbeApplication).journal?.fault("SYNC_GUARD_START_FAILED", error) } catch (_: Exception) {}
            release(requested, "SYNC_GUARD_START_FAILED", notify = true)
        }
        }
        return START_NOT_STICKY
    }
    private fun evidence(event: String, owner: Lease) {
        try { (application as ProbeApplication).journal?.event(event, mapOf(
            "lease" to owner.id, "deadlineMs" to owner.deadline,
            "elapsedRealtimeMs" to SystemClock.elapsedRealtime(), "wakeLockHeld" to (wakeLock?.isHeld == true),
            "batteryOptimizationExempt" to getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
            "noAutomaticRetry" to true)) } catch (_: Exception) {}
    }
    private fun release(owner: Lease?, reason: String, notify: Boolean) {
        if(owner == null) return
        scope.release(owner) {
        owner.closed.set(true)
        leases.remove(owner.id)
        // Cancel actual work before releasing its execution protection. Never wait for UI callbacks.
        try { owner.cancelWork() } catch (_: Exception) {}
        timer.shutdownNow()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        evidence("SYNC_GUARD_RELEASED_$reason", owner)
        owner.service = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } finally {
            stopSelf()
            if (notify) main.post { owner.ended(reason) }
        }
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) { release(scope.current(), "SYSTEM_TIMEOUT", notify = true) }
    override fun onDestroy() { release(scope.current(), "SERVICE_DESTROYED", notify = true); timer.shutdownNow(); super.onDestroy() }
}



