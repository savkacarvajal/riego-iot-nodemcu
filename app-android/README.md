# 📱 Riego IoT — App Android

Cliente Android nativo (Kotlin) para el [firmware del NodeMCU](../firmware/riego_nodemcu). Muestra el estado de los sensores en vivo y permite cambiar de modo AUTOMÁTICO/MANUAL y encender/apagar la bomba, **solo dentro de la misma red WiFi** que el NodeMCU — no usa internet ni servidores externos.

## ⚠️ Antes de abrir el proyecto

Este proyecto se generó sin haber podido compilarlo (la máquina donde se creó no tiene Android Studio/JDK/Android SDK instalados), así que **falta el binario `gradle/wrapper/gradle-wrapper.jar`** (no se puede generar sin Gradle instalado). Antes de abrirlo en Android Studio:

1. Abre la carpeta `app-android/` como proyecto en Android Studio (**File → Open**).
2. Si pide "Gradle wrapper is missing" o similar, acepta que Android Studio lo regenere, o corre `gradle wrapper --gradle-version 8.7` una vez dentro de esta carpeta si tienes Gradle instalado por otro lado.
3. Deja que Android Studio sincronice (descarga el SDK/dependencias la primera vez).
4. El ícono de la app es un placeholder del sistema (`@android:drawable/sym_def_app_icon`) — puedes reemplazarlo con **Image Asset Studio** cuando quieras.

No hay forma de evitar este paso manual sin Gradle o Android Studio disponibles al generar el proyecto.

## ▶️ Uso

1. Sube el firmware al NodeMCU y anota la IP que imprime por el Monitor Serial (`http://192.168.x.x`).
2. Conecta el celular a la **misma red WiFi**.
3. Abre la app, escribe la IP (sin `http://`), el usuario y la clave definidos en `config.h` (`WEB_USER`/`WEB_PASS`), y toca **"Guardar y probar conexión"**.
4. La pantalla se actualiza sola cada 2 s. Los botones ENCENDER/APAGAR solo aparecen en modo MANUAL.

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
| GET | `/api/estado` | Estado actual (temperatura, humedad, modo, bomba) |
| POST | `/api/modo` | Alterna AUTOMÁTICO/MANUAL |
| POST | `/api/on` | Enciende la bomba (solo en MANUAL) |
| POST | `/api/off` | Apaga la bomba (solo en MANUAL) |

## 🔓 Por qué permite tráfico HTTP sin cifrar

El NodeMCU no tiene HTTPS — solo sirve HTTP plano en la red local. `network_security_config.xml` habilita cleartext a propósito, documentado ahí mismo. No expongas el NodeMCU a internet.
