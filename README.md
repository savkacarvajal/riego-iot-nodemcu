<div align="center">

# 🌱 Riego IoT con NodeMCU

**Riego automático (o manual) con sensores reales y control desde el celular**

![ESP8266](https://img.shields.io/badge/ESP8266-NodeMCU%201.0-00979D?logo=espressif&logoColor=white)
![Arduino](https://img.shields.io/badge/Arduino-IDE-00979D?logo=arduino&logoColor=white)
![DHT22](https://img.shields.io/badge/Sensor-DHT22-38bdf8)
![Firebase](https://img.shields.io/badge/Firebase-Realtime%20DB%20%2B%20Auth-FFCA28?logo=firebase&logoColor=black)
![Node.js](https://img.shields.io/badge/Bridge-Node.js-339933?logo=node.js&logoColor=white)
![Estado](https://img.shields.io/badge/estado-funcional-brightgreen)

Lee temperatura y humedad del aire (DHT22) y humedad del suelo, decide cuándo regar, y se controla en modo **automático** o **manual** desde una página web o una app Android — **desde cualquier lugar con internet**, no solo la WiFi del NodeMCU, con corte de seguridad para que la bomba nunca quede regando sola.

<p align="center">
  <img src="app-android/docs/login.png" alt="Login de la app Android" width="260">
  <img src="app-android/docs/dashboard.png" alt="Dashboard de la app Android" width="260">
</p>

</div>

---

## 📋 Índice

- [✨ Qué hace](#-qué-hace)
- [🧰 Materiales](#-materiales)
- [🔌 Conexión](#-conexión)
- [🚰 Bomba real](#-bomba-real)
- [⚙️ Instalación del firmware](#️-instalación-del-firmware)
- [🎚️ Calibración del sensor de suelo](#️-calibración-del-sensor-de-suelo)
- [🧠 Cómo funciona](#-cómo-funciona)
- [☁️ Arquitectura en la nube](#️-arquitectura-en-la-nube)
- [🔥 Configurar Firebase](#-configurar-firebase)
- [📡 API JSON del NodeMCU](#-api-json-del-nodemcu)
- [🖥️ Panel web](#️-panel-web)
- [📱 App Android](#-app-android)
- [🔒 Seguridad](#-seguridad)
- [🗂️ Estructura](#️-estructura)

## ✨ Qué hace

- 🌡️ **Monitoreo en vivo** — temperatura y humedad del aire (DHT22) y humedad del suelo, refrescado cada 2 s.
- 🤖 **Modo automático** — riega cuando el suelo baja de un umbral y se apaga solo al subir de otro (histéresis, sin encendidos/apagados en cadena).
- 📱 **Modo manual** — botones ENCENDER / APAGAR desde el [panel web](#️-panel-web) o la [app Android](#-app-android), ambos protegidos con login (Firebase Authentication).
- ☁️ **Base de datos compartida** — web y app leen/escriben la misma [Firebase Realtime Database](#️-arquitectura-en-la-nube): historial persistente, control desde cualquier lugar.
- 🔔 **Notificaciones push** — la web y la app avisan cuando la bomba se enciende o se apaga, aunque estén cerradas.
- 📈 **Tendencia de humedad** — el panel web grafica la humedad del suelo en el tiempo, con tabla accesible como alternativa.
- 🛑 **Corte de seguridad** — la bomba nunca riega más de 15 s seguidos ni corre en seco si el sensor falla.
- 📶 **Resiliente sin WiFi** — si no hay red, el riego automático sigue funcionando; solo se pierde el control remoto.

> Estado: la salida de la bomba usa por defecto el **LED integrado** como "bomba virtual". La bomba real necesita una pieza extra (ver [Bomba real](#-bomba-real)).

## 🧰 Materiales

| Pieza | Uso |
|---|---|
| NodeMCU ESP8266 (ESP-12E) | Microcontrolador con WiFi |
| DHT22 | Temperatura y humedad del aire |
| Sensor de humedad de suelo (analógico) | Humedad del suelo |
| Mini bomba sumergible 3–6 V | Riego |
| Protoboard y cables | Conexiones |
| *(opcional)* BH1750 | Intensidad de luz (I2C) — si no está conectado, el firmware lo detecta al arrancar y simplemente omite esa lectura |
| *(para la bomba real)* transistor NPN + resistencia + diodo, relé o driver | Interruptor de la bomba |

> **Pendiente, no incluido:** un anemómetro (sensor de viento) requeriría un ADC externo por I2C (ej. ADS1115), porque el ESP8266 solo tiene un pin analógico (A0) y ya lo usa el sensor de suelo.

## 🔌 Conexión

| Componente | Pin del componente | Pin del NodeMCU |
|---|---|---|
| DHT22 | DAT | D5 |
| DHT22 | VCC | 3V3 |
| DHT22 | GND | GND |
| Sensor de suelo | AO | A0 |
| Sensor de suelo | VCC | 3V3 |
| Sensor de suelo | GND | GND |
| BH1750 (opcional) | SDA | D2 |
| BH1750 (opcional) | SCL | D1 |
| BH1750 (opcional) | VCC / GND | 3V3 / GND |
| Bomba virtual | LED integrado | (ya viene en la placa) |

Todos los GND van al mismo riel de GND (masa común).

> El DHT22 va en **D5** y no en D4, porque D4 (GPIO2) comparte pin con el LED azul de la placa.

## 🚰 Bomba real

La bomba consume unos 100–200 mA y un pin del NodeMCU entrega ~12 mA. **No la conectes directo a un pin**: puede dañar la placa. Necesitas un interruptor intermedio.

Con **transistor NPN** (2N2222, BC547 o BC337):

| Conexión | Destino |
|---|---|
| Cable rojo de la bomba | VIN (5 V) |
| Cable negro de la bomba | Colector del transistor |
| Emisor del transistor | GND |
| Base del transistor | Resistencia 330 Ω–1 kΩ → D1 |
| Diodo 1N4007 en paralelo con la bomba | Cátodo (raya) a VIN, ánodo al colector |

Luego, en `riego_nodemcu.ino` cambia:

```cpp
#define PIN_BOMBA             D1
#define BOMBA_ACTIVA_EN_HIGH  true
```

Con un módulo relé o un driver (L9110/L298N) el cambio de código es el mismo; solo cambia el cableado según el módulo.

Para probar la bomba suelta, sin código: rojo a VIN y negro a GND, **sumergida** (en seco se quema).

## ⚙️ Instalación del firmware

1. Arduino IDE → **Archivo → Preferencias** → en "Gestor de URLs adicionales" agrega:
   `http://arduino.esp8266.com/stable/package_esp8266com_index.json`
2. **Herramientas → Placa → Gestor de tarjetas**: instala **esp8266**. Elige **NodeMCU 1.0 (ESP-12E Module)**.
3. **Administrar bibliotecas**: instala **DHT sensor library**, **Adafruit Unified Sensor** y **BH1750** (esta última solo hace falta si vas a conectar el sensor de luz).
4. Copia `firmware/riego_nodemcu/config.h.example` como `config.h` y completa tu WiFi y la clave de la página.
5. Abre `firmware/riego_nodemcu/riego_nodemcu.ino`, elige el puerto y sube el código.
6. Abre el Monitor Serial a **115200**: verás la IP (`http://192.168.x.x`). Ábrela en el celular, en la misma red.

Si la red de la universidad bloquea la conexión, usa el hotspot del celular.

## 🎚️ Calibración del sensor de suelo

1. Con el sensor **al aire**, anota el valor `raw` del Monitor Serial → `RAW_SECO`.
2. Con el sensor **en un vaso con agua**, anota el `raw` → `RAW_HUMEDO`.
3. Ajusta `UMBRAL_RIEGO` (riega bajo ese %) y `UMBRAL_APAGA` (deja de regar sobre ese %) — estos dos también se pueden cambiar en caliente, sin reflashear, desde la app Android (panel **Procesamiento**) o llamando a `/api/umbrales`.

## 🧠 Cómo funciona

- **Automático:** si el suelo baja del umbral, enciende la bomba; la apaga al superar `UMBRAL_APAGA` (histéresis, para que no prenda y apague sin parar).
- **Manual:** botones ENCENDER / APAGAR en la página.
- **Seguridad:** la bomba se corta sola tras 15 s seguidos y espera 60 s antes de volver a regar. Evita que corra en seco o inunde la maceta si el sensor falla.
- **Sin WiFi:** el riego automático sigue funcionando; solo se pierde el control remoto.
- **NodeMCU:** sigue sirviendo su API local igual que siempre (usuario/clave de `config.h`, HTTP Basic Auth), pero ya nadie le habla directo salvo el [bridge](#️-arquitectura-en-la-nube) — ni la página web ni la app apuntan a su IP. Tras 5 intentos de login fallidos, el NodeMCU bloquea todo acceso por 1 minuto (mitiga fuerza bruta).
- **Página web y app:** piden correo y clave de **Firebase Authentication** (no las del NodeMCU) y leen/escriben directo en Firebase — funcionan desde cualquier red con internet. El [bridge](#️-arquitectura-en-la-nube) es el que de verdad conversa con el NodeMCU y mantiene todo sincronizado.

## ☁️ Arquitectura en la nube

El NodeMCU no tiene HTTPS (se evaluó agregar TLS/BearSSL y se descartó: en un ESP8266 con
~30 KB de RAM libre, una conexión TLS puede necesitar ~28 KB solo de buffers — riesgo real de
colgar el dispositivo). Por eso se agregó una pieza intermedia en vez de forzarlo:

```
NodeMCU  --HTTP local-->  bridge (Node.js)  --HTTPS-->  Firebase Realtime Database
                                                              ↑           ↑
                                                    Panel web (Hosting)   App Android
```

- **[`firmware/`](firmware/riego_nodemcu)** — sin cambios: sigue siendo la única fuente de
  verdad de los sensores y quien controla la bomba (la seguridad del riego no se delega a la nube).
- **[`bridge/`](bridge)** — corre en cualquier equipo de la misma red que el NodeMCU (el
  laptop durante una demo, o un Raspberry Pi si se deja permanente). Cada 2 s copia
  `/api/estado` y `/api/historial` del NodeMCU hacia Firebase, y reenvía al NodeMCU los
  comandos que la web/app dejan en Firebase (cambiar modo, encender/apagar, nuevos umbrales).
- **Firebase Realtime Database + Authentication** — la base compartida. El historial ahora
  persiste (antes vivía en RAM del ESP8266 y se perdía al reiniciar).
- **[`web/`](web)** y **[`app-android/`](app-android)** — leen/escriben directo en Firebase,
  con las mismas credenciales de Authentication; no necesitan estar en la WiFi del NodeMCU.

## 🔥 Configurar Firebase

Una sola vez, antes de usar el bridge, la web o la app:

1. Crea un proyecto en la [consola de Firebase](https://console.firebase.google.com/).
2. Habilita **Realtime Database** (elige la región y empieza en modo bloqueado).
3. Habilita **Authentication → Email/contraseña** y crea el usuario (correo/clave) que vas a
   usar para entrar a la web y a la app.
4. Genera las credenciales que necesita cada pieza (detalle en el README de cada carpeta):
   - `bridge/serviceAccountKey.json` (cuenta de servicio, para el bridge)
   - `web/js/firebase-config.js` y `web/js/firebase-config-sw.js` (config del SDK web)
   - `app-android/app/google-services.json` (config del SDK Android)
5. Para las notificaciones push: Configuración del proyecto → Cloud Messaging → Certificados
   push web → "Generate key pair" (la clave VAPID va en `web/js/firebase-config.js`). Android
   no necesita este paso, ya le llega vía `google-services.json`.

## 📡 API JSON del NodeMCU

El NodeMCU sigue exponiendo estos endpoints (protegidos con usuario/clave de `config.h`), pero
ahora el único cliente es el [bridge](#️-arquitectura-en-la-nube):

| Método | Ruta | Qué hace | Respuesta |
|---|---|---|---|
| GET | `/api/estado` | Estado actual | `{"tempC":21.4,"humAire":55,"humSuelo":38,"rawSuelo":612,"luxLuz":320,"modoAuto":true,"bombaOn":false}` |
| GET | `/api/historial` | Riegos de las últimas 24 h, más reciente primero | `[{"haceMin":18,"duracionS":12}, ...]` |
| GET | `/api/umbrales` | Umbrales actuales del modo automático | `{"umbralRiego":40,"umbralApaga":55}` |
| POST | `/api/umbrales` | Actualiza los umbrales (`umbralRiego`/`umbralApaga`, form-encoded) | mismo JSON de umbrales, o `400` si son inválidos |
| POST | `/api/modo` | Alterna AUTOMÁTICO/MANUAL | mismo JSON de estado, ya actualizado |
| POST | `/api/on` | Enciende la bomba (solo si está en MANUAL) | mismo JSON de estado |
| POST | `/api/off` | Apaga la bomba (solo si está en MANUAL) | mismo JSON de estado |

`tempC`/`humAire` llegan como `null` si el DHT22 aún no entrega una lectura válida. `luxLuz` llega como `null` si no se detectó un BH1750 al arrancar. El historial y los umbrales cambiados por API viven en RAM del NodeMCU (sin RTC ni flash): se pierden al reiniciar y vuelven a los valores de `riego_nodemcu.ino` — por eso el bridge los espeja en Firebase, que sí persiste.

## 🖥️ Panel web

Sitio estático (HTML/CSS/JS, sin build) en [`web/`](web), pensado para dejar una URL pública
(Firebase Hosting) que se pueda mostrar en la presentación. Lee/escribe Firebase igual que la
app. Ver [`web/README.md`](web/README.md) para configurarlo y desplegarlo.

## 📱 App Android

Cliente nativo en Kotlin (carpeta [`app-android/`](app-android/)) que muestra el mismo estado en vivo y los mismos controles que el panel web, pero como app instalada en el celular. Lee/escribe Firebase igual que la web — no depende de estar en la WiFi del NodeMCU.

Ver [`app-android/README.md`](app-android/README.md) para cómo configurarla, abrirla en Android Studio, compilarla e instalarla.

## 🔒 Seguridad

El login y el dashboard (web y app) están endurecidos siguiendo **OWASP Top 10 2021** y
**OWASP ASVS 5.0**: reglas de Realtime Database con mínimo privilegio y validación server-side,
Content-Security-Policy estricta, política de contraseñas sin composición forzada, salida de
datos siempre por `textContent` (nunca `innerHTML` con datos de la base), y `allowBackup="false"`
en Android. Detalle completo, control por control, en [`web/README.md`](web/README.md#seguridad-owasp-top-10-2021--asvs-50).

## 🗂️ Estructura

```
riego-iot-nodemcu/
├── firmware/riego_nodemcu/
│   ├── riego_nodemcu.ino
│   └── config.h.example      # copiar como config.h (no se sube a GitHub)
├── bridge/                    # puente Node.js: NodeMCU <-> Firebase — ver su propio README
├── web/                       # panel web (HTML/CSS/JS) — ver su propio README
├── app-android/               # app Android (Kotlin) — ver su propio README
├── README.md
└── .gitignore
```
