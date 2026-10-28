# LSG-Routes: Sensor de Rutas Físicas para la plataforma LifeSync-Games

**Documentación técnica de implementación - Memoria de título**
Mauricio Vicencio - Departamento de Ingeniería Informática (DIINF), Universidad de Santiago de Chile (USACH)
Plataforma LifeSync-Games

**Versión documentada:** prototipo 0.5 (2 de agosto de 2026), revisada contra el código el 14 de octubre de 2026. Donde el sistema cambió después del prototipo 0.5, el texto lo indica.

---

## 1. Introducción y contexto

LSG-Routes es uno de los sensores desarrollados para la plataforma **LifeSync-Games (LSG)**, un ecosistema de *gamification* de hábitos saludables desarrollado en el DIINF de la USACH como soporte para estudios longitudinales sobre conducta y salud. En la arquitectura de LSG, un *sensor* es un componente que observa una conducta del participante en el mundo real, la valida, y la traduce en puntos que se acreditan en el perfil global del jugador. La plataforma central (LSG-Core) administra los jugadores, las dimensiones de puntos y el *ledger* de transacciones; un servicio de autenticación independiente (LSG-Auth) emite los tokens JWT que toda la familia de aplicaciones comparte.

El sensor que documenta este capítulo mide **la realización efectiva de rutas físicas** (caminatas, trote, *hiking* y ciclismo) y otorga puntos LSG en función de la distancia recorrida y de la fidelidad con que el recorrido real reproduce una ruta previamente planificada. Admite además, con una recompensa deliberadamente menor, la actividad no planificada (capítulo 8). Su aporte distintivo respecto de un simple podómetro es la **validación anti-trampa**: antes de acreditar puntos, el sistema somete la telemetría capturada (GPS, acelerómetro y contador de pasos) a una cadena de validadores que detecta ubicaciones simuladas, teletransportaciones, incoherencias entre el movimiento reportado y el sensado, y desvíos respecto de la ruta planificada. Solo cuando la evidencia es físicamente plausible se calcula y acredita la recompensa.

El sensor se materializa en dos artefactos de software desarrollados como parte de esta memoria:

- **`lsg-routes-app`**: una aplicación Android nativa (Kotlin + Jetpack Compose) que permite planificar rutas, ejecutarlas capturando telemetría de sensores en segundo plano, y consultar el historial de actividad y los puntos obtenidos.
- **`lsg-routes-backend`**: un servicio web (Python + FastAPI) que persiste las rutas y la telemetría, ejecuta la cadena de validación anti-trampa, calcula los puntos y sincroniza el resultado con LSG-Core, actuando además como *proxy* de autenticación frente a LSG-Auth.

Ambos componentes son independientes pero se diseñaron como un único sistema cliente-servidor. La aplicación nunca confía en sus propios cálculos para otorgar puntos: su rol es **capturar evidencia**; la autoridad sobre la validación y la recompensa reside íntegramente en el *backend*, que a su vez delega la verdad sobre la identidad y el saldo de puntos en los servicios centrales de LSG. Esta separación de responsabilidades es deliberada y atraviesa todas las decisiones de diseño descritas a continuación.

Este documento se organiza en dos ejes. Los capítulos 1 y 2 presentan el sistema tal como quedó, para dar al lector un marco de referencia. Los capítulos 4 a 8 recorren su construcción **prototipo por prototipo**, mostrando qué se entregó en cada versión y qué limitación de una motivó la siguiente. Los capítulos 9 a 11 tratan aspectos transversales (calidad, despliegue y seguridad) que atraviesan todas las versiones.

---

## 2. Visión general de la arquitectura

El sistema se organiza en cuatro planos que se comunican exclusivamente mediante HTTP/JSON:

```
┌──────────────────────────┐
│   App Android (Kotlin)   │   Captura de sensores, UI, buffer local (Room)
│  lsg-routes-app          │
└───────────┬──────────────┘
            │  HTTP + JWT (Bearer)
            ▼
┌──────────────────────────┐
│  Backend LSG-Routes       │  Persistencia, validación anti-trampa,
│  (FastAPI + PostgreSQL)   │  cálculo de puntos, orquestación
└─────┬───────────────┬────┘
      │               │  token del participante (ingest, ledger, saldo)
      │               │  cuenta de servicio (solo adjust y alta de sensor)
      ▼               ▼
┌──────────┐    ┌──────────────┐
│ LSG-Auth │    │  LSG-Core     │   Jugadores, dimensiones de puntos,
│  (JWT)   │    │  (puntos)     │   ledger, registro de eventos de sensor
└──────────┘    └──────────────┘

   Servicios externos auxiliares: OpenRouteService (ruteo A→B),
   Open-Meteo / MET Norway (clima).
```

La **app** es responsable de la experiencia de usuario y, sobre todo, de la captura de evidencia: registra fijaciones GPS, agrega lecturas del acelerómetro en *buckets* de un segundo y cuenta pasos, almacenándolo todo primero en una base de datos local (Room) para tolerar la pérdida de conectividad. Periódicamente, y de forma idempotente, sube esa telemetría al *backend*.

El **backend** es el cerebro del sensor. No emite tokens propios: cada petición protegida se valida contra LSG-Auth. Persiste rutas y telemetría en PostgreSQL, y al finalizar una sesión ejecuta la cadena de validación, calcula los puntos y los sincroniza con LSG-Core. Toda comunicación con los servicios centrales, tanto para registrar el evento (con fines de auditoría) como para acreditar los puntos, pasa por el *backend*, nunca por la app; el registro del evento viaja a nombre del propio participante y solo la acreditación de puntos usa una cuenta de servicio, por exigirlo el rol.

Esta topología tiene una consecuencia central para la integridad del sensor: **el dispositivo del usuario es no confiable**. Cualquier valor que produzca la app (incluida la distancia o los pasos) se trata como evidencia a verificar, no como hecho. La decisión de otorgar puntos se toma exclusivamente en el servidor, sobre datos que han sido sometidos a la cadena anti-trampa.

### 2.1 El flujo de extremo a extremo

Reuniendo ambos componentes, una ejecución típica de ruta en la versión 0.5 transcurre así:

1. El usuario elige una ruta y pulsa iniciar. La app crea la sesión en el dispositivo, con un UUID generado ahí, y lanza el `TrackingService` sin esperar a la red. El *backend* recién conoce la sesión cuando el `UploadWorker` la crea con ese mismo UUID (`POST /sessions`, idempotente).
2. El servicio captura GPS, acelerómetro y pasos, escribiéndolos en Room. Periódicamente (o al finalizar) el `UploadWorker` sube la telemetría en lotes idempotentes (`POST /sessions/{id}/sync`).
3. Si el usuario no se mueve en ≈2 minutos, la app cancela la sesión; la cancelación (`POST /sessions/{id}/abort`) se encola igual que un cierre normal. Si llega al destino, el servicio auto-finaliza; el usuario también puede finalizar manualmente.
4. Cuando hay conexión, el `UploadWorker` crea la sesión en el *backend* si hacía falta, sube toda la telemetría y llama a `POST /sessions/{id}/finish`.
5. El *backend* filtra *outliers* GPS, ejecuta la cadena anti-trampa, calcula la adherencia y, si todo pasa, computa los puntos.
6. El *backend* registra el evento en LSG-Core a nombre del participante y acredita los puntos con la cuenta de servicio, con reintentos automáticos ante fallos transitorios.
7. La app muestra el resumen (puntos obtenidos, adherencia, recorrido planificado frente al real) y el perfil refleja el nuevo saldo.

Los pasos 3, 5 y 6 no existían en la primera versión del sensor; los capítulos siguientes explican cómo llegaron a existir. La creación diferida de la sesión de los pasos 1 y 4, que permite grabar sin conexión, es posterior al prototipo 0.5. El **recorrido libre** del capítulo 8 recorre esta misma secuencia sin el paso 1: no hay ruta que elegir, y la adherencia del paso 5 se sustituye por una clasificación de la actividad según la velocidad observada.

---

## 3. Metodología: desarrollo incremental por prototipos

El sensor no se construyó de una vez. Se desarrolló mediante **prototipado incremental**: cada iteración produce una versión ejecutable y desplegable del sistema completo (app más *backend*), se contrasta con el uso real, y las carencias observadas fijan el objetivo de la iteración siguiente. Esta estrategia se eligió por dos razones propias del contexto. Primero, porque el sensor depende de servicios externos (LSG-Auth, LSG-Core, OpenRouteService) cuyos contratos solo se validan de verdad al integrarse, de modo que conviene atravesar la cadena completa cuanto antes. Segundo, porque los validadores anti-trampa se calibran contra recorridos reales: ningún umbral de velocidad o de aceleración puede fijarse sensatamente sin haber caminado con el teléfono en el bolsillo.

El criterio de corte entre prototipos es funcional: cada versión agrupa un conjunto de cambios con un objetivo común y deja el sistema en un estado utilizable de extremo a extremo. Las cinco versiones quedaron registradas en el historial de los dos repositorios originales, que se conserva como respaldo fuera del repositorio unificado actual, y declaradas en el propio código, en el `versionName` de la app y en `app_minimum_version` del *backend*.

- **0.1 Núcleo funcional** (1–3 jun 2026): ¿Puede una ruta caminada convertirse en puntos LSG verificables?
- **0.2 Experiencia y perfil** (8 jun 2026): ¿Puede un participante usarlo a diario y ver su progreso?
- **0.3 Comunidad** (9 jun 2026): ¿Pueden los participantes compartir rutas entre sí?
- **0.4 Contexto, integridad y trazabilidad** (30 jul 2026): ¿Está listo para un estudio longitudinal real?
- **0.5 Recorrido libre** (2 ago 2026): ¿Puede registrarse actividad sin planificar, sin abandonar el incentivo a las rutas?

Los cinco capítulos siguientes siguen la misma plantilla: objetivo de la iteración, cambios en el *backend*, cambios en la aplicación, y un cierre de **alcance logrado y limitaciones** que motiva la versión siguiente. Cada subsistema se describe en extenso una única vez, en el prototipo donde nace; cuando una versión posterior lo modifica, se describe solo el cambio y su motivo, con referencia al capítulo original.

---

## 4. Prototipo 0.1 - Núcleo funcional

*1 al 3 de junio de 2026*

### 4.1 Objetivo de la iteración

