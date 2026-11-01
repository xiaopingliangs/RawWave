package com.rawwave.shell

import android.content.Context
import android.os.Process
import java.io.File
import java.util.concurrent.TimeUnit

/** Bounded, own-process SDK log snapshot. Never starts a sensor or a background retry. */
internal class SensorReleaseDiagnostics(context: Context) {
    private val app = context.applicationContext as? ProbeApplication
    private val directory = File(context.filesDir, "diagnostics")
    companion object {
        // One process-wide bounded writer, with no idle worker retained beyond one second.
        private val writer = com.rawwave.core.DiagnosticDispatcher(256)
    }
    fun record(kind: String, fields: Map<String, Any>) {
        writer.submit(kind, fields) { event, snapshot -> recordOnWorker(event, snapshot) }
    }
    private fun recordOnWorker(kind: String, fields: Map<String, Any?>) {
        app?.journal?.event(kind, fields)
        if (fields["stage"] == "OWNER_CLOSED") {
            Thread({
                try {
                    directory.mkdirs()
                    val temp = File(directory, "sensor-sdk-${fields["ownerId"]}.tmp")
                    val process = ProcessBuilder("logcat", "-d", "-t", "500", "--pid=${Process.myPid()}",
                        "-v", "threadtime", "HealthTracker:V", "HealthTrackingConnector:V", "System.err:V", "*:S")
                        .redirectErrorStream(true).redirectOutput(temp).start()
                    if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
                    else {
                        val target = File(directory, "sensor-sdk-logcat.txt")
                        if (target.exists()) target.copyTo(File(directory, "sensor-sdk-logcat.previous.txt"), true)
                        temp.copyTo(target, true)
                        temp.copyTo(File(directory, "sensor-sdk-${fields["ownerId"]}.txt"), true)
                        directory.listFiles()?.filter { it.name.matches(Regex("sensor-sdk-[0-9a-f-]{36}\\.txt")) }
                            ?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
                        temp.delete()
                        app?.journal?.event("SDK_LOG_SNAPSHOT", mapOf("exit" to process.exitValue(),
                            "bytes" to target.length(), "ownerId" to fields["ownerId"].toString()))
                    }
                } catch (e: Exception) {
                    try { app?.journal?.event("SDK_LOG_SNAPSHOT_FAILED", mapOf("error" to e.toString())) } catch (_: Exception) {}
                }
            }, "sensor-release-evidence").start()
        }
    }
}
