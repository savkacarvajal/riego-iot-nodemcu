# 📱 Riego IoT — App Android

Cliente Android nativo (Kotlin) para el [firmware del NodeMCU](../firmware/riego_nodemcu). Muestra el estado de los sensores en vivo y permite cambiar de modo AUTOMÁTICO/MANUAL y encender/apagar la bomba, **solo dentro de la misma red WiFi** que el NodeMCU — no usa internet ni servidores externos.

<p align="center">
  <img src="docs/login.png" alt="Pantalla de login de la app" width="270">
  <img src="docs/dashboard.png" alt="Dashboard con paneles plegables" width="270">
</p>

<p align="center"><em>Capturas reales desde el emulador. El dashboard muestra "--" porque todavía no hay un NodeMCU real conectado enviando datos.</em></p>

## ⚠️ Antes de abrir el proyecto

Confirmado: Android Studio sincroniza y compila este proyecto sin problema (probado en Android Studio 2026.1), aunque en el repo sigue faltando `gradle/wrapper/gradle-wrapper.jar` — Android Studio no lo regenera en disco, pero no lo necesita para sincronizar con su Gradle interno. Si más adelante compilas por línea de comandos (`./gradlew`) y falla por eso, corre `gradle wrapper --gradle-version 8.7` una vez dentro de esta carpeta.

1. Abre la carpeta `app-android/` como proyecto en Android Studio (**File → Open**).
2. Deja que sincronice (descarga el SDK/dependencias la primera vez).

## ▶️ Uso

1. Sube el firmware al NodeMCU y anota la IP que imprime por el Monitor Serial (`http://192.168.x.x`).
2. Conecta el celular a la **misma red WiFi**.
3. Abre la app: primero pide login (IP sin `http://`, usuario y clave de `config.h` — `WEB_USER`/`WEB_PASS`). El botón **"Conectar"** prueba la conexión de verdad (llama a `/api/estado`) antes de dejarte entrar; si falla, muestra el error ahí mismo sin avanzar.
4. Ya dentro, el dashboard organiza todo en paneles plegables por capa (tócalos para abrir/cerrar): **Percepción** (sensores, abierto por defecto), **Procesamiento** (modo, control de la bomba y umbrales de riego automático) y **Aplicación** (registros de riego de las últimas 24 h). Arriba a la derecha, **"🔌 Cambiar conexión"** vuelve al login para apuntar a otro NodeMCU.
5. Dentro de **Procesamiento**, "Riega bajo %" y "Apaga sobre %" ajustan los umbrales del modo automático sin reflashear el NodeMCU — **"Guardar umbrales"** los manda al firmware y confirma. Se pierden al reiniciar el NodeMCU (viven en RAM, igual que el resto de la calibración).
6. La pantalla se actualiza sola cada 2 s. Los botones ENCENDER/APAGAR solo aparecen en modo MANUAL.

## 🎨 Diseño

Fondo con degradado violeta y manchas de color difuminadas de verdad (`RenderEffect`, Android 12+; en versiones anteriores se ven como un resplandor suave sin desenfoque). Tarjetas tipo "glass" semi-transparentes con borde violeta sutil y esquinas redondeadas, usando `MaterialCardView` — sin librerías nuevas, ya viene con Material Components.

Ícono propio (`ic_launcher`/`ic_launcher_round`): un brote de dos hojas sobre el mismo degradado violeta, como adaptive icon (`mipmap-anydpi-v26`, Android 8+) con respaldo en PNG para API 24-25.

## 🧱 Stack

- Kotlin, Views + ViewBinding (sin Compose, para mantenerlo simple)
- OkHttp para las llamadas HTTP con Basic Auth
- Corrutinas (`lifecycleScope`) para el polling cada 2 s
- `SharedPreferences` para IP/usuario (no son secretos); la clave se guarda cifrada con Android Keystore (ver [Seguridad](#-seguridad))
- `minSdk 24`, sin dependencias de nube

## 🌐 Endpoints que consume

Los mismos que expone el firmware (ver [README principal](../README.md#-cómo-funciona)):

| Método | Ruta | Qué hace |
|---|---|---|
| GET | `/api/estado` | Estado actual (temperatura, humedad, suelo, luz, modo, bomba) |
| GET | `/api/historial` | Riegos de las últimas 24 h (relativo a `millis()`, se pierde al reiniciar) |
| GET | `/api/umbrales` | Umbrales actuales del modo automático |
| POST | `/api/umbrales` | Actualiza los umbrales (`umbralRiego`/`umbralApaga`, form-encoded; rechaza valores fuera de 0-100 o donde apagar ≤ regar) |
| POST | `/api/modo` | Alterna AUTOMÁTICO/MANUAL |
| POST | `/api/on` | Enciende la bomba (solo en MANUAL) |
| POST | `/api/off` | Apaga la bomba (solo en MANUAL) |

La tarjeta de luz solo aparece en la app si el NodeMCU detectó un BH1750 conectado al arrancar (`luxLuz` no es `null` en `/api/estado`); si no hay sensor, la tarjeta queda oculta sin romper nada.

## 🔒 Seguridad

Medidas reales, calibradas a lo que es este dispositivo (un NodeMCU casero en una sola red WiFi de confianza, un solo usuario) — no una réplica de arquitecturas empresariales:

- **Clave cifrada en reposo**: la clave del NodeMCU se cifra con AES-256-GCM usando una llave que nunca sale del Android Keystore (respaldada por hardware cuando el equipo lo soporta) — ver [`SeguridadLocal.kt`](app/src/main/java/com/savkacarvajal/riegoiot/SeguridadLocal.kt). IP y usuario no son secretos, se guardan en texto plano. Si desinstalas la app, la llave del Keystore se pierde junto con ella — es lo esperado, no un bug.
- **Bloqueo por fuerza bruta**: el firmware bloquea todo acceso durante 1 minuto tras 5 intentos de login fallidos seguidos (ver `autorizado()` en el `.ino`). El contador es global, no por IP: es una limitación consciente, correcta para un dispositivo de un solo hogar.
- **Por qué el tráfico va sin cifrar (HTTP, no HTTPS)**: el NodeMCU no tiene HTTPS — evaluamos agregar TLS (BearSSL) y lo descartamos: en un ESP8266 con ~30 KB de RAM libre, una conexión TLS puede necesitar ~28 KB solo de buffers, con riesgo real de que el dispositivo se cuelgue. `network_security_config.xml` habilita cleartext a propósito, documentado ahí mismo. Esto significa que alguien con acceso a tu WiFi y un sniffer podría ver la clave viajar (en Basic Auth, base64 sin cifrar) — por eso el diseño exige una red doméstica de confianza y **nunca** exponer el NodeMCU a internet.
