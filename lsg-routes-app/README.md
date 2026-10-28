# lsg-routes-app

Aplicacion Android del sensor LSG Routes para la plataforma LifeSync-Games.
Desarrollada en Kotlin + Jetpack Compose + Material3 como parte de una tesis de ingenieria civil informatica en DIINF, USACH.

---

## Requisitos de construccion

| Herramienta    | Version minima       |
| -------------- | -------------------- |
| Android Studio | Meerkat (2024.3)     |
| JDK            | 17                   |
| Gradle         | 8.11.1 (via wrapper) |
| Min SDK        | 26 (Android 8.0)     |
| Target SDK     | 35 (Android 15)      |

---

## Primer uso: generar el Gradle wrapper

El repositorio no incluye `gradle-wrapper.jar` ni el script `gradlew` de Unix (solo
`gradlew.bat` y `gradle-wrapper.properties`). Ejecutar una vez:

```bash
# Con Gradle instalado globalmente:
gradle wrapper --gradle-version 8.11.1

# O dejar que Android Studio lo genere al abrir el proyecto.
```

`local.properties` (con `sdk.dir`) lo genera Android Studio y esta en `.gitignore`. Ahi va
tambien `BASE_URL` y la clave de MapTiler (ver «Configuracion del backend»).

---

## Construccion debug

```bash
.\gradlew.bat assembleDebug          # Windows
./gradlew assembleDebug              # Linux/Mac
# APK en: app/build/outputs/apk/debug/app-debug.apk
```

---

## Tests

```bash
.\gradlew.bat test                   # Unit tests (JVM, no requiere dispositivo)
.\gradlew.bat connectedAndroidTest   # Instrumentation tests (requiere emulador)
```

**El CI solo corre `test`.** `E2ETrackingTest` (esquema Room + maquina de estados de
la sesion) es instrumentado: necesita emulador y hay que ejecutarlo a mano antes de
publicar un APK. Si alguna vez se rompe el esquema de Room, el CI no se va a enterar.

Sin cobertura automatica: `TrackingService`, `UploadWorker`, `ActiveRouteViewModel`,
`AppUpdateChecker` y las pantallas Compose. Esa logica se prueba solo en el telefono.

---

## Configuracion del backend

`BASE_URL` (igual en debug y release) se lee de `local.properties`, que no se versiona, o de
`-PBASE_URL=...` en CI. Sin ninguno de los dos, apunta al backend local visto desde el emulador:

```
# local.properties
BASE_URL=http://<host>:14141/
```

- **Emulador** (valor por defecto): `http://10.0.2.2:14141/`
- **Dispositivo en LAN**: `http://<IP-PC>:14141/`
- **Servidor desplegado**: `http://<host-del-servidor>:14141/`

El manifiesto declara `usesCleartextTraffic="true"`, necesario mientras el backend se sirva por HTTP.

**Mapa.** MapLibre con el estilo hibrido de MapTiler. La clave se lee de `local.properties`
(ignorado por git) y llega a `MapLibreMapView.kt` como `BuildConfig.MAPTILER_API_KEY`:

```
# local.properties
MAPTILER_API_KEY=<clave>
```

Sin la clave el build compila, pero el mapa no carga su estilo. En CI se puede pasar con
`-PMAPTILER_API_KEY=...`.

**Versionado.** `versionCode` esta fijo en 1 en `app/build.gradle.kts`: hay que subirlo a mano en
cada APK distribuido, o Android rechaza la actualizacion con «aplicacion no instalada».

---

## Arquitectura (Clean Architecture)

