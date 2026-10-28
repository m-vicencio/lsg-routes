"""Calculo de adherencia entre la ruta planificada y el track GPS real.

Metodo (segun especificacion):
  1. Proyectar a EPSG:3857 (metros) para operaciones geometricas precisas.
  2. Buffear la ruta planificada por corridor_m metros.
  3. Intersectar el track con ese buffer.
  4. adherence_ratio = intersection.length / planned.length

Limitacion conocida: EPSG:3857 introduce distorsion a latitudes altas.
Para el area de operacion esperada (Santiago, Chile ~-33°) el error es <20%.
"""

from app.validators.common import FixData, haversine_m


def compute_planned_distance_m(geometry: list[list[float]]) -> float:
    """Calcula la distancia total de la geometria planificada en metros.

    Args:
        geometry: Lista [[lat, lon], ...] de la ruta planificada.

    Returns:
        Distancia total en metros.
    """
    return sum(
        haversine_m(geometry[i - 1][0], geometry[i - 1][1], geometry[i][0], geometry[i][1])
        for i in range(1, len(geometry))
    )


def compute_adherence(
    planned_geometry: list[list[float]],
    fixes: list[FixData],
    corridor_m: float = 50.0,
) -> dict:
    """Calcula la fraccion de la ruta planificada cubierta por el track GPS.

    Args:
        planned_geometry: Geometria de la ruta [[lat, lon], ...].
        fixes: Puntos GPS del track real.
        corridor_m: Radio del corredor en metros (default 50 m).

    Returns:
        dict con adherence_ratio [0, 1] y detalles.
    """
    from pyproj import Transformer
    from shapely.geometry import LineString
    from shapely.ops import transform

    if len(planned_geometry) < 2 or len(fixes) < 2:
        return {"adherence_ratio": 0.0, "details": {"reason": "insufficient_points"}}

    # Construir LineStrings en WGS84: shapely usa (lon, lat)
    planned_line_wgs = LineString([(p[1], p[0]) for p in planned_geometry])
    track_line_wgs = LineString([(f.lon, f.lat) for f in fixes])

    # Proyectar a EPSG:3857 para buffers en metros
    projector = Transformer.from_crs("EPSG:4326", "EPSG:3857", always_xy=True).transform
    planned_proj = transform(projector, planned_line_wgs)
    track_proj = transform(projector, track_line_wgs)

    if planned_proj.length == 0:
        return {"adherence_ratio": 0.0, "details": {"reason": "zero_length_route"}}

    # Buffer de la ruta planificada, intersectar con el track
    planned_buffer = planned_proj.buffer(corridor_m)
    track_in_corridor = track_proj.intersection(planned_buffer)

    raw_ratio = track_in_corridor.length / planned_proj.length
    adherence_ratio = min(1.0, raw_ratio)

    return {
        "adherence_ratio": round(adherence_ratio, 4),
        "details": {
            "track_in_corridor_m": round(track_in_corridor.length, 1),
            "planned_length_m": round(planned_proj.length, 1),
            "corridor_m": corridor_m,
        },
    }
