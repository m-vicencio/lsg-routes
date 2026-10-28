"""Endpoints del ciclo de vida de sesiones de ruta."""

import logging
import uuid as _uuid_mod
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import settings
from app.core.security import get_current_user
from app.db.session import get_db
from app.models.route import PlannedRoute
from app.models.session import (
    AccelSample,
    ActivityLog,
    IngestBatch,
    LSGSyncRecord,
    RouteSession,
    StepCount,
    TrackFix,
    ValidationResult,
)
from app.models.user import User
from app.schemas.session import (
    CreateSessionRequest,
    SessionResponse,
    SessionSummaryResponse,
    SessionTrackResponse,
    SyncBatchRequest,
    SyncBatchResponse,
)

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/sessions", tags=["sessions"])

# Estados que no necesitan reejecutar la cadena de validacion
_SKIP_VALIDATION_STATUSES = frozenset({"VALIDATED", "REJECTED", "SYNCED_TO_LSG"})
# LSG_SYNC_FAILED se puede reintentar via POST /finish


@router.post("", response_model=SessionResponse, status_code=status.HTTP_201_CREATED)
async def create_session(
    body: CreateSessionRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> RouteSession:
    """Inicia una sesion de ruta. Idempotente por client_session_uuid.

    Sin route_id se inicia un recorrido libre: no hay ruta que buscar y el modo
    queda como placeholder hasta que /finish lo reemplace por el modo detectado.
    """
    stmt = select(RouteSession).where(
        RouteSession.client_session_uuid == body.client_session_uuid
    )
    result = await db.execute(stmt)
    existing = result.scalar_one_or_none()
    if existing is not None:
        if existing.id_players != current_user.id_players:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail={"code": "FORBIDDEN", "message": "Sesion no pertenece al usuario"},
            )
        return existing

    # El id lo propone el cliente: si ya pertenece a otra sesion, el INSERT reventaria
    # con un error de integridad sin manejar.
    if body.id is not None and await db.get(RouteSession, body.id) is not None:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"code": "SESSION_ID_CONFLICT", "message": "El id de sesion ya esta en uso"},
        )

    mode = "walking"
    if body.route_id is not None:
        stmt = select(PlannedRoute).where(
            PlannedRoute.id == body.route_id,
            PlannedRoute.id_players == current_user.id_players,
            PlannedRoute.deleted_at.is_(None),
        )
        result = await db.execute(stmt)
        route = result.scalar_one_or_none()
        if route is None:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail={"code": "ROUTE_NOT_FOUND", "message": "Ruta no encontrada o eliminada"},
            )
        mode = route.mode

    session = RouteSession(
        id=body.id or _uuid_mod.uuid4(),
        route_id=body.route_id,
        id_players=current_user.id_players,
        status="STARTED",
        mode=mode,
        client_session_uuid=body.client_session_uuid,
    )
    db.add(session)
    await db.commit()
    await db.refresh(session)
    return session


