"""Configuracion de la aplicacion usando Pydantic Settings."""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Configuracion principal de LSG Routes."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # Base de datos
    database_url: str = "postgresql+asyncpg://lsg:lsg@localhost:5432/lsg_routes"

    # Servidor
    host: str = "0.0.0.0"
    port: int = 14141
    debug: bool = False

    # URLs de servicios LSG
    lsg_auth_base_url: str = "https://lsg.diinf.usach.cl/lsg-auth"
    lsg_core_base_url: str = "https://lsg.diinf.usach.cl/lsg-core-api"

    # Credenciales de la cuenta de servicio (rol admin, researcher o developer). Se usa SOLO para el
    # adjust de puntos y el alta del sensor por jugador; el resto de llamadas a
    # LSG-Core van con el token del propio usuario. Vacia -> fallback al token
    # del usuario (solo funciona si esa cuenta tiene uno de esos roles).
    lsg_service_email: str = ""
    lsg_service_password: str = ""

    # Configuracion del sensor LSG-Routes en LSG-Core
    lsg_routes_sensor_id: int = 6
    # Endpoint por defecto: se usa para los modos que no esten en el mapa.
    lsg_routes_sensor_endpoint_id: int = 0
    # Un endpoint por modo de actividad, para que el mod de BG3 clasifique por
    # id_sensor_endpoint. En el .env va como JSON:
    # LSG_ROUTES_ENDPOINT_IDS={"walking": 15, "jogging": 16, "hiking": 17}
    lsg_routes_endpoint_ids: dict[str, int] = {}
    # Atributo de puntos al que se acredita (Social=1, Fisico=2, Afectivo=3, Mental=4).
    lsg_routes_attribute_id: int = 2
    # [DEPRECADO en LSG-Core 1.2.3] Respaldo si lsg_routes_attribute_id queda en 0.
    lsg_routes_point_dimension_id: int = 1

    # Bicicleta detras de bandera: sin terreno donde probarla, no se ofrece como
    # actividad. En false no se crea su sensor_endpoint, no se puede crear una ruta
    # en ese modo y el recorrido libre trata esas velocidades como exceso.
    bike_activate: bool = False

    # Factores de puntos por km segun modo de actividad
    points_per_km_walking: int = 8
    points_per_km_cycling: int = 4
    points_per_km_jogging: int = 12
    points_per_km_hiking: int = 10

    # Los recorridos libres (sin ruta planificada) rinden una fraccion del factor
    # del modo detectado: planificar una ruta debe seguir siendo lo que mas conviene.
    points_free_roam_multiplier: float = 0.5

    # OpenRouteService
    ors_api_key: str = ""
    ors_base_url: str = "https://api.openrouteservice.org"

    # Open-Meteo (clima actual, sin API key)
    weather_base_url: str = "https://api.open-meteo.com"
    # MET Norway (fallback de clima, sin API key; exige User-Agent descriptivo)
    met_no_base_url: str = "https://api.met.no"
    weather_user_agent: str = "LSG-Routes/1.0 github.com/m-vicencio/lsg-routes"

    # Corredor de adherencia en metros
    adherence_corridor_m: float = 50.0

    # Umbrales de adherencia para rutas FREEHAND (mas laxa que DRAWN/ROUTED)
    adherence_full_freehand: float = 0.50
    adherence_partial_min_freehand: float = 0.15

    # Metadatos de actualizacion de la app movil (sideload)
    app_latest_version: str = "0.5.0"
    app_download_url: str = ""
    app_sha256: str = ""
    app_minimum_version: str = "0.1.0"

    # Bandera de desarrollo: invierte el orden de la sincronizacion con LSG-Core.
    # False: ingest webhook y despues adjust (orden de produccion, por defecto).
    # True: adjust y despues ingest, para probar la acreditacion sin el alta del
    # sensor completa. Se activa con DEV_ADJUST_BEFORE_INGEST=true en el .env; las
    # pruebas la fuerzan a False (tests/conftest.py).
    dev_adjust_before_ingest: bool = False

    def endpoint_id_for(self, mode: str) -> int:
        """id_sensor_endpoint del modo de actividad, o el endpoint por defecto."""
        return self.lsg_routes_endpoint_ids.get(mode) or self.lsg_routes_sensor_endpoint_id

    @property
    def configured_endpoint_ids(self) -> list[int]:
        """Endpoints a activar para un jugador al vincularlo, sin repetidos."""
        ids = list(self.lsg_routes_endpoint_ids.values())
        if not ids and self.lsg_routes_sensor_endpoint_id:
            ids = [self.lsg_routes_sensor_endpoint_id]
        return sorted({i for i in ids if i})


settings = Settings()
