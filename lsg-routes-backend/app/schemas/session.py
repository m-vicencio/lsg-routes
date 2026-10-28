"""Schemas Pydantic para sesiones de ruta."""

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict


class CreateSessionRequest(BaseModel):
    """Cuerpo de la solicitud para iniciar una sesion de ruta.

    route_id ausente = recorrido libre (sin ruta planificada). id es opcional: si
    la app lo manda, la sesion offline-first ya arranco localmente con ese id y el
    backend lo adopta como propio para no tener que remapear la telemetria ya
    guardada en el dispositivo. Si no viene, el backend genera uno (compatibilidad
    con clientes viejos).
    """

    id: uuid.UUID | None = None
    route_id: uuid.UUID | None = None
    client_session_uuid: uuid.UUID


class SessionResponse(BaseModel):
    """Respuesta con los datos de una sesion de ruta."""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    route_id: uuid.UUID | None
    id_players: int
    status: str
    started_at: datetime
    ended_at: datetime | None
    mode: str
    client_session_uuid: uuid.UUID


# Schemas para el endpoint de ingest /sync

class FixInput(BaseModel):
    """Punto GPS enviado por la app."""

    client_seq: int
    lat: float
    lon: float
    accuracy_m: float | None = None
    altitude_m: float | None = None
    speed_mps: float | None = None
    bearing_deg: float | None = None
    is_from_mock_provider: bool = False
    captured_at: datetime


class AccelBucketInput(BaseModel):
    """Bucket de acelerometro enviado por la app."""

    bucket_started_at: datetime
    bucket_seconds: int
    magnitude_mean: float
    magnitude_variance: float


class StepCountInput(BaseModel):
    """Delta de pasos enviado por la app."""

    steps_delta: int
    captured_at: datetime


class SyncBatchRequest(BaseModel):
    """Lote de telemetria enviado por la app con idempotencia por client_ref."""

    client_ref: uuid.UUID
    fixes: list[FixInput] = []
    accel_buckets: list[AccelBucketInput] = []
    step_counts: list[StepCountInput] = []


class SyncBatchResponse(BaseModel):
    """Respuesta 207 al lote de telemetria."""

    client_ref: uuid.UUID
    status: str        # PROCESSED | DUPLICATE
    fixes_stored: int = 0
    fixes_skipped: int = 0
    accel_buckets_stored: int = 0
    step_counts_stored: int = 0


# Schema para el resumen de la sesion

class SessionSummaryResponse(BaseModel):
    """Resumen visible para el usuario de una sesion finalizada."""

    session_id: uuid.UUID
    status: str
    mode: str
    awarded_points: int | None
    adherence_ratio: float | None
    validators: dict[str, str]    # nombre -> "OK" | "FAILED" | "N/A"
    sync_status: str | None
    started_at: datetime
    ended_at: datetime | None


# Schema para la visualizacion del track recorrido

class SessionTrackResponse(BaseModel):
    """Geometria para dibujar el recorrido de una sesion en un mapa.

    planned_geometry y fixes son listas de pares [lat, lon]. fixes es el track GPS
    real (vacio si la sesion no registro puntos); planned_geometry es la ruta guia.
    """

    session_id: uuid.UUID
    mode: str
    status: str
    planned_geometry: list[list[float]]
    fixes: list[list[float]]