@router.post("/{session_id}/sync", response_model=SyncBatchResponse, status_code=status.HTTP_207_MULTI_STATUS)
async def sync_batch(
    session_id: str,
    body: SyncBatchRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> SyncBatchResponse:
    """Recibe un lote de telemetria GPS. Idempotente por client_ref y client_seq."""
    session = await _get_session_or_404(session_id, current_user.id_players, db)

    # Idempotencia a nivel de lote
    stmt = select(IngestBatch).where(IngestBatch.client_ref == body.client_ref)
    result = await db.execute(stmt)
    existing_batch = result.scalar_one_or_none()
    if existing_batch is not None:
        return SyncBatchResponse(client_ref=body.client_ref, status="DUPLICATE")

    # Cerrada la sesion su telemetria ya se valido: aceptar mas solo ensuciaria la
    # auditoria. El reenvio de un lote ya recibido sigue respondiendo DUPLICATE (arriba).
    if session.status != "STARTED":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"code": "SESSION_CLOSED", "message": "La sesion ya fue cerrada"},
        )

    batch = IngestBatch(session_id=session.id, client_ref=body.client_ref, processed=False)
    db.add(batch)

    # Obtener client_seq ya almacenados para esta sesion
    existing_seqs_stmt = select(TrackFix.client_seq).where(TrackFix.session_id == session.id)
    existing_seqs_result = await db.execute(existing_seqs_stmt)
    existing_seqs = {row[0] for row in existing_seqs_result}

    fixes_stored = fixes_skipped = 0
    for fix in body.fixes:
        if fix.client_seq in existing_seqs:
            fixes_skipped += 1
            continue
        db.add(TrackFix(
            session_id=session.id,
            lat=fix.lat, lon=fix.lon,
            accuracy_m=fix.accuracy_m, altitude_m=fix.altitude_m,
            speed_mps=fix.speed_mps, bearing_deg=fix.bearing_deg,
            is_from_mock_provider=fix.is_from_mock_provider,
            captured_at=fix.captured_at,
            client_seq=fix.client_seq,
        ))
        existing_seqs.add(fix.client_seq)
        fixes_stored += 1

    for bucket in body.accel_buckets:
        db.add(AccelSample(
            session_id=session.id,
            magnitude_mean=bucket.magnitude_mean,
            magnitude_variance=bucket.magnitude_variance,
            bucket_started_at=bucket.bucket_started_at,
            bucket_seconds=bucket.bucket_seconds,
        ))

    for step in body.step_counts:
        db.add(StepCount(
            session_id=session.id,
            steps_delta=step.steps_delta,
            captured_at=step.captured_at,
        ))

    batch.processed = True
    await db.commit()

    return SyncBatchResponse(
        client_ref=body.client_ref,
        status="PROCESSED",
        fixes_stored=fixes_stored,
        fixes_skipped=fixes_skipped,
        accel_buckets_stored=len(body.accel_buckets),
        step_counts_stored=len(body.step_counts),
    )


