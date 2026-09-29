# Web — panel de riego

Panel web estático (HTML/CSS/JS, sin build ni framework) que muestra el estado del riego
en vivo y permite controlarlo, leyendo/escribiendo directo en Firebase Realtime Database.
No habla con el NodeMCU: eso lo hace el [bridge](../bridge).

Sitio de varias páginas (no un solo dashboard largo), cada una con su propio script y su
barra de navegación compartida:

| Página | Qué muestra |
|---|---|
| `login.html` | Ingreso (Firebase Authentication) |
| `index.html` | Percepción — sensores en vivo |
| `procesamiento.html` | Modo, control de la bomba y umbrales |
| `aplicacion.html` | Notificaciones, tendencia de humedad e historial de riego |

Las tres páginas post-login comparten lógica común (guardia de sesión, botón de salir,
estado de conexión, envío de comandos) desde `js/comun.js`.

## Configuración

1. Copia `js/firebase-config.js.example` a `js/firebase-config.js` y pega los datos de tu
   proyecto (Firebase Console → Configuración del proyecto → Tus apps → agregar app Web),
   incluida la `vapidKey` (Cloud Messaging → Certificados push web → "Generate key pair") —
   se usa para las notificaciones.
2. Copia también `js/firebase-config-sw.js.example` a `js/firebase-config-sw.js` con los
   mismos datos (el service worker no es un módulo ES y no puede hacer `import`, por eso
   necesita su propia copia en otro formato).
3. (Opcional, solo si vas a desplegar) copia `.firebaserc.example` a `.firebaserc` y pon el
   id de tu proyecto.
4. En Firebase Console → Authentication → Email/contraseña, habilítalo y crea el usuario
   (correo/clave) que vas a usar para entrar al panel.

## Funciones

- **Gráfico de tendencia**: `/lecturas` (una muestra de humedad de suelo cada minuto, la
  escribe el [bridge](../bridge)) se grafica como línea con área, con tooltip al pasar el
  mouse. Si prefieres los números exactos, "Ver datos como tabla" debajo del gráfico los
  lista — también es lo que usa un lector de pantalla.
- **Notificaciones push**: el botón "🔔 Activar notificaciones" pide permiso al navegador,
  guarda el token en `/dispositivos` y desde ahí el bridge le manda un push cuando la bomba
  se enciende o se apaga — funciona incluso con la pestaña cerrada, vía el service worker
  (`sw.js`).
- **Instalable (PWA)**: `manifest.json` + `sw.js` permiten "Instalar" el panel desde el menú
  del navegador; también cachea lo esencial para tolerar un wifi de aula que se corta un
  momento durante la demo.
- **Accesibilidad**: los anillos de humedad son `role="meter"` con su valor actual, el
  estado de conexión y los mensajes de error son regiones `aria-live`, y los iconos
  decorativos llevan `aria-hidden`.

## Probar en local

Como usa módulos ES (`type="module"`), no se puede abrir `index.html` directo con
doble clic (el navegador bloquea `import` sobre `file://`). Sirve la carpeta con cualquier
servidor estático, por ejemplo:

```
npx serve web
```

o, si ya tienes Firebase CLI instalado:

```
cd web
firebase serve
```

## Desplegar (URL pública para la demo)

```
npm install -g firebase-tools   # una sola vez
cd web
firebase login
firebase deploy --only hosting
```

Al terminar, la consola imprime la URL pública (algo como
`https://tu-proyecto.web.app`) — esa es la que muestras en la presentación.

## Seguridad (OWASP Top 10 2021 + ASVS 5.0)

Controles aplicados, mapeados a la categoría que mitigan:

| Control | Dónde | Categoría |
|---|---|---|
| Reglas de Realtime Database con mínimo privilegio: los clientes (web/app) solo pueden **leer** `estado`/`historial`/`umbrales` y **escribir únicamente** en `comandos`; solo el bridge (Admin SDK, que ignora las reglas) escribe el resto | `../bridge`, reglas en Firebase Console | A01 Broken Access Control · ASVS V4 |
| Validación server-side de cada campo de `comandos` (tipos, rango 0-100, valores permitidos "on"/"off", rechazo de campos no esperados con `$otro`) — no se confía solo en la validación del cliente | reglas de Realtime Database | A03 Injection / Input Validation · ASVS V5 |
| `Content-Security-Policy` estricta sin `unsafe-inline` (todos los `style=""` inline se movieron a clases CSS), más `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy` y `Strict-Transport-Security` | `firebase.json` (headers HTTP reales) + `<meta>` en cada página (fallback para dev local) | A05 Security Misconfiguration · ASVS V14 |
| Salida de datos de Firebase renderizada con `textContent`/DOM seguro, nunca `innerHTML` con datos de la base (historial, estado de la bomba) | `js/procesamiento.js`, `js/aplicacion.js` | A03 Injection · ASVS V5 (output encoding) |
| Autenticación con Firebase Authentication (Email/contraseña): mensajes de error genéricos ("correo o clave incorrectos", nunca cuál de los dos falló), sin credenciales guardadas en `localStorage` propio | `js/login.js` | A07 Identification and Authentication Failures · ASVS V2 |
| Política de contraseñas exigida en el proyecto: longitud mínima 10, **sin** reglas de composición forzada (mayúscula/especial/número) — ASVS 5.0 prioriza longitud sobre complejidad forzada, siguiendo la misma línea que NIST 800-63B | Firebase Console → Authentication → Política de contraseñas | A07 · ASVS V2.1 |
| SDK de Firebase cargado desde una versión fijada (`10.13.0`), no `@latest`, para evitar que una actualización de un tercero cambie el comportamiento sin control explícito | `js/firebase.js` | A06 Vulnerable and Outdated Components · ASVS V14.2 |
| Todo el tráfico va sobre HTTPS (Firebase Hosting lo fuerza) y las credenciales sensibles (`firebase-config.js`, `firebase-config-sw.js`, `serviceAccountKey.json`) nunca se suben a git | `.gitignore` en cada carpeta | A02 Cryptographic Failures · ASVS V9 |
| `/dispositivos` (tokens de notificación) es de solo escritura para el cliente dueño de su propio token — nadie puede leer los tokens de otros dispositivos, y las reglas validan que solo se escriban los dos campos esperados | reglas de Realtime Database | A01 Broken Access Control · ASVS V4 |
| Las notificaciones push nunca llevan datos sensibles en el payload (solo "bomba encendida/apagada" y la humedad, que ya es visible para cualquiera con la app) | `bridge/index.js` → `notificar()` | A09 (higiene de datos en logs/eventos) |

**Fuera de alcance a propósito** (dejarlo explícito, no es un olvido): logging/monitoreo centralizado (A09) no aplica a un sitio estático sin backend propio — Firebase ofrece sus propios logs de Auth/Database en la consola; Firebase App Check (verificar que las peticiones vienen de la app real, no solo de un usuario autenticado) quedó como mejora futura, no crítica para un proyecto de un solo usuario demo.
