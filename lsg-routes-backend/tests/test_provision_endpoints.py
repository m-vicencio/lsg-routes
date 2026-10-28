"""El payload de alta de sensor_endpoint solo puede usar campos que LSG-Core acepte.

El guion mandaba `data_type` y `unit`, que NO existen en
SensorEndpointCreateRequest (LSG-Core 1.2.3): Pydantic los descarta en silencio y
el endpoint queda creado sin semantica. Como la API no expone PUT/PATCH/DELETE de
sensor_endpoint, ese error no se puede deshacer: hay que crear otro endpoint.
"""

import importlib.util
from pathlib import Path

# Unicos campos de SensorEndpointCreateRequest (openapi 1.2.3 en vivo).
ALLOWED = {
    "name",
    "description",
    "url_endpoint",
    "token_parameters",
    "specific_parameters",
    "watch_parameters",
}

_spec = importlib.util.spec_from_file_location(
    "provision_endpoints",
    Path(__file__).resolve().parents[1] / "scripts" / "provision_endpoints.py",
)
provision = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(provision)


def test_payload_solo_usa_campos_aceptados():
    for mode, label in provision.WANTED.items():
        payload = {
            "name": f"LSG-Routes - {label} v1",
            "description": f"Ruta completada en modo {label.lower()} por LSG-Routes",
            "watch_parameters": provision._watch_parameters(mode),
        }
        assert set(payload) <= ALLOWED, f"{mode}: campos no aceptados por LSG-Core"


def test_watch_parameters_no_congela_valores_ajustables():
    """Lo declarado es inmutable: nada que se pueda tunear por .env puede entrar.

    Si alguien agrega el factor de puntos por km, quedaria grabado para siempre el
    valor de la maquina que corrio el guion, y no hay PUT para corregirlo.
    """
    tuneables = {"points_per_km", "free_roam_multiplier", "base_points"}
    for mode in provision.WANTED:
        assert not tuneables & set(provision._watch_parameters(mode))
