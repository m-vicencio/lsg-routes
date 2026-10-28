"""Endpoints de planificacion de rutas."""

import logging
import uuid as _uuid
from datetime import datetime, timezone

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.clients.ors import ors_client
from app.clients.weather import weather_client
from app.core.config import settings
from app.core.security import get_current_user
from app.db.session import get_db
from app.models.route import PlannedRoute
from app.models.user import User
from app.validators.adherence import compute_planned_distance_m
from app.validators.common import haversine_m
from app.schemas.route import (
    ActivityMode,
    CreateRouteRequest,
    PublicRouteResponse,
    RouteOrigin,
    RouteResponse,
    VisibilityUpdateRequest,
    WeatherResponse,
)

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/routes", tags=["routes"])

# Velocidad de crucero estimada por modo (km/h) para estimar la duracion de rutas
# dibujadas a mano (las rutas ORS ya traen duracion real del proveedor).
_CRUISE_SPEED_KMH: dict[str, float] = {
    "walking": 5.0,
    "jogging": 9.0,
    "hiking": 4.0,
    "cycling": 16.0,
}


def _cut_start(points: list, cut_m: float) -> list:
    """Quita los primeros cut_m metros de la polilinea, interpolando el punto de corte."""
    if cut_m <= 0:
        return points
    walked = 0.0
    for i in range(1, len(points)):
        (lat1, lon1), (lat2, lon2) = points[i - 1][:2], points[i][:2]
        seg = haversine_m(lat1, lon1, lat2, lon2)
        if walked + seg > cut_m:
            # Interpolacion lineal en lat/lon, sobra a la escala de un tramo
            f = (cut_m - walked) / seg
            return [[lat1 + (lat2 - lat1) * f, lon1 + (lon2 - lon1) * f]] + points[i:]
        walked += seg
    return []


def _trim_geometry(geometry: list, start_m: float, end_m: float) -> list:
    """Geometria sin los primeros start_m ni los ultimos end_m metros; [] si no queda ruta."""
    trimmed = _cut_start(geometry, start_m)
    trimmed = _cut_start(trimmed[::-1], end_m)[::-1]
    return trimmed if len(trimmed) >= 2 else []


def _public_view(route: PlannedRoute) -> tuple[list, float | None, int | None]:
    """Geometria, distancia y duracion de la version compartida (extremos recortados).

    Es lo unico que ve o copia quien no es dueno. La distancia se escala porque los
    puntos de quien camina la copia salen de planned_distance_m (lsg_sync).
    """
    if not (route.hide_start_m or route.hide_end_m):
        return route.geometry, route.planned_distance_m, route.planned_duration_s
    geometry = _trim_geometry(route.geometry, route.hide_start_m, route.hide_end_m)
    full_m = compute_planned_distance_m(route.geometry)
    ratio = compute_planned_distance_m(geometry) / full_m if geometry and full_m else 0.0
    distance_m = route.planned_distance_m * ratio if route.planned_distance_m is not None else None
    duration_s = round(route.planned_duration_s * ratio) if route.planned_duration_s is not None else None
    return geometry, distance_m, duration_s


@router.post("", response_model=RouteResponse, status_code=status.HTTP_201_CREATED)
async def create_route(
    body: CreateRouteRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> PlannedRoute:
    """Crea una ruta planificada trazada manualmente o generada por ORS."""
    # La app esconde el modo, pero un build viejo o una llamada directa no: sin la
    # bandera no existe el sensor_endpoint de bicicleta y la ruta no podria sincronizarse.
    if body.mode == ActivityMode.CYCLING and not settings.bike_activate:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail={"code": "MODE_DISABLED", "message": "El modo bicicleta no esta habilitado"},
        )

    if body.origin in (RouteOrigin.DRAWN, RouteOrigin.FREEHAND):
        geometry = body.geometry
        # Rutas dibujadas no pasan por ORS: estimamos distancia (Haversine sobre la
        # geometria) y duracion (distancia / velocidad de crucero del modo).
        if geometry and len(geometry) >= 2:
            distance_m = compute_planned_distance_m(geometry)
            speed_kmh = _CRUISE_SPEED_KMH.get(body.mode.value, 5.0)
            duration_s = int(round(distance_m / (speed_kmh / 3.6))) if distance_m > 0 else 0
        else:
            distance_m = None
            duration_s = None
        source_payload = None
    else:
        try:
            ors_result = await ors_client.route_between(
                start_latlon=(body.start_latlon[0], body.start_latlon[1]),
                end_latlon=(body.end_latlon[0], body.end_latlon[1]),
                mode=body.mode.value,
            )
        except httpx.HTTPStatusError as exc:
            logger.error("ORS retorno error %s: %s", exc.response.status_code, exc)
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail={"code": "ORS_ERROR", "message": "Servicio de ruteo no disponible"},
            )
        except httpx.RequestError as exc:
            logger.error("Error de red al llamar ORS: %s", exc)
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail={"code": "ORS_ERROR", "message": "Servicio de ruteo no disponible"},
            )

        geometry = ors_result["geometry"]
        distance_m = ors_result["distance_m"]
        duration_s = ors_result["duration_s"]
        source_payload = {
            "start_latlon": body.start_latlon,
            "end_latlon": body.end_latlon,
            "mode": body.mode.value,
        }

    route = PlannedRoute(
        id_players=current_user.id_players,
        name=body.name,
        mode=body.mode.value,
        geometry=geometry,
        planned_distance_m=distance_m,
        planned_duration_s=duration_s,
        origin=body.origin.value,
        source_payload=source_payload,
    )
    db.add(route)
    await db.commit()
    await db.refresh(route)
    return route