Demostrar el recorrido vertical completo: que un usuario pueda autenticarse con su cuenta LSG, planificar una ruta, recorrerla mientras el teléfono captura telemetría, y que esa telemetría se valide en el servidor y termine convertida en puntos acreditados en LSG-Core. La prioridad no era la experiencia de uso sino **cerrar la cadena**, porque cada eslabón dependía de contratos externos que solo la integración real podía confirmar.

Esta versión es, con diferencia, la más voluminosa: entrega 38 módulos de *backend* y la app completa con su servicio de captura. Todo lo que describen los capítulos 5 a 8 son refinamientos sobre esta base.

### 4.2 Backend: tecnologías y organización del código

El *backend* está construido sobre **FastAPI** (Python ≥ 3.12) con un *stack* asíncrono de extremo a extremo: **SQLAlchemy 2.0** en modo `asyncio` sobre **PostgreSQL** (a través de `asyncpg`), **Pydantic v2** para la validación de esquemas, **httpx** para los clientes HTTP salientes, y **APScheduler** para el trabajo de reintentos en segundo plano. El cálculo geométrico de adherencia se apoya en **Shapely** y **pyproj**. La gestión del entorno y las dependencias se realiza con `uv`.

El código sigue una organización por responsabilidad que refleja la canónica de FastAPI. Tal como quedó en esta primera versión:

```
app/
├── main.py              # Punto de entrada, ciclo de vida (lifespan), montaje de routers
├── core/
│   ├── config.py        # Configuración (Pydantic Settings, .env)
│   ├── security.py      # Dependencia de autenticación (get_current_user)
│   └── logging.py       # Configuración de logging
├── db/session.py        # Motor async y fábrica de sesiones
├── models/              # Modelos ORM (tablas)
│   ├── route.py         # PlannedRoute
│   ├── session.py       # RouteSession, TrackFix, AccelSample, StepCount,
│   │                    #   IngestBatch, ValidationResult, LSGSyncRecord
│   └── user.py          # User (cache del perfil LSG)
├── schemas/             # Esquemas Pydantic (request/response)
├── api/routes/          # Endpoints HTTP por dominio
│   ├── auth.py          # /auth/login, /auth/refresh, /auth/me
│   ├── routes.py        # CRUD de rutas
│   ├── sessions.py      # Ciclo de vida de sesión, ingesta, finalización
│   ├── health.py        # Sondas de disponibilidad
│   └── app_meta.py      # Versión del APK (actualización por sideload)
├── validators/          # Cadena anti-trampa + adherencia
├── services/            # Puntos, sincronización LSG, worker de reintentos
└── clients/             # Clientes HTTP de servicios externos
    ├── lsg_auth.py      # LSG-Auth
    ├── lsg_core.py      # LSG-Core
    └── ors.py           # OpenRouteService
```

Todos los *endpoints* se montan bajo el prefijo `/lsg-routes`, de modo que el servicio puede coexistir tras un *reverse proxy* con los demás componentes de la plataforma. La documentación OpenAPI se sirve en `/lsg-routes/docs`.

El arranque de la aplicación (`lifespan` en `main.py`) crea de forma idempotente las tablas que aún no existan mediante `Base.metadata.create_all`, cubriendo la incorporación de tablas nuevas sin requerir una migración manual, y a continuación inicia el *scheduler* de reintentos de sincronización, que se detiene limpiamente al apagar el servicio.

### 4.3 Backend: modelo de datos

El esquema relacional separa tres preocupaciones: la planificación de rutas, la telemetría de cada ejecución, y el rastro de validación y sincronización con LSG.

**`PlannedRoute`** representa una ruta que el usuario ha planificado. Guarda su geometría como una lista de coordenadas `[[lat, lon], ...]` en una columna JSON, junto con el modo de actividad (`walking`, `cycling`, `jogging`, `hiking`), la distancia y duración planificadas, y el `origin` que indica cómo se creó: `ROUTED` (generada por OpenRouteService entre dos puntos) o `DRAWN` (trazada punto a punto sobre el mapa). El origen es relevante porque condiciona el rigor con que más tarde se exige adherencia. Un borrado lógico (`deleted_at`) preserva la integridad referencial de las sesiones históricas cuando el usuario elimina una ruta.

**`RouteSession`** es una ejecución concreta de una ruta. Su ciclo de estados (`status`) es el hilo conductor de todo el proceso: `STARTED` → `COMPLETED_PENDING_VALIDATION` → `VALIDATED` → `SYNCED_TO_LSG`, con ramas `REJECTED` (falló la validación o la adherencia) y `LSG_SYNC_FAILED` (la validación fue exitosa pero la sincronización con LSG-Core falló y debe reintentarse). Cada sesión almacena un `client_session_uuid` único generado por la app, que es la clave de idempotencia que permite reintentar la creación de sesión sin duplicarla.

La telemetría se reparte en tres tablas, cada una correspondiente a un sensor del teléfono:

- **`TrackFix`**: cada fijación GPS, con latitud, longitud, precisión, altitud, velocidad y rumbo reportados por el sistema, un *timestamp* de captura, un número de secuencia (`client_seq`) asignado por el cliente, y el campo crítico `is_from_mock_provider`, que indica si Android marcó esa ubicación como proveniente de un proveedor de ubicaciones simuladas.
- **`AccelSample`**: *buckets* de un segundo del acelerómetro, ya agregados en el cliente a una media y una varianza de la magnitud. No se almacenan las lecturas crudas (que serían cientos por segundo), sino el resumen estadístico suficiente para los validadores.
- **`StepCount`**: deltas de pasos del podómetro del dispositivo entre lecturas sucesivas.

**`IngestBatch`** registra cada lote de telemetría recibido, identificado por un `client_ref` único, garantizando que un reintento de subida por parte de la app no duplique los datos. Junto con la unicidad de `client_seq` por sesión, constituye la doble barrera de idempotencia de la ingesta. En la práctica, la primera barrera no operó hasta octubre de 2026, porque la app generaba un `client_ref` nuevo en cada reintento; desde entonces lo deriva de la sesión y del rango de fixes.

Finalmente, dos tablas registran el resultado del procesamiento: **`ValidationResult`** guarda, por sesión, el veredicto y el detalle de cada validador ejecutado, útil tanto para el resumen que ve el usuario como para la auditoría; y **`LSGSyncRecord`** rastrea el estado de la sincronización con LSG-Core, incluyendo los identificadores devueltos por los servicios centrales, el número de intentos y el último error.

### 4.4 Backend: autenticación como proxy de LSG-Auth

Una decisión arquitectónica deliberada es que **el backend de LSG-Routes no emite ni verifica tokens propios**. La autoridad sobre la identidad reside en LSG-Auth, el servicio de autenticación común de la plataforma. El *backend* actúa como un *proxy* delgado.

Cuando un usuario inicia sesión, el *endpoint* `POST /lsg-routes/auth/login` reenvía las credenciales a LSG-Auth (que las espera como `form-data` con campos `username` y `password`, no como JSON). Si LSG-Auth valida, devuelve un JWT y el perfil del jugador (`id_players`, `name`, `email`, `roles`). El *backend* registra o actualiza el usuario en su tabla local `users` (un mero *cache* del perfil, sin contraseña alguna) y devuelve el JWT de LSG-Auth tal cual al cliente. Ese mismo token es el que la app usará en el *header* `Authorization: Bearer <token>` de toda petición posterior.

La validación de cada petición protegida ocurre en la dependencia `get_current_user` (`core/security.py`), que extrae el *token* del *header* y lo valida llamando a `GET /whoami` de LSG-Auth. Para no saturar a LSG-Auth con una llamada por cada petición, el cliente mantiene un *cache* en memoria de 60 segundos indexado por el *hash* SHA-256 del *token*. Si LSG-Auth responde 401 o 403, el *backend* propaga un 401 que la app interpreta como token expirado; si LSG-Auth es inalcanzable, responde 503. Esta delegación implica que la revocación de un usuario en LSG-Auth surte efecto en LSG-Routes en a lo sumo 60 segundos, sin que el sensor tenga que conocer la clave de firma del JWT.

En esta primera versión, todas las llamadas salientes a LSG-Core viajan con el *token* de una **cuenta de servicio** gestionada dentro del propio cliente de LSG-Auth, que se re-autentica cuando le restan menos de cinco minutos de vigencia. Era la opción más simple para cerrar la cadena, y su revisión es uno de los cambios centrales del prototipo 0.4 (§7.8).

### 4.5 Backend: planificación de rutas

El módulo de rutas (`api/routes/routes.py`) ofrece el CRUD de rutas planificadas y la lógica de creación según el origen. Para rutas `ROUTED`, el *backend* llama a **OpenRouteService** (`clients/ors.py`), que recibe un punto de inicio y uno de destino y devuelve la geometría óptima, la distancia y la duración estimadas por el proveedor. El cliente traduce los modos de LSG a los perfiles de ORS (`walking` y `jogging` → `foot-walking`, `hiking` → `foot-hiking`, `cycling` → `cycling-regular`) y convierte el orden de coordenadas de ORS (`[lon, lat]`) al interno (`[lat, lon]`).

Las rutas `DRAWN` no pasan por ORS y, en esta versión, se guardan sin distancia ni duración planificadas. La consecuencia de esa carencia se explica en §4.14 y se corrige en §7.3.

### 4.6 Backend: el ciclo de vida de una sesión

El núcleo funcional del sensor está en `api/routes/sessions.py` y orquesta el viaje completo de una ejecución de ruta, desde su inicio hasta la acreditación de puntos.

**Inicio (`POST /sessions`).** En esta versión la app crea la sesión al iniciar el recorrido, enviando el `route_id` y un `client_session_uuid` (después del prototipo 0.5 la creación pasó a hacerse al sincronizar; ver §2.1). La operación es idempotente: si ya existe una sesión con ese UUID, se devuelve la existente, verificando que pertenezca al mismo jugador. Se valida que la ruta exista y no esté borrada, y se persiste la sesión en estado `STARTED`.

**Ingesta de telemetría (`POST /sessions/{id}/sync`).** Durante la ejecución, la app sube lotes de telemetría. Cada lote trae un `client_ref` que actúa como llave de idempotencia: si ese `client_ref` ya fue procesado, se responde `DUPLICATE` sin reinsertar nada. Dentro del lote, cada fijación GPS se compara con los `client_seq` ya almacenados para esa sesión, descartando los repetidos. Las muestras de acelerómetro y los conteos de pasos se insertan tal cual. La respuesta detalla cuántos elementos se almacenaron y cuántos se omitieron, y usa el código HTTP `207 Multi-Status` para reflejar el carácter potencialmente parcial de la operación. Este diseño permite que la app reintente subidas con total libertad ante cortes de red sin riesgo de corromper el conteo.

