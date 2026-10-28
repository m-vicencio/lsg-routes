"""Modelo de ruta planificada."""

import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, Float, Integer, String
from sqlalchemy import JSON, Uuid
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.sql import func

from app.models import Base


class PlannedRoute(Base):
    """Ruta planificada por el usuario, trazada o generada por ORS."""

    __tablename__ = "planned_routes"

    id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True, default=uuid.uuid4)
    id_players: Mapped[int] = mapped_column(Integer, nullable=False, index=True)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    mode: Mapped[str] = mapped_column(String(50), nullable=False)  # walking | cycling | jogging | hiking
    # Geometria como [[lat, lon], ...] almacenada en JSON/JSONB
    geometry: Mapped[list] = mapped_column(JSON, nullable=False)
    planned_distance_m: Mapped[float | None] = mapped_column(Float, nullable=True)
    planned_duration_s: Mapped[int | None] = mapped_column(Integer, nullable=True)
    origin: Mapped[str] = mapped_column(String(20), nullable=False)  # DRAWN | ROUTED | FREEHAND
    source_payload: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    # Compartir rutas (feed comunitario)
    is_public: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False, index=True)
    copied_from_id: Mapped[uuid.UUID | None] = mapped_column(Uuid(as_uuid=True), nullable=True)  # procedencia
    shared_by_name: Mapped[str | None] = mapped_column(String(255), nullable=True)  # nombre del dueno que publico
    # Metros que se ocultan al inicio y al final en la version compartida. La geometria
    # guardada no se recorta: el recorte se aplica al responder a quien no es dueno.
    hide_start_m: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    hide_end_m: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
