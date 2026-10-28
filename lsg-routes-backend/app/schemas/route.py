"""Schemas Pydantic para rutas planificadas."""

import uuid
from datetime import datetime
from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, model_validator


class WeatherResponse(BaseModel):
    """Clima actual en el punto de inicio de una ruta (via Open-Meteo)."""

    latitude: float
    longitude: float
    temperature_c: float | None
    apparent_temperature_c: float | None
    humidity_pct: int | None
    precipitation_mm: float | None
    weather_code: int | None  # codigo WMO (0=despejado, 61=lluvia, etc.)
    wind_speed_kmh: float | None
    is_day: bool


class ActivityMode(str, Enum):
    """Modos de actividad soportados en v1."""

    WALKING = "walking"
    CYCLING = "cycling"
    JOGGING = "jogging"
    HIKING = "hiking"


class RouteOrigin(str, Enum):
    """Origen de la geometria de la ruta."""

    DRAWN = "DRAWN"
    ROUTED = "ROUTED"
    FREEHAND = "FREEHAND"  # trazado libre a mano: adherencia mas laxa


class CreateRouteRequest(BaseModel):
    """Cuerpo de la solicitud para crear una ruta planificada."""

    name: str
    mode: ActivityMode
    origin: RouteOrigin
    # Requerido cuando origin=DRAWN
    geometry: list[list[float]] | None = None
    # Requeridos cuando origin=ROUTED
    start_latlon: list[float] | None = None
    end_latlon: list[float] | None = None

    @model_validator(mode="after")
    def validate_by_origin(self) -> "CreateRouteRequest":
        """Verifica que los campos requeridos esten presentes segun el origen."""
        if self.origin in (RouteOrigin.DRAWN, RouteOrigin.FREEHAND):
            if not self.geometry or len(self.geometry) < 2:
                raise ValueError("geometry debe tener al menos 2 puntos para rutas DRAWN/FREEHAND")
        else:
            if not self.start_latlon or len(self.start_latlon) != 2:
                raise ValueError("start_latlon [lat, lon] es requerido para rutas ROUTED")
            if not self.end_latlon or len(self.end_latlon) != 2:
                raise ValueError("end_latlon [lat, lon] es requerido para rutas ROUTED")
        return self


class RouteResponse(BaseModel):
    """Respuesta con los datos de una ruta planificada."""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    id_players: int
    name: str
    mode: str
    geometry: list
    planned_distance_m: float | None
    planned_duration_s: int | None
    origin: str
    is_public: bool = False
    shared_by_name: str | None = None
    # Solo el dueno los ve con su valor real; a los demas se les responde 0
    hide_start_m: int = 0
    hide_end_m: int = 0
    created_at: datetime


class PublicRouteResponse(BaseModel):
    """Ruta publica para el feed comunitario (Explorar)."""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    name: str
    mode: str
    geometry: list
    planned_distance_m: float | None
    planned_duration_s: int | None
    origin: str
    shared_by_name: str | None
    is_mine: bool = False  # True si la ruta es del usuario que consulta
    created_at: datetime


class VisibilityUpdateRequest(BaseModel):
    """Cambia la visibilidad publica de una ruta y el recorte de extremos al compartir."""

    is_public: bool
    hide_start_m: int = Field(default=0, ge=0, le=2000)
    hide_end_m: int = Field(default=0, ge=0, le=2000)
