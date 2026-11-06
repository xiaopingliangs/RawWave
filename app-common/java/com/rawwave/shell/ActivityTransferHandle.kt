package com.rawwave.shell

/** Minimal handle the watch sync owner needs from one transfer attempt. */
internal interface ActivityTransferHandle {
    fun start()
    fun cancel()
}