**Finalización (`POST /sessions/{id}/finish`).** Es el paso más complejo y el que concentra la lógica de validación. Si la sesión ya está en un estado terminal (`VALIDATED`, `REJECTED`, `SYNCED_TO_LSG`) se devuelve simplemente su resumen, garantizando idempotencia. Si quedó en `LSG_SYNC_FAILED`, se reintenta únicamente la sincronización con LSG-Core sin re-ejecutar los validadores. En el caso normal, el procesamiento es:

1. Se marca la sesión `COMPLETED_PENDING_VALIDATION` y se cargan de la base de datos las fijaciones GPS, las muestras de acelerómetro y los conteos de pasos.
2. Se ejecuta la **cadena de validación anti-trampa** en orden: `mock_location` → `velocity` → `acceleration` → `gps_accel_coherence` → `step_correlation`. Cada validador devuelve un veredicto y un detalle; se registra el primer fallo.
3. Se calcula la **adherencia** entre la ruta planificada y el recorrido real, siempre, incluso si algún validador falló, para que el reporte sea completo.
4. Se determina el rechazo: la sesión se rechaza si algún validador anti-trampa falló *o* si la adherencia quedó bajo el umbral mínimo.
5. Si la sesión pasa, se calculan los puntos (§4.8), se marca `VALIDATED` y se invoca la sincronización con LSG-Core (§4.9). El estado final de la sesión refleja el resultado de esa sincronización.

Cada `ValidationResult` (incluido el de adherencia, con su veredicto sobre el umbral de 0,30) se persiste para construir más tarde el resumen y para la auditoría.

### 4.7 Backend: la cadena de validación anti-trampa

La cadena consta de cinco validadores independientes más el cálculo de adherencia. Cada uno es una función pura que recibe la telemetría y devuelve `{"passed": bool, "details": dict}`. Se ejecutan en orden y el primer fallo determina el motivo del rechazo, aunque todos se evalúan y registran. El diseño modular permite añadir, quitar o re-calibrar validadores sin tocar el orquestador, y es lo que hará posible el ajuste del prototipo 0.4 descrito en §7.6.

**1. Detección de ubicación simulada (`mock_location`).** El más directo y, a la vez, la primera línea de defensa. Android marca cada fijación con `isFromMockProvider` cuando proviene de una app de *fake GPS*. Si cualquier fijación de la sesión está marcada, la sesión se rechaza de inmediato. Esto frustra la forma más simple y común de fraude: instalar una aplicación que finge desplazamientos.

**2. Plausibilidad de velocidad (`velocity`).** Calcula la velocidad de cada segmento (distancia Haversine sobre tiempo) y la contrasta con los límites del modo, definidos en `SPEED_BOUNDS`:

| Modo     | Velocidad sostenida máx. | *Spike* máx. |
| -------- | ------------------------ | ------------ |
| Caminata | 8 km/h                   | 12 km/h      |
| Trote    | 16 km/h                  | 22 km/h      |
| *Hiking* | 8 km/h                   | 14 km/h      |
| Ciclismo | 50 km/h                  | 70 km/h      |

Rechaza si la velocidad media supera el límite sostenido, o si más del 10 % de los segmentos superan el límite de *spike*. La distinción entre límite sostenido y límite de *spike* tolera aceleraciones momentáneas legítimas sin permitir un desplazamiento globalmente imposible.

**3. Aceleración instantánea (`acceleration`).** Detecta teletransportaciones examinando el cambio de velocidad entre segmentos consecutivos. En esta primera versión, **basta un solo segmento** con aceleración superior a 10 m/s² (muy por encima de lo que un humano produce) para rechazar la sesión. Ese criterio resultó ser demasiado estricto en la práctica; §7.6 explica por qué y cómo se corrigió.

**4. Coherencia GPS–acelerómetro (`gps_accel_coherence`).** Es el validador conceptualmente más interesante, porque cruza dos sensores físicamente independientes. Su premisa: si el GPS reporta movimiento, el acelerómetro debe registrar la vibración propia de caminar o correr. Calcula la velocidad media del GPS y la varianza media del acelerómetro; si el GPS indica movimiento (> 0,5 m/s) pero la varianza del acelerómetro es prácticamente nula (< 0,01, propia de un dispositivo inmóvil), concluye que el desplazamiento GPS no corresponde a un movimiento corporal real (por ejemplo, un teléfono quieto cuya ubicación es manipulada de forma que escape al chequeo de *mock provider*). Solo aplica a modos pedestres (caminata, trote, *hiking*), donde la firma de aceleración del cuerpo es característica; en ciclismo la vibración es demasiado variable para servir de señal.

**5. Correlación pasos–distancia (`step_correlation`).** Contrasta el conteo del podómetro con la distancia GPS. Estima los pasos esperados dividiendo la distancia por una longitud de zancada típica del modo (0,70 m caminando, 1,00 m trotando, 0,65 m en *hiking*) y rechaza si los pasos contados son menos del 50 % de los esperados. Solo aplica a modos pedestres, requiere datos de podómetro, y omite tracks demasiado cortos (< 50 m) donde el ruido domina. Detecta el caso de un desplazamiento real del dispositivo (en vehículo, por ejemplo) sin la cadencia de pasos que un humano caminando produciría.

Cada validador está deliberadamente diseñado para *no* rechazar cuando carece de datos suficientes (pocas fijaciones, sin muestras de acelerómetro, modo no aplicable): la filosofía es exigir evidencia de trampa para rechazar, no exigir evidencia de inocencia para aprobar, evitando así penalizar a usuarios honestos por limitaciones de sus dispositivos.

**Adherencia.** No es un validador binario sino un cálculo geométrico continuo (`validators/adherence.py`) que mide qué fracción de la ruta planificada cubrió el recorrido real. El método: ambas geometrías se proyectan de WGS-84 (EPSG:4326) a Web Mercator (EPSG:3857) para operar en metros; se construye un *corredor* expandiendo la ruta planificada con un *buffer* de 50 m; se intersecta el recorrido real con ese corredor; y la adherencia es la razón entre la longitud de la intersección y la longitud de la ruta planificada, acotada a [0, 1]. Se implementa con Shapely (operaciones geométricas) y pyproj (proyección). La elección de EPSG:3857 introduce una distorsión conocida en latitudes altas, pero para el área de operación esperada (Santiago, ≈ −33°) el error es inferior al 20 %, aceptable para un corredor de 50 m.

### 4.8 Backend: cálculo de puntos

Una sesión que supera la cadena de validación se traduce en puntos mediante `services/points.py`. El cálculo combina tres factores: la distancia planificada, la adherencia lograda y un factor de puntos por kilómetro propio de cada modo. El factor por modo recompensa el esfuerzo relativo: caminar (8 pts/km) rinde más por kilómetro que pedalear (4 pts/km), con el trote (12) y el *hiking* (10) en posiciones intermedias.

La distancia que efectivamente cuenta es la *distancia efectiva*: la distancia planificada multiplicada por la adherencia, de modo que completar solo la mitad de una ruta acredita la mitad de su distancia. Sobre esa base se aplica un sistema de niveles según la adherencia:

- **Adherencia ≥ 0,70 (nivel `FULL`):** se otorgan los puntos completos (`distancia_efectiva_km × factor`).
- **0,30 ≤ adherencia < 0,70 (nivel `PARTIAL`):** se otorga una fracción proporcional, escalada por `adherencia / 0,70`, penalizando suavemente los recorridos incompletos.
- **Adherencia < 0,30 (nivel `REJECTED`):** cero puntos; el recorrido no se parece lo suficiente a la ruta planificada.

El resultado del cálculo se devuelve junto a un *breakdown* detallado (nivel alcanzado, distancia efectiva, factor, adherencia) que viaja a LSG-Core como parte del registro de auditoría.

Conviene notar la dependencia que esto introduce: **el cálculo se basa en la distancia planificada, no en la recorrida**. Una ruta sin distancia planificada no puede otorgar puntos, lo que en esta versión afecta a todas las rutas `DRAWN`. El recorrido libre del prototipo 0.5 no puede usar este cálculo por carecer de ruta planificada, y sigue una vía propia descrita en §8.3; los factores por kilómetro definidos aquí son, sin embargo, los mismos que aquella reduce a la mitad.

### 4.9 Backend: sincronización con LSG-Core

La acreditación de puntos en la plataforma central (`services/lsg_sync.py`) es una operación de dos pasos contra LSG-Core, deliberadamente ordenada y protegida por idempotencia:

1. **Registro del evento de sensor (`POST /sensors/ingest/webhook`).** Primero se registra el evento en LSG-Core con todo el *payload* crudo de la ruta (modo, distancia, adherencia, resumen de validadores, *breakdown*) como registro de auditoría. Este paso requiere el `players_sensor_endpoint_id`, el identificador del enlace entre el jugador y el sensor; se obtiene de LSG-Core la primera vez y se *cachea* en la tabla `users`. Si este enlace no existe, no se acreditan puntos.
2. **Ajuste de puntos (`POST /players/{id}/points/adjust`).** Solo si hay puntos que otorgar, se acreditan al jugador en la dimensión configurada, con un `reason` que incluye el `session_uuid` para trazabilidad. Antes de acreditar, se consulta el *ledger* de LSG-Core buscando ese `session_uuid`: si ya figura, se omite el ajuste, evitando una doble acreditación ante reintentos.

El orden es *ingesta antes que ajuste*: la auditoría precede a la recompensa, porque un evento auditable sin puntos es preferible a puntos sin auditoría. Si el primer paso falla, no se intenta el segundo. Si el segundo falla tras un primero exitoso, la sesión queda en `LSG_SYNC_FAILED` y el `LSGSyncRecord` conserva el `sensor_event_id` ya obtenido y el detalle del error.

La recuperación ante fallos transitorios de LSG-Core la maneja un **worker de reintentos** (`services/retry_worker.py`) que APScheduler ejecuta cada 60 segundos. Busca los `LSGSyncRecord` en estado `LSG_SYNC_FAILED` con menos de seis intentos y los reintenta respetando un *backoff* exponencial (`2^(intentos−1) × 60 s`), de modo que las fallas persistentes se espacian progresivamente en lugar de martillar al servicio caído. Junto con la idempotencia basada en el *ledger*, este mecanismo garantiza que los puntos terminen acreditados exactamente una vez tan pronto como LSG-Core vuelva a estar disponible, sin intervención manual.

