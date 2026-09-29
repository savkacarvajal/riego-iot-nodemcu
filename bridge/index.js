// Puente entre el NodeMCU (API HTTP local, sin TLS) y Firebase Realtime Database.
//
// El NodeMCU no habla HTTPS (ver README de firmware/): por eso este bridge corre
// en una máquina de la misma red (laptop durante la demo, o un Raspberry Pi si
// se deja permanente) y hace de "traductor" entre ambos mundos.
//
// Flujo:
//   - Cada POLL_MS ms: lee /api/estado y /api/historial del NodeMCU y los escribe en RTDB.
//   - Escucha /comandos en RTDB; cuando llega un comando nuevo (ts más reciente que
//     el último aplicado) lo reenvía al NodeMCU vía sus endpoints POST existentes.

import "dotenv/config";
import { readFileSync } from "node:fs";
import admin from "firebase-admin";

const {
  NODEMCU_IP,
  NODEMCU_USER,
  NODEMCU_PASS,
  POLL_MS = "2000",
  FIREBASE_SERVICE_ACCOUNT = "./serviceAccountKey.json",
  FIREBASE_DB_URL,
  HISTORIAL_MAX = "200",
  LECTURA_INTERVALO_MS = "60000",
  LECTURAS_MAX = "200",
} = process.env;

for (const [nombre, valor] of Object.entries({ NODEMCU_IP, NODEMCU_USER, NODEMCU_PASS, FIREBASE_DB_URL })) {
  if (!valor) {
    console.error(`Falta la variable de entorno ${nombre}. Copia .env.example a .env y complétalo.`);
    process.exit(1);
  }
}

const serviceAccount = JSON.parse(readFileSync(FIREBASE_SERVICE_ACCOUNT, "utf-8"));
admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: FIREBASE_DB_URL,
});
const db = admin.database();

const authHeader = "Basic " + Buffer.from(`${NODEMCU_USER}:${NODEMCU_PASS}`).toString("base64");
const baseUrl = `http://${NODEMCU_IP}`;

async function llamarNodemcu(path, metodo = "GET", cuerpo = null) {
  const opciones = { method: metodo, headers: { Authorization: authHeader } };
  if (cuerpo) {
    opciones.headers["Content-Type"] = "application/x-www-form-urlencoded";
    opciones.body = new URLSearchParams(cuerpo).toString();
  }
  const resp = await fetch(`${baseUrl}${path}`, opciones);
  if (!resp.ok) throw new Error(`${path} -> HTTP ${resp.status}`);
  return resp.json();
}

// --- Espejo de /estado y /umbrales ---

let ultimoBombaOn = null;
let ultimaLectura = 0;

async function sincronizarEstado() {
  const estado = await llamarNodemcu("/api/estado");
  await db.ref("estado").set({ ...estado, actualizado: admin.database.ServerValue.TIMESTAMP });

  const umbrales = await llamarNodemcu("/api/umbrales");
  await db.ref("umbrales").set(umbrales);

  // Notifica solo en el flanco de encendido/apagado, no en cada poll.
  if (ultimoBombaOn !== null && estado.bombaOn !== ultimoBombaOn) {
    notificar(estado.bombaOn ? "💧 Bomba encendida" : "Bomba apagada", `Humedad del suelo: ${estado.humSuelo}%`);
  }
  ultimoBombaOn = estado.bombaOn;

  // Muestra periódica de humedad del suelo, para el gráfico de tendencia (no en cada poll).
  const ahora = Date.now();
  if (typeof estado.humSuelo === "number" && ahora - ultimaLectura >= Number(LECTURA_INTERVALO_MS)) {
    ultimaLectura = ahora;
    await registrarLectura(estado.humSuelo);
  }

  return estado;
}

async function registrarLectura(humSuelo) {
  await db.ref("lecturas").push({ ts: Date.now(), humSuelo });

  const snap = await db.ref("lecturas").once("value");
  const maximo = Number(LECTURAS_MAX);
  if (snap.numChildren() > maximo) {
    const exceso = snap.numChildren() - maximo;
    const claves = [];
    snap.forEach((hijo) => {
      if (claves.length < exceso) claves.push(hijo.key);
    });
    await Promise.all(claves.map((k) => db.ref(`lecturas/${k}`).remove()));
  }
}

// --- Notificaciones push (Firebase Cloud Messaging) ---
// Cada dispositivo (web o Android) registra su token en /dispositivos al pedir permiso.
// El bridge le manda un push directo a cada token; si alguno ya no es válido, lo limpia.

