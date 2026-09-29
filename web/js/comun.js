// Piezas compartidas entre las tres páginas del panel (index/procesamiento/aplicacion):
// guardia de sesión, botón de salir, service worker, estado de conexión y envío de
// comandos. Cada página importa de aquí en vez de repetir esta lógica.

import { onAuthStateChanged, signOut } from "https://www.gstatic.com/firebasejs/10.13.0/firebase-auth.js";
import { ref, onValue, set } from "https://www.gstatic.com/firebasejs/10.13.0/firebase-database.js";
import { auth, db } from "./firebase.js";
import { registrarServiceWorker } from "./sw-registro.js";

export function protegerSesion() {
  onAuthStateChanged(auth, (usuario) => {
    if (!usuario) location.href = "login.html";
  });
}

export function configurarSalir() {
  document.getElementById("btn-salir")?.addEventListener("click", () => signOut(auth));
}

export const registroSW = registrarServiceWorker();

let ultimoActualizado = null;

// Escucha /estado (para el punto "en vivo" del header) y opcionalmente reenvía
// cada snapshot a quien la llame, para que cada página pinte lo que le importa.
export function observarConexion(alCambiarEstado) {
  onValue(ref(db, "estado"), (snap) => {
    const estado = snap.val();
    if (!estado) return;
    ultimoActualizado = estado.actualizado ?? Date.now();
    marcarConexion(true);
    alCambiarEstado?.(estado);
  });

  setInterval(() => {
    if (!ultimoActualizado) return;
    const segundos = Math.round((Date.now() - ultimoActualizado) / 1000);
    marcarConexion(segundos < 15);
  }, 2000);
}

function marcarConexion(conectado) {
  const badge = document.getElementById("estado-conexion");
  if (!badge) return;
  badge.classList.toggle("en-vivo", conectado);
  const texto = document.getElementById("texto-conexion");
  if (!ultimoActualizado) {
    texto.textContent = conectado ? "En vivo" : "Sin datos";
    return;
  }
  const segundos = Math.round((Date.now() - ultimoActualizado) / 1000);
  texto.textContent = segundos < 5 ? "En vivo" : `hace ${segundos}s`;
  const vActualizado = document.getElementById("v-actualizado");
  if (vActualizado) vActualizado.textContent = segundos < 5 ? "Actualizado ahora" : `Actualizado hace ${segundos}s`;
}

export function enviarComando(campos) {
  return set(ref(db, "comandos"), { ...campos, ts: Date.now() });
}