```
feature/          # Composables y ViewModels por funcionalidad
  auth/           # Login
  onboarding/     # Landing de permisos (ubicacion, actividad fisica, notificaciones)
  routes/         # Listado, nueva ruta, detalle, mapa MapLibre, clima
  explore/        # Feed comunitario y previsualizacion de rutas ajenas
  session/        # Seguimiento activo, resumen, replay, actividades pendientes
    service/      #   TrackingService (foreground) y PerfLogger
    worker/       #   UploadWorker (WorkManager)
  profile/        # Historial, saldo LSG, detalle de actividad
  update/         # Dialogo de actualizacion sideload
core/
  network/        # OkHttp, interceptores JWT
  auth/           # JwtStorage (EncryptedSharedPreferences), TokenRefresher
  config/         # FeatureFlags (banderas que decide el backend)
  permissions/    # Catalogo de permisos runtime
  di/             # Modulos Hilt: NetworkModule, AppModule, DatabaseModule
  ui/theme/       # Material3 con color semilla #2E7D32
data/
  remote/         # Retrofit services y DTOs
  local/          # Room v2: 6 entidades, 3 DAOs
  repository/     # Implementaciones
domain/
  model/          # Route, User, SessionSummary, ActivityMode, GpsTrack, SpeedBands
  repository/     # Interfaces
navigation/       # NavHost, Screen, BottomNavBar
```

`GpsTrack.kt` y `SpeedBands.kt` son **espejos Kotlin** de `validators/common.py` y
`services/free_roam.py` del backend. Si cambia una constante o un criterio en el backend,
tiene que cambiar aqui. La recompensa efectiva la decide siempre el servidor.

---

## Flujo de la aplicacion

La barra inferior tiene tres destinos: **Rutas**, **Explorar** y **Perfil**. Si falta algun
permiso, la app arranca en un landing que los explica y los pide. Con un token guardado entra
directo a Rutas aunque no haya red.

```
Login ─┬─ Rutas ──── Nueva Ruta (Dibujar, mano alzada o A→B)
       │         ├── Detalle de Ruta (con clima)
       │         │      └── Ruta Activa (GPS + accel + pasos)
       │         │             └── Resumen de Sesion (puntos, adherencia, replay)
       │         ├── Recorrido Libre (misma pantalla activa, sin ruta)
       │         │      └── Resumen de Sesion (puntos, replay)
       │         └── Actividades pendientes (reintentar, reanudar o cerrar)
       │
       ├─ Explorar ── Previsualizacion (clima, quien la comparto, copiar)
       │
       └─ Perfil ──── Saldo LSG y transacciones
                 └── Historial → Detalle de Actividad (metricas + replay)
```

El seguimiento GPS corre como **Foreground Service** con notificacion persistente; tocarla
devuelve al recorrido en curso. La sincronizacion al backend usa **WorkManager** con restriccion
de red activa.

### Offline-first

Una sesion se graba por completo en el telefono: el UUID se genera en el dispositivo, la
telemetria va a Room y el backend recien conoce la sesion cuando `UploadWorker` la crea con ese
mismo id, sube los lotes y llama a `/finish` (o a `/abort`). Sin conexion el recorrido se
guarda y se sincroniza despues, aunque sea tras cerrar la app. La pantalla de resumen muestra
«pendiente» hasta que la sesion queda `SYNCED`.

Estados locales: `RECORDING`, `COMPLETED_PENDING_UPLOAD`, `UPLOADING`, `SYNCED`, `UPLOAD_FAILED`.
Las rutas ya vistas se guardan en Room para poder iniciarlas sin conexion.

Si el proceso muere con una grabacion abierta, la sesion queda `RECORDING` y aparece en
**Actividades pendientes**, desde donde se puede reanudar o cerrar.

### Cierre de una sesion

Una sesion se cierra por cuatro caminos, todos observables desde el mismo estado en Room:

- **Manual**: el usuario pulsa finalizar.
- **Llegada automatica**: el servicio detecta que se entro en un radio de 30 m del
  destino, tras haberse alejado antes de el (para no completar rutas en bucle al
  arrancar). Vive en el servicio para funcionar con la pantalla bloqueada.
- **Cancelacion por inactividad**: a los 2 minutos sin un desplazamiento neto mayor
  a 20 m, la sesion se aborta y se explica el motivo en pantalla. No aplica al
  recorrido libre ni a una sesion reanudada.
