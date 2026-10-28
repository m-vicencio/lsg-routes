# Logger interno de rendimiento

Componente de instrumentación para las pruebas de rendimiento de campo. Registra el
costo (CPU, RAM, batería) de `TrackingService` durante un recorrido real, sin depender de
herramientas externas (Android Studio Profiler, `adb shell top`, etc.) que no están disponibles
en campo con la pantalla bloqueada.

## Dónde vive

- [`PerfLogger.kt`](app/src/main/kotlin/cl/usach/diinf/lsg/routes/feature/session/service/PerfLogger.kt):
  clase independiente, sin Hilt ni Room. Sabe muestrear y escribir CSV; no conoce nada del
  dominio de rutas.
- [`TrackingService.kt`](app/src/main/kotlin/cl/usach/diinf/lsg/routes/feature/session/service/TrackingService.kt):
  la instancia (`by lazy`), la arranca y la detiene (`perfLogger.start(...)` /
  `perfLogger.stop()`) y le pasa dos contadores atómicos (`gpsUpdateCount`, `stepTotal`).

## Ciclo de vida

- **Arranque:** `perfLogger.start(...)` se llama en `onStartCommand` cuando llega
  `ACTION_START`, es decir, al iniciar cualquier recorrido (libre o planificado), el mismo punto
  donde arrancan GPS y sensores.
- **Parada:** una sola llamada a `perfLogger.stop()` en `onDestroy()`. Como todas las salidas
  del servicio (STOP manual, auto-completado al llegar al destino, corte por exceso de
  velocidad) terminan en `stopSelf()` → `onDestroy()`, ese único punto cubre los tres casos sin
  duplicar lógica de cierre.
- El logger corre en su propio `CoroutineScope(Dispatchers.IO + SupervisorJob())`, separado del
  `serviceScope` de tracking: si el logger fallara, no arrastra al resto del servicio.
- Sigue funcionando con la pantalla bloqueada porque `TrackingService` ya sostiene un
  `PARTIAL_WAKE_LOCK` mientras la sesión está activa; el logger no necesita nada adicional.

## Muestreo (cada 5 s)

Cada muestra ejecuta un solo `try/catch`: si la escritura a disco falla (almacenamiento lleno,
error de E/S), se registra un `Timber.w` y se descarta la muestra: el seguimiento GPS/sensores
nunca se ve afectado, porque el logger no comparte estado mutable con esa lógica salvo lectura
de dos contadores.

- `timestamp_ms`: `System.currentTimeMillis()`
- `elapsed_s`: `SystemClock.elapsedRealtime()` desde el inicio del logger
- `cpu_time_ms`: `Process.getElapsedCpuTime()` (acumulado del proceso)
- `cpu_pct`: ver [cálculo de CPU](#cálculo-del-cpu_pct)
- `ram_total_pss_kb` / `ram_native_pss_kb` / `ram_dalvik_pss_kb` / `ram_other_pss_kb`: `Debug.getMemoryInfo(memInfo)` → `Debug.MemoryInfo.getTotalPss()` y campos `nativePss`/`dalvikPss`/`otherPss`
- `battery_pct`: `BatteryManager.BATTERY_PROPERTY_CAPACITY`
- `gps_updates_total`: contador acumulado de fixes GPS usables recibidos (incrementado en `locationCallback`)
- `steps_total`: contador acumulado de pasos (incrementado en `handleSteps`)
- `screen_on`: `PowerManager.isInteractive` (1/0)

No se toma más de una muestra cada 5 s (impuesto por `delay(5_000L)` dentro del propio loop del
logger), para no distorsionar la medición que se quiere hacer.

## Resumen final

Al llamar `stop()`, se agregan al mismo CSV líneas `# summary_*` (prefijo `#` para que sigan
siendo ignorables al leer el archivo como datos tabulares en pandas/Excel):

```
# summary_duration_s,912.3
# summary_cpu_pct_avg,4.2
# summary_cpu_pct_max,18.7
# summary_ram_pss_kb_avg,118432
# summary_ram_pss_kb_max,134210
# summary_gps_updates_total,182
# summary_steps_total,1340
```

## Dónde quedan los CSV y cómo extraerlos

`context.getExternalFilesDir(null)/perf/` → en el dispositivo:

```
/sdcard/Android/data/cl.usach.diinf.lsg.routes/files/perf/
perf_<yyyyMMdd_HHmmss>_<planificada|libre>_<sessionId>.csv
```

Es almacenamiento propio de la app (no requiere `WRITE_EXTERNAL_STORAGE` ni ningún permiso
runtime, y se borra solo al desinstalar). Desde Android 11 esa carpeta **no aparece** en apps de
archivos normales, así que hay que extraerla por uno de estos dos caminos:

```bash
adb pull /sdcard/Android/data/cl.usach.diinf.lsg.routes/files/perf/ .
```

o desde Android Studio: **View → Tool Windows → Device Explorer** → navegar a la misma ruta →
clic derecho → *Save As*.

## Cálculo del `cpu_pct`

```
cpu_pct = (cpuTimeAhora − cpuTimeMuestraAnterior) / (tiempoRealAhora − tiempoRealMuestraAnterior) × 100
```

donde `cpuTime` viene de `Process.getElapsedCpuTime()` y `tiempoReal` de
`SystemClock.elapsedRealtime()`, ambos en milisegundos. Es la fórmula estándar de utilización de
CPU por proceso (tiempo de CPU consumido / tiempo real transcurrido), la misma que usan `top` y
`ps` en Linux a partir de los campos `utime`+`stime` de `/proc/[pid]/stat`. Android corre sobre
un kernel Linux y `Process.getElapsedCpuTime()` expone justamente esa cifra acumulada para el
proceso actual.

Al ser una suma sobre todos los hilos del proceso, en un dispositivo multi-core el valor puede
superar 100 % si hay trabajo repartido en más de un núcleo en la ventana de 5 s (es el
comportamiento esperado de esta fórmula, no un error. No se normaliza por número de núcleos
porque no hace falta para comparar "antes vs. después" de una optimización en el mismo
dispositivo).

**Fuentes:**
- Android Developers: [`Process.getElapsedCpuTime()`](https://developer.android.com/reference/kotlin/android/os/Process#getElapsedCpuTime()), API reference.
- Ubuntu Manpages: [`proc_pid_stat(5)`](https://manpages.ubuntu.com/manpages/questing/man5/proc_pid_stat.5.html), definición de los campos `utime`/`stime` en los que se basa la contabilidad de CPU por proceso en Linux.
- Baeldung on Linux: [Calculate the Total CPU Usage of a Process From /proc/pid/stat](https://www.baeldung.com/linux/total-process-cpu-usage), derivación de la fórmula Δcpu/Δtiempo real usada por `top`/`ps`.
