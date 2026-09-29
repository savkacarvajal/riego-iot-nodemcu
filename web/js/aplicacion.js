import {
  ref,
  onValue,
  set,
  query,
  orderByChild,
  limitToLast,
} from "https://www.gstatic.com/firebasejs/10.13.0/firebase-database.js";
import {
  getMessaging,
  isSupported as mensajeriaSoportada,
  getToken,
  onMessage,
} from "https://www.gstatic.com/firebasejs/10.13.0/firebase-messaging.js";
import { db, app } from "./firebase.js";
import { vapidKey } from "./firebase-config.js";
import { protegerSesion, configurarSalir, observarConexion, registroSW } from "./comun.js";

protegerSesion();
configurarSalir();
observarConexion();

const ICONO_GOTA =
  '<svg viewBox="0 0 24 24"><path d="M12 2s7 8.5 7 13a7 7 0 1 1-14 0c0-4.5 7-13 7-13Z"/></svg>';

// --- Historial (últimos 30, más reciente primero) ---

const consultaHistorial = query(ref(db, "historial"), orderByChild("inicio"), limitToLast(30));

onValue(consultaHistorial, (snap) => {
  const eventos = [];
  // OJO: el callback de forEach NO puede ser una expresión que devuelva algo
  // truthy (como push(), que devuelve la nueva longitud) -- Firebase trata
  // cualquier retorno truthy como "cancelar la iteración" y corta en el primer
  // elemento. Por eso el bloque con llaves, aunque parezca innecesario.
  snap.forEach((hijo) => {
    eventos.push(hijo.val());
  });
  eventos.reverse();

  const lista = document.getElementById("lista-historial");
  if (eventos.length === 0) {
    // Marcado estático (sin datos del usuario interpolados): seguro como innerHTML.
    lista.innerHTML = `<p class="vacio"><span class="icono"><svg viewBox="0 0 24 24"><path d="M12 3s7 7.5 7 12a7 7 0 1 1-14 0c0-4.5 7-12 7-12Z"/></svg></span>Sin riegos registrados todavía.</p>`;
    return;
  }

  // DOM construido con textContent (nunca innerHTML) para los valores que vienen
  // de la base: aunque hoy solo el bridge puede escribir /historial, esto evita
  // cualquier vector de inyección si esa garantía cambiara más adelante.
  lista.replaceChildren(
    ...eventos.map((evento, indice) => {
      const item = document.createElement("div");
      item.className = "historial-item";
      item.style.animationDelay = `${Math.min(indice, 8) * 0.04}s`;

      const izquierda = document.createElement("span");
      const icono = document.createElement("span");
      icono.className = "icono-gota";
      icono.innerHTML = ICONO_GOTA; // constante estática del código, no dato de usuario
      const fecha = document.createTextNode(
        new Date(evento.inicio).toLocaleString("es-CL", {
          day: "2-digit",
          month: "2-digit",
          hour: "2-digit",
          minute: "2-digit",
        })
      );
      izquierda.append(icono, fecha);

      const duracion = document.createElement("span");
      duracion.textContent = `${evento.duracionS}s`;

      item.append(izquierda, duracion);
      return item;
    })
  );
});

// --- Gráfico de tendencia (lecturas periódicas de humedad del suelo) ---

const NS_SVG = "http://www.w3.org/2000/svg";
const svgGrafico = document.getElementById("grafico-lecturas");
const graficoVacio = document.getElementById("grafico-vacio");
const tooltipGrafico = document.getElementById("tooltip-grafico");
const tablaLecturas = document.getElementById("tabla-lecturas");

const MARGEN = { izq: 32, der: 8, arriba: 10, abajo: 20 };
const ANCHO = 600;
const ALTO = 180;

function crearSvg(tag, atributos) {
  const el = document.createElementNS(NS_SVG, tag);
  for (const [clave, valor] of Object.entries(atributos)) el.setAttribute(clave, valor);
  return el;
}

function formatearHora(ts) {
  return new Date(ts).toLocaleTimeString("es-CL", { hour: "2-digit", minute: "2-digit" });
}

const consultaLecturas = query(ref(db, "lecturas"), orderByChild("ts"), limitToLast(60));