### 4.10 Aplicación: tecnologías y arquitectura

La app está escrita íntegramente en **Kotlin** con interfaz declarativa en **Jetpack Compose** y Material 3. Apunta a `minSdk 26` / `targetSdk 35`. Su columna vertebral tecnológica incluye **Hilt** (inyección de dependencias), **Retrofit + OkHttp + Gson** (red), **Room** (persistencia local), **WorkManager** (trabajo en segundo plano), **Coroutines + Flow** (asincronía y reactividad), **MapLibre** (mapas vectoriales), los **Play Services Location** (GPS fusionado) y **Timber** (*logging*).

El código sigue **Clean Architecture** en tres capas:

- **`data`** (implementación): servicios Retrofit, DTOs, entidades y DAOs de Room, e implementaciones de los repositorios.
- **`domain`** (contratos): modelos de dominio puros e interfaces de repositorio, sin dependencias de Android.
- **`feature`** (presentación): cada pantalla agrupa su `Screen` (Compose) y su `ViewModel`, organizados por característica funcional.

Esta separación permite que la lógica de negocio y los *ViewModels* se prueben con tests JVM puros, sin emulador, sustituyendo los repositorios por dobles. La migración a la organización por característica quedó a medias en esta versión: las pantallas de autenticación y de sesión ya viven bajo `feature/`, mientras que las de rutas permanecen en un paquete `ui/` plano que se reorganizará más adelante.

Las pantallas entregadas son **Login**, **RoutesList**, **NewRoute**, **RouteDetail**, **ActiveRoute** y **SessionSummary**: exactamente las necesarias para recorrer la cadena de principio a fin, sin perfil ni descubrimiento.

### 4.11 Aplicación: capa de red y seguridad

La configuración de red vive en `NetworkModule` (Hilt). Un `AuthInterceptor` de OkHttp inyecta automáticamente el JWT almacenado en el *header* `Authorization` de cada petición. Un `AuthAuthenticator` se activa ante respuestas 401: intenta refrescar el *token* llamando a `/auth/refresh`, reintenta la petición original con el nuevo *token* (marcándola para no entrar en bucle), y si el refresco falla, limpia el *token* y fuerza un nuevo *login*. Este mecanismo hace que la expiración del JWT sea transparente para el usuario.

El JWT y las credenciales recordadas se guardan cifrados con **EncryptedSharedPreferences** (AES-256) en `JwtStorage`, no en texto plano. Para las *builds* de *release* se configuró **certificate pinning** sobre el dominio de la plataforma, pero el pin quedó con un valor de relleno y apuntaba a un host al que la app no llegó a conectarse, de modo que nunca protegió nada; se retiró en octubre de 2026. El despliegue de la tesis sirve el *backend* por HTTP, así que el transporte no va cifrado (§11). La base de la URL del *backend* se inyecta como `BuildConfig.BASE_URL` en tiempo de compilación.

### 4.12 Aplicación: captura de telemetría con el TrackingService

El corazón de la app (y el componente más relevante para el sensor) es el **`TrackingService`** (`feature/session/service/`), un *foreground service* de Android. Su naturaleza de servicio en primer plano, declarado con `foregroundServiceType="location"` y una notificación persistente, es lo que le permite seguir capturando sensores **con la pantalla apagada y la app en segundo plano**, el escenario real de una persona caminando con el teléfono en el bolsillo.

Al iniciarse (acción `ACTION_START`), el servicio:

- **Adquiere un *wake lock* parcial** para mantener la CPU despierta y que el acelerómetro siga muestreando con la pantalla bloqueada. El *wake lock* tiene un *timeout* de seguridad de 4 horas como red contra fugas, y se libera al terminar la sesión.
- **Solicita actualizaciones de ubicación** al `FusedLocationProviderClient` con prioridad de alta precisión y un intervalo de un segundo. Cada fijación recibida se inserta en Room con su número de secuencia incremental.
- **Registra escuchas del acelerómetro y del podómetro**. Las lecturas del acelerómetro (decenas por segundo) se agregan en *buckets* de un segundo: el servicio acumula las magnitudes `√(x²+y²+z²)`, y al cerrar cada *bucket* calcula la media y la varianza, persistiendo solo ese resumen. El podómetro, que en Android reporta un acumulado monótono, se convierte en deltas calculando la diferencia con la lectura anterior.

En esta versión el servicio solo se detiene cuando el usuario pulsa finalizar; la detección automática de llegada al destino llega en el prototipo 0.4 (§7.4).

### 4.13 Aplicación: persistencia local y subida en segundo plano

La app **nunca envía telemetría directamente desde el sensor a la red**. Toda fijación, *bucket* de acelerómetro y conteo de pasos se escribe primero en una base de datos local **Room** (`LsgRoutesDatabase`, cinco entidades: `RouteSessionEntity`, `LocationFixEntity`, `AccelSampleEntity`, `StepCountEntity`, `UploadBatchEntity`). Este *buffer* local es la clave de la resiliencia del sensor: una persona puede recorrer una ruta en una zona sin cobertura, y la telemetría se acumula localmente para subirse cuando regrese la conectividad, sin pérdida de datos ni dependencia de una conexión continua.

La subida la realiza un **`UploadWorker`** de WorkManager, encolado con una restricción de red disponible. Cuando se ejecuta, marca la sesión local como `UPLOADING`, recupera las fijaciones aún no subidas y las sube en **lotes de 100**, generando un `client_ref` único por lote. Tras cada lote exitoso, marca como subidas las fijaciones correspondientes; ante un fallo, devuelve `Result.retry()` para que WorkManager reintente con su propia política de *backoff*. Cuando toda la telemetría está arriba, llama a `/sessions/{id}/finish` para disparar la validación en el *backend* y, según el resultado, marca la sesión local `SYNCED` o `UPLOAD_FAILED`.

La combinación de Room como *buffer*, WorkManager como ejecutor resiliente y la idempotencia del *backend* (por `client_ref` y `client_seq`) hace que la subida sea **segura ante cualquier interrupción**: cortes de red, cierre de la app o muerte del proceso por el sistema. WorkManager garantiza que el trabajo se reintente; el *backend* garantiza que los reintentos no dupliquen datos.

### 4.14 Alcance logrado y limitaciones

Al cierre de esta iteración el sensor funcionaba de extremo a extremo: era posible autenticarse, planificar una ruta A→B, recorrerla, y ver los puntos acreditados en LSG-Core. La cadena anti-trampa estaba completa y probada, y la resiliencia ante cortes de red quedó demostrada.

Las carencias observadas fueron de tres tipos, y fijaron el objetivo de la versión 0.2:

- **No había forma de ver el progreso propio.** Terminada una ruta, el resumen se mostraba una vez y desaparecía. El *backend* no guardaba ningún registro de actividad legible por el usuario: LSG-Core almacena puntos y motivo en su *ledger*, pero no distancia, duración ni velocidad media, de modo que el historial del participante simplemente no existía.
- **Las rutas dibujadas no otorgaban puntos.** Como el cálculo se basa en la distancia planificada (§4.8) y las rutas `DRAWN` se guardaban sin ella, recorrer una ruta trazada a mano producía cero puntos. En la práctica esto obligaba a usar siempre ORS, que exige conexión y un punto de destino concreto.
- **La interfaz era funcional pero áspera.** Sin tema oscuro, con las pantallas de rutas fuera de la organización por característica, y sin una pantalla de perfil que diera continuidad al uso diario.

---

## 5. Prototipo 0.2 - Experiencia y perfil

*8 de junio de 2026*

### 5.1 Objetivo de la iteración

Convertir un sistema que funcionaba en un sistema que se podía usar a diario. Tres frentes: dar al participante un lugar donde ver su progreso acumulado, permitir que las rutas trazadas a mano otorguen puntos, y pulir la interfaz hasta un estándar presentable.

### 5.2 Backend: registro de actividad e historial

La carencia de historial se resuelve con una tabla nueva, **`ActivityLog`**, que conserva el resumen de cada actividad finalizada: distancia real recorrida, duración, velocidad media, adherencia lograda, puntos otorgados y estado (`VALIDATED` o `REJECTED`). Se escribe dentro de `finish_session`, tanto en el camino de aceptación como en el de rechazo, de modo que el historial documenta también los intentos fallidos y su motivo.

La razón de que esta tabla viva en el *backend* de LSG-Routes y no se delegue a LSG-Core es que el *ledger* central solo almacena puntos y un texto de motivo: no tiene lugar donde guardar la distancia, la duración ni la velocidad media, que son precisamente las métricas que interesan al participante y al estudio.

Un detalle de diseño que conviene resaltar: el `ActivityLog` está indexado por `id_players`, **no por dispositivo**. Esto significa que el historial de actividad es transversal al dispositivo: el mismo jugador, autenticado en otro teléfono, recupera su historial completo. El único dato genuinamente local al dispositivo sigue siendo el *buffer* de telemetría en Room, que existe solo mientras la sesión se sube.

Sobre esa tabla se expone `GET /activity/me`, que devuelve los totales agregados del jugador (número de actividades, distancia y puntos acumulados) junto con sus últimas actividades.

### 5.3 Backend: rutas a mano alzada y umbrales propios

Se incorpora un tercer origen de ruta, **`FREEHAND`**: el usuario dibuja el trazado deslizando el dedo sobre el mapa, en lugar de marcar vértices uno a uno. Es la forma más natural de planificar un paseo por un parque o un circuito conocido, donde no existe un "destino" que ORS pueda rutear.

Ese origen trae consigo una consecuencia sobre la validación. Una ruta dibujada a pulso es intrínsecamente menos precisa que una calculada por ORS sobre la red vial: el dedo no sigue la acera. Exigirle la misma adherencia sería castigar al usuario por la imprecisión de la herramienta, no por la de su recorrido. Por eso las rutas `FREEHAND` reciben **umbrales más laxos**: adherencia plena desde 0,50 (en lugar de 0,70) y mínimo aceptable de 0,15 en lugar de 0,30. El resto del cálculo de puntos (§4.8) es idéntico.

### 5.4 Backend: la bandera de orden entre ingesta y ajuste

