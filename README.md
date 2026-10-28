# LSG-Routes

Sensor de rutas físicas para la plataforma LifeSync-Games. Una persona recorre una ruta a pie,
trotando o en bicicleta con el teléfono; el sistema valida que el movimiento sea físicamente
plausible y acredita puntos LSG en LSG-Core.

Desarrollado como parte de una memoria de titulo de ingeniería civil informática en DIINF, USACH.

## Contenido

- [`lsg-routes-backend/`](lsg-routes-backend/README.md): API en FastAPI + PostgreSQL: validación anti-trampa, puntos y sincronización con LSG
- [`lsg-routes-app/`](lsg-routes-app/README.md): App Android (Kotlin + Jetpack Compose): captura de GPS, acelerómetro y pasos, offline-first
- [`docs/`](docs/): Documentación técnica

## Por donde empezar

- **Para poner en marcha cada proyecto:** el README de su carpeta.
- **Para entender por que el sistema es como es:** [`docs/DOCUMENTACION-LSG-ROUTES.md`](docs/DOCUMENTACION-LSG-ROUTES.md),
  la memoria técnica contada prototipo por prototipo.

## Ejecución con Docker

El backend (API + PostgreSQL) corre con Docker Compose. La app Android no se ejecuta en Docker:
se compila como APK y se instala en el teléfono (ver «Compilar el APK» más abajo).

### Requisitos previos

- **Docker Engine 24+** con el plugin **Docker Compose v2** (`docker compose`, no `docker-compose`).
  En Windows y macOS basta con Docker Desktop.
- **Puertos libres**: `14141` (API) y `5432` (PostgreSQL, publicado solo en `127.0.0.1`) en el host.
- **Acceso a internet** desde el contenedor: el backend llama a LSG-Auth y LSG-Core
  (`lsg.diinf.usach.cl`), a OpenRouteService y a Open-Meteo.
