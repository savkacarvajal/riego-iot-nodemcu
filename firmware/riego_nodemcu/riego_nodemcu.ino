/*
  Riego IoT con NodeMCU (ESP8266)
  - Lee temperatura y humedad del aire (DHT22)
  - Lee humedad del suelo (sensor analogico en A0)
  - Lee intensidad de luz (BH1750, I2C) — opcional, se autodetecta al arrancar
  - Riega en modo AUTO segun un umbral, o en modo MANUAL desde una pagina web
  - Salida de "bomba": por defecto el LED integrado (bomba virtual).
    Cuando tengas transistor/rele/driver, cambia PIN_BOMBA y BOMBA_ACTIVA_EN_HIGH.

  Librerias (Arduino IDE -> Administrar bibliotecas):
    - DHT sensor library (Adafruit)
    - Adafruit Unified Sensor
    - BH1750 (claws) — solo si conectas el sensor de luz

  Nota: el anemometro del informe de la U (0-5V analogico) NO cabe en este
  NodeMCU: el ESP8266 solo tiene un pin analogico (A0) y ya lo ocupa el
  sensor de suelo. Para agregarlo hace falta un ADC externo (ej. ADS1115
  por I2C, mismo bus que el BH1750) — pendiente hasta tener ese modulo.

  Placa: NodeMCU 1.0 (ESP-12E Module), Serial a 115200.
*/

#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <Wire.h>
#include <DHT.h>
#include <BH1750.h>
#include "config.h"   // copia config.h.example -> config.h

// ---------------- Pines ----------------
#define PIN_DHT    D5          // DHT22 (DAT). Evitamos D4: es GPIO2, comparte el LED azul
#define PIN_SUELO  A0          // sensor de suelo (AO)
#define PIN_SDA    D2          // BH1750 SDA (bus I2C)
#define PIN_SCL    D1          // BH1750 SCL (bus I2C)

// Salida de la bomba.
//  Bomba virtual (LED integrado): PIN_BOMBA LED_BUILTIN, ACTIVA_EN_HIGH false (el LED es invertido)
//  Bomba real con transistor NPN / driver: PIN_BOMBA D1, ACTIVA_EN_HIGH true
#define PIN_BOMBA               LED_BUILTIN
#define BOMBA_ACTIVA_EN_HIGH    false

// ---------------- Calibracion ----------------
// Anota el valor "raw" con el sensor al aire y dentro de un vaso con agua.
int RAW_SECO   = 800;   // sensor al aire
int RAW_HUMEDO = 350;   // sensor en agua
int UMBRAL_RIEGO = 40;  // AUTO: riega si el suelo esta bajo este % ...
int UMBRAL_APAGA = 55;  // ... y apaga cuando sube de este % (histeresis)

// ---------------- Seguridad ----------------
const unsigned long MAX_RIEGO_MS   = 15000UL;  // corte de seguridad por riego continuo
const unsigned long ESPERA_MS      = 60000UL;  // pausa minima tras un corte por tiempo
const unsigned long LECTURA_MS     = 2000UL;   // cada cuanto se leen los sensores

DHT dht(PIN_DHT, DHT22);
BH1750 bh1750;
ESP8266WebServer server(80);

float tempC = NAN, humAire = NAN;
int rawSuelo = 0, humSuelo = 0;
float luxLuz = NAN;
bool luzDisponible = false;   // true si el BH1750 respondio al arrancar
bool modoAuto = true;
bool bombaOn = false;
unsigned long bombaDesde = 0, bloqueoHasta = 0, ultimaLectura = 0;

// ---------------- Bloqueo por intentos de login fallidos ----------------
// Mitiga fuerza bruta desde un dispositivo comprometido en la misma red.
// El contador es global (no por IP): tras varios intentos fallidos de
// cualquiera, se bloquea TODO acceso un rato. Correcto para un dispositivo
// de un solo hogar; no escalaria a un despliegue multiusuario.
int fallosAuth = 0;
unsigned long bloqueoAuthHasta = 0;
const int MAX_FALLOS_AUTH = 5;
const unsigned long BLOQUEO_AUTH_MS = 60000UL;   // 1 min