Aparece `dev_adjust_before_ingest`, una bandera de configuración que invierte el orden de los dos pasos de la sincronización descritos en §4.9. El orden de producción es siempre *ingesta antes que ajuste*; la bandera existe para escenarios de desarrollo en que interesa verificar la acreditación de puntos sin depender de que el alta del sensor en LSG-Core esté completa. Está documentada explícitamente como transitoria y su valor por defecto es el de producción, de modo que las pruebas automatizadas validan el orden correcto.

### 5.5 Aplicación: perfil y renovación de la interfaz

La app incorpora la pantalla de **Perfil**, que consume `GET /activity/me` y presenta los totales del jugador junto a sus actividades recientes, cerrando el ciclo de retroalimentación que faltaba: el participante ya puede ver cuánto lleva acumulado.

En paralelo se rehace la capa visual con **tema claro y oscuro** conmutable y una revisión completa de tipografía, color y espaciado sobre Material 3. Se corrigen además varios defectos del flujo de sesión detectados en el uso del prototipo anterior, y se completa la traducción de los literales de la interfaz.

### 5.6 Alcance logrado y limitaciones

El sensor pasó a ser utilizable de forma sostenida: el participante planifica como prefiera (ruteado, por vértices o a mano alzada), recorre, y ve su progreso acumulado en el perfil.

La limitación que motivó la versión siguiente fue de naturaleza distinta a las anteriores, y provino del contexto de la plataforma más que del sensor: **el sistema era estrictamente individual**. Cada participante planificaba sus rutas en solitario, sin manera de aprovechar el trabajo de otro ni de descubrir recorridos nuevos. En una plataforma cuyo propósito es sostener hábitos mediante *gamification*, la dimensión social es un incentivo desaprovechado.

---

## 6. Prototipo 0.3 - Comunidad

*9 de junio de 2026*

### 6.1 Objetivo de la iteración

Permitir que los participantes compartan rutas entre sí: que una ruta bien trazada por alguien sea aprovechable por el resto, y que descubrir recorridos nuevos no dependa de conocerlos de antemano.

### 6.2 Backend: visibilidad y feed comunitario

`PlannedRoute` recibe tres campos que dan soporte a la función: `is_public`, que gobierna la visibilidad; `copied_from_id`, que registra el origen cuando una ruta es copia de otra; y `shared_by_name`, que conserva el nombre de quien la compartió para atribuirla en la interfaz.

Sobre ellos se construyen los *endpoints* de comunidad. `PATCH /routes/{id}/visibility` permite al dueño publicar o retirar una ruta. `GET /routes/public` sirve el *feed*: devuelve las rutas publicadas por cualquier participante, cada una marcada con `is_mine` para que la app distinga las propias de las ajenas (de otro modo, un usuario vería sus propias rutas ofrecidas como si fueran descubrimientos). La consulta de una ruta individual se amplía para aceptar tanto las propias como cualquiera pública, condición necesaria para previsualizarla antes de adoptarla.

`POST /routes/{id}/copy` materializa la adopción: crea una ruta nueva en la cuenta de quien copia, con la misma geometría y modo, anotando en `copied_from_id` la ruta de origen. La operación es **idempotente respecto del origen**: si el usuario ya copió esa ruta, se devuelve la copia existente en lugar de crear una segunda. Sin esa precaución, pulsar dos veces el botón llenaría la lista de duplicados.

### 6.3 Aplicación: la pantalla Explorar

La app suma un tercer destino en la barra inferior, **Explorar**, con el *feed* de rutas públicas y un buscador para filtrarlas. Desde allí se accede a **RoutePreview**, que muestra la ruta ajena sobre el mapa con sus datos (distancia, duración, modo, quién la compartió) y ofrece copiarla a la cuenta propia.

Con esta iteración la app completa su estructura de navegación definitiva: **Rutas**, **Explorar** y **Perfil**.

### 6.4 Alcance logrado y limitaciones

El sensor quedó funcionalmente completo desde el punto de vista del participante: planificar en solitario o a partir de la comunidad, recorrer, validar y acumular puntos.

Las limitaciones que quedaron pendientes eran de otro orden, y son las que definieron la última iteración. Se agrupan en tres familias:

- **Falta de contexto y de cierre.** La previsualización mostraba el trazado pero nada sobre las condiciones para recorrerlo. Terminada una ruta, el resumen daba cifras pero no permitía *ver* el recorrido. Y el cierre de la sesión seguía siendo exclusivamente manual: quien llegaba a destino y guardaba el teléfono dejaba la sesión abierta indefinidamente.
- **Fragilidad frente al ruido GPS.** Las pruebas de campo acumularon un patrón preocupante: caminatas honestas rechazadas por el validador de aceleración. La causa resultó ser una única fijación ruidosa por reflexión de señal entre edificios, suficiente para disparar el criterio de rechazo al primer *spike* de §4.7.
- **Trazabilidad insuficiente para el estudio.** Todas las llamadas a LSG-Core seguían viajando con la cuenta de servicio de §4.4, de modo que en la plataforma central cada evento de sensor quedaba registrado a nombre de una cuenta compartida y no del participante que había caminado. Para un estudio longitudinal, esa atribución es precisamente el dato que importa.

---

## 7. Prototipo 0.4 - Contexto, integridad y trazabilidad

*30 de julio de 2026*

### 7.1 Objetivo de la iteración

Dejar el sensor en condiciones de sostener un estudio longitudinal real. Es la iteración con el alcance más amplio y también la más heterogénea: añade contexto a la experiencia (clima, replay del recorrido), cierra el ciclo de la sesión sin intervención del usuario, corrige la fragilidad del anti-trampa frente al ruido, y rehace la frontera de identidad con la plataforma central.

### 7.2 Clima en el punto de inicio

`GET /routes/{id}/weather` devuelve las condiciones actuales en el primer punto de la geometría, para rutas propias o públicas. Alimenta una tarjeta que aparece tanto en el detalle de una ruta propia como en la previsualización de una ajena, respondiendo a una pregunta obvia antes de salir a caminar.

La implementación (`clients/weather.py`) tiene una particularidad que merece registro porque nació de un problema real de infraestructura. El proveedor elegido inicialmente fue **Open-Meteo**, gratuito y sin clave de API. Al probarlo desde la red de trabajo resultó alcanzable de forma intermitente: seis intentos consecutivos fallaron con tiempo de espera agotado mientras otros servicios externos respondían con normalidad, lo que apuntaba a un problema de encaminamiento hacia ese *host* concreto y no a un fallo del proveedor. La solución fue añadir **MET Norway** como respaldo: el cliente intenta Open-Meteo, y si falla tras tres reintentos, recurre al segundo proveedor. Como MET Norway describe el tiempo con códigos de símbolo propios en vez del estándar WMO que usa Open-Meteo, se mapean a la categoría WMO equivalente (despejado, nublado, lluvia, nieve, niebla, tormenta) de modo que la aplicación no percibe diferencia alguna entre orígenes. Un *cache* en memoria de dos horas por coordenada redondeada evita repetir consultas para la misma zona.

El clima es un servicio auxiliar, no esencial: si ambos proveedores fallan, el *backend* responde 502 y la app dibuja la pantalla sin la tarjeta, sin interrumpir nada.

### 7.3 Distancia y duración en rutas dibujadas

Se cierra la carencia heredada de §4.5. Las rutas `DRAWN` y `FREEHAND` ya no se guardan sin métricas: al crearlas, el *backend* estima la distancia planificada integrando la fórmula de **Haversine** sobre los vértices de la geometría, y deriva la duración dividiendo esa distancia por una velocidad de crucero típica del modo (caminata 5 km/h, trote 9, *hiking* 4, ciclismo 16). Las rutas `ROUTED` siguen usando los valores que devuelve ORS, más precisos por estar calculados sobre la red vial real.

### 7.4 Cierre de la sesión: cancelación por inactividad y llegada automática

Dos caminos nuevos para terminar una sesión sin que el usuario tenga que acordarse de pulsar un botón.

**Cancelación por inactividad (`POST /sessions/{id}/abort`).** Cuando el jugador inicia una ruta pero no comienza a moverse, la app lo detecta y cancela la sesión. El *endpoint* marca la sesión `REJECTED` **sin** ejecutar la cadena de validación (no hay telemetría que validar) y registra un `ValidationResult` con motivo `no_movement` junto a un `ActivityLog` de cero puntos, de modo que el resumen y el historial expliquen el descarte en lugar de mostrar un rechazo mudo. Es idempotente y conceptualmente distinto de `finish`.

La detección vive en la app: tras dos minutos de gracia, si el **desplazamiento neto** desde la primera fijación no supera los 20 m, se aborta. La decisión de medir desplazamiento neto y no distancia acumulada es deliberada: un teléfono inmóvil acumula decenas de metros de deriva GPS, que se interpretarían erróneamente como movimiento.

**Llegada automática al destino.** La detección se implementa dentro del `TrackingService` (y no en el *ViewModel*) precisamente para que funcione con la pantalla bloqueada, que es el escenario normal de alguien caminando. El servicio recibe las coordenadas del último punto de la ruta y, en cada fijación, mide la distancia a ese destino. Para soportar rutas en bucle, donde inicio y fin coinciden, la detección solo se "arma" tras haberse alejado más de 30 m del destino; una vez armada, al volver a entrar en un radio de 30 m completa la sesión: marca la sesión local como completada, encola el `UploadWorker` y se detiene.

El estado de la sesión en Room es la **única señal compartida** por los tres caminos de cierre (manual, cancelación y llegada automática), de modo que el *ViewModel* observa ese `Flow` en lugar de que cada camino le avise por su cuenta.

### 7.5 Replay del recorrido

`GET /sessions/{id}/track` devuelve la geometría planificada y el rastro GPS real de la sesión, ambos como listas de pares `[lat, lon]`. El rastro va vacío si la sesión no llegó a registrar fijaciones.

La app lo dibuja en un mapa de solo lectura: la ruta planificada en ámbar discontinuo, el recorrido real en azul continuo, y un marcador que lo recorre en bucle a velocidad proporcional a la distancia (del orden de 500 m cada 30 s), de modo que el participante *vea* dónde se desvió en lugar de deducirlo de un porcentaje de adherencia. El componente se extrajo a un elemento reutilizable compartido por la pantalla de resumen y el detalle de actividad.

### 7.6 Robustez frente al ruido GPS

Un hallazgo de las pruebas de campo fue que una única fijación GPS ruidosa (un *spike* que se aleja y vuelve, característico de la reflexión de la señal entre edificios) bastaba para disparar el validador de aceleración descrito en §4.7 y rechazar caminatas por lo demás impecables, con velocidades medias de 5 km/h. La corrección tiene dos partes complementarias.

