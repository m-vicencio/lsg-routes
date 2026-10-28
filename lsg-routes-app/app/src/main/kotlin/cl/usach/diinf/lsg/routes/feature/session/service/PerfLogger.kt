package cl.usach.diinf.lsg.routes.feature.session.service

import android.content.Context
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger de rendimiento para las pruebas de campo (ver Logger-Interno.md). Muestrea cada [SAMPLE_INTERVAL_MS]
 * y escribe un CSV por recorrido en almacenamiento propio de la app (sin permisos). Componente
 * independiente: [TrackingService] solo lo arranca/detiene y le pasa los contadores que ya lleva.
 *
 * Errores de escritura se registran y se ignoran: nunca deben afectar el seguimiento.
 */
class PerfLogger(private val context: Context) {

    private var job: Job? = null
    private var writer: BufferedWriter? = null
    private var file: File? = null

    private var startElapsedMs = 0L
    private var prevCpuMs = 0L
    private var prevSampleElapsedMs = 0L

    private var sampleCount = 0
    private var cpuPctSum = 0.0
    private var cpuPctMax = 0.0
    private var ramPssSum = 0L
    private var ramPssMax = 0
    private var lastGpsUpdates = 0
    private var lastSteps = 0

    private val powerManager by lazy { context.getSystemService(PowerManager::class.java) }
    private val batteryManager by lazy { context.getSystemService(BatteryManager::class.java) }
    private val memInfo = Debug.MemoryInfo()

    fun start(sessionId: String, mode: String, gpsUpdates: () -> Int, steps: () -> Int) {
        if (job?.isActive == true) return
        try {
            val dir = File(context.getExternalFilesDir(null), "perf").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val out = File(dir, "perf_${stamp}_${mode}_${sessionId}.csv")
            val w = BufferedWriter(FileWriter(out))
            w.write(HEADER)
            w.newLine()
            file = out
            writer = w
        } catch (e: Exception) {
            Timber.w(e, "PerfLogger: no se pudo crear el CSV, sesion sin logging de rendimiento")
            return
        }

        startElapsedMs = SystemClock.elapsedRealtime()
        prevCpuMs = Process.getElapsedCpuTime()
        prevSampleElapsedMs = startElapsedMs
        sampleCount = 0
        cpuPctSum = 0.0
        cpuPctMax = 0.0
        ramPssSum = 0L
        ramPssMax = 0
        lastGpsUpdates = 0
        lastSteps = 0

        job = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                sample(gpsUpdates(), steps())
            }
        }
    }

    private fun sample(gpsUpdates: Int, steps: Int) {
        try {
            val nowElapsed = SystemClock.elapsedRealtime()
            val cpuNow = Process.getElapsedCpuTime()
            val wallDeltaMs = nowElapsed - prevSampleElapsedMs
            val cpuPct = if (wallDeltaMs > 0) {
                (cpuNow - prevCpuMs).toDouble() / wallDeltaMs * 100.0
            } else 0.0
            prevCpuMs = cpuNow
            prevSampleElapsedMs = nowElapsed

            Debug.getMemoryInfo(memInfo)
            val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val screenOn = powerManager.isInteractive

            sampleCount++
            cpuPctSum += cpuPct
            if (cpuPct > cpuPctMax) cpuPctMax = cpuPct
            ramPssSum += memInfo.totalPss
            if (memInfo.totalPss > ramPssMax) ramPssMax = memInfo.totalPss
            lastGpsUpdates = gpsUpdates
            lastSteps = steps

            val row = listOf(
                System.currentTimeMillis(),
                String.format(Locale.US, "%.1f", (nowElapsed - startElapsedMs) / 1000.0),
                cpuNow,
                String.format(Locale.US, "%.1f", cpuPct),
                memInfo.totalPss,
                memInfo.nativePss,
                memInfo.dalvikPss,
                memInfo.otherPss,
                batteryPct,
                gpsUpdates,
                steps,
                if (screenOn) 1 else 0,
            ).joinToString(",")
            writer?.write(row)
            writer?.newLine()
            writer?.flush()
        } catch (e: Exception) {
            Timber.w(e, "PerfLogger: fallo al escribir una muestra, se ignora")
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        val w = writer ?: return
        try {
            val durationS = (SystemClock.elapsedRealtime() - startElapsedMs) / 1000.0
            val avgCpu = if (sampleCount > 0) cpuPctSum / sampleCount else 0.0
            val avgRam = if (sampleCount > 0) ramPssSum / sampleCount else 0L
            w.newLine()
            w.write("# summary_duration_s,${String.format(Locale.US, "%.1f", durationS)}")
            w.newLine()
            w.write("# summary_cpu_pct_avg,${String.format(Locale.US, "%.1f", avgCpu)}")
            w.newLine()
            w.write("# summary_cpu_pct_max,${String.format(Locale.US, "%.1f", cpuPctMax)}")
            w.newLine()
            w.write("# summary_ram_pss_kb_avg,$avgRam")
            w.newLine()
            w.write("# summary_ram_pss_kb_max,$ramPssMax")
            w.newLine()
            w.write("# summary_gps_updates_total,$lastGpsUpdates")
            w.newLine()
            w.write("# summary_steps_total,$lastSteps")
            w.newLine()
            Timber.d("PerfLogger: CSV cerrado en ${file?.absolutePath}")
        } catch (e: Exception) {
            Timber.w(e, "PerfLogger: fallo al escribir el resumen final")
        } finally {
            try { w.close() } catch (e: Exception) { Timber.w(e, "PerfLogger: fallo al cerrar el CSV") }
            writer = null
            file = null
        }
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 5_000L
        private const val HEADER =
            "timestamp_ms,elapsed_s,cpu_time_ms,cpu_pct,ram_total_pss_kb,ram_native_pss_kb," +
                "ram_dalvik_pss_kb,ram_other_pss_kb,battery_pct,gps_updates_total,steps_total,screen_on"
    }
}
