"""Schemas Pydantic para el log de actividad del perfil."""

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict


class ActivityLogResponse(BaseModel):
    """Una entrada del historial de actividad del jugador."""

    model_config = ConfigDict(from_attributes=True)

    id: int
    session_id: uuid.UUID
    route_id: uuid.UUID | None  # None = recorrido libre
    mode: str
    status: str
    distance_m: float
    duration_s: int
    avg_speed_kmh: float
    adherence_ratio: float | None
    awarded_points: int
    created_at: datetime


class ActivityStatsResponse(BaseModel):
    """Totales agregados del jugador para el encabezado del perfil."""

    total_activities: int
    total_distance_m: float
    total_points: int
    recent: list[ActivityLogResponse]


class PointDimensionBalance(BaseModel):
    """Saldo de puntos del jugador en una dimension de LSG-Core."""

    id_point_dimension: int
    balance: int
    # LSG-Core 1.2.3 enriquece el saldo con el nombre de la dimension; None si
    # una version anterior no lo trae y la app cae a "Dimension {id}".
    name: str | None = None


class PointsBalanceResponse(BaseModel):
    """Saldo de puntos del perfil del jugador en LSG-Core (todas las dimensiones)."""

    total: int
    dimensions: list[PointDimensionBalance]


class PointTransaction(BaseModel):
    """Un movimiento de credito de puntos del ledger de LSG-Core.

    balance_before/balance_after son opcionales porque el ledger de LSG-Core
    podria no incluirlos en todos los entornos.
    """

    point_dimension_id: int
    amount: int  # puntos otorgados en esta transaccion
    balance_before: int | None  # saldo inicial de la dimension
    balance_after: int | None  # saldo final de la dimension
    source_type: str | None  # SENSOR, ADJUST, OFFLINE_GAME, ...
    reason: str | None
    created_at: str | None
