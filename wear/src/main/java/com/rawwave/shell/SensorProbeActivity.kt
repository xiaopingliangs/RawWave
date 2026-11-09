package com.rawwave.shell

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.rawwave.designsystem.RwBodyText
import com.rawwave.designsystem.RwDialogs
import com.rawwave.designsystem.RwGradientTitle
import com.rawwave.designsystem.RwPrimaryButton
import com.rawwave.designsystem.RwSecondaryButton
import com.rawwave.designsystem.RwTheme
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject

/**
 * Short, explicit sensor-registration probes.
 *
 * This is diagnostic evidence, not an activity source. light_cct is never added to a template;
 * platform heart rate is only recorded next to Samsung SDK heart rate for an independence test.
 */
class SensorProbeActivity : ComponentActivity() {

    private enum class Kind { LIGHT_CCT, HEART_RATE_AB }

    private val app get() = application as ProbeApplication
    private val handler = Handler(Looper.getMainLooper())
    private val sensorManager by lazy { getSystemService(SensorManager::class.java) }
    private lateinit var status: TextView
    private lateinit var lightButton: RwPrimaryButton
    private lateinit var heartButton: RwSecondaryButton
    private lateinit var latestButton: RwSecondaryButton

    private var running = false
    private var finished = false
    private var pending: Kind? = null
    private var activeKind: Kind? = null
    private var startedElapsedMs = 0L
    private var startedUtcMs = 0L
    private var durationMs = 0L

    private var lightSensor: Sensor? = null
    private var lightRegistered = false
    private val lightSamples = Collections.synchronizedList(mutableListOf<JSONObject>())
    private var lightError: String? = null

    private var platformHeartSensor: Sensor? = null
    private var platformHeartRegistered = false
    private val platformHeartSamples = Collections.synchronizedList(mutableListOf<JSONObject>())
    private var platformHeartError: String? = null

    private var samsungBackend: SamsungBackend? = null
    private var samsungTracker: SamsungTracker? = null
    private var samsungError: String? = null
    private var samsungCapability: Map<String, Any> = emptyMap()
    private val samsungHeartSamples = Collections.synchronizedList(mutableListOf<JSONObject>())

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val requested = pending
        pending = null
        if (requested != null && result.isNotEmpty() && result.values.all { it }) begin(requested)
        else status.text = "未获得所需权限；这次没有开始。允许心率权限后可重试。"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val colors = RwTheme.colors(this)
        window.statusBarColor = colors.background
        window.navigationBarColor = colors.background
        val inset = (resources.displayMetrics.widthPixels * .15f).toInt()
        val scroll = ScrollView(this).apply {
            setBackgroundColor(colors.background)
            isFillViewport = true
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(inset, dp(28), inset, dp(44))
        }
        body.addView(RwGradientTitle(this).apply {
            text = "传感器测试"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
        })
        body.addView(RwBodyText(this).apply {
            text = "只做短时注册测试\n${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_RUN}"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setTextColor(colors.onSurfaceVariant)
            setPadding(0, 0, 0, dp(8))
        })
        body.addView(RwBodyText(this).apply {
            text = "light_cct 只验证能否注册；平台心率只做 A/B 对照。\n不加入活动模板，不改原始数据。"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setTextColor(colors.onSurface)
            setPadding(0, dp(2), 0, dp(6))
        })
        status = RwBodyText(this).apply {
            text = "准备就绪。测试结果写入 diagnostics/sensor-probes，随问题报告导出。"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setTextColor(colors.onSurface)
            setPadding(0, dp(6), 0, dp(6))
        }
        body.addView(status)

