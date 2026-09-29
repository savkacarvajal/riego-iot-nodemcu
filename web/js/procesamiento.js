import { ref, onValue } from "https://www.gstatic.com/firebasejs/10.13.0/firebase-database.js";
import { db } from "./firebase.js";
import { protegerSesion, configurarSalir, observarConexion, enviarComando } from "./comun.js";

protegerSesion();
configurarSalir();

let estadoActual = null;

const barraUmbrales = document.getElementById("barra-umbrales");

function actualizarMarcadorSuelo(humSuelo) {
  if (typeof humSuelo !== "number") return;
  const marcador = document.getElementById("marcador-suelo");
  barraUmbrales.style.setProperty("--u-actual", humSuelo);
  marcador.dataset.valor = `${humSuelo}%`;
}

observarConexion((estado) => {
  estadoActual = estado;
  actualizarMarcadorSuelo(estado.humSuelo);

  const auto = estado.modoAuto;
  document.getElementById("selector-modo").classList.toggle("manual", !auto);
  document.getElementById("opcion-auto").classList.toggle("activa", auto);
  document.getElementById("opcion-manual").classList.toggle("activa", !auto);

  const pastillaBomba = document.getElementById("v-bomba");
  pastillaBomba.replaceChildren();
  const puntoBomba = document.createElement("span");
  puntoBomba.className = "punto";
  pastillaBomba.append(puntoBomba, document.createTextNode(estado.bombaOn ? "Encendida" : "Apagada"));
  pastillaBomba.className = "pastilla " + (estado.bombaOn ? "on" : "off");

  document.getElementById("btn-on").disabled = estado.modoAuto || estado.bombaOn;
  document.getElementById("btn-off").disabled = estado.modoAuto || !estado.bombaOn;
});

document.getElementById("btn-modo").addEventListener("click", () => {
  if (!estadoActual) return;
  enviarComando({ modoAuto: !estadoActual.modoAuto });
});

document.getElementById("btn-on").addEventListener("click", () => enviarComando({ accionBomba: "on" }));
document.getElementById("btn-off").addEventListener("click", () => enviarComando({ accionBomba: "off" }));

// --- Umbrales (no se sobreescriben mientras el usuario los está editando) ---

let editandoUmbrales = false;
const inUmbralRiego = document.getElementById("in-umbral-riego");
const inUmbralApaga = document.getElementById("in-umbral-apaga");
[inUmbralRiego, inUmbralApaga].forEach((input) => {
  input.addEventListener("focus", () => (editandoUmbrales = true));
  input.addEventListener("blur", () => (editandoUmbrales = false));
});

onValue(ref(db, "umbrales"), (snap) => {
  const umbrales = snap.val();
  if (!umbrales) return;

  barraUmbrales.style.setProperty("--u-riego", umbrales.umbralRiego);
  barraUmbrales.style.setProperty("--u-apaga", umbrales.umbralApaga);

  if (editandoUmbrales) return;
  inUmbralRiego.value = umbrales.umbralRiego;
  inUmbralApaga.value = umbrales.umbralApaga;
});

document.getElementById("btn-guardar-umbrales").addEventListener("click", () => {
  const errorEl = document.getElementById("error-umbrales");
  const riego = Number(inUmbralRiego.value);
  const apaga = Number(inUmbralApaga.value);

  if (Number.isNaN(riego) || Number.isNaN(apaga) || riego < 0 || riego > 100 || apaga < 0 || apaga > 100) {
    errorEl.textContent = "Los umbrales deben estar entre 0 y 100.";
    return;
  }
  if (apaga <= riego) {
    errorEl.textContent = "El umbral de apagado debe ser mayor al de riego.";
    return;
  }

  errorEl.textContent = "";
  enviarComando({ umbralRiego: riego, umbralApaga: apaga });
});
