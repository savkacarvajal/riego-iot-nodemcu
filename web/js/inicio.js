import { ref, onValue } from "https://www.gstatic.com/firebasejs/10.13.0/firebase-database.js";
import { db } from "./firebase.js";
import { protegerSesion, configurarSalir, observarConexion } from "./comun.js";

protegerSesion();
configurarSalir();

const valoresPrevios = {};

function pintarValor(id, valor) {
  const el = document.getElementById(id);
  const texto = valor ?? "—";
  if (valoresPrevios[id] !== texto) {
    valoresPrevios[id] = texto;
    el.textContent = texto;
    el.classList.remove("actualizado");
    void el.offsetWidth; // reinicia la animación aunque se repita el valor
    el.classList.add("actualizado");
  }
}

function pintarAnillo(idAnillo, idValor, valor, zona) {
  const anillo = document.getElementById(idAnillo);
  anillo.style.setProperty("--valor", Number.isFinite(valor) ? valor : 0);
  anillo.classList.remove("zona-alerta", "zona-bien");
  if (zona) anillo.classList.add(zona);
  if (Number.isFinite(valor)) anillo.setAttribute("aria-valuenow", valor);
  pintarValor(idValor, valor);
}

let ultimosUmbrales = null;

onValue(ref(db, "umbrales"), (snap) => {
  ultimosUmbrales = snap.val();
});

observarConexion((estado) => {
  pintarValor("v-temp", estado.tempC);
  pintarAnillo("anillo-aire", "v-hum-aire", estado.humAire);

  let zonaSuelo = null;
  if (ultimosUmbrales && typeof estado.humSuelo === "number") {
    zonaSuelo = estado.humSuelo < ultimosUmbrales.umbralRiego ? "zona-alerta" : "zona-bien";
  }
  pintarAnillo("anillo-suelo", "v-hum-suelo", estado.humSuelo, zonaSuelo);

  pintarValor("v-luz", estado.luxLuz);
});