// ---------------- Historial de riego ----------------
// Buffer circular en RAM (se pierde al reiniciar; no hay RTC en esta placa).
// Cada riego que termina se registra con "hace cuanto empezo" (relativo a
// millis()) para poder filtrar los ultimos 24 h sin depender de hora real.
#define HIST_MAX 48
struct EventoRiego { unsigned long inicioMs; unsigned long duracionMs; };
EventoRiego historial[HIST_MAX];
int histCount = 0;
int histHead = 0;   // proximo indice a escribir

void registrarRiego(unsigned long inicioMs, unsigned long duracionMs) {
  historial[histHead] = { inicioMs, duracionMs };
  histHead = (histHead + 1) % HIST_MAX;
  if (histCount < HIST_MAX) histCount++;
}

void setBomba(bool on) {
  if (on && millis() < bloqueoHasta) return;   // en pausa por seguridad
  if (on && !bombaOn) bombaDesde = millis();
  if (!on && bombaOn) registrarRiego(bombaDesde, millis() - bombaDesde);
  bombaOn = on;
  digitalWrite(PIN_BOMBA, (on == BOMBA_ACTIVA_EN_HIGH) ? HIGH : LOW);
}

void leerSensores() {
  float t = dht.readTemperature();
  float h = dht.readHumidity();
  if (!isnan(t)) tempC = t;       // si falla la lectura, se conserva la anterior
  if (!isnan(h)) humAire = h;
  rawSuelo = analogRead(PIN_SUELO);
  humSuelo = constrain(map(rawSuelo, RAW_SECO, RAW_HUMEDO, 0, 100), 0, 100);

  if (luzDisponible) {
    float l = bh1750.readLightLevel();
    if (l >= 0) luxLuz = l;   // el BH1750 devuelve negativo si la lectura fallo
  }
}

void logicaRiego() {
  // Corte de seguridad: nunca regar mas de MAX_RIEGO_MS seguidos
  if (bombaOn && millis() - bombaDesde >= MAX_RIEGO_MS) {
    setBomba(false);
    bloqueoHasta = millis() + ESPERA_MS;
    Serial.println("[SEGURIDAD] Corte por tiempo maximo de riego");
    return;
  }
  if (!modoAuto) return;
  if (!bombaOn && humSuelo < UMBRAL_RIEGO) setBomba(true);
  else if (bombaOn && humSuelo >= UMBRAL_APAGA) setBomba(false);
}

bool autorizado() {
  if (millis() < bloqueoAuthHasta) {
    server.send(429, "text/plain", "Demasiados intentos fallidos. Espera un momento.");
    return false;
  }
  if (server.authenticate(WEB_USER, WEB_PASS)) {
    fallosAuth = 0;
    return true;
  }
  fallosAuth++;
  if (fallosAuth >= MAX_FALLOS_AUTH) {
    bloqueoAuthHasta = millis() + BLOQUEO_AUTH_MS;
    Serial.println("[SEGURIDAD] Bloqueo temporal por intentos de login fallidos");
  }
  server.requestAuthentication();
  return false;
}

// JSON del estado actual. Se arma a mano (sin ArduinoJson) para no sumar
// una dependencia nueva a un objeto tan simple.
String estadoJson() {
  String j = "{";
  j += "\"tempC\":"    + (isnan(tempC)   ? String("null") : String(tempC, 1)) + ",";
  j += "\"humAire\":"  + (isnan(humAire) ? String("null") : String(humAire, 0)) + ",";
  j += "\"humSuelo\":" + String(humSuelo) + ",";
  j += "\"rawSuelo\":" + String(rawSuelo) + ",";
  j += "\"luxLuz\":" + (luzDisponible && !isnan(luxLuz) ? String(luxLuz, 0) : String("null")) + ",";
  j += "\"modoAuto\":" + String(modoAuto ? "true" : "false") + ",";
  j += "\"bombaOn\":"  + String(bombaOn  ? "true" : "false");
  j += "}";
  return j;
}

void apiEstado() {
  if (!autorizado()) return;
  server.send(200, "application/json", estadoJson());
}