        lightButton = RwPrimaryButton(this).apply {
            text = "light_cct 注册测试（10 秒）"
            setOnClickListener { startTest(Kind.LIGHT_CCT) }
        }
        body.addView(lightButton, buttonParams(8))
        heartButton = RwSecondaryButton(this).apply {
            text = "平台 HR / SDK HR A/B（60 秒）"
            setOnClickListener { startTest(Kind.HEART_RATE_AB) }
        }
        body.addView(heartButton, buttonParams(8))
        latestButton = RwSecondaryButton(this).apply {
            text = "查看最新测试结果"
            setOnClickListener { showLatest() }
        }
        body.addView(latestButton, buttonParams(8))
        body.addView(RwSecondaryButton(this).apply {
            text = "‹ 返回"
            setOnClickListener { finish() }
        }, buttonParams(16))
        scroll.addView(body)
        return scroll
    }

    private fun startTest(kind: Kind) {
        if (running) return
        if (ActivityRecordingService.state.active) {
            status.text = "正在采集活动；结束活动后再做传感器注册测试。"
            return
        }
        if (app.activeSyncOwner != null || app.activitySyncStatus.running) {
            status.text = "正在接收/同步数据；完成后再测试，避免抢占传感器。"
            return
        }
        if (kind == Kind.HEART_RATE_AB) {
            val missing = heartPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                pending = kind
                status.text = "需要心率权限；请在系统弹窗中允许。"
                permissions.launch(missing.toTypedArray())
                return
            }
        }
        begin(kind)
    }

    private fun heartPermissions(): List<String> = listOf(
        if (Build.VERSION.SDK_INT < 36) Manifest.permission.BODY_SENSORS
        else "android.permission.health.READ_HEART_RATE",
    )

    private fun begin(kind: Kind) {
        running = true
        finished = false
        activeKind = kind
        startedElapsedMs = SystemClock.elapsedRealtime()
        startedUtcMs = System.currentTimeMillis()
        durationMs = if (kind == Kind.LIGHT_CCT) 10_000L else 60_000L
        lightError = null
        platformHeartError = null
        samsungError = null
        samsungCapability = emptyMap()
        lightSamples.clear()
        platformHeartSamples.clear()
        samsungHeartSamples.clear()
        setButtons(false)
        status.text = if (kind == Kind.LIGHT_CCT) {
            "正在查找并注册 light_cct…"
        } else {
            "正在注册平台心率和 Samsung SDK 心率…"
        }
        if (kind == Kind.LIGHT_CCT) beginLight() else beginHeartAb()
        handler.post(countdown)
    }

    private fun beginLight() {
        lightSensor = sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull { sensor ->
            sensor.type == LIGHT_CCT_TYPE ||
                sensor.name.contains("light_cct", ignoreCase = true) ||
                sensor.stringType?.contains("light_cct", ignoreCase = true) == true
        }
        val sensor = lightSensor
        if (sensor == null) {
            lightError = "UNAVAILABLE: device sensor list has no light_cct"
            return
        }
        lightRegistered = try {
            sensorManager.registerListener(lightListener, sensor, 200_000)
        } catch (error: Exception) {
            lightError = "REGISTER_EXCEPTION:${error.javaClass.simpleName}:${error.message}"
            false
        }
        if (!lightRegistered) lightError = lightError ?: "REGISTRATION_FAILED: registerListener returned false"
    }

    private fun beginHeartAb() {
        platformHeartSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        val platform = platformHeartSensor
        if (platform == null) {
            platformHeartError = "UNAVAILABLE: device sensor list has no TYPE_HEART_RATE"
        } else {
            platformHeartRegistered = try {
                sensorManager.registerListener(platformHeartListener, platform, SensorManager.SENSOR_DELAY_NORMAL)
            } catch (error: Exception) {
                platformHeartError = "REGISTER_EXCEPTION:${error.javaClass.simpleName}:${error.message}"
                false
            }
            if (!platformHeartRegistered) platformHeartError = platformHeartError ?: "REGISTRATION_FAILED"
        }
        val backend = DeviceSamsungBackend(this, null)
        samsungBackend = backend
        try {
            backend.connect(
                ready = {
                    if (!running || activeKind != Kind.HEART_RATE_AB) return@connect
                    try {
                        samsungCapability = backend.capability()
                        val tracker = backend.create("HEART_RATE")
                        samsungTracker = tracker
                        tracker.listen(samsungHeartListener)
                    } catch (error: Exception) {
                        samsungError = "CREATE_FAILED:${error.javaClass.simpleName}:${error.message}"
                    }
                },
                ended = { samsungError = "SDK_CONNECTION_ENDED" },
                failed = { reason -> samsungError = "SDK_CONNECTION_FAILED:$reason" },
            )
        } catch (error: Exception) {
            samsungError = "CONNECT_EXCEPTION:${error.javaClass.simpleName}:${error.message}"
        }
    }

    private val countdown = object : Runnable {
        override fun run() {
            if (!running) return
            val remaining = (durationMs - (SystemClock.elapsedRealtime() - startedElapsedMs)).coerceAtLeast(0)
            val label = if (activeKind == Kind.LIGHT_CCT) "light_cct" else "HR A/B"
            status.text = buildString {
                append(label)
                append(" 进行中：剩余 ")
                append((remaining + 999) / 1000)
                append(" 秒")
                if (activeKind == Kind.HEART_RATE_AB) {
                    append("\n平台样本 ")
                    append(platformHeartSamples.size)
                    append(" · SDK 样本 ")
                    append(samsungHeartSamples.size)
                } else {
                    append("\n样本 ")
                    append(lightSamples.size)
                }
            }
            if (remaining <= 0) {
                finishCurrent("OK", null)
            } else {
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!running || activeKind != Kind.LIGHT_CCT || lightSamples.size >= 5000) return
            lightSamples.add(JSONObject().apply {
                put("receivedUtcMs", System.currentTimeMillis())
                put("receivedElapsedMs", SystemClock.elapsedRealtime())
                put("sensorTimestampNs", event.timestamp)
                put("accuracy", event.accuracy)
                put("values", JSONArray().apply { event.values.forEach { put(it.toDouble()) } })
            })
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val platformHeartListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!running || activeKind != Kind.HEART_RATE_AB || platformHeartSamples.size >= 2000) return
            platformHeartSamples.add(JSONObject().apply {
                put("receivedUtcMs", System.currentTimeMillis())
                put("receivedElapsedMs", SystemClock.elapsedRealtime())
                put("sensorTimestampNs", event.timestamp)
                put("accuracy", event.accuracy)
                put("values", JSONArray().apply { event.values.forEach { put(it.toDouble()) } })
            })
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val samsungHeartListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(points: List<DataPoint>) {
            if (!running || activeKind != Kind.HEART_RATE_AB) return
            val receivedUtc = System.currentTimeMillis()
            val receivedElapsed = SystemClock.elapsedRealtime()
            for (point in points) {
                if (samsungHeartSamples.size >= 2000) break
                try {
                    samsungHeartSamples.add(JSONObject().apply {
                        put("sdkTimestamp", point.timestamp)
                        put("receivedUtcMs", receivedUtc)
                        put("receivedElapsedMs", receivedElapsed)
                        put("HEART_RATE", readValue(point, ValueKey.HeartRateSet.HEART_RATE))
                        put("HEART_RATE_STATUS", readValue(point, ValueKey.HeartRateSet.HEART_RATE_STATUS))
                        put("IBI_LIST", jsonArray(readValue(point, ValueKey.HeartRateSet.IBI_LIST)))
                        put("IBI_STATUS_LIST", jsonArray(readValue(point, ValueKey.HeartRateSet.IBI_STATUS_LIST)))
                    })
                } catch (error: Exception) {
                    samsungError = "CALLBACK_COPY:${error.javaClass.simpleName}:${error.message}"
                }
            }
        }

        override fun onFlushCompleted() = Unit

        override fun onError(error: HealthTracker.TrackerError) {
            samsungError = "SDK_TRACKER_ERROR:${error.name}"
        }
    }

    private fun finishCurrent(result: String, error: String?) {
        if (!running || finished) return
        finished = true
        running = false
        handler.removeCallbacks(countdown)
        val kind = activeKind ?: return
        cleanupSensors()
        val endedUtc = System.currentTimeMillis()
        val finalResult = when {
            result != "OK" -> result
            kind == Kind.LIGHT_CCT && lightError != null -> "FAILED"
            kind == Kind.HEART_RATE_AB && (platformHeartError != null || samsungError != null) -> "PARTIAL"
            else -> "OK"
        }
        val report = JSONObject().apply {
            put("schema", 1)
            put("kind", "SENSOR_REGISTRATION_PROBE")
            put("test", kind.name)
            put("build", BuildConfig.VERSION_NAME)
            put("buildRun", BuildConfig.BUILD_RUN)
            put("device", JSONObject().apply {
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("sdkInt", Build.VERSION.SDK_INT)
            })
            put("startedUtcMs", startedUtcMs)
            put("endedUtcMs", endedUtc)
            put("durationMs", durationMs)
            put("registration", finalResult)
            put("error", error ?: JSONObject.NULL)
            put("sensors", JSONArray().apply {
                lightSensor?.let { put(sensorJson(it)) }
                platformHeartSensor?.let { put(sensorJson(it)) }
            })
            put("errors", JSONObject().apply {
                put("light_cct", lightError ?: JSONObject.NULL)
                put("platform_heart_rate", platformHeartError ?: JSONObject.NULL)
                put("samsung_heart_rate", samsungError ?: JSONObject.NULL)
            })
            put("samples", JSONObject().apply {
                if (kind == Kind.LIGHT_CCT) put("light_cct", JSONArray(lightSamples))
                else {
                    put("platform_heart_rate", JSONArray(platformHeartSamples))
                    put("samsung_heart_rate", JSONArray(samsungHeartSamples))
                }
            })
            put("samsungCapability", JSONObject(samsungCapability))
            put("notes", "light_cct is registration-only; platform heart rate is A/B-only. Neither is added to core sources or rewritten.")
        }
        val file = writeReport(if (kind == Kind.LIGHT_CCT) "light_cct" else "hr_ab", report)
        val sampleCount = if (kind == Kind.LIGHT_CCT) lightSamples.size
        else platformHeartSamples.size + samsungHeartSamples.size
        try {
            app.journal?.event("SENSOR_PROBE_RESULT", mapOf(
                "test" to kind.name,
                "file" to file.name,
                "registration" to finalResult,
                "samples" to sampleCount,
                "error" to (error ?: ""),
            ))
        } catch (_: Exception) {}
        activeKind = null
        setButtons(true)
        status.text = buildString {
            append(if (kind == Kind.LIGHT_CCT) "light_cct" else "HR A/B")
            append(" 完成：")
            append(finalResult)
            append("\n样本：")
            append(sampleCount)
            append("\n结果：diagnostics/sensor-probes/")
            append(file.name)
            append("\n随问题报告导出。")
            val detail = when (kind) {
                Kind.LIGHT_CCT -> lightError
                Kind.HEART_RATE_AB -> listOfNotNull(platformHeartError, samsungError).joinToString("；").ifBlank { null }
            }
            if (!detail.isNullOrBlank()) {
                append("\n")
                append(detail)
            }
        }
    }

    private fun cleanupSensors() {
        if (lightRegistered) {
            try { sensorManager.unregisterListener(lightListener) } catch (_: Exception) {}
            lightRegistered = false
        }
        if (platformHeartRegistered) {
            try { sensorManager.unregisterListener(platformHeartListener) } catch (_: Exception) {}
            platformHeartRegistered = false
        }
        try { samsungTracker?.unset() } catch (_: Exception) {}
        samsungTracker = null
        try { samsungBackend?.disconnect() } catch (_: Exception) {}
        samsungBackend = null
    }

    private fun writeReport(prefix: String, report: JSONObject): File {
        val directory = File(filesDir, "diagnostics/sensor-probes")
        directory.mkdirs()
        val target = File(directory, "$prefix-$startedUtcMs.json")
        val temporary = File(directory, target.name + ".part")
        FileOutputStream(temporary).use { output ->
            output.write(report.toString(2).toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    private fun sensorJson(sensor: Sensor): JSONObject = JSONObject().apply {
        put("type", sensor.type)
        put("name", sensor.name)
        put("stringType", sensor.stringType ?: JSONObject.NULL)
        put("vendor", sensor.vendor)
        put("version", sensor.version)
        put("minDelayUs", sensor.minDelay)
        put("maxRange", sensor.maximumRange.toDouble())
        put("resolution", sensor.resolution.toDouble())
        put("powerMa", sensor.power.toDouble())
        put("fifoMax", sensor.fifoMaxEventCount)
        put("fifoReserved", sensor.fifoReservedEventCount)
        put("reportingMode", sensor.reportingMode)
        put("wakeUp", sensor.isWakeUpSensor)
    }

    private fun <T> readValue(point: DataPoint, key: ValueKey<T>): T? =
        try { point.getValue(key) } catch (_: Exception) { null }

    private fun jsonArray(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is List<*> -> JSONArray(value)
        is IntArray -> JSONArray(value.toList())
        else -> JSONArray().put(value)
    }

    private fun showLatest() {
        val directory = File(filesDir, "diagnostics/sensor-probes")
        val latest = directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() }
        if (latest == null) {
            RwDialogs.message(this, "传感器测试", "还没有测试结果。先运行一次 light_cct 或 HR A/B。", "知道了")
            return
        }
        val report = try { JSONObject(latest.readText(StandardCharsets.UTF_8)) }
        catch (error: Exception) {
            RwDialogs.message(this, "传感器测试", "结果文件读取失败：${error.javaClass.simpleName}: ${error.message}", "知道了")
            return
        }
        val samples = report.optJSONObject("samples")
        val sampleText = samples?.keys()?.asSequence()?.joinToString(" · ") { key ->
            "$key=${samples.optJSONArray(key)?.length() ?: 0}"
        }.orEmpty()
        RwDialogs.message(
            this,
            "最新传感器测试",
            "类型：${report.optString("test")}\n" +
                "结果：${report.optString("registration")}\n" +
                "样本：${sampleText.ifBlank { "0" }}\n" +
                "文件：diagnostics/sensor-probes/${latest.name}",
            "知道了",
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        running = false
        cleanupSensors()
        super.onDestroy()
    }

    private fun setButtons(enabled: Boolean) {
        lightButton.isEnabled = enabled
        heartButton.isEnabled = enabled
        latestButton.isEnabled = enabled
    }

    private fun buttonParams(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = RwTheme.dp(this, value)

    private companion object {
        const val LIGHT_CCT_TYPE = 65587
    }
}
