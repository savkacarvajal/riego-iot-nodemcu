import {
  signInWithEmailAndPassword,
  onAuthStateChanged,
} from "https://www.gstatic.com/firebasejs/10.13.0/firebase-auth.js";
import { auth } from "./firebase.js";
import { registrarServiceWorker } from "./sw-registro.js";

registrarServiceWorker();

const form = document.getElementById("form-login");
const error = document.getElementById("error");
const btnEntrar = document.getElementById("btn-entrar");

onAuthStateChanged(auth, (usuario) => {
  if (usuario) location.href = "index.html";
});

const textoOriginalBoton = btnEntrar.textContent;

form.addEventListener("submit", async (evento) => {
  evento.preventDefault();
  error.textContent = "";
  btnEntrar.disabled = true;
  btnEntrar.innerHTML = '<span class="girando"></span>Ingresando…';

  const email = document.getElementById("email").value.trim();
  const clave = document.getElementById("clave").value;

  try {
    await signInWithEmailAndPassword(auth, email, clave);
    location.href = "index.html";
  } catch (err) {
    error.textContent = "Correo o clave incorrectos.";
    btnEntrar.disabled = false;
    btnEntrar.textContent = textoOriginalBoton;
  }
});
