# Riego IoT con NodeMCU (ESP8266)

Sistema de riego con NodeMCU: lee temperatura y humedad del aire (DHT22) y la humedad del suelo, y decide cuándo regar. Se controla en modo **automático** o **manual** desde una página web en el celular.

> Estado: la salida de la bomba usa por defecto el **LED integrado** como "bomba virtual". La bomba real necesita una pieza extra (ver [Bomba real](#bomba-real)).

## Materiales

| Pieza | Uso |
|---|---|
| NodeMCU ESP8266 (ESP-12E) | Microcontrolador con WiFi |
| DHT22 | Temperatura y humedad del aire |
| Sensor de humedad de suelo (analógico) | Humedad del suelo |
| Mini bomba sumergible 3–6 V | Riego |
| Protoboard y cables | Conexiones |
| *(para la bomba real)* transistor NPN + resistencia + diodo, relé o driver | Interruptor de la bomba |

## Conexión

| Componente | Pin del componente | Pin del NodeMCU |
|---|---|---|
| DHT22 | DAT | D5 |
| DHT22 | VCC | 3V3 |
| DHT22 | GND | GND |
| Sensor de suelo | AO | A0 |
| Sensor de suelo | VCC | 3V3 |
| Sensor de suelo | GND | GND |
| Bomba virtual | LED integrado | (ya viene en la placa) |

Todos los GND van al mismo riel de GND (masa común).

> El DHT22 va en **D5** y no en D4, porque D4 (GPIO2) comparte pin con el LED azul de la placa.

## Bomba real

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

## Instalación

1. Arduino IDE → **Archivo → Preferencias** → en "Gestor de URLs adicionales" agrega:
   `http://arduino.esp8266.com/stable/package_esp8266com_index.json`
2. **Herramientas → Placa → Gestor de tarjetas**: instala **esp8266**. Elige **NodeMCU 1.0 (ESP-12E Module)**.
3. **Administrar bibliotecas**: instala **DHT sensor library** y **Adafruit Unified Sensor**.
4. Copia `firmware/riego_nodemcu/config.h.example` como `config.h` y completa tu WiFi y la clave de la página.
5. Abre `firmware/riego_nodemcu/riego_nodemcu.ino`, elige el puerto y sube el código.
6. Abre el Monitor Serial a **115200**: verás la IP (`http://192.168.x.x`). Ábrela en el celular, en la misma red.

Si la red de la universidad bloquea la conexión, usa el hotspot del celular.

## Calibración del sensor de suelo

1. Con el sensor **al aire**, anota el valor `raw` del Monitor Serial → `RAW_SECO`.
2. Con el sensor **en un vaso con agua**, anota el `raw` → `RAW_HUMEDO`.
3. Ajusta `UMBRAL_RIEGO` (riega bajo ese %) y `UMBRAL_APAGA` (deja de regar sobre ese %).

## Cómo funciona

- **Automático:** si el suelo baja del umbral, enciende la bomba; la apaga al superar `UMBRAL_APAGA` (histéresis, para que no prenda y apague sin parar).
- **Manual:** botones ENCENDER / APAGAR en la página.
- **Seguridad:** la bomba se corta sola tras 15 s seguidos y espera 60 s antes de volver a regar. Evita que corra en seco o inunde la maceta si el sensor falla.
- **Sin WiFi:** el riego automático sigue funcionando; solo se pierde la página.
- **Página web:** pide usuario y clave (definidos en `config.h`). Va sobre HTTP sin cifrar: úsala solo en una red local de confianza y no la expongas a internet.

## Estructura

```
riego-iot-nodemcu/
├── firmware/riego_nodemcu/
│   ├── riego_nodemcu.ino
│   └── config.h.example      # copiar como config.h (no se sube a GitHub)
├── README.md
└── .gitignore
```
