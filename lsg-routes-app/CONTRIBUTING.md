# Guia de Contribucion - lsg-routes-app

## Convenciones de codigo

- **Comentarios y docstrings:** en castellano.
- **Identificadores (variables, funciones, clases):** en ingles.
- **Strings de UI:** siempre en `res/values/strings.xml` (es) y `res/values-en/strings.xml` (en).

## Mensajes de commit

Formato Conventional Commits (en ingles):

```
feat: add route detail screen with map preview
fix: correct haversine calculation for short tracks
chore: update MapLibre to 11.8.0
test: add ViewModel unit tests for LoginScreen
```

## Estructura de capas (Clean Architecture)

```
domain/       # Solo Kotlin puro, sin Android. Modelos e interfaces.
data/         # Implementaciones de repositorios, DTOs, DAOs.
feature/      # Composables + ViewModels por funcionalidad.
core/         # DI, red, auth, tema.
```

Regla: `domain` no debe importar `data` ni `feature`.

## Tests

```bash
./gradlew test          # unit tests (JVM)
./gradlew connectedAndroidTest  # instrumentation tests (requiere emulador)
```

## Configuracion de release

Ver `release.keystore.example.properties` para el proceso de firma.
Nunca commitear el keystore ni las contrasenas.

## BASE_URL en diferentes entornos

- **Emulador** (valor por defecto): `http://10.0.2.2:14141/`
- **Dispositivo en LAN**: `http://<IP-PC>:14141/`
- **Servidor desplegado**: `http://<host-del-servidor>:14141/`

Se define en `local.properties` (`BASE_URL=...`) o con `-PBASE_URL=...`; vale para debug y release.
Nunca commitear la URL del servidor real.

## Claves locales

La clave de MapTiler va en `local.properties`, que no se versiona:

```
MAPTILER_API_KEY=<clave>
```

En CI se puede pasar como `-PMAPTILER_API_KEY=...`. Nunca escribir claves en el codigo ni en
`gradle.properties`.