// JSON del historial de riego de las ultimas 24 h, mas reciente primero.
// "haceMin": minutos desde que empezo ese riego. "duracionS": cuanto duro.
String historialJson() {
  const unsigned long VENTANA_MS = 24UL * 60UL * 60UL * 1000UL;
  unsigned long ahora = millis();
  String j = "[";
  int n = min(histCount, HIST_MAX);
  bool primero = true;
  for (int i = 0; i < n; i++) {
    int idx = (histHead - 1 - i + HIST_MAX) % HIST_MAX;
    unsigned long antiguedad = ahora - historial[idx].inicioMs;
    if (antiguedad > VENTANA_MS) break;   // el resto del buffer es aun mas viejo
    if (!primero) j += ",";
    primero = false;
    j += "{\"haceMin\":" + String(antiguedad / 60000UL) +
         ",\"duracionS\":" + String(historial[idx].duracionMs / 1000UL) + "}";
  }
  j += "]";
  return j;
}

void apiHistorial() {
  if (!autorizado()) return;
  server.send(200, "application/json", historialJson());
}

// JSON de los umbrales de riego automatico actuales.
String umbralesJson() {
  String j = "{";
  j += "\"umbralRiego\":" + String(UMBRAL_RIEGO) + ",";
  j += "\"umbralApaga\":" + String(UMBRAL_APAGA);
  j += "}";
  return j;
}

void apiUmbrales() {
  if (!autorizado()) return;
  server.send(200, "application/json", umbralesJson());
}

// Los umbrales viven en RAM (como el resto de la calibracion): vuelven a
// sus valores por defecto si el NodeMCU se reinicia.
void apiUmbralesSet() {
  if (!autorizado()) return;
  if (server.hasArg("umbralRiego") && server.hasArg("umbralApaga")) {
    int nuevoRiego = server.arg("umbralRiego").toInt();
    int nuevoApaga = server.arg("umbralApaga").toInt();
    bool valido = nuevoRiego >= 0 && nuevoRiego <= 100 &&
                  nuevoApaga >= 0 && nuevoApaga <= 100 &&
                  nuevoApaga > nuevoRiego;
    if (!valido) {
      server.send(400, "application/json", "{\"error\":\"umbrales invalidos\"}");
      return;
    }
    UMBRAL_RIEGO = nuevoRiego;
    UMBRAL_APAGA = nuevoApaga;
  }
  server.send(200, "application/json", umbralesJson());
}

void apiModo() {
  if (!autorizado()) return;
  modoAuto = !modoAuto;
  setBomba(false);
  server.send(200, "application/json", estadoJson());
}

void apiOn() {
  if (!autorizado()) return;
  if (!modoAuto) setBomba(true);
  server.send(200, "application/json", estadoJson());
}

void apiOff() {
  if (!autorizado()) return;
  if (!modoAuto) setBomba(false);
  server.send(200, "application/json", estadoJson());
}

