"""Configuracion de logging estructurado para LSG Routes."""

import logging
import sys


def setup_logging() -> None:
    """Configura el sistema de logging de la aplicacion."""
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
        handlers=[logging.StreamHandler(sys.stdout)],
    )