onValue(consultaLecturas, (snap) => {
  const puntos = [];
  snap.forEach((hijo) => {
    puntos.push(hijo.val());
  });
  puntos.sort((a, b) => a.ts - b.ts);

  tablaLecturas.replaceChildren(
    ...puntos
      .slice()
      .reverse()
      .map((p) => {
        const fila = document.createElement("tr");
        const celdaHora = document.createElement("td");
        celdaHora.textContent = new Date(p.ts).toLocaleString("es-CL");
        const celdaValor = document.createElement("td");
        celdaValor.textContent = `${p.humSuelo}%`;
        fila.append(celdaHora, celdaValor);
        return fila;
      })
  );

  svgGrafico.replaceChildren();
  if (puntos.length < 2) {
    // display, no [hidden]: la clase "vacio" ya trae su propio display:flex,
    // que pisa el display:none que el navegador le da a [hidden] por defecto.
    graficoVacio.style.display = "flex";
    svgGrafico.setAttribute("aria-hidden", "true");
    return;
  }
  graficoVacio.style.display = "none";
  svgGrafico.removeAttribute("aria-hidden");

  const anchoUtil = ANCHO - MARGEN.izq - MARGEN.der;
  const altoUtil = ALTO - MARGEN.arriba - MARGEN.abajo;
  const tsMin = puntos[0].ts;
  const tsMax = puntos[puntos.length - 1].ts;
  const rangoTs = Math.max(tsMax - tsMin, 1);

  const x = (ts) => MARGEN.izq + ((ts - tsMin) / rangoTs) * anchoUtil;
  const y = (valor) => MARGEN.arriba + altoUtil - (Math.max(0, Math.min(100, valor)) / 100) * altoUtil;

  [0, 50, 100].forEach((marca) => {
    const yPos = y(marca);
    svgGrafico.append(
      crearSvg("line", { class: "grilla", x1: MARGEN.izq, x2: ANCHO - MARGEN.der, y1: yPos, y2: yPos }),
      crearSvg("text", { class: "etiqueta-eje", x: 2, y: yPos + 3 })
    );
    svgGrafico.lastChild.textContent = `${marca}`;
  });

  svgGrafico.append(crearSvg("text", { class: "etiqueta-eje", x: MARGEN.izq, y: ALTO - 4 }));
  svgGrafico.lastChild.textContent = formatearHora(tsMin);
  svgGrafico.append(crearSvg("text", { class: "etiqueta-eje", x: ANCHO - MARGEN.der, y: ALTO - 4, "text-anchor": "end" }));
  svgGrafico.lastChild.textContent = formatearHora(tsMax);

  const coordenadas = puntos.map((p) => [x(p.ts), y(p.humSuelo)]);
  const dLinea = coordenadas.map(([cx, cy], i) => `${i === 0 ? "M" : "L"}${cx},${cy}`).join(" ");
  const dArea = `${dLinea} L${coordenadas[coordenadas.length - 1][0]},${MARGEN.arriba + altoUtil} L${coordenadas[0][0]},${MARGEN.arriba + altoUtil} Z`;

  svgGrafico.append(
    crearSvg("path", { class: "area-grafico", d: dArea }),
    crearSvg("path", { class: "linea-grafico", d: dLinea })
  );

  const cruce = crearSvg("line", { class: "cruce-hover", y1: MARGEN.arriba, y2: MARGEN.arriba + altoUtil, x1: -100, x2: -100 });
  const punto = crearSvg("circle", { class: "punto-hover", r: 4, cx: -100, cy: -100 });
  const capaHover = crearSvg("rect", {
    x: MARGEN.izq,
    y: MARGEN.arriba,
    width: anchoUtil,
    height: altoUtil,
    fill: "transparent",
  });
  svgGrafico.append(cruce, punto, capaHover);

  function alMover(evento) {
    const rectPantalla = svgGrafico.getBoundingClientRect();
    const xRelativo = ((evento.clientX - rectPantalla.left) / rectPantalla.width) * ANCHO;
    let indiceCercano = 0;
    let distanciaMinima = Infinity;
    coordenadas.forEach(([cx], i) => {
      const d = Math.abs(cx - xRelativo);
      if (d < distanciaMinima) {
        distanciaMinima = d;
        indiceCercano = i;
      }
    });
    const [cx, cy] = coordenadas[indiceCercano];
    cruce.setAttribute("x1", cx);
    cruce.setAttribute("x2", cx);
    punto.setAttribute("cx", cx);
    punto.setAttribute("cy", cy);

    const punteroPantalla = ((cx - 0) / ANCHO) * rectPantalla.width + rectPantalla.left;
    const punteroVertical = ((cy - 0) / ALTO) * rectPantalla.height + rectPantalla.top;
    tooltipGrafico.style.left = `${punteroPantalla - svgGrafico.parentElement.getBoundingClientRect().left}px`;
    tooltipGrafico.style.top = `${punteroVertical - svgGrafico.parentElement.getBoundingClientRect().top}px`;
    tooltipGrafico.textContent = `${formatearHora(puntos[indiceCercano].ts)} — ${puntos[indiceCercano].humSuelo}%`;
    tooltipGrafico.hidden = false;
  }

  capaHover.addEventListener("mousemove", alMover);
  capaHover.addEventListener("mouseleave", () => {
    tooltipGrafico.hidden = true;
    cruce.setAttribute("x1", -100);
    cruce.setAttribute("x2", -100);
    punto.setAttribute("cx", -100);
  });
});

// --- Notificaciones push ---

const btnNotificaciones = document.getElementById("btn-notificaciones");
const estadoNotificaciones = document.getElementById("estado-notificaciones");

btnNotificaciones.addEventListener("click", async () => {
  estadoNotificaciones.textContent = "";
  if (!(await mensajeriaSoportada())) {
    estadoNotificaciones.textContent = "Este navegador no soporta notificaciones push.";
    return;
  }
  if (Notification.permission === "denied") {
    estadoNotificaciones.textContent = "Bloqueaste las notificaciones para este sitio; habilítalas desde el navegador.";
    return;
  }

  btnNotificaciones.disabled = true;
  try {
    const permiso = await Notification.requestPermission();
    if (permiso !== "granted") {
      estadoNotificaciones.textContent = "No se activaron las notificaciones.";
      return;
    }

    const swRegistration = (await registroSW) || undefined;
    const messaging = getMessaging(app);
    const token = await getToken(messaging, { vapidKey, serviceWorkerRegistration: swRegistration });
    await set(ref(db, `dispositivos/${token}`), { token, plataforma: "web" });

    onMessage(messaging, (payload) => {
      const { title, body } = payload.notification || {};
      new Notification(title || "Riego IoT", { body, icon: "icons/icon.svg" });
    });

    estadoNotificaciones.textContent = "✓ Notificaciones activadas.";
    estadoNotificaciones.style.color = "var(--bien)";
  } catch (err) {
    estadoNotificaciones.textContent = "No se pudo activar: " + err.message;
  } finally {
    btnNotificaciones.disabled = false;
  }
});
