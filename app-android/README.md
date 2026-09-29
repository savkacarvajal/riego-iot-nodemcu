# 📱 Riego IoT — App Android

Cliente Android nativo (Kotlin) para el [firmware del NodeMCU](../firmware/riego_nodemcu). Muestra el estado de los sensores en vivo y permite cambiar de modo AUTOMÁTICO/MANUAL y encender/apagar la bomba, **solo dentro de la misma red WiFi** que el NodeMCU — no usa internet ni servidores externos.

<p align="center">
  <img src="docs/login.png" alt="Pantalla de login de la app" width="280">
</p>

<p align="center"><em>Captura real desde el emulador. El dashboard (paneles de Percepción/Procesamiento/Aplicación) todavía no tiene captura — falta conectar un NodeMCU real para probarlo de punta a punta.</em></p>

## ⚠️ Antes de abrir el proyecto

Confirmado: Android Studio sincroniza y compila este proyecto sin problema (probado en Android Studio 2026.1), aunque en el repo sigue faltando `gradle/wrapper/gradle-wrapper.jar` — Android Studio no lo regenera en disco, pero no lo necesita para sincronizar con su Gradle interno. Si más adelante compilas por línea de comandos (`./gradlew`) y falla por eso, corre `gradle wrapper --gradle-version 8.7` una vez dentro de esta carpeta.

1. Abre la carpeta `app-android/` como proyecto en Android Studio (**File → Open**).
2. Deja que sincronice (descarga el SDK/dependencias la primera vez).
3. El ícono de la app es un placeholder del sistema (`@android:drawable/sym_def_app_icon`) — puedes reemplazarlo con **Image Asset Studio** cuando quieras.

## ▶️ Uso

1. Sube el firmware al NodeMCU y anota la IP que imprime por el Monitor Serial (`http://192.168.x.x`).
2. Conecta el celular a la **misma red WiFi**.
3. Abre la app: primero pide login (IP sin `http://`, usuario y clave de `config.h` — `WEB_USER`/`WEB_PASS`). El botón **"Conectar"** prueba la conexión de verdad (llama a `/api/estado`) antes de dejarte entrar; si falla, muestra el error ahí mismo sin avanzar.
4. Ya dentro, el dashboard organiza todo en paneles plegables por capa (tócalos para abrir/cerrar): **Percepción** (sensores, abierto por defecto), **Procesamiento** (modo y control de la bomba) y **Aplicación** (registros de riego de las últimas 24 h). Arriba a la derecha, **"🔌 Cambiar conexión"** vuelve al login para apuntar a otro NodeMCU.
5. La pantalla se actualiza sola cada 2 s. Los botones ENCENDER/APAGAR solo aparecen en modo MANUAL.

## 🎨 Diseño

Fondo con degradado violeta y manchas de color difuminadas de verdad (`RenderEffect`, Android 12+; en versiones anteriores se ven como un resplandor suave sin desenfoque). Tarjetas tipo "glass" semi-transparentes con borde violeta sutil y esquinas redondeadas, usando `MaterialCardView` — sin librerías nuevas, ya viene con Material Components.

## 🧱 Stack

- Kotlin, Views + ViewBinding (sin Compose, para mantenerlo simple)
- OkHttp para las llamadas HTTP con Basic Auth
- Corrutinas (`lifecycleScope`) para el polling cada 2 s
- `SharedPreferences` para recordar IP/usuario/clave entre sesiones
- `minSdk 24`, sin dependencias de nube

## 🌐 Endpoints que consume

Los mismos que expone el firmware (ver [README principal](../README.md#-cómo-funciona)):

| Método | Ruta | Qué hace |
|---|---|---|
| GET | `/api/estado` | Estado actual (temperatura, humedad, suelo, luz, modo, bomba) |
| GET | `/api/historial` | Riegos de las últimas 24 h (relativo a `millis()`, se pierde al reiniciar) |
| POST | `/api/modo` | Alterna AUTOMÁTICO/MANUAL |
| POST | `/api/on` | Enciende la bomba (solo en MANUAL) |
| POST | `/api/off` | Apaga la bomba (solo en MANUAL) |

La tarjeta de luz solo aparece en la app si el NodeMCU detectó un BH1750 conectado al arrancar (`luxLuz` no es `null` en `/api/estado`); si no hay sensor, la tarjeta queda oculta sin romper nada.

## 🔓 Por qué permite tráfico HTTP sin cifrar

El NodeMCU no tiene HTTPS — solo sirve HTTP plano en la red local. `network_security_config.xml` habilita cleartext a propósito, documentado ahí mismo. No expongas el NodeMCU a internet.