async function notificar(titulo, cuerpo) {
  const snap = await db.ref("dispositivos").once("value");
  const tokens = [];
  // Igual que en el web: el callback no puede devolver un valor truthy (push()
  // devuelve la nueva longitud), o Firebase corta la iteración en el primero.
  snap.forEach((hijo) => {
    tokens.push(hijo.val().token);
  });
  if (tokens.length === 0) return;

  try {
    const respuesta = await admin.messaging().sendEachForMulticast({
      tokens,
      notification: { title: titulo, body: cuerpo },
      webpush: { fcmOptions: { link: "/" } },
    });
    respuesta.responses.forEach((r, i) => {
      if (!r.success && r.error?.code === "messaging/registration-token-not-registered") {
        db.ref(`dispositivos/${tokens[i]}`).remove();
      }
    });
    console.log(`[notificar] "${titulo}" enviado a ${respuesta.successCount}/${tokens.length} dispositivo(s)`);
  } catch (err) {
    console.error("[notificar] error:", err.message);
  }
}

// --- Historial: el NodeMCU solo sabe "hace cuántos minutos", sin reloj de pared.
// Reconstruimos un timestamp absoluto y deduplicamos por (duración, minuto de inicio). ---

const firmasVistas = new Set();

function firmaDe(duracionS, inicioMs) {
  return `${duracionS}_${Math.floor(inicioMs / 60000)}`;
}

async function cargarFirmasExistentes() {
  const snap = await db.ref("historial").limitToLast(Number(HISTORIAL_MAX)).once("value");
  snap.forEach((hijo) => {
    const { inicio, duracionS } = hijo.val();
    firmasVistas.add(firmaDe(duracionS, inicio));
  });
}

async function sincronizarHistorial() {
  const eventos = await llamarNodemcu("/api/historial");
  const ahora = Date.now();

  for (const { haceMin, duracionS } of eventos) {
    const inicio = ahora - haceMin * 60_000;
    const firma = firmaDe(duracionS, inicio);
    if (firmasVistas.has(firma)) continue;

    firmasVistas.add(firma);
    await db.ref("historial").push({ inicio, duracionS });
    console.log(`[historial] nuevo riego: ${duracionS}s, hace ${haceMin} min`);
  }

  // Recorta el historial en RTDB para que no crezca sin límite en demos largas.
  const snap = await db.ref("historial").once("value");
  const total = snap.numChildren();
  const maximo = Number(HISTORIAL_MAX);
  if (total > maximo) {
    const exceso = total - maximo;
    const claves = [];
    snap.forEach((hijo) => {
      if (claves.length < exceso) claves.push(hijo.key);
    });
    await Promise.all(claves.map((k) => db.ref(`historial/${k}`).remove()));
  }
}

// --- Comandos: web/app escriben en /comandos, el bridge los aplica al NodeMCU. ---

let ultimoTsAplicado = 0;

async function aplicarComando(comando) {
  if (!comando || !comando.ts || comando.ts <= ultimoTsAplicado) return;
  ultimoTsAplicado = comando.ts;

  try {
    if (typeof comando.modoAuto === "boolean") {
      const estadoActual = await llamarNodemcu("/api/estado");
      if (estadoActual.modoAuto !== comando.modoAuto) {
        await llamarNodemcu("/api/modo", "POST");
      }
    }

    if (comando.accionBomba === "on") {
      await llamarNodemcu("/api/on", "POST");
    } else if (comando.accionBomba === "off") {
      await llamarNodemcu("/api/off", "POST");
    }

    if (typeof comando.umbralRiego === "number" && typeof comando.umbralApaga === "number") {
      await llamarNodemcu("/api/umbrales", "POST", {
        umbralRiego: comando.umbralRiego,
        umbralApaga: comando.umbralApaga,
      });
    }

    console.log("[comando] aplicado:", comando);
  } catch (err) {
    console.error("[comando] error al aplicar:", err.message);
  } finally {
    // Refleja el resultado real de inmediato, sin esperar al próximo poll.
    await sincronizarEstado().catch((err) => console.error("[sync] error:", err.message));
  }
}

db.ref("comandos").on("value", (snap) => {
  aplicarComando(snap.val());
});

// --- Loop de polling ---

async function ciclo() {
  try {
    await sincronizarEstado();
    await sincronizarHistorial();
  } catch (err) {
    console.error("[poll] error hablando con el NodeMCU:", err.message);
  }
}

console.log(`Bridge riego-iot iniciado. NodeMCU en ${baseUrl}, DB en ${FIREBASE_DB_URL}`);
await cargarFirmasExistentes();
await ciclo();
setInterval(ciclo, Number(POLL_MS));
