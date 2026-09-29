// Service worker: cachea el "app shell" (para que la demo aguante un wifi de
// aula que se corta un momento) y muestra notificaciones push en segundo plano.

const CACHE = "riego-iot-v3";
const ARCHIVOS_APP_SHELL = [
  "login.html",
  "index.html",
  "procesamiento.html",
  "aplicacion.html",
  "css/estilos.css",
  "js/firebase.js",
  "js/comun.js",
  "js/login.js",
  "js/inicio.js",
  "js/procesamiento.js",
  "js/aplicacion.js",
  "icons/icon.svg",
  "manifest.json",
];

self.addEventListener("install", (evento) => {
  evento.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(ARCHIVOS_APP_SHELL)));
  self.skipWaiting();
});

self.addEventListener("activate", (evento) => {
  evento.waitUntil(
    caches.keys().then((claves) => Promise.all(claves.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
  );
  self.clients.claim();
});

// Red primero (así la demo siempre ve lo último), con la cache como respaldo
// si no hay conexión. Si la respuesta viene de una redirección (ej. servidores
// que limpian ".html" de la URL), se reconstruye sin el flag "redirected":
// Chrome rechaza devolver una respuesta redirigida a una navegación.
self.addEventListener("fetch", (evento) => {
  if (evento.request.method !== "GET") return;
  evento.respondWith(
    (async () => {
      try {
        const respuestaRed = await fetch(evento.request);
        const limpia = respuestaRed.redirected ? new Response(respuestaRed.body, respuestaRed) : respuestaRed;
        if (limpia.ok) {
          caches.open(CACHE).then((cache) => cache.put(evento.request, limpia.clone()));
        }
        return limpia;
      } catch {
        const respuestaCache = await caches.match(evento.request);
        return respuestaCache || Response.error();
      }
    })()
  );
});

// --- Notificaciones push en segundo plano (pestaña cerrada o sin foco) ---
// firebase-config-sw.js no es un módulo: define self.FIREBASE_CONFIG (ver su .example).

importScripts("https://www.gstatic.com/firebasejs/10.13.0/firebase-app-compat.js");
importScripts("https://www.gstatic.com/firebasejs/10.13.0/firebase-messaging-compat.js");
importScripts("./js/firebase-config-sw.js");

firebase.initializeApp(self.FIREBASE_CONFIG);
const messaging = firebase.messaging();

messaging.onBackgroundMessage((payload) => {
  const { title, body } = payload.notification || {};
  self.registration.showNotification(title || "Riego IoT", {
    body: body || "",
    icon: "icons/icon.svg",
  });
});
