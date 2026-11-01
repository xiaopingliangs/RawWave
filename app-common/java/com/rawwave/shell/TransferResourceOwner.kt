package com.rawwave.shell

import com.google.android.gms.tasks.Task
import com.rawwave.core.ResourceHandoff
import java.util.concurrent.Executor

/** Platform assembly for late Google Task success. Never waits on the UI thread to clean up. */
class TransferResourceOwner(private val evidence: (String, String) -> Unit) {
    companion object {
        // Stream.close may block on platform IO; never run it on cancellation/UI caller.
        private val streamCleanup = java.util.concurrent.ThreadPoolExecutor(0,2,1,
            java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
            java.util.concurrent.ThreadFactory { r -> Thread(r,"rawwave-stream-close").apply { isDaemon=true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
    }
    private val direct = Executor { it.run() }
    fun <T : java.io.Closeable> pendingStream(task: Task<T>, label: String): Task<T> =
        pending(task,label) { stream -> com.google.android.gms.tasks.Tasks.call(streamCleanup) { stream.close(); null } }

    private val scope = ResourceHandoff { safeEvidence("RESOURCE_RELEASE_REQUEST_FAILED", it.toString()) }
    private fun safeEvidence(stage: String, detail: String) {
        try { evidence(stage, detail) } catch (_: Exception) {}
    }
    fun <T> pending(task: Task<T>, label: String, release: (T) -> Task<*>): Task<T> {
        // Use task identity also for Void registrations; success can race cancellation/await timeout.
        task.addOnSuccessListener(direct) { value -> own(task, label) { release(value) } }
        return task
    }
    fun own(identity: Any, label: String, release: () -> Task<*>) {
        scope.accept(identity) {
            val task = release()
            safeEvidence("RESOURCE_RELEASE_REQUESTED", label)
            task.addOnSuccessListener(direct) { safeEvidence("RESOURCE_RELEASE_API_SUCCEEDED", label) }
            task.addOnFailureListener(direct) { safeEvidence("RESOURCE_RELEASE_API_FAILED", "$label:${it.message}") }
        }
    }
    fun close() = scope.close()
}
