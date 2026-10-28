"""Modelo de usuario (cache del perfil LSG del jugador)."""

from datetime import datetime

from sqlalchemy import JSON, DateTime, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.sql import func

from app.models import Base


class User(Base):
    """Cache local del perfil del jugador obtenido de LSG-Auth y LSG-Core.

    El backend no almacena contrasenas. LSG-Auth es el dueno de la autenticacion.
    Si almacena el ultimo access_token del jugador (vida util 120 min), porque el
    retry_worker sincroniza con LSG-Core en nombre del participante sin nadie
    delante.
    """

    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    id_players: Mapped[int] = mapped_column(Integer, unique=True, nullable=False, index=True)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    email: Mapped[str] = mapped_column(String(255), unique=True, nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    last_sync_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    # Cache de {"<id_sensor_endpoint>": <id_players_sensor_endpoint>}: LSG asigna
    # un pseid distinto por endpoint, y hay un endpoint por modo de actividad.
    # Es solo cache; si se pierde se repuebla desde GET /sensors/players/{id}.
    players_sensor_endpoints: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    # Ultimo access_token de LSG-Auth validado para este jugador, para que el
    # retry_worker pueda ingestar en su nombre sin el usuario delante.
    lsg_token: Mapped[str | None] = mapped_column(Text, nullable=True)
    lsg_token_expires_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
