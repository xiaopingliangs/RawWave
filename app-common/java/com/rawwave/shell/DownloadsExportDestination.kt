package com.rawwave.shell

import android.content.ContentResolver
import android.content.ContentValues
import android.provider.MediaStore
import com.rawwave.core.ExportDestination
import java.io.IOException

/** Only this adapter knows MediaStore. No timers, transfer, UI or process termination. */
class DownloadsExportDestination(private val resolver: ContentResolver) : ExportDestination {
    override fun create(name: String): ExportDestination.Pending {
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/RawWave")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IOException("创建 Downloads 目标失败；本机日志仍保留")
        return object : ExportDestination.Pending {
            override fun openOutput() = resolver.openOutputStream(uri, "w") ?: throw IOException("无法打开目标文件")
            override fun openInput() = resolver.openInputStream(uri) ?: throw IOException("无法回读目标文件")
            override fun publish() {
                if (resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1)
                    throw IOException("发布导出失败")
            }
            override fun discard() {
                if (resolver.delete(uri, null, null) != 1) throw IOException("未发布的诊断副本清理失败")
            }
        }
    }
}
