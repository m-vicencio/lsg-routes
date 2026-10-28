"""Modelos de la base de datos para LSG Routes."""

from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    """Clase base para todos los modelos SQLAlchemy."""

    pass


# Importar todos los modelos para registrarlos en Base.metadata.
# El orden importa: Base debe estar definido antes de que los modulos
# de modelos lo importen.
from app.models.user import User  # noqa: E402, F401
from app.models.route import PlannedRoute  # noqa: E402, F401
from app.models.session import (  # noqa: E402, F401
    AccelSample,
    IngestBatch,
    LSGSyncRecord,
    RouteSession,
    StepCount,
    TrackFix,
    ValidationResult,
)
