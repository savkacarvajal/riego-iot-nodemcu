/*
  Riego IoT con NodeMCU (ESP8266)
  - Lee temperatura y humedad del aire (DHT22)
  - Lee humedad del suelo (sensor analogico en A0)
  - Riega en modo AUTO segun un umbral, o en modo MANUAL desde una pagina web
  - Salida de "bomba": por defecto el LED integrado (bomba virtual).
    Cuando tengas transistor/rele/driver, cambia PIN_BOMBA y BOMBA_ACTIVA_EN_HIGH.

  Librerias (Arduino IDE -> Administrar bibliotecas):
    - DHT sensor library (Adafruit)
    - Adafruit Unified Sensor
  Placa: NodeMCU 1.0 (ESP-12E Module), Serial a 115200.
*/

#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <DHT.h>
#include "config.h"   // copia config.h.example -> config.h

// ---------------- Pines ----------------
#define PIN_DHT    D5          // DHT22 (DAT). Evitamos D4: es GPIO2, comparte el LED azul
#define PIN_SUELO  A0          // sensor de suelo (AO)

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
ESP8266WebServer server(80);

float tempC = NAN, humAire = NAN;
int rawSuelo = 0, humSuelo = 0;
bool modoAuto = true;
bool bombaOn = false;
unsigned long bombaDesde = 0, bloqueoHasta = 0, ultimaLectura = 0;

void setBomba(bool on) {
  if (on && millis() < bloqueoHasta) return;   // en pausa por seguridad
  if (on && !bombaOn) bombaDesde = millis();
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
  if (server.authenticate(WEB_USER, WEB_PASS)) return true;
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
  j += "\"modoAuto\":" + String(modoAuto ? "true" : "false") + ",";
  j += "\"bombaOn\":"  + String(bombaOn  ? "true" : "false");
  j += "}";
  return j;
}

void apiEstado() {
  if (!autorizado()) return;
  server.send(200, "application/json", estadoJson());
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
    Serial.printf("T:%.1fC Aire:%.0f%% Suelo:%d%% (raw %d) Modo:%s Bomba:%s\n",
                  tempC, humAire, humSuelo, rawSuelo,
                  modoAuto ? "AUTO" : "MANUAL", bombaOn ? "ON" : "OFF");
  }
}
