# Modelo de Seguridad - lsg-routes-app

## Principio fundamental

La app movil es un recolector de evidencia cruda. **No computa puntos.**
El backend LSG Routes es el unico responsable de validar la evidencia y acreditar puntos.
Lo que la app calcula (distancia, puntos estimados del recorrido libre) es solo para mostrarlo.

## Protecciones implementadas

### Almacenamiento del JWT y de las credenciales
El token de acceso se almacena en `EncryptedSharedPreferences` (AES256-SIV/GCM).
En el mismo archivo cifrado se guardan el correo y la contrasena del usuario para el
autorrelleno del login. No se almacena nada de esto en SharedPreferences ni archivos en
texto plano, y `allowBackup="false"` evita que salga del dispositivo en un respaldo.

### Renovacion del token
`TokenRefresher` renueva el JWT cuando le quedan menos de 15 minutos (al volver a primer
plano, cada 10 minutos durante un recorrido y antes de subir). `AuthAuthenticator` cubre los
401 que se escapen y corta el bucle si el propio refresh responde 401.

### Deteccion de mock location
Cada fix GPS incluye el campo `isFromMockProvider` (proveniente de Android Location API).
El backend rechaza sesiones con cualquier fix de origen simulado.

### Claves fuera del repositorio
La clave de MapTiler se lee de `local.properties` (ignorado por git) y llega al codigo via
`BuildConfig`. Ninguna clave debe escribirse en el codigo ni en `gradle.properties`, que si se
versiona.

## Que NO protege la app

- **Transporte sin cifrar:** el backend se sirve hoy por HTTP plano (`usesCleartextTraffic="true"`).
  El login (correo y contrasena) y el JWT viajan sin cifrar. Pasar `BASE_URL` a HTTPS es el
  cambio pendiente de mayor impacto.
- **Sin certificate pinning:** no hay pin configurado. Al pasar a HTTPS conviene fijar la clave
  publica (con un pin de respaldo), porque el certificado de Let's Encrypt rota cada 60-90 dias.
- **Sin verificacion de integridad del dispositivo:** Play Integrity no esta implementado ni en
  la app ni en el backend.
- **Dispositivos rooteados con Magisk:** pueden falsificar `isFromMockProvider = false`.
- **GPS hardware spoofers:** la correlacion con acelerometro y pasos ayuda pero no es infalible.

## Permisos requeridos y por que

| Permiso                       | Razon                                                        |
| ----------------------------- | ------------------------------------------------------------ |
| `ACCESS_FINE_LOCATION`        | Seguimiento GPS de alta precision                            |
| `FOREGROUND_SERVICE_LOCATION` | Continuar tracking con app en segundo plano                  |
| `POST_NOTIFICATIONS`          | Notificacion persistente del servicio de tracking            |
| `ACTIVITY_RECOGNITION`        | Acceso al pedometro para correlacion de pasos                |
| `WAKE_LOCK`                   | Mantener el acelerometro muestreando con la pantalla apagada |
| `INTERNET`                    | Comunicacion con el backend LSG Routes                       |

## Reporte de vulnerabilidades

Contactar al equipo LSG-DIINF-USACH. No publicar en issues publicos.