- **Corte por exceso de velocidad**: solo en recorrido libre. Sobre el techo de la banda mas
  alta (35 km/h con la bicicleta habilitada, 15 sin ella) sostenidos se avisa por
  notificacion al minuto y se cierra la sesion a los dos. Tambien vive en el servicio, porque
  el caso real ocurre con la pantalla apagada. No es un rechazo: la sesion se guarda con los
  puntos de los tramos validos.

### Recorrido libre

Registra actividad sin ruta planificada. Reutiliza `ActiveRouteScreen` sin el argumento
`routeId`, de modo que el ViewModel arranca en modo libre. La actividad (caminata, trote
o bicicleta) se deduce de la velocidad, y los puntos rinden la mitad que la ruta
equivalente: planificar sigue siendo lo que mas conviene. `domain/model/SpeedBands.kt`
espeja la clasificacion del backend para el estimado en pantalla.

### Bicicleta tras bandera

El backend decide si la bicicleta esta disponible (`bike_activate` en `GET /app/version`, la
unica llamada sin token al arrancar). `FeatureFlags` la guarda en `SharedPreferences`; con la
bandera apagada se esconde el modo y el techo de exceso baja a 15 km/h. El valor por defecto
es apagado, incluso sin red.

---

## Seguridad

- **JWT:** almacenado en `EncryptedSharedPreferences` (AES256-GCM). Junto al token se guardan,
  tambien cifrados, el correo y la contrasena para el autorrelleno del login.
- **Renovacion del token:** `TokenRefresher` lo renueva antes de caducar (menos de 15 min de
  vida); `AuthAuthenticator` queda como red de seguridad ante un 401.
- **Sin certificate pinning.** Conviene agregarlo al pasar a HTTPS, fijando la clave publica
  con un pin de respaldo.
- **Transporte:** el backend se sirve hoy por HTTP plano; el login y el JWT viajan sin cifrar.
- **Subida idempotente:** el `client_ref` de cada lote se deriva de la sesion y del rango de
  fixes, asi que un reintento tras una respuesta perdida recibe `DUPLICATE` y no duplica
  acelerometro ni pasos.
- **Play Integrity: no implementado.** Ni la app solicita el token ni el backend lo verifica.
- Ver `SECURITY.md` para el modelo de amenaza.

---

## Release firmado

Ver `release.keystore.example.properties` para instrucciones de generacion del keystore.
`build.gradle.kts` aun no define `signingConfigs`: hay que configurar la firma antes de usar
`assembleRelease`. Release activa minificacion y `proguard-rules.pro`.

---

## Instrumentacion de rendimiento

`PerfLogger` registra CPU, RAM y bateria cada 5 s durante cualquier recorrido y escribe un CSV
en el almacenamiento propio de la app. Ver `Logger-Interno.md`.

---

## Versiones del prototipo

Version actual: **0.5.0** (`versionName` en `app/build.gradle.kts`). El backend
declara `app_minimum_version` para exigir una version minima cuando un cambio de
contrato lo hace necesario. El detalle de cada iteracion esta en
`DOCUMENTACION-LSG-ROUTES.md`.

- **0.1** (1-3 jun 2026): Login, planificacion de rutas, seguimiento GPS en foreground service, Room, WorkManager, resumen, cert pinning
- **0.2** (8 jun 2026): Tema claro/oscuro, pantalla de Perfil con historial, rutas a mano alzada
- **0.3** (9 jun 2026): Explorar: feed comunitario con buscador, previsualizacion y copia
- **0.4** (30 jul 2026): Clima, replay del recorrido, llegada automatica, cancelacion por inactividad, saldo LSG y detalle de actividad
- **0.5** (2 ago 2026): Recorrido libre: sesion sin ruta, actividad detectada por velocidad, puntos estimados en vivo y corte por exceso de velocidad
- **0.5+** (4-18 ago 2026): Sin cambio de `versionName`: renovacion proactiva del token, eliminar ruta, limpieza GPS espejada del backend, bicicleta tras bandera, grabacion y sincronizacion offline, landing de permisos, suavizado del track, tirar para recargar, reanudar grabaciones abiertas