Primero, la función `filter_gps_outliers` (`validators/common.py`) elimina las fijaciones *outlier* de un solo punto. Un punto se considera *outlier* solo si la velocidad implícita tanto **al entrar** (desde el punto anterior) como **al salir** (hacia el siguiente) supera la velocidad máxima plausible del modo: exactamente la firma de un salto y retorno. Los extremos del recorrido se conservan siempre. El filtro se aplica en `finish_session` sobre una **copia de trabajo**: las fijaciones crudas permanecen intactas en la base de datos para la auditoría del estudio, y el número de *outliers* removidos se registra en el desglose de puntos.

Es importante notar que este filtro **no debilita la detección de trampa**: una teletransportación real es *sostenida* (el dispositivo salta y se queda en la nueva posición), por lo que no presenta el patrón de ida y vuelta, no se filtra, y sigue siendo capturada por el validador de velocidad.

Segundo, el validador de aceleración pasa de rechazar al primer *spike* a un **criterio de proporción**: rechaza solo si más del 10 % de los segmentos exceden los 10 m/s², el mismo criterio que el validador de velocidad ya usaba. Así, el ruido aislado queda por debajo del umbral mientras que una manipulación sistemática lo supera. Conviene tener presente al calibrar que un solo *outlier* produce dos *spikes* de aceleración (uno de ida y otro de vuelta), de modo que la proporción solo se mantiene baja en recorridos suficientemente largos.

### 7.7 Perfil: saldo global y transacciones

Hasta aquí el perfil mostraba únicamente los puntos otorgados por este sensor. Se añaden dos vistas que reflejan la posición del jugador en **toda la plataforma**, ambas sirviendo de *proxy* hacia LSG-Core con el *token* del propio participante:

- `GET /activity/points-balance` devuelve el saldo global desglosado por dimensión de puntos.
- `GET /activity/transactions` lista los créditos del *ledger* con su dimensión, saldo inicial y saldo final. Los nombres de campo del *ledger* no están documentados en el manual de LSG-Core, de modo que se parsean de forma tolerante, aceptando las variantes observadas.

Ambos son *best-effort*: si LSG-Core falla, el *backend* responde 502 y la app degrada con elegancia, dibujando el perfil sin esas secciones. La distinción entre puntos *locales* otorgados por este sensor y saldo *global* del jugador es la que el perfil refleja en sus dos bloques.

`GET /activity/me` se amplía para aceptar un `limit` opcional: omitirlo devuelve el historial completo en lugar de las diez últimas actividades. Cada entrada del historial pasa a ser navegable hacia un **detalle de actividad** con sus métricas y el mapa del recorrido descrito en §7.5.

Un beneficio colateral de estas vistas: el saldo es la **única fuente de nombres de dimensión** del sistema, porque LSG-Core no expone un catálogo aparte. Desde su versión 1.2.3 la respuesta del saldo viene enriquecida con el nombre de cada dimensión, de modo que la app lo reutiliza también para etiquetar el historial de transacciones (cuyo *ledger* solo trae el identificador numérico) en lugar de recurrir a una tabla de nombres codificada en el cliente.

### 7.8 La frontera de tokens

Es el cambio de mayor calado de esta iteración y el que responde directamente a la limitación de trazabilidad señalada en §6.4.

Hasta esta versión, **todas** las llamadas a LSG-Core viajaban con la cuenta de servicio: los siete métodos del cliente resolvían el *token* internamente. La consecuencia para el estudio longitudinal era severa: cada evento de sensor, cada consulta de *ledger* y cada lectura de saldo quedaban registrados en la plataforma central a nombre de una cuenta compartida y no del participante que efectivamente había recorrido la ruta.

El criterio nuevo es repartir según **qué rol exige cada operación**:

- Con el **token del participante**, permitidas a todos los roles:
  - `POST /sensors/ingest/webhook` (`player` solo lo suyo)
  - `GET /sensors/players/{id}`
  - `GET /points/ledger` (`player` autofiltrado)
  - `GET /players/{id}/points/balance`
- Con la **cuenta de servicio**, permitidas solo a admin, researcher y developer:
  - `POST /players/{id}/points/adjust`
  - `POST /sensors/players/{id}/link`
  - `POST /sensors/players/{id}/link-endpoint`

Las cuatro primeras están abiertas al rol `player`, de modo que pueden (y deben) viajar a nombre del participante. Las tres últimas exigen rol `admin`, `researcher` o `developer` y devolverían 403 a un participante corriente: la acreditación de puntos y el alta del sensor para un jugador, que ocurre una sola vez por participante.

La implementación descarga al cliente de LSG-Core de toda decisión sobre identidad: cada método **recibe su *token* explícitamente**, de modo que la frontera se lee en el punto de llamada y no queda escondida en la capa de transporte. El *token* de servicio se traslada a su propio módulo (`clients/lsg_service_account.py`) y, si no hay credenciales configuradas, devuelve nulo y el llamador cae al *token* del usuario (comportamiento que funciona mientras las cuentas de prueba tengan uno de esos roles y que evita romper el entorno de desarrollo).

Queda un problema derivado: el *worker* de reintentos de §4.9 sincroniza **sin nadie delante**, de modo que no tiene un *token* de participante a mano. Se resuelve conservando en `users.lsg_token` el último *access_token* validado de cada jugador, deslizándolo mediante `POST /token/refresh` mientras siga vivo (`services/user_token.py`). Es un *bearer* de 120 minutos, nunca una contraseña (el *backend* sigue sin almacenar credenciales), y los supuestos de riesgo que introduce se discuten en §11. Cuando no hay *token* vigente, la sesión **no consume intentos** del presupuesto de reintentos: de lo contrario, una noche con el servicio caído agotaría los seis intentos y dejaría la sesión varada para siempre.

### 7.9 Un endpoint de sensor por modo de actividad

LSG-Core modela cada sensor con uno o más `sensor_endpoint`, y ese identificador es **el único campo uniforme entre plugins** que un consumidor externo puede leer sin parsear el `raw_payload`, que cada plugin define a su antojo. Hasta esta versión, LSG-Routes emitía los cuatro modos de actividad por un endpoint único, de modo que quien leyera los eventos no podía distinguir una caminata de una salida en bicicleta.

El cambio registra **un endpoint por modo** (caminata, trote, senderismo y bicicleta), lo que permite que un consumidor externo clasifique la actividad reciente del jugador leyendo `GET /sensors/players/{id}/ingest-events` y agrupando por `id_sensor_endpoint`. El caso de uso que lo motiva es un *mod* de Baldur's Gate 3 que aplica efectos pasivos distintos según el tipo de actividad física reciente del participante, materializando la conexión entre el ejercicio real y la mecánica del videojuego que persigue la plataforma.

Tres consecuencias técnicas se derivan de la decisión:

- El mapa modo → *endpoint* se configura en `LSG_ROUTES_ENDPOINT_IDS` y se resuelve mediante `settings.endpoint_id_for(mode)`, con un endpoint por defecto de respaldo para modos no mapeados.
- Como LSG asigna un `players_sensor_endpoint_id` distinto por cada endpoint, la *cache* de ese identificador en la tabla `users` pasa de ser un escalar a un diccionario.
- El `parsed_value` de cada evento pasa de ser los puntos otorgados a ser **los kilómetros recorridos**, que es el escalar comparable dentro de un mismo endpoint y el que un consumidor externo espera encontrar junto al identificador de actividad.

El alta de los endpoints en LSG-Core exige rol `admin`, `researcher` o `developer` y se realiza una sola vez con el guion `scripts/provision_endpoints.py`, que los descubre o los crea e imprime las líneas de configuración resultantes. Cada endpoint declara su semántica en `watch_parameters` (tipo de evento, atributo de puntos y significado del `parsed_value`), de modo que el consumidor externo la lea desde la plataforma en lugar de llevarla codificada; es el mismo criterio con el que el sensor EggSys declara los suyos. Lo que **no** se declara son los factores de puntos por kilómetro: son parámetros ajustables por entorno y, como se explica más abajo, lo escrito en el endpoint es inmutable, de modo que declararlos equivaldría a congelar el valor que tuviera la máquina que ejecutó el alta. El endpoint describe qué actividad es; la regla que la puntúa sigue viviendo en este backend. `SensorEndpointCreateRequest` no admite ningún campo fuera de `name`, `description`, `url_endpoint`, `token_parameters`, `specific_parameters` y `watch_parameters`, y descarta en silencio los que no reconoce.

La especificación **no expone actualización ni borrado** de `sensor_endpoint`: la ruta `/sensors/{id}/endpoints` solo admite lectura y creación. Un alta incompleta, por lo tanto, no se puede corregir en su lugar; obliga a crear un endpoint nuevo, actualizar el mapa de configuración y solicitar al administrador de la plataforma la eliminación del anterior.

Después del prototipo 0.5 la bicicleta quedó detrás de la bandera `BIKE_ACTIVATE`, por no haber terreno donde probarla: su *endpoint* no se ha dado de alta, la app no ofrece el modo y el recorrido libre trata esas velocidades como exceso.

### 7.10 Estado de las API de LSG

La revisión de las especificaciones OpenAPI en vivo, hecha al abordar la frontera de tokens, reveló que los manuales que distribuye el equipo de la plataforma van por detrás de los servicios desplegados: LSG-Core se documenta como 1.2.1 cuando publica **1.2.3**, y LSG-Auth como 1.1.1 cuando publica **1.2.0.1**. Dos diferencias tienen consecuencia directa sobre este sensor:

- El campo `point_dimension_id` del ajuste de puntos quedó marcado **`[DEPRECADO]`** en favor de `attribute_id`, que identifica el atributo base (Social 1, Físico 2, Afectivo 3, Mental 4). LSG-Routes acredita al atributo Físico y conserva el campo antiguo solo como respaldo.
- La respuesta del saldo de puntos se enriqueció con el nombre y el código de cada dimensión, lo que resuelve la carencia mencionada en §7.7.

Los manuales son del equipo de la plataforma y no forman parte del repositorio de este sensor; el desfase se deja consignado para quien consulte este documento.

### 7.11 Alcance logrado y limitaciones

Con esta iteración el sensor alcanza el estado que documentan los capítulos 1 y 2: trazabilidad por participante en la plataforma central, actividad clasificable por modo para consumidores externos, cierre de sesión sin intervención manual, tolerancia al ruido GPS sin pérdida de capacidad anti-trampa, y contexto suficiente (clima, replay, saldo global) para que el uso diario tenga sentido.

