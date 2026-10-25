/**
 * AttendIQ Faculty Portal — Firebase Configuration & Emulator Integration
 */
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.12.0/firebase-app.js";
import {
  getAuth,
  connectAuthEmulator,
  signInWithEmailAndPassword,
  signOut,
  onAuthStateChanged
} from "https://www.gstatic.com/firebasejs/10.12.0/firebase-auth.js";
import {
  getFirestore,
  connectFirestoreEmulator,
  doc,
  getDoc,
  setDoc,
  updateDoc,
  collection,
  query,
  where,
  onSnapshot,
  writeBatch
} from "https://www.gstatic.com/firebasejs/10.12.0/firebase-firestore.js";
import {
  getFunctions,
  connectFunctionsEmulator,
  httpsCallable
} from "https://www.gstatic.com/firebasejs/10.12.0/firebase-functions.js";

// Firebase project configuration
const firebaseConfig = {
  projectId: "attendmark-c2f07",
  apiKey: "AIzaSyBPX1lzhabOvpdpZAhq7lfGXUJ7dxQ07WE",
  authDomain: "attendmark-c2f07.firebaseapp.com",
  storageBucket: "attendmark-c2f07.firebasestorage.app",
  appId: "1:326779359358:web:a3b3ecfb18b9376c41082f"
};

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const db = getFirestore(app);
const functions = getFunctions(app);

// Connect to Local Firebase Emulators when running locally
const isLocal =
  window.location.hostname === "localhost" ||
  window.location.hostname === "127.0.0.1" ||
  window.location.protocol === "file:";

if (isLocal) {
  try {
    connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
    connectFirestoreEmulator(db, "127.0.0.1", 8080);
    connectFunctionsEmulator(functions, "127.0.0.1", 5001);
    console.log("Connected to Firebase Local Emulator Suite (Auth: 9099, Firestore: 8080, Functions: 5001)");
  } catch (err) {
    console.warn("Notice during emulator connection (may already be initialized):", err.message);
  }
}

/**
 * Executes a call to the AttendIQ backend API.
 * In Cloud Production (Firebase Hosting + Render):
 *   Makes an authenticated POST request to Render with Bearer Firebase ID Token.
 * In Local Development:
 *   Attempts local backend URL (http://127.0.0.1:3000) or falls back to Firebase Functions emulator.
 */
async function callBackendApi(endpoint, data = {}) {
  const currentUser = auth.currentUser;
  if (!currentUser) {
    throw new Error("User must be authenticated.");
  }

  const idToken = await currentUser.getIdToken();
  const defaultProdBackendUrl = "https://attenmark.onrender.com";
  const backendBaseUrl = window.ATTENDIQ_BACKEND_URL ||
    (isLocal
      ? (localStorage.getItem("attendiq_backend_url") || "http://127.0.0.1:3000")
      : defaultProdBackendUrl);

  const normalizedBase = backendBaseUrl.replace(/\/+$/, "");
  const targetUrl = `${normalizedBase}/api/${endpoint}`;

  try {
    const res = await fetch(targetUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${idToken}`
      },
      body: JSON.stringify({ data })
    });

    const resJson = await res.json().catch(() => ({}));
    if (!res.ok) {
      const err = new Error((resJson.error && resJson.error.message) || `API error (${res.status})`);
      err.code = resJson.error && resJson.error.code;
      throw err;
    }

    const returnData = resJson.data !== undefined ? resJson.data : resJson;
    return { data: returnData };
  } catch (err) {
    if (isLocal) {
      console.warn(`Backend fetch to ${targetUrl} failed (${err.message}). Trying Cloud Functions emulator fallback.`);
      const fn = httpsCallable(functions, endpoint);
      return await fn(data);
    }
    throw err;
  }
}

export {
  app,
  auth,
  db,
  functions,
  isLocal,
  callBackendApi,
  signInWithEmailAndPassword,
  signOut,
  onAuthStateChanged,
  doc,
  getDoc,
  setDoc,
  updateDoc,
  collection,
  query,
  where,
  onSnapshot,
  writeBatch,
  httpsCallable
};