@router.get("", response_model=list[RouteResponse])
async def list_routes(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> list[PlannedRoute]:
    """Lista las rutas planificadas del usuario autenticado (excluye eliminadas)."""
    stmt = (
        select(PlannedRoute)
        .where(
            PlannedRoute.id_players == current_user.id_players,
            PlannedRoute.deleted_at.is_(None),
        )
        .order_by(PlannedRoute.created_at.desc())
    )
    result = await db.execute(stmt)
    return list(result.scalars().all())


@router.get("/public", response_model=list[PublicRouteResponse])
async def list_public_routes(
    limit: int = Query(default=30, ge=1, le=50),
    offset: int = Query(default=0, ge=0),
    mode: str | None = Query(default=None),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> list[PublicRouteResponse]:
    """Feed comunitario: rutas publicas, incluidas las propias (marcadas is_mine)."""
    stmt = (
        select(PlannedRoute)
        .where(
            PlannedRoute.is_public.is_(True),
            PlannedRoute.deleted_at.is_(None),
        )
        .order_by(
            # Las propias primero, luego las mas recientes
            (PlannedRoute.id_players == current_user.id_players).desc(),
            PlannedRoute.created_at.desc(),
        )
        .limit(limit)
        .offset(offset)
    )
    if mode:
        stmt = stmt.where(PlannedRoute.mode == mode)
    routes = list((await db.execute(stmt)).scalars().all())
    feed = []
    # Tambien las propias van recortadas: el dueno ve lo mismo que los demas
    for r in routes:
        geometry, distance_m, duration_s = _public_view(r)
        feed.append(
            PublicRouteResponse(
                id=r.id,
                name=r.name,
                mode=r.mode,
                geometry=geometry,
                planned_distance_m=distance_m,
                planned_duration_s=duration_s,
                origin=r.origin,
                shared_by_name=r.shared_by_name,
                is_mine=(r.id_players == current_user.id_players),
                created_at=r.created_at,
            )
        )
    return feed


@router.get("/{route_id}", response_model=RouteResponse)
async def get_route(
    route_id: str,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> PlannedRoute | RouteResponse:
    """Retorna una ruta propia, o la version compartida de una ruta publica ajena."""
    try:
        rid = _uuid.UUID(route_id)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail={"code": "ROUTE_NOT_FOUND"})

    stmt = select(PlannedRoute).where(
        PlannedRoute.id == rid,
        PlannedRoute.deleted_at.is_(None),
        # Propia O publica
        (PlannedRoute.id_players == current_user.id_players) | (PlannedRoute.is_public.is_(True)),
    )
    result = await db.execute(stmt)
    route = result.scalar_one_or_none()
    if route is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": "ROUTE_NOT_FOUND", "message": "Ruta no encontrada"},
        )
    if route.id_players == current_user.id_players:
        return route
    geometry, distance_m, duration_s = _public_view(route)
    # hide_*_m en 0: a quien no es dueno no se le dice cuanto se oculto
    return RouteResponse.model_validate(route).model_copy(update={
        "geometry": geometry,
        "planned_distance_m": distance_m,
        "planned_duration_s": duration_s,
        "hide_start_m": 0,
        "hide_end_m": 0,
    })


@router.get("/{route_id}/weather", response_model=WeatherResponse)
async def get_route_weather(
    route_id: str,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> WeatherResponse:
    """Clima actual en el punto de inicio de la ruta (propia o publica)."""
    try:
        rid = _uuid.UUID(route_id)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail={"code": "ROUTE_NOT_FOUND"})

    stmt = select(PlannedRoute).where(
        PlannedRoute.id == rid,
        PlannedRoute.deleted_at.is_(None),
        (PlannedRoute.id_players == current_user.id_players) | (PlannedRoute.is_public.is_(True)),
    )
    route = (await db.execute(stmt)).scalar_one_or_none()
    # A quien no es dueno, el inicio de la version recortada: el real revelaria la ubicacion
    if route is not None and route.id_players != current_user.id_players:
        geometry = _public_view(route)[0]
    else:
        geometry = route.geometry if route is not None else None
    if not geometry:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": "ROUTE_NOT_FOUND", "message": "Ruta no encontrada o sin geometria"},
        )

    # El punto de inicio de la ruta (geometry guardada como [[lat, lon], ...])
    lat, lon = float(geometry[0][0]), float(geometry[0][1])
    try:
        current = await weather_client.current(lat, lon)
    except (httpx.HTTPStatusError, httpx.RequestError) as exc:
        logger.error("Error obteniendo clima para ruta %s: %s", route_id, exc)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={"code": "WEATHER_SERVICE_ERROR", "message": "No se pudo obtener el clima"},
        )

    return WeatherResponse(latitude=lat, longitude=lon, **current)