- **Credenciales externas**:
  - Una cuenta LSG con rol admin, researcher o developer (cuenta de servicio).
  - Una API key de [OpenRouteService](https://openrouteservice.org/), para las rutas A→B.

### Variables de entorno

La configuración se lee de `lsg-routes-backend/.env`, que se crea a partir de la plantilla:

```bash
cd lsg-routes-backend
cp .env.example .env
```

Variables a revisar en `.env`:

- **`POSTGRES_PASSWORD`** (obligatoria): contraseña del usuario `lsg` de PostgreSQL. Docker
  Compose no arranca sin ella. Generar una aleatoria, por ejemplo con `openssl rand -hex 24`.
- **`LSG_SERVICE_EMAIL` / `LSG_SERVICE_PASSWORD`**: cuenta de servicio LSG. Sin ella, el ajuste
  de puntos y el alta del sensor por jugador solo funcionan si el propio usuario tiene uno de
  esos roles.
- **`ORS_API_KEY`**: clave de OpenRouteService. Sin ella no se pueden planificar rutas A→B
  (el recorrido libre sí funciona).
- **`LSG_ROUTES_ENDPOINT_IDS`**: un `sensor_endpoint` por modo de actividad, por ejemplo
  `{"walking": 12, "jogging": 13, "hiking": 14}`. Se obtiene con
  `scripts/provision_endpoints.py` (ver [README del backend](lsg-routes-backend/README.md#alta-del-sensor-en-lsg-core)).
- **`LSG_AUTH_BASE_URL` / `LSG_CORE_BASE_URL`**: por defecto apuntan a `lsg.diinf.usach.cl`;
  cambiarlas solo para otra instancia de LSG.
- **`LSG_ROUTES_SENSOR_ID`, `LSG_ROUTES_ATTRIBUTE_ID`**: sensor y atributo de puntos en LSG-Core
  (por defecto `6` y `2`, Físico).
- **`POINTS_PER_KM_*`, `POINTS_FREE_ROAM_MULTIPLIER`, `ADHERENCE_CORRIDOR_M`**: factores de
  puntos y corredor de adherencia; los valores de la plantilla son los de la tesis.
- **`BIKE_ACTIVATE`**: `false` por defecto (no hubo oportunidad de probarla); habilita la bicicleta como actividad.
- **`APP_LATEST_VERSION`, `APP_DOWNLOAD_URL`, `APP_SHA256`, `APP_MINIMUM_VERSION`**: metadatos
  del APK para la actualización por sideload que consulta la app.
- **`DEBUG`**: `false` en producción.

`DATABASE_URL` no hace falta tocarla: el `docker-compose.yml` la sobrescribe para apuntar al
servicio `postgres` con `POSTGRES_PASSWORD`. PostgreSQL solo escucha en `127.0.0.1:5432`, así
que no queda accesible desde fuera del host aunque el router reenvíe ese puerto.

`POSTGRES_PASSWORD` solo se aplica al crear el volumen. Para cambiarla en una base que ya
existe, primero cambiarla dentro de PostgreSQL y después en `.env`:

```bash
docker compose exec postgres psql -U lsg -d lsg_routes -c "ALTER USER lsg PASSWORD '<nueva>';"
# editar POSTGRES_PASSWORD en .env y recrear los contenedores
docker compose up -d --force-recreate
```

### Despliegue con Docker Compose

Primer despliegue, con base de datos nueva:

```bash
cd lsg-routes-backend

# 1. Levantar solo PostgreSQL y esperar a que quede healthy
docker compose up -d postgres

# 2. Crear el esquema con las migraciones de Alembic
docker compose run --rm backend uv run alembic upgrade head

# 3. Construir y levantar el backend
docker compose up -d --build
```

El paso 2 deja registrada la tabla `alembic_version`. Si se omite, el backend crea las tablas
con `create_all` al arrancar, pero las migraciones futuras fallarán hasta ejecutar
`alembic stamp head` (ver [Base de datos y migraciones](lsg-routes-backend/README.md#base-de-datos-y-migraciones)).

Comprobar que responde:

- Salud: http://localhost:14141/lsg-routes/health
- Documentación interactiva (Swagger): http://localhost:14141/lsg-routes/docs

Operación habitual:

```bash
docker compose logs -f backend                                  # ver logs
git pull && docker compose up -d --build                        # actualizar el código
docker compose exec backend uv run alembic upgrade head         # aplicar migraciones nuevas
docker compose down                                             # detener (conserva los datos)
docker compose down -v                                          # detener y BORRAR la base de datos
```

### Compilar el APK

La app se compila fuera de Docker. Requisitos:

- **JDK 17**.
- **Android SDK** con la plataforma 35 (`compileSdk = 35`). Android Studio lo instala solo.
- **Gradle 8.11.1**: el repositorio no versiona `gradle-wrapper.jar` ni el script `gradlew`.
  Opciones: abrir `lsg-routes-app/` en Android Studio, que regenera el wrapper, o instalar
  Gradle 8.11.1 y ejecutar `gradle wrapper --gradle-version 8.11.1` una vez.

Configuración previa:

1. **`local.properties`** en `lsg-routes-app/` (no se versiona), con la ruta del SDK, la URL
   del backend y la clave de MapTiler. Sin la clave el APK compila, pero el mapa no carga su
   estilo:

   ```properties
   sdk.dir=C\:\\Users\\<usuario>\\AppData\\Local\\Android\\Sdk
   BASE_URL=http://<host>:14141/
   MAPTILER_API_KEY=<clave>
   ```

2. **URL del backend** (`BASE_URL`): vale para debug y release. En CI se pasa con
   `-PBASE_URL=...`. Valores según dónde corre el backend:
   - Emulador (valor por defecto si no se define): `http://10.0.2.2:14141/`
   - Teléfono en la misma red: `http://<IP-del-PC>:14141/`
   - Servidor desplegado: `http://<host-del-servidor>:14141/`

3. **`versionCode`**: subirlo en `app/build.gradle.kts` antes de distribuir un APK nuevo; si no,
   Android rechaza la actualización.

APK de depuración (firmado con la clave debug, se instala directo):

```bash
cd lsg-routes-app
.\gradlew.bat assembleDebug          # Windows
./gradlew assembleDebug              # Linux/macOS
# Salida: app/build/outputs/apk/debug/app-debug.apk

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK de release (con minificación). `build.gradle.kts` aún no define `signingConfigs`, así que
Gradle genera un APK sin firmar y la firma se hace a mano con `apksigner` (incluido en las
build-tools del SDK). El keystore se genera con los pasos de `release.keystore.example.properties`:

```bash
./gradlew assembleRelease
# Salida: app/build/outputs/apk/release/app-release-unsigned.apk

apksigner sign --ks lsg-routes-release.jks --ks-key-alias lsg-routes \
  --out app-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify app-release.apk
```

Todas las versiones distribuidas deben firmarse con el mismo keystore; si cambia, los teléfonos
no aceptan la actualización. Para publicarla por sideload, subir `app-release.apk`, calcular su
SHA-256 (`sha256sum app-release.apk`) y cargar `APP_LATEST_VERSION`, `APP_DOWNLOAD_URL` y
`APP_SHA256` en el `.env` del backend.

El CI ([`.github/workflows/app.yml`](.github/workflows/app.yml)) compila el APK debug en cada push
a `main` o `dev` y lo deja como artefacto `lsg-routes-debug-apk` en la ejecución del workflow.

Más detalle en el README de cada carpeta.

## Regla de oro

`lsg-routes-app/app/src/main/kotlin/.../domain/model/GpsTrack.kt` y `SpeedBands.kt` son espejos
de `lsg-routes-backend/app/validators/common.py` y `app/services/free_roam.py`. Si cambia una
constante o un criterio en el backend, cambiarla en la app en el mismo commit.
