# Bridge — NodeMCU ↔ Firebase

El NodeMCU solo habla HTTP local (no tiene TLS, ver `firmware/README.md`). Este bridge es
el único componente que le sigue hablando directo por IP: se para en cualquier PC/Raspberry
Pi de la misma red, y hace de traductor hacia Firebase Realtime Database, que es lo que
consumen la [página web](../web) y la [app Android](../app-android).

```
NodeMCU  <--HTTP local-->  bridge (este)  <--HTTPS-->  Firebase Realtime Database
```

## Requisitos

- Node.js 18 o superior (usa `fetch` nativo).
- El NodeMCU encendido y conectado a la misma red que esta máquina.
- Un proyecto de Firebase con Realtime Database habilitada (ver README raíz del repo).

## Configuración

1. `npm install`
2. Copia `.env.example` a `.env` y completa:
   - `NODEMCU_IP`: la IP que el NodeMCU imprime por Serial al conectarse al WiFi.
   - `NODEMCU_USER` / `NODEMCU_PASS`: igual a `WEB_USER`/`WEB_PASS` de `firmware/riego_nodemcu/config.h`.
   - `FIREBASE_DB_URL`: la URL de tu Realtime Database.
3. En Firebase Console → Configuración del proyecto → Cuentas de servicio → "Generar nueva
   clave privada". Guarda el JSON descargado como `bridge/serviceAccountKey.json` (ya está
   en `.gitignore`, nunca se sube).

## Uso

```
npm start
```

Deja esta ventana corriendo mientras quieras que la web/app vean datos en vivo y puedan
controlar el riego. Si se cierra, el NodeMCU sigue funcionando solo (AUTO local sigue
regando según sus propios umbrales) — solo se pierde la sincronización con la nube hasta
que se vuelva a levantar el bridge.

## Qué hace

- Cada `POLL_MS` (2s por defecto) lee `/api/estado` y `/api/historial` del NodeMCU y los
  escribe en `/estado` y `/historial` de la base.
- Cada `LECTURA_INTERVALO_MS` (1 min por defecto) guarda una muestra de humedad de suelo en
  `/lecturas` — eso es lo que grafica el panel web como tendencia (no cada poll, para no
  llenar la base de puntos redundantes).
- Escucha `/comandos` en la base; cuando la web o la app escriben un comando nuevo (con un
  `ts` más reciente que el último aplicado), lo reenvía al NodeMCU (`/api/modo`, `/api/on`,
  `/api/off`, `/api/umbrales`) y refresca `/estado` de inmediato con el resultado.
- Cuando la bomba cambia de estado (se prende o se apaga), manda una notificación push
  (Firebase Cloud Messaging) a cada dispositivo registrado en `/dispositivos` — la web y la
  app se registran solas ahí cuando el usuario activa las notificaciones. Si un token ya no
  es válido (la app se desinstaló, el navegador revocó el permiso), lo borra solo.
