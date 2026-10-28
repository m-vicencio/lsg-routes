"""Endpoints del historial de actividad del jugador (para el perfil)."""

import logging

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.clients.lsg_core import lsg_core_client
from app.core.security import get_current_user
from app.db.session import get_db
from app.models.session import ActivityLog
from app.models.user import User
from app.schemas.activity import (
    ActivityStatsResponse,
    PointDimensionBalance,
    PointsBalanceResponse,
    PointTransaction,
)

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/activity", tags=["activity"])


@router.get("/me", response_model=ActivityStatsResponse)
async def get_my_activity(
    limit: int | None = Query(default=None, ge=1),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> ActivityStatsResponse:
    """Retorna los totales del jugador y su historial de actividades.

    Sin `limit` devuelve el historial completo (mas reciente primero); con `limit`
    acota la lista. Los totales agregados siempre cubren todas las actividades.
    """
    pid = current_user.id_players

    # Totales agregados (siempre sobre todas las actividades)
    totals_stmt = select(
        func.count(ActivityLog.id),
        func.coalesce(func.sum(ActivityLog.distance_m), 0.0),
        func.coalesce(func.sum(ActivityLog.awarded_points), 0),
    ).where(ActivityLog.id_players == pid)
    totals = (await db.execute(totals_stmt)).one()

    # Historial (mas reciente primero); sin limit -> todas
    recent_stmt = (
        select(ActivityLog)
        .where(ActivityLog.id_players == pid)
        .order_by(ActivityLog.created_at.desc())
    )
    if limit is not None:
        recent_stmt = recent_stmt.limit(limit)
    recent = list((await db.execute(recent_stmt)).scalars().all())

    return ActivityStatsResponse(
        total_activities=totals[0],
        total_distance_m=float(totals[1]),
        total_points=int(totals[2]),
        recent=recent,
    )


@router.get("/points-balance", response_model=PointsBalanceResponse)
async def get_my_points_balance(
    current_user: User = Depends(get_current_user),
) -> PointsBalanceResponse:
    """Retorna el saldo de puntos del perfil del jugador en LSG-Core.

    A diferencia de /activity/me (que solo cuenta los puntos otorgados por este
    sensor), este saldo es el perfil global del jugador en LSG, desglosado por
    dimension de puntos.

    Es ademas la unica fuente de nombres de dimension del sistema: LSG-Core no
    tiene un catalogo aparte, pero desde 1.2.3 el saldo viene enriquecido con
    `dimension_name`. La app lo reutiliza para etiquetar tambien el historial de
    transacciones, cuyo ledger solo trae el id.
    """
    try:
        raw = await lsg_core_client.get_points_balance(
            current_user.id_players, current_user.lsg_token
        )
    except (httpx.HTTPStatusError, httpx.RequestError) as exc:
        logger.error("Error obteniendo saldo de puntos del jugador %s: %s", current_user.id_players, exc)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={"code": "CORE_SERVICE_ERROR", "message": "No se pudo obtener el saldo de puntos"},
        )

    dimensions = [
        PointDimensionBalance(
            id_point_dimension=int(d["id_point_dimension"]),
            balance=int(d["balance"]),
            name=d.get("dimension_name") or d.get("attribute_name"),
        )
        for d in raw
    ]
    return PointsBalanceResponse(
        total=sum(d.balance for d in dimensions),
        dimensions=dimensions,
    )


def _coerce_int(value) -> int | None:
    """Convierte a int tolerando None/strings; retorna None si no es numerico."""
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _is_credit(entry: dict) -> bool:
    """True si la entrada del ledger es un credito (puntos otorgados, no gastados)."""
    direction = str(entry.get("direction", "")).upper()
    if direction:
        return direction == "CREDIT"
    # Sin direction explicito: las redenciones son debitos, el resto se asume credito.
    return str(entry.get("source_type", "")).upper() != "REDEMPTION"


@router.get("/transactions", response_model=list[PointTransaction])
async def get_my_transactions(
    limit: int = Query(default=20, ge=1, le=100),
    current_user: User = Depends(get_current_user),
) -> list[PointTransaction]:
    """Historial de transacciones de puntos otorgados (creditos) del jugador en LSG-Core.

    Para cada transaccion muestra la dimension afectada, el saldo inicial, el saldo
    final y el total otorgado. Lee GET /points/ledger (todos los source_type) y filtra
    a creditos. Los nombres de campo del ledger no estan documentados; se parsean de
    forma tolerante (current_balance/balance_before, resulting_balance/balance_after).
    """
    try:
        raw = await lsg_core_client.get_points_ledger(
            player_id=current_user.id_players,
            token=current_user.lsg_token,
            source_type=None,
        )
    except (httpx.HTTPStatusError, httpx.RequestError) as exc:
        logger.error("Error obteniendo ledger del jugador %s: %s", current_user.id_players, exc)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail={"code": "CORE_SERVICE_ERROR", "message": "No se pudo obtener el historial de puntos"},
        )

    transactions: list[PointTransaction] = []
    for entry in raw:
        if not _is_credit(entry):
            continue
        dimension = entry.get("point_dimension_id", entry.get("id_point_dimension"))
        created = entry.get("created_at") or entry.get("occurred_at") or entry.get("timestamp")
        transactions.append(
            PointTransaction(
                point_dimension_id=_coerce_int(dimension) or 0,
                amount=_coerce_int(entry.get("amount")) or 0,
                balance_before=_coerce_int(entry.get("current_balance", entry.get("balance_before"))),
                balance_after=_coerce_int(entry.get("resulting_balance", entry.get("balance_after"))),
                source_type=entry.get("source_type"),
                reason=entry.get("reason"),
                created_at=str(created) if created is not None else None,
            )
        )

    return transactions[:limit]
