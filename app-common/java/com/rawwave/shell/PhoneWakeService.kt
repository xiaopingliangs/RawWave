package com.rawwave.shell

import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Phone-only exact watch-initiated HELLO and bounded diagnostic summary paths. */
class PhoneWakeService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        super.onMessageReceived(event)
        val path = event.path
        if (BuildConfig.DEVICE_ROLE != "phone" || event.data.size > 4096) return
        if (path == com.rawwave.core.DiagnosticSummary.PATH) {
            // Independent bounded receipt, before sync admission. Never preempt health transfer.
            try {
                val bytes = event.data.copyOf()
                val receipt = (application as ProbeApplication).summaryReceiver.receive(bytes)
                try { (application as ProbeApplication).journal?.event("DIAGNOSTIC_SUMMARY_SAVED",mapOf("transferId" to com.rawwave.core.DiagnosticSummary.id(bytes),"source" to event.sourceNodeId,"bytesReceived" to bytes.size)) } catch (_: Exception) {}
                com.google.android.gms.tasks.Tasks.await(com.google.android.gms.wearable.Wearable.getMessageClient(this)
                    .sendMessage(event.sourceNodeId,com.rawwave.core.DiagnosticSummary.ACK,receipt),3,java.util.concurrent.TimeUnit.SECONDS)
            } catch (failure: Exception) {
                try { (application as ProbeApplication).journal?.fault("DIAGNOSTIC_SUMMARY_RECEIVE_FAILED",failure) } catch (_: Exception) {}
            }
            return
        }
        if (path != "/rawwave/g2a/hello" && path != "/rawwave/activity/v1/hello") return
        val node = event.sourceNodeId
        val bytes = event.data.copyOf()
        val app = application as ProbeApplication
        try { app.journal?.event("PHONE_WAKE_MESSAGE_RECEIVED", mapOf("path" to path, "node" to node, "bytes" to bytes.size)) } catch (_: Exception) {}
        // The lease owns the receiver; unbinding this short-lived listener must not cancel it.
        Handler(Looper.getMainLooper()).post {
            if (path == "/rawwave/activity/v1/hello") {
                val receiver = app.receiveActivityHello
                if (receiver != null) receiver(node,bytes) else ActivityPhoneReceiveSession(app,node,bytes).start()
            } else {
                val receiver = app.receiveHello
                if (receiver != null) receiver(node,bytes) else PhoneReceiveSession(app,node,bytes).start()
            }
        }
    }
}