Quedan tres frentes abiertos, que se recogen como trabajo futuro en el capítulo 12: el alta del `sensor_endpoint` de bicicleta en LSG-Core sigue pendiente; los umbrales de los validadores están calibrados sobre un conjunto de recorridos limitado y merecen revisión con datos de campo más amplios; y la validación de integridad del dispositivo (Play Integrity) no está implementada.

A ellos se suma una carencia de uso observada en las pruebas de campo, que motiva la iteración siguiente:

- **Toda actividad exige planificación previa.** El sensor solo sabe registrar la ejecución de una `PlannedRoute`: para que una caminata cuente, el participante debe primero crearla o copiar una ajena. Un paseo espontáneo, o la caminata de vuelta a casa, no tienen forma de registrarse. Para un estudio longitudinal esto sesga el dato hacia la actividad deliberada y descarta la incidental, que es justamente la que un programa de hábitos saludables quiere estimular.

---

## 8. Prototipo 0.5 - Recorrido libre

*2 de agosto de 2026*

### 8.1 Objetivo de la iteración

Permitir registrar actividad física **sin planificación previa**, sin que ello erosione el incentivo a usar rutas planificadas, que es la función central del sensor y la que produce el dato más rico para el estudio. La tensión de diseño es explícita: si recorrer libremente rindiera lo mismo que seguir una ruta, nadie planificaría; si no rindiera nada, la actividad incidental seguiría sin registrarse. La respuesta adoptada es un **factor de recompensa reducido**, visible para el usuario, que hace del recorrido libre una alternativa legítima pero secundaria.

El problema técnico que abre esta decisión es que, sin ruta de referencia, dos piezas del sistema quedan sin sustento: el **modo de actividad**, que hasta ahora venía declarado en la `PlannedRoute`, y la **adherencia**, que era simultáneamente la métrica de recompensa y una de las defensas anti-trampa. Ambas deben sustituirse por algo derivable únicamente de la telemetría.

### 8.2 Backend: la sesión sin ruta

No se introduce una entidad nueva. Una `RouteSession` cuyo `route_id` es **nulo** *es* un recorrido libre; la columna pasa a ser opcional en `route_sessions` y en `activity_logs` mediante la migración `003`. Esta decisión (frente a la alternativa de sintetizar una ruta ficticia al iniciar) mantiene intacto todo el resto del ciclo de vida descrito en §4.6: la ingesta idempotente de telemetría, la subida en lotes, el resumen, el historial y la visualización del recorrido funcionan sin cambios, porque ninguno de ellos necesitaba realmente la ruta planificada más que para dibujarla como guía.

`POST /sessions` acepta ahora el cuerpo sin `route_id` y omite la búsqueda de la ruta. Como el modo todavía se desconoce, la sesión se abre con `walking` como marcador, valor que `POST /sessions/{id}/finish` reemplaza por el modo realmente observado. Ese reemplazo tiene una consecuencia deliberada: al llegar a la sincronización, `session.mode` ya contiene un modo válido, de modo que el enrutamiento por `sensor_endpoint` descrito en §7.9 y todo el orquestador de §4.9 siguen funcionando sin conocer siquiera la existencia del recorrido libre.

### 8.3 Backend: clasificación de la actividad por velocidad

El nuevo servicio `services/free_roam.py` recorre la telemetría **segmento a segmento** (cada par de fijaciones consecutivas) y clasifica cada uno según su velocidad implícita, acumulando distancia por actividad:

| Velocidad        | Actividad | Puntos por km |
| ---------------- | --------- | ------------- |
| < 1,5 km/h       | detenido  | 0             |
| 1,5 – 7,0 km/h   | caminata  | 4             |
| 7,0 – 15,0 km/h  | trote     | 6             |
| 15,0 – 35,0 km/h | bicicleta | 2             |
| > 35,0 km/h      | exceso    | 0             |

Los puntos son la suma, sobre cada actividad, de sus kilómetros por el factor del modo (§4.8) multiplicado por `points_free_roam_multiplier`, fijado en **0,5**. Un solo parámetro de configuración gobierna así toda la diferencia de recompensa respecto de las rutas planificadas, lo que permite recalibrar el incentivo sin tocar código.

La clasificación por segmento (y no una única etiqueta para toda la sesión) resuelve el caso real de la actividad mixta: quien camina hasta la plaza y vuelve en bicicleta obtiene el factor correcto en cada tramo. El **modo dominante**, el de mayor distancia acumulada, es el que se persiste en la sesión y el que el historial muestra al participante.

No se incluye *hiking* entre las actividades detectables. El GPS no distingue una caminata por sendero de una por vereda: la diferencia es del terreno, no de la cinemática. El modo sigue existiendo, pero solo puede declararse al planificar una ruta.

### 8.4 Backend: integridad sin adherencia

La adherencia cumplía dos funciones a la vez, y al perderla hay que reemplazar ambas por separado.

Como **métrica de recompensa**, la sustituye la clasificación por velocidad de §8.3: solo los tramos cuya velocidad es físicamente plausible para una actividad humana acumulan distancia puntuable.

Como **defensa anti-trampa**, la sustituye ese mismo mecanismo, pero operando por descarte en lugar de por rechazo. La cadena de §4.7 se ejecuta reducida a dos validadores, `mock_location` y `acceleration`, y la razón de cada exclusión es distinta:

- **`velocity` no se ejecuta** porque su criterio es de sesión completa: rechazaría el recorrido entero por unos pocos tramos rápidos. En el recorrido libre esos tramos simplemente no suman (quedan en la banda de exceso), lo que es una respuesta proporcionada al no existir una ruta declarada contra la cual el exceso constituya una anomalía.
- **`step_correlation` y `gps_accel_coherence` no se ejecutan** porque ambos parametrizan sus umbrales por modo de actividad (longitud de zancada, umbral de movimiento) y una sesión mixta no tiene un modo único que pasarles. Queda anotado en el código como simplificación deliberada, con su vía de mejora: ejecutarlos por tramos, usando el modo que la clasificación asigna a cada uno.

El filtrado de *outliers* de §7.6 sí se aplica, tomando como referencia el techo de la banda más alta admitida (bicicleta), ya que el modo real aún se desconoce en ese punto del procesamiento.

### 8.5 Corte por exceso de velocidad sostenido

La banda de exceso descarta tramos, pero por sí sola no impide que alguien deje la aplicación grabando durante un viaje en vehículo. El corte cierra ese caso y, sobre todo, comunica al participante lo que está ocurriendo en lugar de descartar su sesión en silencio al final.

La vigilancia reside en el `TrackingService` y no en el *ViewModel*, por una razón práctica: el escenario real es ir en un vehículo con la pantalla apagada, de modo que el aviso debe llegar como **notificación**. El servicio calcula una velocidad rodante sobre los últimos quince segundos de fijaciones y, si supera los 35 km/h de forma continua, avisa **al minuto** de que la sesión se cortará, y **a los dos minutos** la cierra reutilizando el mismo mecanismo de auto-finalización por llegada al destino de §7.4. Volver a un rango plausible reinicia el contador y restaura el texto de la notificación: un descenso en bicicleta o un tramo de GPS ruidoso no cuestan la sesión.

El corte **no es un rechazo**. El recorrido se guarda como cualquier otro, con su mapa y con los puntos de los tramos válidos; solo los tramos en exceso quedan sin puntuar. La distinción importa: castigar con la pérdida total una sesión que puede haber sido mayoritariamente legítima produciría exactamente el tipo de falso positivo que el prototipo 0.4 se dedicó a corregir.

### 8.6 Revisión de los límites de velocidad existentes

Al fijar la banda de exceso se revisaron los umbrales de `SPEED_BOUNDS` heredados de §4.7, y dos resultaron mal calibrados en direcciones opuestas:

- **Trote (pico)**: de 22 km/h a **28 km/h**. Una persona en forma esprinta entre 25 y 30 km/h; el techo anterior marcaba como *spike* un tramo legítimo.
- **Bicicleta (sostenido)**: de 50 km/h a **35 km/h**. 50 km/h de **promedio de sesión** supera al pelotón profesional; el umbral dejaba pasar un viaje urbano en automóvil completo.

Los límites de caminata (8 sostenido / 12 pico) y *hiking* (8 / 14) se revisaron y se conservan: 8 km/h sostenidos ya corresponden a marcha atlética, y son un techo razonable. El pico de bicicleta se mantiene en 70 km/h porque un descenso real los alcanza; lo que no es sostenible es el promedio.

El nuevo techo de bicicleta coincide deliberadamente con el umbral de exceso del recorrido libre: el sistema traza una única frontera entre lo que un cuerpo humano propulsa y lo que hace un motor.

### 8.7 Aplicación: una pantalla, dos modos

La app no gana una pantalla de seguimiento nueva. La ruta de navegación `free_roam/{idPlayers}` reutiliza `ActiveRouteScreen` sin el argumento `routeId`, de modo que el *ViewModel* lo lee nulo y arranca en modo libre. El mapa ya se comportaba correctamente sin geometría planificada (la capa de la ruta guía no dibuja nada con menos de dos puntos) y el seguimiento de la posición ya estaba activo.

Sobre esa base, el modo libre añade a la vista tres elementos: la **actividad detectada** en cada momento con su ícono, los **puntos estimados** junto a tiempo, distancia y velocidad media, y el **aviso de exceso** cuando la velocidad rodante cruza el umbral. Los puntos que se muestran son una estimación local: la lógica de clasificación vive en `SpeedBands.kt`, espejo del servicio del *backend*, siguiendo el mismo precedente que ya existía para los factores por kilómetro. La autoridad sobre la recompensa efectiva sigue estando exclusivamente en el servidor, conforme al principio del capítulo 2.

Dos comportamientos heredados se desactivan en modo libre. La **cancelación por inactividad** de §7.4 no aplica: quien sale a caminar sin ruta decide cuándo termina, y detenerse dos minutos no es una anomalía. La **llegada al destino** tampoco, por no haber destino.

El punto de entrada es un botón flotante extendido «Recorrido libre» en la lista de rutas, situado sobre el botón de crear ruta. La jerarquía visual es intencional y refleja la del diseño: crear o elegir una ruta sigue siendo la acción principal.

