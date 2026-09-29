// Registro del service worker, compartido por login.js y app.js.
// Si falla (ej. navegador sin soporte), la app sigue funcionando igual, solo
// sin cache offline ni notificaciones en segundo plano.

export async function registrarServiceWorker() {
  if (!("serviceWorker" in navigator)) return null;
  try {
    return await navigator.serviceWorker.register("sw.js");
  } catch (err) {
    console.warn("No se pudo registrar el service worker:", err.message);
    return null;
  }
}
