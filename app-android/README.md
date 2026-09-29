# 📱 Riego IoT — App Android

Cliente Android nativo (Kotlin) para el proyecto de riego. Muestra el estado de los sensores
en vivo y permite cambiar de modo AUTOMÁTICO/MANUAL, encender/apagar la bomba y ajustar los
umbrales — **desde cualquier red con internet**, no solo la WiFi del NodeMCU: la app habla
con [Firebase Realtime Database](https://firebase.google.com/products/realtime-database),
la misma base que usa el [panel web](../web). Quien sincroniza esa base con el NodeMCU real
es el [bridge](../bridge) (necesita estar corriendo en la red del NodeMCU para que los datos
se actualicen; ver diagrama en el [README principal](../README.md)).

<p align="center">
  <img src="docs/login.png" alt="Pantalla de login de la app" width="270">
  <img src="docs/dashboard.png" alt="Dashboard con paneles plegables" width="270">
</p>

<p align="center"><em>Capturas reales desde el emulador (de una versión anterior, conectada directo al NodeMCU). La UI del dashboard es la misma; el login ahora pide correo/clave de Firebase en vez de IP/usuario del NodeMCU.</em></p>

## ⚙️ Antes de abrir el proyecto

1. Sigue la sección "Firebase" del [README principal](../README.md) para crear el proyecto,
   habilitar Realtime Database + Authentication (Email/contraseña), y crear el usuario que
   vas a usar para entrar a la app.
2. Descarga `google-services.json` (Firebase Console → Configuración del proyecto → Tus apps
   → agregar app Android con el paquete `com.savkacarvajal.riegoiot`) y guárdalo en
   `app-android/app/google-services.json` (está en `.gitignore`, no se sube a GitHub).
3. Abre la carpeta `app-android/` como proyecto en Android Studio (**File → Open**) y deja
   que sincronice.

Sigue faltando `gradle/wrapper/gradle-wrapper.jar` en el repo — Android Studio no lo necesita
para sincronizar con su Gradle interno. Si compilas por línea de comandos (`./gradlew`) y
falla por eso, corre `gradle wrapper --gradle-version 8.7` una vez dentro de esta carpeta.

## ▶️ Uso

1. Abre la app: pide correo y clave (el usuario de Firebase Authentication que creaste).
2. Ya dentro, el dashboard organiza todo en paneles plegables por capa (tócalos para abrir/
   cerrar): **Percepción** (sensores, abierto por defecto), **Procesamiento** (modo, control
   de la bomba y umbrales de riego automático) y **Aplicación** (historial de riego). Arriba
   a la derecha, **"🔌 Cerrar sesión"** vuelve al login.
3. Dentro de **Procesamiento**, "Riega bajo %" y "Apaga sobre %" ajustan los umbrales del modo
   automático — **"Guardar umbrales"** los manda a Firebase; el bridge los reenvía al NodeMCU
   la próxima vez que hace su ciclo. Ya no se pierden al reiniciar el NodeMCU: quedan en la base.
4. El dashboard usa listeners en tiempo real de Firebase (no polling): en cuanto el bridge
   actualiza el estado o el historial, la app lo refleja sin recargar nada. Los botones
   ENCENDER/APAGAR solo aparecen en modo MANUAL.
5. Dentro de **Aplicación**, "🔔 Activar notificaciones" pide el permiso de notificaciones
   (Android 13+ lo exige en tiempo de ejecución) y registra el dispositivo — desde ahí recibes
   un aviso cada vez que la bomba se enciende o se apaga, aunque la app esté cerrada.

## 🎨 Diseño

Fondo con degradado violeta y manchas de color difuminadas de verdad (`RenderEffect`, Android 12+; en versiones anteriores se ven como un resplandor suave sin desenfoque). Tarjetas tipo "glass" semi-transparentes con borde violeta sutil y esquinas redondeadas, usando `MaterialCardView` — sin librerías nuevas, ya viene con Material Components.

Ícono propio (`ic_launcher`/`ic_launcher_round`): un brote de dos hojas sobre el mismo degradado violeta, como adaptive icon (`mipmap-anydpi-v26`, Android 8+) con respaldo en PNG para API 24-25.

## 🧱 Stack

- Kotlin, Views + ViewBinding (sin Compose, para mantenerlo simple)
- Firebase Authentication (Email/contraseña) + Firebase Realtime Database + Firebase Cloud Messaging (SDK Android)
- Corrutinas (`lifecycleScope`) para lecturas puntuales; listeners en vivo (`ValueEventListener`) para estado/historial
- `minSdk 24`

## 🗄️ De dónde vienen los datos

La app ya no habla con el NodeMCU directo (eso solo lo hace el [bridge](../bridge)). Lee y
escribe en Realtime Database:

| Nodo | Quién escribe | Qué contiene |
|---|---|---|
| `/estado` | bridge | Última lectura: temperatura, humedad, suelo, luz, modo, bomba |
| `/historial` | bridge | Eventos de riego con timestamp absoluto (persiste entre reinicios del NodeMCU) |
| `/umbrales` | bridge | Umbrales actuales del modo automático |
| `/lecturas` | bridge | Muestra de humedad de suelo cada minuto, para el gráfico de tendencia (la app no lo grafica, pero comparte el mismo nodo que la web) |
| `/dispositivos` | app / web | Token de notificaciones de este dispositivo (se agrega solo al activar "🔔 Activar notificaciones") |
| `/comandos` | app / web | Lo que el usuario pide (cambiar modo, encender/apagar, nuevos umbrales); el bridge lo aplica al NodeMCU |

La tarjeta de luz solo aparece si el NodeMCU detectó un BH1750 al arrancar (`luxLuz` no es
`null` en `/estado`); si no hay sensor, la tarjeta queda oculta sin romper nada.

## 🔒 Seguridad (OWASP Top 10 2021 + ASVS 5.0)

- **Sesión (A07 · ASVS V2)**: maneja Firebase Authentication (Email/contraseña) — la app ya no
  guarda ni cifra ninguna clave localmente, esa responsabilidad es del SDK de Firebase. La misma
  política de contraseñas (longitud mínima 10, sin composición forzada) del proyecto de Firebase
  aplica aquí — ver [detalle en el README de `web/`](../web/README.md#seguridad-owasp-top-10-2021--asvs-50).
- **Acceso a datos (A01 · ASVS V4)**: las reglas de Realtime Database solo dejan a la app
  **leer** `estado`/`historial`/`umbrales` y **escribir** en `comandos` — nunca puede tocar
  directo el estado ni el historial, eso es exclusivo del bridge (Admin SDK). Ver el detalle de
  reglas y validación server-side en el mismo README de `web/`.
- **`android:allowBackup="false"`** en el manifest (ASVS V8, MASVS-STORAGE): evita que un backup
  de Android (ej. `adb backup`) pueda extraer datos de la app en dispositivos rooteados o vía
  depuración USB habilitada. No hay nada sensible que respaldar (la sesión la maneja el SDK de
  Firebase), así que desactivarlo no quita funcionalidad.
- **Componentes exportados al mínimo**: solo `LoginActivity` es `exported="true"` (la exige el
  launcher); `MainActivity` es `exported="false"` — nada más puede lanzarla desde fuera de la app.
- **Por qué el NodeMCU sigue sin HTTPS**: no cambió — evaluamos agregar TLS (BearSSL) al
  firmware y lo descartamos: en un ESP8266 con ~30 KB de RAM libre, una conexión TLS puede
  necesitar ~28 KB solo de buffers, con riesgo real de que el dispositivo se cuelgue. Por eso
  el NodeMCU sigue hablando HTTP plano solo con el bridge, en la red local de confianza, y
  nunca directo con la app ni con internet.