La pantalla de resumen y el detalle de actividad **no requirieron cambios**. El replay del recorrido de §7.5 ya dibuja únicamente el track real cuando la geometría planificada llega vacía, de modo que el mapa de vista previa del recorrido libre y sus puntos aparecen exactamente igual que los de cualquier ruta; donde iría el porcentaje de adherencia se muestra la etiqueta «Recorrido libre».

### 8.8 Alcance logrado y limitaciones

El sensor deja de exigir planificación previa para registrar actividad, sin renunciar al incentivo que la sostiene. La actividad incidental (el trayecto de vuelta, el paseo no previsto) pasa a ser dato disponible para el estudio, etiquetada por la actividad que la telemetría evidencia y no por la que el participante declaró.

Quedan dos limitaciones propias de esta iteración:

- **La cadena anti-trampa es más débil en modo libre.** Con solo dos validadores activos, un desplazamiento en vehículo dentro de la banda de bicicleta (un trayecto urbano lento, un bus en hora punta) puede acumular puntos hasta que el corte lo interrumpa. Ejecutar `gps_accel_coherence` por tramos con el modo clasificado de cada uno cerraría buena parte de ese margen, y es la vía de mejora anotada en el código.
- **El factor 0,5 no está calibrado empíricamente.** Se eligió por ser una proporción legible para el participante y fácil de comunicar, no por evidencia de que produzca el equilibrio deseado entre actividad planificada e incidental. Es exactamente el tipo de parámetro que un despliegue con participantes reales permitiría ajustar, y se suma a la línea de calibración del capítulo 12.

---

## 9. Calidad y verificación

El *backend* cuenta con una suite de **180 pruebas** (161 al cierre del prototipo 0.5) que cubre cada validador de forma aislada, el cálculo de puntos en todos sus niveles, los flujos de los *endpoints*, la idempotencia de la ingesta y de la sincronización, y un conjunto de extremo a extremo del ciclo completo de sesión. Las pruebas usan **SQLite en memoria** vía `aiosqlite`, eludiendo la necesidad de un PostgreSQL real, y **respx** para simular los servicios externos (LSG-Auth, LSG-Core, ORS y los proveedores de clima). Los modelos que dependen de tipos específicos de PostgreSQL usan variantes compatibles con SQLite para que las pruebas corran idénticas en ambos motores.

Dos invariantes del prototipo 0.4 tienen pruebas dedicadas por ser especialmente frágiles ante un cambio descuidado: que el registro del evento viaje con el *token* del participante mientras la acreditación de puntos usa la cuenta de servicio (§7.8), y que cada modo de actividad salga por su propio `sensor_endpoint` (§7.9). Ambas se romperían en silencio si alguien volviera a centralizar la resolución de *tokens* en el cliente.

El prototipo 0.5 añade `test_free_roam.py`, que cubre las fronteras de cada banda de velocidad, el reparto de distancia en una sesión mixta, el descarte de los tramos en exceso y el ciclo completo de una sesión sin ruta hasta la sincronización. Las bandas se verifican con recorridos sintéticos de velocidad constante, construidos sobre el mismo radio terrestre que usa la fórmula de Haversine para que la distancia de cada tramo sea exacta y los puntos esperados puedan afirmarse sin tolerancia.

La app dispone de tests JVM puros para los *ViewModels* y la lógica de dominio, con MockK y Turbine para *flows*, más un test instrumentado de extremo a extremo de la base Room. `SpeedBandsTest` replica las afirmaciones de banda del *backend* sobre la implementación de la app, que es el mecanismo con que se mantiene alineado el espejo cliente-servidor descrito en §8.7. El repositorio cuenta con integración continua mediante **GitHub Actions**, con un flujo por proyecto.

---

## 10. Despliegue y operación

El *backend* se empaqueta con **Docker** (`Dockerfile` y `docker-compose.yml`) e incluye **Alembic** para migraciones de esquema, complementando la creación idempotente de tablas del arranque. La configuración se externaliza por completo en variables de entorno: URLs de los servicios LSG, credenciales de la cuenta de servicio, clave de ORS, identificadores del sensor en LSG-Core (sensor, mapa de *endpoints* por modo y atributo de puntos), factores de puntos por modo y el multiplicador del recorrido libre (§8.3).

La app se distribuye por **sideload**, es decir, instalación directa del APK fuera de Google Play, lo que resulta apropiado para el contexto de investigación. Para soportarlo, el *backend* expone `GET /app/version` con la última versión disponible, su URL de descarga y su *hash* SHA-256; la app consulta este *endpoint* al iniciar y, mediante `AppUpdateChecker`, ofrece al usuario actualizar cuando hay una versión nueva abriendo la URL de descarga en el navegador. El *hash* se publica, pero la app no lo verifica: la descarga y la instalación quedan a cargo del navegador y del instalador de Android. Ese mecanismo es el que da sentido operativo a la numeración de prototipos del capítulo 3: `app_minimum_version` permite exigir una versión mínima cuando un cambio de contrato lo hace necesario.

---

## 11. Seguridad e integridad

La integridad del sensor descansa en una premisa explícita: **el cliente es no confiable**. Todas las decisiones con consecuencia sobre los puntos se toman en el servidor, sobre evidencia validada. Las defensas se estratifican: detección de *mock provider* en la fijación misma, validación física del movimiento mediante velocidad y aceleración, corroboración cruzada entre sensores independientes (GPS contra acelerómetro y podómetro), y verificación geométrica de adherencia a la ruta. Ninguna defensa aislada es infalible, pero su composición eleva sustancialmente el costo de defraudar el sistema.

En reposo, el almacenamiento cifrado protege el *token* y las credenciales en el dispositivo. El transporte, en cambio, es hoy el punto más débil: el despliegue de la tesis sirve el *backend* por HTTP, de modo que el inicio de sesión y el *token* viajan sin cifrar, y el *certificate pinning* configurado en 0.1 nunca llegó a operar (§4.11). En la frontera de identidad, la delegación total en LSG-Auth evita que el sensor maneje contraseñas o claves de firma, reduciendo su superficie de ataque.

El prototipo 0.4 introduce una excepción a ese principio que conviene declarar sin rodeos: el *backend* conserva el último *access_token* de cada participante para que el *worker* de reintentos pueda sincronizar en su nombre (§7.8). Es un secreto de vida corta (120 minutos) y no una contraseña, se escribe solo después de que LSG-Auth lo haya validado, y se descarta ante cualquier rechazo. Aun así, amplía la superficie expuesta ante un compromiso de la base de datos.

El prototipo 0.5 abre una segunda excepción, esta vez en la propia cadena de validación: el recorrido libre se valida con dos de los cinco validadores (§8.4). No es un descuido sino una consecuencia de haber perdido la ruta de referencia (tres de los validadores ausentes la necesitan, directa o indirectamente) y el diseño lo compensa por otra vía: en lugar de rechazar la sesión, descarta los tramos implausibles y corta la sesión cuando el exceso es sostenido. El resultado es una defensa más permeable, cuyo margen concreto queda declarado en §8.8. La decisión de aceptarla se apoya en que el recorrido libre rinde la mitad de puntos, de modo que también es la mitad de rentable defraudarlo.

Finalmente, el registro completo de telemetría cruda y de resultados de validación en el *backend* (preservado incluso tras el filtrado de *outliers* de §7.6) habilita la auditoría posterior, un requisito del estudio longitudinal bajo el que opera la plataforma.

---

## 12. Síntesis y trabajo futuro

LSG-Routes implementa un sensor de rutas físicas completo y robusto para la plataforma LifeSync-Games, repartido en una app Android de captura y un *backend* de validación y orquestación. Su contribución técnica central no es la captura de telemetría (tarea hoy rutinaria) sino el **diseño de integridad**: una cadena de validación anti-trampa que combina señales de múltiples sensores físicamente independientes, un modelo de confianza que sitúa toda la autoridad en el servidor, y mecanismos de idempotencia y reintento que hacen el sistema resiliente a las realidades de las redes móviles sin comprometer la corrección.

El recorrido por prototipos de los capítulos 4 a 8 muestra que ese diseño no se alcanzó de una vez. La primera versión cerró la cadena completa pero con un anti-trampa demasiado rígido para el ruido real del GPS y una atribución de identidad que servía para funcionar, no para investigar. Las cuatro versiones siguientes fueron sucesivamente cubriendo la experiencia del participante, la dimensión comunitaria, las exigencias propias de un estudio longitudinal y, por último, el registro de la actividad no planificada. Cada corrección nació de una observación concreta (una caminata honesta rechazada, un participante sin forma de ver su progreso, un evento atribuido a la cuenta equivocada, un paseo espontáneo que no había forma de registrar) y no de una previsión de diseño.

Cinco líneas quedan abiertas:

**Operación previa al estudio.** Los `sensor_endpoint` de caminata, trote y senderismo descritos en §7.9 ya están dados de alta en LSG-Core. Falta el de bicicleta, que depende de activar `BIKE_ACTIVATE`, y pedir al administrador de la plataforma que elimine una primera tanda de *endpoints* creada sin `watch_parameters`, que no se puede corregir en su lugar.

**Calibración con datos de campo.** Los umbrales de los validadores (límites de velocidad por modo, proporción de *spikes* de aceleración tolerada, longitud de zancada, corredor de adherencia, bandas de clasificación del recorrido libre y su multiplicador de recompensa) se fijaron sobre un conjunto reducido de recorridos de prueba. Un despliegue con participantes reales produciría el volumen necesario para ajustarlos con criterio estadístico, distinguiendo el rechazo legítimo del falso positivo con evidencia en lugar de con intuición.

**Cadena anti-trampa completa en recorrido libre.** Los validadores de correlación de pasos y de coherencia GPS-acelerómetro no se ejecutan en modo libre por no haber un modo de actividad único que parametrizarlos (§8.4). Ejecutarlos por tramos, con el modo que la clasificación por velocidad asigna a cada uno, elevaría la defensa de esta modalidad al nivel de la de rutas planificadas.

**Validación de integridad del dispositivo.** Play Integrity no está implementado: ni la app solicita el *token* ni el *backend* lo verifica. Complementaría la detección de *mock provider* cubriendo el caso de un dispositivo con permisos de administrador donde esa marca puede suprimirse.

**Transporte cifrado.** Pasar el *backend* a HTTPS (la ruta detrás de Caddy ya está validada y no exige cambiar la API) y, con ello, fijar la clave pública del servidor con un pin de respaldo. Es el cambio pendiente de mayor impacto en la seguridad del sensor.
