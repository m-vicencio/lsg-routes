"""Modelos de sesion de ruta, telemetria y sincronizacion con LSG-Core."""

import uuid
from datetime import datetime

from sqlalchemy import BigInteger, Boolean, DateTime, Float, Integer, String
from sqlalchemy import JSON, Uuid

# SQLite no soporta AUTOINCREMENT en BIGINT; se usa Integer en tests y BigInteger en Postgres
_PK_TYPE = BigInteger().with_variant(Integer(), "sqlite")
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.sql import func

from app.models import Base


class RouteSession(Base):
    """Sesion de ejecucion de una ruta planificada.

    route_id NULL identifica un recorrido libre: sin ruta de referencia, sin
    adherencia, y con puntos calculados por tramos segun la velocidad observada.
    """

    __tablename__ = "route_sessions"

    id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True, default=uuid.uuid4)
    route_id: Mapped[uuid.UUID | None] = mapped_column(Uuid(as_uuid=True), nullable=True, index=True)
    id_players: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    status: Mapped[str] = mapped_column(String(50), nullable=False, default="STARTED")
    started_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    ended_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    mode: Mapped[str] = mapped_column(String(50), nullable=False)
    client_session_uuid: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), unique=True, nullable=False, index=True
    )


class TrackFix(Base):
    """Punto GPS capturado durante una sesion activa."""

    __tablename__ = "track_fixes"

    id: Mapped[int] = mapped_column(_PK_TYPE, primary_key=True, autoincrement=True)
    session_id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), nullable=False, index=True)
    lat: Mapped[float] = mapped_column(Float, nullable=False)
    lon: Mapped[float] = mapped_column(Float, nullable=False)
    accuracy_m: Mapped[float | None] = mapped_column(Float, nullable=True)
    altitude_m: Mapped[float | None] = mapped_column(Float, nullable=True)
    speed_mps: Mapped[float | None] = mapped_column(Float, nullable=True)
    bearing_deg: Mapped[float | None] = mapped_column(Float, nullable=True)
    is_from_mock_provider: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    captured_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    client_seq: Mapped[int] = mapped_column(Integer, nullable=False)


class AccelSample(Base):
    """Bucket de un segundo de datos de acelerometro."""

    __tablename__ = "accel_samples"

    id: Mapped[int] = mapped_column(_PK_TYPE, primary_key=True, autoincrement=True)
    session_id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), nullable=False, index=True)
    magnitude_mean: Mapped[float] = mapped_column(Float, nullable=False)
    magnitude_variance: Mapped[float] = mapped_column(Float, nullable=False)
    bucket_started_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    bucket_seconds: Mapped[int] = mapped_column(Integer, nullable=False)


class StepCount(Base):
    """Delta de pasos capturado durante una sesion."""

    __tablename__ = "step_counts"

    id: Mapped[int] = mapped_column(_PK_TYPE, primary_key=True, autoincrement=True)
    session_id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), nullable=False, index=True)
    steps_delta: Mapped[int] = mapped_column(Integer, nullable=False)
    captured_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class IngestBatch(Base):
    """Registro de idempotencia para un lote de datos enviado por la app."""

    __tablename__ = "ingest_batches"

    id: Mapped[int] = mapped_column(_PK_TYPE, primary_key=True, autoincrement=True)
    session_id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), nullable=False, index=True)
    client_ref: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), unique=True, nullable=False, index=True
    )
    received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    processed: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)


class ValidationResult(Base):
    """Resultado de un validador individual del chain anti-cheat."""

    __tablename__ = "validation_results"

    id: Mapped[int] = mapped_column(_PK_TYPE, primary_key=True, autoincrement=True)
    session_id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), nullable=False, index=True)
    validator_name: Mapped[str] = mapped_column(String(100), nullable=False)
    passed: Mapped[bool] = mapped_column(Boolean, nullable=False)
    details: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    computed_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )


class ActivityLog(Base):
    """Registro de actividad de una sesion finalizada para mostrar en el perfil.

    LSG-Core solo guarda puntos + razon en su ledger; este log local conserva
    distancia, velocidad y puntos para que la app pueda mostrar el historial.
    """

    __tablename__ = "activity_logs"

    id: Mapped[int] = mapped_column(_PK_TYPE, primary_key=True, autoincrement=True)
    id_players: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    session_id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), unique=True, nullable=False, index=True
    )
    route_id: Mapped[uuid.UUID | None] = mapped_column(Uuid(as_uuid=True), nullable=True)
    mode: Mapped[str] = mapped_column(String(50), nullable=False)
    status: Mapped[str] = mapped_column(String(50), nullable=False)  # VALIDATED | REJECTED | ...
    distance_m: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    duration_s: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    avg_speed_kmh: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    adherence_ratio: Mapped[float | None] = mapped_column(Float, nullable=True)
    awarded_points: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )


class LSGSyncRecord(Base):
    """Registro de sincronizacion con LSG-Core (ingest webhook + adjust points)."""

    __tablename__ = "lsg_sync_records"

    id: Mapped[int] = mapped_column(_PK_TYPE, primary_key=True, autoincrement=True)
    session_id: Mapped[uuid.UUID] = mapped_column(
        Uuid(as_uuid=True), unique=True, nullable=False, index=True
    )
    sensor_event_id: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    # source_ref retornado por POST /players/{id}/points/adjust
    points_ledger_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    awarded_points: Mapped[int | None] = mapped_column(Integer, nullable=True)
    point_dimension_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    sync_status: Mapped[str] = mapped_column(String(50), nullable=False)
    last_attempt_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    last_error: Mapped[dict | None] = mapped_column(JSON, nullable=True)
