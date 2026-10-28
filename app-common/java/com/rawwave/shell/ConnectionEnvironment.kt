package com.rawwave.shell

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.google.android.gms.common.GoogleApiAvailability
import com.rawwave.core.DiagnosticArchive

/** Read-only, narrowly scoped evidence. Does not force a service route or bypass SDK trust. */
object ConnectionEnvironment {
    @Suppress("DEPRECATION")
    fun inspect(context: Context): Map<String, Any> {
        val pm = context.packageManager
        val packages = listOf("com.google.android.gms", "com.google.android.wearable.app.cn").map { name ->
            try {
                val info = pm.getPackageInfo(name,PackageManager.GET_SIGNING_CERTIFICATES)
                val services = pm.queryIntentServices(Intent("com.google.android.gms.wearable.BIND").setPackage(name),0)
                mapOf("package" to name,"visible" to true,"versionName" to (info.versionName ?: "UNKNOWN"),
                    "versionCode" to info.longVersionCode,"enabled" to (info.applicationInfo?.enabled ?: false),
                    "signerSha256" to (info.signingInfo?.apkContentsSigners?.map { DiagnosticArchive.hash(it.toByteArray()) } ?: emptyList()),
                    "wearableBindServices" to services.map { "${it.serviceInfo.packageName}/${it.serviceInfo.name}" })
            } catch (error: Exception) {
                mapOf("package" to name,"visible" to false,"error" to "${error.javaClass.simpleName}: ${error.message}")
            }
        }
        val gmsStatus = try { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) } catch (_: Exception) { -1 }
        return mapOf("packages" to packages,"googlePlayServicesStatus" to gmsStatus,
            "note" to "GMS availability is not Wearable API availability; package visibility is not signature acceptance. SDK chooses route; not independently observed.")
    }
}
