# lsg-routes-backend

Backend del sensor LSG Routes para la plataforma LifeSync-Games.
Desarrollado como parte de una tesis de ingenieria civil informatica en DIINF, USACH.

**Stack:** Python 3.12, FastAPI, SQLAlchemy 2.0 async, PostgreSQL 16, Alembic, httpx, Shapely, APScheduler.

---

## Inicio rapido (Docker)

```bash
docker compose up --build
curl http://localhost:14141/lsg-routes/health
# {"status": "ok"}
```

Documentacion interactiva: http://localhost:14141/lsg-routes/docs

---

## Configuracion manual (venv + PostgreSQL local)

**Requisitos:** Python 3.12+, PostgreSQL 16+, [uv](https://docs.astral.sh/uv/)

```bash
uv sync --dev
cp .env.example .env          # editar con credenciales locales
psql -U postgres -c "CREATE USER lsg WITH PASSWORD 'lsg';"
psql -U postgres -c "CREATE DATABASE lsg_routes OWNER lsg;"
uv run uvicorn app.main:app --host 0.0.0.0 --port 14141 --reload
```

> **Windows / SSL:** usar `uv sync --native-tls` si hay errores de certificado.

---

## Base de datos y migraciones

Las migraciones de Alembic (`001` a `005`) cubren todas las tablas y columnas del modelo.
La `003` y la `004` comprueban lo que ya existe, asi que sirven tanto para una base nueva como
para una que se creo con `create_all` en versiones anteriores.

Ademas, al arrancar, la app ejecuta `create_all`, que crea las tablas que falten pero **no
agrega columnas** a tablas existentes. El `Dockerfile` y el `docker-compose.yml` no ejecutan
Alembic.

- **Base nueva**: `uv run alembic upgrade head` antes del primer arranque
- **Base creada solo por `create_all` (sin tabla `alembic_version`)**: `uv run alembic stamp head` una vez
- **Base existente con migraciones anteriores**: `docker compose exec backend uv run alembic upgrade head`
- **Agregar una columna**: Migracion nueva (`006`) y `upgrade head` en cada entorno

`alembic check` reporta diferencias heredadas de la `001` (claves foraneas que los modelos no
declaran, JSONB frente a JSON, restriccion unica frente a indice unico). Son inocuas; no generar
una migracion automatica a partir de ese reporte sin revisarla.

---

## Tests

```bash
uv run pytest            # 180 tests (sin PostgreSQL, sin servicios LSG)
uv run pytest -v -k e2e  # solo los tests E2E del ciclo de sesion
```

`Settings` lee `.env` tambien durante las pruebas: un `.env` con valores distintos de
`.env.example` (por ejemplo los factores `POINTS_PER_KM_*` o `LSG_ROUTES_ENDPOINT_IDS`) puede
hacer fallar pruebas que en CI pasan. CI no tiene `.env`.

---

## Alta del sensor en LSG-Core

El sensor emite un `sensor_endpoint` por modo de actividad, para que un consumidor
externo (el mod de Baldur's Gate 3) pueda clasificar la actividad por
`id_sensor_endpoint` sin parsear el payload. Hay que darlos de alta una sola vez,
con una cuenta de rol admin, researcher o developer:

```bash
uv run python scripts/provision_endpoints.py <email> <password>            # inspecciona
uv run python scripts/provision_endpoints.py <email> <password> --create   # crea
```

El script imprime la linea `LSG_ROUTES_ENDPOINT_IDS` lista para el `.env`.

> **Es irreversible.** LSG-Core no permite editar ni borrar un `sensor_endpoint`. Si un alta
> sale mal hay que crear otro y pedir al administrador de la plataforma que borre el viejo.
> Ejecutar el script dentro del contenedor (`docker cp` + `docker exec`): desde Windows
> `httpx` falla con `CERTIFICATE_VERIFY_FAILED` contra `lsg.diinf.usach.cl`.

---

## Variables de entorno

Ver `.env.example`. Las principales:

- `DATABASE_URL`: URL PostgreSQL async (el compose la sobrescribe)
- `LSG_SERVICE_EMAIL`: Cuenta de servicio LSG (rol admin, researcher o developer)
- `LSG_SERVICE_PASSWORD`: Contrasena de la cuenta de servicio
- `ORS_API_KEY`: Clave OpenRouteService (necesaria para rutas A→B)
- `LSG_ROUTES_SENSOR_ID`: ID del sensor LSG-Routes en LSG-Core
- `LSG_ROUTES_ENDPOINT_IDS`: Mapa JSON modo → `id_sensor_endpoint`
- `LSG_ROUTES_SENSOR_ENDPOINT_ID`: Endpoint por defecto para modos fuera del mapa (dejarlo sin definir hace fallar ruidoso a un modo sin mapear)
- `BIKE_ACTIVATE`: Habilita la bicicleta como actividad (default `false`)
- `LSG_ROUTES_ATTRIBUTE_ID`: Atributo de puntos: Social 1, Fisico 2, Afectivo 3, Mental 4
- `LSG_ROUTES_POINT_DIMENSION_ID`: [Deprecado en LSG-Core 1.2.3] Respaldo si el anterior es 0
- `POINTS_PER_KM_WALKING`, `_CYCLING`, `_JOGGING`, `_HIKING`: Puntos por km de cada modo (8, 4, 12, 10). La app los tiene fijos para su estimado en pantalla
- `POINTS_FREE_ROAM_MULTIPLIER`: Fraccion del factor por km que rinde un recorrido libre (0.5)
- `APP_LATEST_VERSION`, `APP_DOWNLOAD_URL`, `APP_SHA256`, `APP_MINIMUM_VERSION`: Metadatos del APK para sideload
- `DEV_ADJUST_BEFORE_INGEST`: Bandera de desarrollo que invierte el orden ingest/ajuste. Dejar en `false`

> **Cuenta de servicio.** Solo se usa donde LSG-Core exige un rol que el participante no
> tiene: el ajuste de puntos y el alta del sensor por jugador. El resto de llamadas (ingest
> webhook, lectura de sensores, ledger y saldo) viajan con el token del propio participante,
> para que el estudio longitudinal registre quien hizo cada ruta. Sin configurar,
> esas llamadas caen al token del usuario y solo funcionan si tiene un rol suficiente.

---

## Arquitectura

```
app/
  core/config.py           Pydantic Settings
  core/security.py         Dependencia get_current_user (valida con LSG-Auth /whoami)
  core/timeutil.py         Fechas aware, caducidad de JWT
  clients/
    lsg_auth.py            Login, whoami (cache 60s), refresh
    lsg_service_account.py Token de servicio (solo adjust y alta de sensor)
    lsg_core.py            Ingest webhook, adjust, ledger, saldo, sensores
    ors.py                 Rutas A→B via OpenRouteService
    weather.py             Open-Meteo con respaldo MET Norway
  validators/              5 validadores anti-cheat + adherencia + limpieza del track GPS
  services/
    points.py              Calculo de puntos por adherencia y modo
    free_roam.py           Recorrido libre: clasificacion por velocidad y puntos por tramo
    lsg_sync.py            Orquestador: ingest → adjust con idempotencia
    user_token.py          Ciclo de vida del token del participante
    retry_worker.py        APScheduler: reintentos con backoff exponencial
  models/                  10 tablas SQLAlchemy ORM
  api/routes/              6 grupos de endpoints
scripts/
  provision_endpoints.py   Alta de los sensor_endpoint por modo en LSG-Core
```

Cada metodo de `lsg_core.py` recibe su token explicitamente: quien llama decide si
va el del participante o el de la cuenta de servicio, y esa frontera se lee en el
punto de llamada.

**Limpieza del track.** Antes de validar y medir, `/finish` trabaja sobre una copia del track:
descarta los fixes con precision peor que 50 m, elimina outliers de un solo punto y mide la
distancia solo sobre tramos que superan el piso de ruido del GPS. Los fixes crudos nunca se
modifican (quedan para auditoria). La app replica esta logica en `domain/model/GpsTrack.kt`
para mostrar en pantalla el mismo numero que se acredita.

**Sesiones offline-first.** La app genera el UUID de la sesion en el telefono y la crea aqui
recien al sincronizar (`POST /sessions` adopta el `id` que envia). Por eso `started_at` y
`ended_at` se reescriben en `/finish` con la ventana real de los fixes.

---

## Endpoints

Todos exigen `Authorization: Bearer <jwt de LSG-Auth>` salvo `health` y `app/version`.

- `GET /lsg-routes/health`: Disponibilidad (sin token)
- `POST /lsg-routes/auth/login`: Login via LSG-Auth
- `POST /lsg-routes/auth/refresh`: Renovar el token sin re-autenticar
- `GET /lsg-routes/auth/me`: Perfil local del usuario
- `GET /lsg-routes/routes`: Listar rutas del usuario
- `POST /lsg-routes/routes`: Crear ruta (DRAWN, FREEHAND o ROUTED via ORS)
- `GET /lsg-routes/routes/{id}`: Ruta propia o publica
- `DELETE /lsg-routes/routes/{id}`: Borrado logico de una ruta propia
- `GET /lsg-routes/routes/public`: Feed comunitario de rutas publicas
- `PATCH /lsg-routes/routes/{id}/visibility`: Publicar o retirar una ruta
- `POST /lsg-routes/routes/{id}/copy`: Copiar una ruta publica (idempotente por origen)
- `GET /lsg-routes/routes/{id}/weather`: Clima actual en el punto de inicio
- `POST /lsg-routes/sessions`: Crear sesion (idempotente por client_session_uuid; acepta `id` del cliente, 409 si ya esta en uso; sin `route_id` = recorrido libre)
- `GET /lsg-routes/sessions`: Sesiones del usuario
- `GET /lsg-routes/sessions/{id}`: Detalle de una sesion
- `POST /lsg-routes/sessions/{id}/sync`: Subir lote GPS (HTTP 207; idempotente por client_ref y, en fixes, por client_seq; 409 si la sesion ya se cerro)
- `POST /lsg-routes/sessions/{id}/finish`: Validar, calcular puntos y sincronizar con LSG
- `POST /lsg-routes/sessions/{id}/abort`: Cancelar por falta de movimiento, sin validar
- `GET /lsg-routes/sessions/{id}/summary`: Resumen para el usuario
- `GET /lsg-routes/sessions/{id}/track`: Ruta planificada y track real limpio, para dibujarlos
- `GET /lsg-routes/activity/me`: Historial de actividad (limit opcional)
- `GET /lsg-routes/activity/points-balance`: Saldo global por dimension en LSG-Core
- `GET /lsg-routes/activity/transactions`: Historial de creditos del ledger LSG
- `GET /lsg-routes/app/version`: Version del APK y banderas (`bike_activate`), sin token
- `GET /lsg-routes/docs`: Swagger UI

Los errores llevan `detail: {"code": ..., "message": ...}`.

---

## Limitaciones conocidas

- La cadena anti-trampa del recorrido libre ejecuta solo 2 de los 5 validadores.
- Los buckets de acelerometro y los pasos no tienen clave propia: su idempotencia depende de que
  el cliente reenvie un lote con el mismo `client_ref`, como hace la app.
- No hay verificacion de integridad del dispositivo (Play Integrity).
- La version vive en `app/__init__.py` (`__version__`) y en `pyproject.toml`: cambiar ambas.

---

## Versiones del prototipo

El sensor se desarrollo de forma incremental. Cada version deja el sistema completo
y desplegable; el detalle esta en `DOCUMENTACION-LSG-ROUTES.md`.

- **0.1** (1-3 jun 2026): Nucleo funcional: auth, rutas, sesiones, anti-cheat, puntos, sync LSG
- **0.2** (8 jun 2026): Historial de actividad, rutas a mano alzada, perfil
- **0.3** (9 jun 2026): Comunidad: publicar, explorar y copiar rutas
- **0.4** (30 jul 2026): Clima, replay, cancelacion y llegada automatica, saldo LSG, frontera de tokens, endpoint por modo
- **0.5** (2 ago 2026): Recorrido libre: sesion sin ruta, clasificacion por velocidad, puntos a mitad de factor, limites de velocidad revisados
- **0.5+** (3-18 ago 2026): Sin numero de version: descarte de fixes imprecisos, bicicleta tras bandera, sesiones offline-first, aprovisionamiento con `watch_parameters`, duracion medida con la ventana real del track