@router.post("/{session_id}/finish", response_model=SessionSummaryResponse)
async def finish_session(
    session_id: str,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> SessionSummaryResponse:
    """Cierra la sesion, ejecuta la cadena anti-cheat y acredita puntos en LSG."""
    session = await _get_session_or_404(session_id, current_user.id_players, db)

    # Retornar el estado actual si ya fue procesado exitosamente o rechazado
    if session.status in _SKIP_VALIDATION_STATUSES:
        return await _build_summary(session, db)

    # Si el sync fallo anteriormente, reintentar sin re-ejecutar validadores
    if session.status == "LSG_SYNC_FAILED":
        sync_stmt = select(LSGSyncRecord).where(LSGSyncRecord.session_id == session.id)
        sync_result = await db.execute(sync_stmt)
        existing_sync = sync_result.scalar_one_or_none()
        awarded_points = existing_sync.awarded_points or 0 if existing_sync else 0
        from app.services.lsg_sync import sync_to_lsg
        updated_sync = await sync_to_lsg(
            session=session,
            awarded_points=awarded_points,
            adherence_ratio=0.0,
            validators_summary={"retry": True},
            breakdown={"retry": True},
            planned_distance_m=None,  # se reconstruye desde la BD
            db=db,
            user_token=current_user.lsg_token,
        )
        session.status = updated_sync.sync_status
        await db.commit()
        return await _build_summary(session, db)

    # Cargar datos de telemetria
    fixes = await _load_fixes(session.id, db)
    accel = await _load_accel(session.id, db)
    steps = await _load_steps(session.id, db)

    # Cerrar sesion con la ventana real de la actividad, no con la del servidor
    _apply_real_span(session, fixes)
    session.status = "COMPLETED_PENDING_VALIDATION"

    # Un recorrido libre no tiene ruta de referencia: sin adherencia y con puntos
    # calculados por tramos segun la velocidad observada.
    is_free_roam = session.route_id is None

    # Cargar ruta planificada
    route = None
    if not is_free_roam:
        route_stmt = select(PlannedRoute).where(PlannedRoute.id == session.route_id)
        route_result = await db.execute(route_stmt)
        route = route_result.scalar_one_or_none()
        if route is None:
            raise HTTPException(status_code=404, detail={"code": "ROUTE_NOT_FOUND"})

    from app.validators.common import (
        AccelData, FixData, StepData, average_speed_mps, drop_imprecise_fixes,
        filter_gps_outliers, total_distance_m,
    )
    fix_data = [FixData(lat=f.lat, lon=f.lon, captured_at=f.captured_at,
                        speed_mps=f.speed_mps, is_from_mock_provider=f.is_from_mock_provider,
                        accuracy_m=f.accuracy_m)
                for f in fixes]

    # Limpieza del track antes de validar y medir. Los fixes crudos quedan intactos en la
    # BD para auditoria del estudio; solo se filtra la copia de trabajo usada por
    # validadores, distancia y puntos.
    # Primero los fixes sin precision utilizable: el piso de ruido no los cubre y
    # alimentarian todo lo demas con posiciones a decenas de metros del punto real.
    fix_data, imprecise_dropped = drop_imprecise_fixes(fix_data)
    if imprecise_dropped:
        logger.info("Sesion %s: %d fix(es) descartados por precision insuficiente",
                    session.id, imprecise_dropped)

    # Despues los outliers de un solo fix (spikes que saltan y vuelven).
    # En un recorrido libre el modo real todavia no se conoce: se usa el techo de la
    # banda mas alta admitida (bicicleta) para no descartar tramos legitimos.
    from app.validators.velocity import SPEED_BOUNDS
    bounds_mode = "cycling" if is_free_roam else session.mode
    max_spike_mps = SPEED_BOUNDS.get(bounds_mode, SPEED_BOUNDS["walking"])["max_spike"] / 3.6
    fix_data, outliers_removed = filter_gps_outliers(fix_data, max_spike_mps)
    if outliers_removed:
        logger.info("Sesion %s: %d outlier(s) GPS filtrados antes de validar", session.id, outliers_removed)

    accel_data = [AccelData(magnitude_mean=a.magnitude_mean, magnitude_variance=a.magnitude_variance,
                            bucket_started_at=a.bucket_started_at, bucket_seconds=a.bucket_seconds)
                  for a in accel]
    step_data = [StepData(steps_delta=s.steps_delta, captured_at=s.captured_at) for s in steps]

    # Cadena anti-cheat: corren todos los validadores y el primero que falla queda como motivo
    from app.validators import mock_location, velocity, acceleration, gps_accel_coherence, step_correlation, adherence as adherence_v

    if is_free_roam:
        # Cadena reducida: velocity rechazaria la sesion entera por unos tramos rapidos
        # (en libre esos tramos se descartan uno a uno al puntuar), y step_correlation
        # y gps_accel_coherence asumen un modo fijo que aqui no existe.
        # Pendiente: correr gps_accel_coherence (y step_correlation) por tramos,
        # con el modo que la clasificacion asigna a cada uno.
        chain = [
            ("mock_location", mock_location.validate(fix_data)),
            ("acceleration",  acceleration.validate(fix_data)),
        ]
    else:
        chain = [
            ("mock_location",       mock_location.validate(fix_data)),
            ("velocity",            velocity.validate(fix_data, session.mode)),
            ("acceleration",        acceleration.validate(fix_data)),
            ("gps_accel_coherence", gps_accel_coherence.validate(fix_data, accel_data, session.mode)),
            ("step_correlation",    step_correlation.validate(fix_data, step_data, session.mode)),
        ]

    validators_summary: dict[str, dict] = {}
    first_failure: str | None = None
    for name, vresult in chain:
        validators_summary[name] = vresult
        if not vresult["passed"] and first_failure is None:
            first_failure = name

    # Calcular adherencia (siempre, para reporte). No aplica al recorrido libre.
    planned_distance_m = 0.0
    adh_result = None
    adherence_ratio = None
    if not is_free_roam:
        planned_distance_m = route.planned_distance_m
        if planned_distance_m is None or planned_distance_m == 0:
            from app.validators.adherence import compute_planned_distance_m
            planned_distance_m = compute_planned_distance_m(route.geometry)

        adh_result = adherence_v.compute_adherence(
            planned_geometry=route.geometry,
            fixes=fix_data,
            corridor_m=settings.adherence_corridor_m,
        )
        adherence_ratio = adh_result["adherence_ratio"]

    # Metricas reales del track para el log de actividad del perfil
    actual_distance_m = total_distance_m(fix_data)
    duration_s = _duration_seconds(session.started_at, session.ended_at)
    avg_speed_kmh = round(average_speed_mps(fix_data) * 3.6, 2)

    # En libre los puntos se calculan antes de decidir el rechazo: parado o con solo
    # tramos de jitter/exceso, los validadores anti-cheat no objetan nada.
    free_roam_points = 0
    free_roam_breakdown: dict | None = None
    if is_free_roam:
        from app.services.free_roam import compute_free_roam_points
        free_roam_points, free_roam_breakdown = compute_free_roam_points(fix_data)

    # Guardar resultados de validacion
    for name, vresult in chain:
        db.add(ValidationResult(
            session_id=session.id,
            validator_name=name,
            passed=vresult["passed"],
            details=vresult["details"],
        ))
    if adh_result is not None:
        db.add(ValidationResult(
            session_id=session.id,
            validator_name="adherence",
            passed=adherence_ratio >= 0.30,
            details=adh_result["details"],
        ))

    # Determinar si fue rechazado (rutas FREEHAND usan umbral de adherencia mas bajo)
    is_freehand = not is_free_roam and route.origin == "FREEHAND"
    if is_free_roam:
        rejected = first_failure is not None or free_roam_points <= 0
    else:
        min_adherence = settings.adherence_partial_min_freehand if is_freehand else 0.30
        rejected = first_failure is not None or adherence_ratio < min_adherence

    if rejected:
        session.status = "REJECTED"
        awarded_points = 0
        breakdown: dict = {
            "tier": "REJECTED",
            "reason": first_failure or ("no_movement" if is_free_roam else "low_adherence"),
            "outliers_removed": outliers_removed,
        }
        _record_activity(
            db, session, "REJECTED", actual_distance_m, duration_s,
            avg_speed_kmh, adherence_ratio, awarded_points=0,
        )
        await db.commit()
        return SessionSummaryResponse(
            session_id=session.id, status=session.status, mode=session.mode,
            awarded_points=0, adherence_ratio=adherence_ratio,
            validators=_format_validators(validators_summary),
            sync_status=None, started_at=session.started_at, ended_at=session.ended_at,
        )

    # Calcular puntos
    if is_free_roam:
        awarded_points, breakdown = free_roam_points, free_roam_breakdown
        # El modo dominante define como se muestra en el historial y por que endpoint
        # de LSG-Core viaja el evento; sin tramos validos se conserva el placeholder.
        session.mode = breakdown["dominant_mode"] or session.mode
        # Para libre, el escalar que consume LSG son los km realmente recorridos.
        planned_distance_m = actual_distance_m
    else:
        from app.services.points import compute_points
        awarded_points, breakdown = compute_points(session.mode, planned_distance_m, adherence_ratio, is_freehand=is_freehand)
    breakdown["outliers_removed"] = outliers_removed
    session.status = "VALIDATED"
    _record_activity(
        db, session, "VALIDATED", actual_distance_m, duration_s,
        avg_speed_kmh, adherence_ratio, awarded_points=awarded_points,
    )
    await db.commit()

    # Sincronizar con LSG-Core
    from app.services.lsg_sync import sync_to_lsg
    sync_record = await sync_to_lsg(
        session=session,
        awarded_points=awarded_points,
        adherence_ratio=adherence_ratio or 0.0,
        validators_summary=validators_summary,
        breakdown=breakdown,
        planned_distance_m=planned_distance_m,
        db=db,
        user_token=current_user.lsg_token,
    )
    session.status = sync_record.sync_status
    await db.commit()

    return SessionSummaryResponse(
        session_id=session.id, status=session.status, mode=session.mode,
        awarded_points=awarded_points, adherence_ratio=adherence_ratio,
        validators=_format_validators(validators_summary),
        sync_status=sync_record.sync_status,
        started_at=session.started_at, ended_at=session.ended_at,
    )


@router.post("/{session_id}/abort", response_model=SessionSummaryResponse)
async def abort_session(
    session_id: str,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> SessionSummaryResponse:
    """Cancela una sesion marcandola REJECTED sin ejecutar la cadena de validacion.

    Usado cuando el jugador no comienza a moverse dentro del tiempo de gracia inicial
    (~2 min). No otorga puntos. Idempotente: si la sesion ya termino, devuelve su resumen.
    """
    session = await _get_session_or_404(session_id, current_user.id_players, db)

    if session.status in _SKIP_VALIDATION_STATUSES:
        return await _build_summary(session, db)

    from app.validators.common import (
        FixData, average_speed_mps, drop_imprecise_fixes, total_distance_m,
    )
    fixes = await _load_fixes(session.id, db)
    _apply_real_span(session, fixes)  # mismo motivo que en /finish
    session.status = "REJECTED"
    fix_data = [FixData(lat=f.lat, lon=f.lon, captured_at=f.captured_at,
                        speed_mps=f.speed_mps, is_from_mock_provider=f.is_from_mock_provider,
                        accuracy_m=f.accuracy_m)
                for f in fixes]
    fix_data, _ = drop_imprecise_fixes(fix_data)  # mismo criterio que la ruta de validacion
    distance_m = total_distance_m(fix_data)
    duration_s = _duration_seconds(session.started_at, session.ended_at)
    avg_speed_kmh = round(average_speed_mps(fix_data) * 3.6, 2)

    # Registrar el motivo para que aparezca en el resumen/historial
    db.add(ValidationResult(
        session_id=session.id,
        validator_name="no_movement",
        passed=False,
        details={"reason": "no_movement_in_grace_period"},
    ))
    _record_activity(db, session, "REJECTED", distance_m, duration_s, avg_speed_kmh, None, awarded_points=0)
    await db.commit()

    return SessionSummaryResponse(
        session_id=session.id, status=session.status, mode=session.mode,
        awarded_points=0, adherence_ratio=None,
        validators={"no_movement": "FAILED"},
        sync_status=None, started_at=session.started_at, ended_at=session.ended_at,
    )


@router.get("/{session_id}/summary", response_model=SessionSummaryResponse)
async def get_summary(
    session_id: str,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> SessionSummaryResponse:
    """Retorna el resumen de una sesion finalizada."""
    session = await _get_session_or_404(session_id, current_user.id_players, db)
    return await _build_summary(session, db)


@router.get("/{session_id}/track", response_model=SessionTrackResponse)
async def get_session_track(
    session_id: str,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> SessionTrackResponse:
    """Retorna la ruta planificada y el track GPS real de la sesion para dibujarlos.

    Sirve para la visualizacion del recorrido (pantalla de resumen e historial). El
    track real va vacio si la sesion no registro fixes.
    """
    session = await _get_session_or_404(session_id, current_user.id_players, db)

    planned: list[list[float]] = []
    if session.route_id is not None:
        route_stmt = select(PlannedRoute).where(PlannedRoute.id == session.route_id)
        route = (await db.execute(route_stmt)).scalar_one_or_none()
        planned = route.geometry if route and route.geometry else []

    # Se dibuja el track limpio, con el mismo criterio con que se midio y acredito:
    # el crudo trae el zigzag del multipath y no representa por donde se camino.
    from app.validators.common import (
        FixData, drop_imprecise_fixes, filter_gps_outliers, significant_legs,
    )
    from app.validators.velocity import SPEED_BOUNDS

    fixes = await _load_fixes(session.id, db)
    fix_data = [FixData(lat=f.lat, lon=f.lon, captured_at=f.captured_at,
                        accuracy_m=f.accuracy_m)
                for f in fixes]
    fix_data, _ = drop_imprecise_fixes(fix_data)
    max_spike_mps = SPEED_BOUNDS.get(session.mode, SPEED_BOUNDS["walking"])["max_spike"] / 3.6
    fix_data, _ = filter_gps_outliers(fix_data, max_spike_mps)
    # El origen mas el destino de cada tramo significativo: el track sin jitter.
    drawable = fix_data[:1] + [leg[1] for leg in significant_legs(fix_data)]

    return SessionTrackResponse(
        session_id=session.id,
        mode=session.mode,
        status=session.status,
        planned_geometry=planned,
        fixes=[[f.lat, f.lon] for f in drawable],
    )


@router.get("", response_model=list[SessionResponse])
async def list_sessions(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> list[RouteSession]:
    """Lista las sesiones del usuario autenticado ordenadas por inicio."""
    stmt = (
        select(RouteSession)
        .where(RouteSession.id_players == current_user.id_players)
        .order_by(RouteSession.started_at.desc())
    )
    result = await db.execute(stmt)
    return list(result.scalars().all())


@router.get("/{session_id}", response_model=SessionResponse)
async def get_session(
    session_id: str,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> RouteSession:
    """Retorna el detalle de una sesion del usuario."""
    return await _get_session_or_404(session_id, current_user.id_players, db)


# Helpers privados

def _duration_seconds(started_at, ended_at) -> int:
    """Segundos entre inicio y fin, robusto ante datetimes naive vs aware.

    En SQLite (tests) started_at vuelve naive y ended_at es aware; normalizamos
    ambos a UTC-aware para evitar TypeError al restar.
    """
    if not started_at or not ended_at:
        return 0
    if started_at.tzinfo is None:
        started_at = started_at.replace(tzinfo=timezone.utc)
    if ended_at.tzinfo is None:
        ended_at = ended_at.replace(tzinfo=timezone.utc)
    return max(0, int((ended_at - started_at).total_seconds()))


def _apply_real_span(session: RouteSession, fixes: list[TrackFix]) -> None:
    """Reescribe started_at/ended_at con la ventana real de captura del track.

    Con el seguimiento offline-first, started_at es el instante en que el UploadWorker
    creo la sesion (o sea, cuando volvio la conexion) y ended_at el de /finish unos
    segundos despues, asi que medir entre ellos daria una duracion de segundos para una
    caminata de varios minutos. Los fixes son lo unico que trae la hora real, y es la
    misma fuente que usa average_speed_mps, asi que duracion y velocidad son coherentes.

    Con el track vacio no hay ventana que inventar: se cierra al momento actual y la
    duracion queda en 0, que es lo honesto para una sesion sin recorrido.
    """
    if not fixes:
        session.ended_at = datetime.now(tz=timezone.utc)
        return
    # min/max y no primero/ultimo: _load_fixes ordena por client_seq, y una sesion
    # reanudada podria traer numeraciones que no coincidan con el orden temporal.
    session.started_at = min(f.captured_at for f in fixes)
    session.ended_at = max(f.captured_at for f in fixes)


def _record_activity(
    db: AsyncSession,
    session: RouteSession,
    status: str,
    distance_m: float,
    duration_s: int,
    avg_speed_kmh: float,
    adherence_ratio: float | None,
    awarded_points: int,
) -> None:
    """Agrega un ActivityLog a la sesion de DB (idempotente por session_id unico).

    El commit lo hace el caller. La cadena de validacion solo corre una vez por
    sesion (finish reejecutado retorna antes via _SKIP_VALIDATION_STATUSES).
    """
    db.add(ActivityLog(
        # created_at es server_default: sin pasarlo, el historial fecharia la actividad
        # el dia en que volvio la conexion y no el de la caminata. Tambien define el
        # orden de GET /activity/me.
        created_at=session.ended_at or datetime.now(tz=timezone.utc),
        id_players=session.id_players,
        session_id=session.id,
        route_id=session.route_id,
        mode=session.mode,
        status=status,
        distance_m=round(distance_m, 1),
        duration_s=duration_s,
        avg_speed_kmh=avg_speed_kmh,
        adherence_ratio=adherence_ratio,
        awarded_points=awarded_points,
    ))


async def _get_session_or_404(session_id: str, id_players: int, db: AsyncSession) -> RouteSession:
    try:
        sid = _uuid_mod.UUID(session_id)
    except ValueError:
        raise HTTPException(status_code=404, detail={"code": "SESSION_NOT_FOUND"})
    stmt = select(RouteSession).where(
        RouteSession.id == sid, RouteSession.id_players == id_players
    )
    result = await db.execute(stmt)
    session = result.scalar_one_or_none()
    if session is None:
        raise HTTPException(
            status_code=404, detail={"code": "SESSION_NOT_FOUND", "message": "Sesion no encontrada"}
        )
    return session


async def _load_fixes(session_id, db: AsyncSession) -> list[TrackFix]:
    stmt = select(TrackFix).where(TrackFix.session_id == session_id).order_by(TrackFix.client_seq)
    result = await db.execute(stmt)
    return list(result.scalars().all())


async def _load_accel(session_id, db: AsyncSession) -> list[AccelSample]:
    stmt = select(AccelSample).where(AccelSample.session_id == session_id)
    result = await db.execute(stmt)
    return list(result.scalars().all())


async def _load_steps(session_id, db: AsyncSession) -> list[StepCount]:
    stmt = select(StepCount).where(StepCount.session_id == session_id)
    result = await db.execute(stmt)
    return list(result.scalars().all())


async def _build_summary(session: RouteSession, db: AsyncSession) -> SessionSummaryResponse:
    """Construye el resumen desde los registros ya persistidos."""
    v_stmt = select(ValidationResult).where(ValidationResult.session_id == session.id)
    v_result = await db.execute(v_stmt)
    vrows = list(v_result.scalars().all())

    validators: dict[str, str] = {}
    adherence_ratio: float | None = None
    for row in vrows:
        if row.validator_name == "adherence":
            adh_details = row.details or {}
            if adh_details.get("track_in_corridor_m") and adh_details.get("planned_length_m"):
                plen = adh_details["planned_length_m"]
                tic = adh_details["track_in_corridor_m"]
                adherence_ratio = round(min(1.0, tic / plen), 4) if plen > 0 else 0.0
        else:
            validators[row.validator_name] = "OK" if row.passed else "FAILED"

    sync_stmt = select(LSGSyncRecord).where(LSGSyncRecord.session_id == session.id)
    sync_result = await db.execute(sync_stmt)
    sync_record = sync_result.scalar_one_or_none()

    return SessionSummaryResponse(
        session_id=session.id,
        status=session.status,
        mode=session.mode,
        awarded_points=sync_record.awarded_points if sync_record else None,
        adherence_ratio=adherence_ratio,
        validators=validators,
        sync_status=sync_record.sync_status if sync_record else None,
        started_at=session.started_at,
        ended_at=session.ended_at,
    )


def _format_validators(validators_summary: dict[str, dict]) -> dict[str, str]:
    return {name: ("OK" if v["passed"] else "FAILED") for name, v in validators_summary.items()}