@router.post("/{route_id}/copy", response_model=RouteResponse, status_code=status.HTTP_201_CREATED)
async def copy_route(
    route_id: str,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> PlannedRoute:
    """Clona una ruta publica (o propia) dentro de la cuenta del que llama."""
    try:
        rid = _uuid.UUID(route_id)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail={"code": "ROUTE_NOT_FOUND"})

    stmt = select(PlannedRoute).where(
        PlannedRoute.id == rid,
        PlannedRoute.deleted_at.is_(None),
        (PlannedRoute.id_players == current_user.id_players) | (PlannedRoute.is_public.is_(True)),
    )
    source = (await db.execute(stmt)).scalar_one_or_none()
    if source is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": "ROUTE_NOT_FOUND", "message": "Ruta no encontrada o no es publica"},
        )

    # Idempotencia: si ya copio esta ruta, devolver la copia existente
    existing_stmt = select(PlannedRoute).where(
        PlannedRoute.id_players == current_user.id_players,
        PlannedRoute.copied_from_id == source.id,
        PlannedRoute.deleted_at.is_(None),
    )
    existing = (await db.execute(existing_stmt)).scalar_one_or_none()
    if existing is not None:
        return existing

    # La copia ajena nace recortada: la geometria completa nunca entra a otra cuenta
    if source.id_players == current_user.id_players:
        geometry, distance_m, duration_s = source.geometry, source.planned_distance_m, source.planned_duration_s
    else:
        geometry, distance_m, duration_s = _public_view(source)
    copy = PlannedRoute(
        id_players=current_user.id_players,
        name=source.name,
        mode=source.mode,
        geometry=geometry,
        planned_distance_m=distance_m,
        planned_duration_s=duration_s,
        origin=source.origin,
        is_public=False,
        copied_from_id=source.id,
        shared_by_name=source.shared_by_name,
    )
    db.add(copy)
    await db.commit()
    await db.refresh(copy)
    return copy


@router.patch("/{route_id}/visibility", response_model=RouteResponse)
async def update_route_visibility(
    route_id: str,
    body: VisibilityUpdateRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> PlannedRoute:
    """Publica o vuelve privada una ruta (solo el dueno)."""
    try:
        rid = _uuid.UUID(route_id)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail={"code": "ROUTE_NOT_FOUND"})

    stmt = select(PlannedRoute).where(
        PlannedRoute.id == rid,
        PlannedRoute.id_players == current_user.id_players,
        PlannedRoute.deleted_at.is_(None),
    )
    route = (await db.execute(stmt)).scalar_one_or_none()
    if route is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": "ROUTE_NOT_FOUND", "message": "Ruta no encontrada"},
        )

    # El recorte solo se fija al publicar; volver a privada lo conserva
    if body.is_public:
        if (body.hide_start_m or body.hide_end_m) and not _trim_geometry(
            route.geometry, body.hide_start_m, body.hide_end_m
        ):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail={"code": "TRIM_TOO_LONG", "message": "El recorte es mas largo que la ruta"},
            )
        route.hide_start_m = body.hide_start_m
        route.hide_end_m = body.hide_end_m
    route.is_public = body.is_public
    route.shared_by_name = current_user.name if body.is_public else None
    await db.commit()
    await db.refresh(route)
    return route


@router.delete("/{route_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_route(
    route_id: str,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> None:
    """Elimina suavemente una ruta planificada (soft delete)."""
    import uuid as _uuid

    try:
        rid = _uuid.UUID(route_id)
    except ValueError:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail={"code": "ROUTE_NOT_FOUND"})

    stmt = select(PlannedRoute).where(
        PlannedRoute.id == rid,
        PlannedRoute.id_players == current_user.id_players,
        PlannedRoute.deleted_at.is_(None),
    )
    result = await db.execute(stmt)
    route = result.scalar_one_or_none()
    if route is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"code": "ROUTE_NOT_FOUND", "message": "Ruta no encontrada"},
        )
    route.deleted_at = datetime.now(tz=timezone.utc)
    await db.commit()
