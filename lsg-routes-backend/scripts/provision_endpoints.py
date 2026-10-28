"""Alta de los sensor_endpoint de LSG-Routes en LSG-Core (un endpoint por modo).

El mod de BG3 clasifica la actividad por `id_sensor_endpoint` (el unico campo
uniforme entre plugins), asi que cada modo necesita su propio endpoint.

Por defecto solo INSPECCIONA: lista los endpoints que ya existen e imprime las
lineas del .env. Crear endpoints toca un servicio compartido, asi que exige
--create explicito.

Uso:
    uv run python scripts/provision_endpoints.py <email> <password>
    uv run python scripts/provision_endpoints.py <email> <password> --create

    (login puntual de un researcher/admin: POST /sensors/{id}/endpoints exige
     ese rol. Los catalogos son de lectura para cualquier rol.)
"""

import asyncio
import sys

import httpx

from app.clients.lsg_auth import lsg_auth_client
from app.core.config import settings

# modo de actividad (los de RouteSession.mode) -> etiqueta en espanol.
# De aqui salen el nombre y la descripcion del endpoint, que quedan guardados en
# una base compartida: una sola fuente y sin mezclar idiomas.
WANTED = {
    "walking": "Caminata",
    "jogging": "Trote",
    "hiking": "Senderismo",
    "cycling": "Bicicleta",
}


def _watch_parameters(mode: str) -> dict:
    """Semantica declarada del endpoint, para que el consumidor no la hardcodee.

    Mismas llaves que los endpoints de EggSys (ver EggSys-LSG/docs/
    lsg-sensor-endpoints.md), con una ausencia deliberada: NO se declara el factor
    de puntos por km. LSG-Core no expone PUT/PATCH de sensor_endpoint, asi que lo
    que se escriba aqui es inmutable, y ese factor es un valor ajustable del .env
    (esta tuneado distinto en cada entorno). Declararlo seria grabar para siempre
    el numero que tuviera la maquina que corrio este guion. El endpoint es
    descriptivo, no normativo: las reglas de puntuacion viven en este backend.
    """
    return {
        "event_type": f"route.completed.{mode}",
        "parsed_value": "km recorridos en la ruta completada",
        "point_dimension": "Fisico",
        # attribute_id es el campo vigente (LSG-Core 1.2.3); point_dimension_id
        # queda por paridad con EggSys y como respaldo, igual que en points/adjust.
        "attribute_id": settings.lsg_routes_attribute_id,
        "point_dimension_id": settings.lsg_routes_attribute_id,
        "caps": None,
    }


async def _get(client: httpx.AsyncClient, token: str, path: str):
    response = await client.get(
        f"{settings.lsg_core_base_url}{path}",
        headers={"Authorization": f"Bearer {token}"},
        timeout=15.0,
    )
    response.raise_for_status()
    return response.json()


def _endpoint_id(endpoint: dict) -> int:
    return endpoint.get("id_sensor_endpoint") or endpoint.get("id") or 0


async def main() -> None:
    if len(sys.argv) < 3:
        sys.exit(
            "Uso: uv run python scripts/provision_endpoints.py <email> <password> [--create]"
        )
    email, password = sys.argv[1], sys.argv[2]
    create = "--create" in sys.argv[3:]

    login = await lsg_auth_client.login(email, password)
    token = login["access_token"]
    roles = (login.get("player") or {}).get("roles", [])
    print(f"Sesion iniciada como {email} (roles: {', '.join(roles) or 'desconocidos'})\n")

    sensor_id = settings.lsg_routes_sensor_id
    ids: dict[str, int] = {}

    async with httpx.AsyncClient() as client:
        endpoints = await _get(client, token, f"/sensors/{sensor_id}/endpoints")
        print(f"Endpoints actuales del sensor [{sensor_id}]:")
        for endpoint in endpoints or []:
            mark = "" if endpoint.get("watch_parameters") else "  ← sin watch_parameters"
            print(f"  [{_endpoint_id(endpoint)}] {endpoint.get('name', '')}{mark}")
        if not endpoints:
            print("  (ninguno)")

        # Solo cuenta como existente el que ya trae watch_parameters: LSG-Core 1.2.3
        # no expone PUT/PATCH/DELETE de sensor_endpoint, asi que un endpoint viejo e
        # incompleto no se puede reparar, solo reemplazar por uno nuevo. Los viejos
        # quedan como filas muertas hasta que el admin los borre a mano.
        by_name = {
            str(e.get("name", "")): e for e in endpoints or [] if e.get("watch_parameters")
        }
        for mode, label in WANTED.items():
            # La bicicleta no se aprovisiona mientras siga detras de la bandera.
            if mode == "cycling" and not settings.bike_activate:
                print(f"\n… omitiendo '{label}' (BIKE_ACTIVATE=false)")
                continue
            # El sufijo evita chocar con los endpoints de la primera tanda, creados
            # sin watch_parameters y que LSG-Core no deja ni editar ni borrar.
            name = f"LSG-Routes - {label} v1"
            existing = by_name.get(name)
            if existing is not None:
                ids[mode] = _endpoint_id(existing)
                continue
            if not create:
                print(f"\n… falta '{name}' (modo {mode}): re-ejecuta con --create")
                continue
            response = await client.post(
                f"{settings.lsg_core_base_url}/sensors/{sensor_id}/endpoints",
                json={
                    "name": name,
                    "description": f"Ruta completada en modo {label.lower()} por LSG-Routes",
                    "watch_parameters": _watch_parameters(mode),
                },
                headers={"Authorization": f"Bearer {token}"},
                timeout=15.0,
            )
            if response.status_code >= 400:
                print(f"\nError creando '{name}': {response.status_code} {response.text}")
                continue
            created = response.json()
            ids[mode] = _endpoint_id(created)
            print(f"\nCreado '{name}' -> id {ids[mode]}")

    print("\n--- Lineas para el .env ---")
    print(f"LSG_ROUTES_SENSOR_ID={sensor_id}")
    mapping = ", ".join(f'"{mode}": {ids[mode]}' for mode in WANTED if ids.get(mode))
    print("LSG_ROUTES_ENDPOINT_IDS={" + mapping + "}")

    missing = [m for m in WANTED if not ids.get(m)]
    if missing:
        print(f"\nAviso: sin ID para: {', '.join(missing)}. Esos modos caeran en")
        print("  LSG_ROUTES_SENSOR_ENDPOINT_ID (endpoint por defecto) si esta configurado.")


if __name__ == "__main__":
    asyncio.run(main())