// Pagina estatica: no lleva valores embebidos por el servidor, los pide
// por fetch() a /api/estado y se repinta sola cada 2 s, sin recargar.
// El navegador ya autentico con Basic Auth para cargar "/", asi que
// reusa esas mismas credenciales al llamar a /api/* (mismo origen).
void paginaPrincipal() {
  if (!autorizado()) return;
  server.send(200, "text/html", F(
    "<!doctype html><meta charset='utf-8'>"
    "<meta name='viewport' content='width=device-width,initial-scale=1'>"
    "<title>Riego IoT</title>"
    "<style>"
    "body{font-family:sans-serif;max-width:420px;margin:1rem auto;padding:0 1rem;background:#f7fafc;color:#1a202c}"
    "h2{margin-bottom:.2rem}"
    ".card{background:#fff;border-radius:12px;padding:.8rem 1rem;margin:.6rem 0;box-shadow:0 1px 3px rgba(0,0,0,.15)}"
    ".v{font-size:28px;font-weight:600}"
    "button{font-size:18px;padding:12px 16px;margin:4px 0;width:100%;border:0;border-radius:8px;background:#2b6cb0;color:#fff}"
    "button:active{opacity:.8}"
    "#manual{display:none}"
    "</style>"
    "<h2>&#127793; Riego IoT</h2>"
    "<div class='card'><p>Suelo<br><span class='v' id='suelo'>--</span> (raw <span id='raw'>--</span>)</p></div>"
    "<div class='card'><p>Aire<br><span class='v'><span id='temp'>--</span> &middot; <span id='aire'>--</span></span></p></div>"
    "<div class='card' id='cardLuz' style='display:none'><p>Luz<br><span class='v' id='luz'>--</span> lux</p></div>"
    "<div class='card'><p>Modo: <b id='modo'>--</b> &middot; Bomba: <b id='bomba'>--</b></p></div>"
    "<button id='btnModo'>Cambiar modo</button>"
    "<div id='manual'>"
    "<button id='btnOn'>ENCENDER</button>"
    "<button id='btnOff'>APAGAR</button>"
    "</div>"
    "<script>"
    "async function actualizar(){"
      "try{"
        "const r=await fetch('/api/estado');if(!r.ok)return;const d=await r.json();"
        "document.getElementById('suelo').textContent=d.humSuelo+' %';"
        "document.getElementById('raw').textContent=d.rawSuelo;"
        "document.getElementById('temp').textContent=(d.tempC===null?'--':d.tempC.toFixed(1))+' \\u00b0C';"
        "document.getElementById('aire').textContent=(d.humAire===null?'--':Math.round(d.humAire))+' %';"
        "if(d.luxLuz!==null){document.getElementById('cardLuz').style.display='block';document.getElementById('luz').textContent=Math.round(d.luxLuz);}"
        "document.getElementById('modo').textContent=d.modoAuto?'AUTOMATICO':'MANUAL';"
        "document.getElementById('bomba').textContent=d.bombaOn?'ENCENDIDA':'apagada';"
        "document.getElementById('btnModo').textContent='Cambiar a '+(d.modoAuto?'MANUAL':'AUTOMATICO');"
        "document.getElementById('manual').style.display=d.modoAuto?'none':'block';"
      "}catch(e){}"
    "}"
    "async function accion(u){await fetch(u,{method:'POST'});actualizar();}"
    "document.getElementById('btnModo').onclick=()=>accion('/api/modo');"
    "document.getElementById('btnOn').onclick=()=>accion('/api/on');"
    "document.getElementById('btnOff').onclick=()=>accion('/api/off');"
    "actualizar();setInterval(actualizar,2000);"
    "</script>"
  ));
}

void setup() {
  Serial.begin(115200);
  pinMode(PIN_BOMBA, OUTPUT);
  setBomba(false);
  dht.begin();

  Wire.begin(PIN_SDA, PIN_SCL);
  luzDisponible = bh1750.begin(BH1750::CONTINUOUS_HIGH_RES_MODE);
  Serial.println(luzDisponible ? "BH1750 detectado: luz disponible"
                                : "BH1750 no detectado: se omite la lectura de luz");

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.print("Conectando a WiFi");
  unsigned long t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < 20000UL) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();
  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("Abre en el celular: http://");
    Serial.println(WiFi.localIP());
  } else {
    Serial.println("Sin WiFi: el riego AUTO sigue funcionando, sin pagina web.");
  }

  server.on("/", HTTP_GET, paginaPrincipal);
  server.on("/api/estado", HTTP_GET,  apiEstado);
  server.on("/api/historial", HTTP_GET, apiHistorial);
  server.on("/api/umbrales", HTTP_GET, apiUmbrales);
  server.on("/api/umbrales", HTTP_POST, apiUmbralesSet);
  server.on("/api/modo",   HTTP_POST, apiModo);
  server.on("/api/on",     HTTP_POST, apiOn);
  server.on("/api/off",    HTTP_POST, apiOff);
  server.begin();
}

void loop() {
  server.handleClient();

  if (millis() - ultimaLectura >= LECTURA_MS) {
    ultimaLectura = millis();
    leerSensores();
    logicaRiego();
    Serial.printf("T:%.1fC Aire:%.0f%% Suelo:%d%% (raw %d) Luz:%s Modo:%s Bomba:%s\n",
                  tempC, humAire, humSuelo, rawSuelo,
                  (luzDisponible && !isnan(luxLuz)) ? String(luxLuz, 0).c_str() : "N/D",
                  modoAuto ? "AUTO" : "MANUAL", bombaOn ? "ON" : "OFF");
  }
}
