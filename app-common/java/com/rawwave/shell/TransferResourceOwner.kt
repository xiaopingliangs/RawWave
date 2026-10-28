package com.rawwave.shell

import com.google.android.gms.tasks.Task
import com.rawwave.core.ResourceHandoff
import java.util.concurrent.Executor

/** Platform assembly for late Google Task success. Never waits on the UI thread to clean up. */
class TransferResourceOwner(private val evidence: (String, String) -> Unit) {
    private val direct = Executor { it.run() }
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
