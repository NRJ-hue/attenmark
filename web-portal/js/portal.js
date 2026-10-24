/**
 * AttendIQ — Faculty Web Portal Controller
 */
import {
  auth,
  db,
  functions,
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
} from "./firebase-config.js";

// ─── Application State ────────────────────────────────────────────────────────
let currentFacultyUser = null;
let courses = [];
let currentCourse = null;
let activeSession = null;
let attendanceRecords = [];
let manualRequests = [];
let currentTab = "all";

let qrRotationTimer = null;
let qrCountdownInterval = null;
let sessionDurationInterval = null;
let recordsUnsubscribe = null;
let manualRequestsUnsubscribe = null;
let coursesUnsubscribe = null;
let qrCodeInstance = null;
let currentRejectIsManual = false;

// ─── DOM Elements ─────────────────────────────────────────────────────────────
const loginView = document.getElementById("login-view");
const dashboardView = document.getElementById("dashboard-view");
const sessionView = document.getElementById("session-view");

const loginForm = document.getElementById("login-form");
const loginEmail = document.getElementById("login-email");
const loginPassword = document.getElementById("login-password");
const loginAlert = document.getElementById("login-alert");
const btnLoginSubmit = document.getElementById("btn-login-submit");

const facultyProfile = document.getElementById("faculty-profile");
const facultyDisplayName = document.getElementById("faculty-display-name");
const facultyDisplayEmail = document.getElementById("faculty-display-email");
const btnLogout = document.getElementById("btn-logout");

const coursesGrid = document.getElementById("courses-grid");
const btnOpenCreateCourse = document.getElementById("btn-open-create-course");
const modalCreateCourse = document.getElementById("modal-create-course");
const formCreateCourse = document.getElementById("form-create-course");
const newCourseName = document.getElementById("new-course-name");
const newCourseCode = document.getElementById("new-course-code");
const createCourseAlert = document.getElementById("create-course-alert");

const btnBackToCourses = document.getElementById("btn-back-to-courses");
const sessionCourseTitle = document.getElementById("session-course-title");
const sessionCourseSubtitle = document.getElementById("session-course-subtitle");

const sessionSetupPanel = document.getElementById("session-setup-panel");
const sessionActivePanel = document.getElementById("session-active-panel");
const sessionDurationSelect = document.getElementById("session-duration");
const sessionLatInput = document.getElementById("session-lat");
const sessionLonInput = document.getElementById("session-lon");
const btnUseCurrentLocation = document.getElementById("btn-use-current-location");
const btnStartSession = document.getElementById("btn-start-session");
const startSessionAlert = document.getElementById("start-session-alert");

const qrCanvas = document.getElementById("qr-canvas");
const qrRotationCountdown = document.getElementById("qr-rotation-countdown");
const rotationProgressBar = document.getElementById("rotation-progress-bar");
const sessionTimeRemaining = document.getElementById("session-time-remaining");
const btnStopSession = document.getElementById("btn-stop-session");

const statEnrolled = document.getElementById("stat-enrolled");
const statApproved = document.getElementById("stat-approved");
const statReview = document.getElementById("stat-review");
const statRejected = document.getElementById("stat-rejected");

const countTabAll = document.getElementById("count-tab-all");
const countTabReview = document.getElementById("count-tab-review");
const countTabApproved = document.getElementById("count-tab-approved");
const countTabRejected = document.getElementById("count-tab-rejected");
const countTabUnsubmitted = document.getElementById("count-tab-unsubmitted");

const recordsTableBody = document.getElementById("records-table-body");
const tableEmptyState = document.getElementById("table-empty-state");
const tabButtons = document.querySelectorAll(".tab-btn");

const modalRejectReason = document.getElementById("modal-reject-reason");
const formRejectReason = document.getElementById("form-reject-reason");
const rejectRecordId = document.getElementById("reject-record-id");
const rejectReasonText = document.getElementById("reject-reason-text");

// ─── View Routing ─────────────────────────────────────────────────────────────
function switchView(viewName) {
  [loginView, dashboardView, sessionView].forEach((v) => v.classList.remove("active"));
  if (viewName === "login") loginView.classList.add("active");
  if (viewName === "dashboard") dashboardView.classList.add("active");
  if (viewName === "session") sessionView.classList.add("active");
}

function showAlert(element, message, type = "error") {
  element.className = `alert-banner ${type}`;
  element.textContent = message;
  element.style.display = "block";
}

function hideAlert(element) {
  element.style.display = "none";
}

// ─── 1. Authentication Lifecycle ──────────────────────────────────────────────
onAuthStateChanged(auth, async (user) => {
  if (user) {
    try {
      const userDocRef = doc(db, "users", user.uid);
      const userSnap = await getDoc(userDocRef);

      if (!userSnap.exists() || userSnap.data().role !== "faculty") {
        await signOut(auth);
        showAlert(loginAlert, "Access Denied: Only verified faculty accounts are permitted to enter this portal.", "error");
        switchView("login");
        return;
      }

      const userData = userSnap.data();
      currentFacultyUser = {
        uid: user.uid,
        email: user.email,
        name: userData.name || user.displayName || user.email.split("@")[0],
        role: "faculty"
      };

      facultyDisplayName.textContent = currentFacultyUser.name;
      facultyDisplayEmail.textContent = currentFacultyUser.email;
      facultyProfile.style.display = "flex";
      btnLogout.style.display = "inline-flex";

      switchView("dashboard");
      listenToFacultyCourses();
    } catch (err) {
      console.error("Error verifying faculty profile:", err);
      showAlert(loginAlert, "Failed to verify faculty permissions: " + err.message, "error");
      await signOut(auth);
      switchView("login");
    }
  } else {
    currentFacultyUser = null;
    facultyProfile.style.display = "none";
    btnLogout.style.display = "none";
    cleanUpActiveSession();
    if (coursesUnsubscribe) coursesUnsubscribe();
    switchView("login");
  }
});

loginForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  hideAlert(loginAlert);

  const email = loginEmail.value.trim();
  const password = loginPassword.value;

  if (!email || !password) {
    showAlert(loginAlert, "Please enter both email and password.", "error");
    return;
  }

  btnLoginSubmit.disabled = true;
  btnLoginSubmit.textContent = "Signing In...";

  try {
    await signInWithEmailAndPassword(auth, email, password);
  } catch (err) {
    console.error("Login failed:", err);
    let msg = "Invalid email or password.";
    if (err.code === "auth/network-request-failed") {
      msg = "Network error: Ensure the Firebase Auth emulator is running on port 9099.";
    }
    showAlert(loginAlert, msg, "error");
  } finally {
    btnLoginSubmit.disabled = false;
    btnLoginSubmit.textContent = "Sign In to Faculty Portal";
  }
});

btnLogout.addEventListener("click", async () => {
  cleanUpActiveSession();
  await signOut(auth);
});

// ─── 2. Courses Dashboard ─────────────────────────────────────────────────────
function listenToFacultyCourses() {
  if (!currentFacultyUser) return;
  if (coursesUnsubscribe) coursesUnsubscribe();

  const q = query(collection(db, "courses"), where("facultyEmail", "==", currentFacultyUser.email));
  coursesUnsubscribe = onSnapshot(q, (snapshot) => {
    courses = snapshot.docs.map((d) => ({ id: d.id, ...d.data() }));
    renderCoursesGrid();
  }, (err) => {
    console.error("Error listening to courses:", err);
  });
}

function renderCoursesGrid() {
  coursesGrid.innerHTML = "";

  if (courses.length === 0) {
    coursesGrid.innerHTML = `
      <div style="grid-column: 1 / -1; text-align: center; padding: 3rem; background: var(--bg-surface); border-radius: 12px; border: 1px dashed var(--border-color);">
        <p style="color: var(--text-muted); margin-bottom: 1rem;">No courses assigned yet.</p>
        <button class="btn btn-primary btn-sm" onclick="document.getElementById('btn-open-create-course').click()">Create First Course</button>
      </div>
    `;
    return;
  }

  courses.forEach((course) => {
    const card = document.createElement("div");
    card.className = "course-card";
    const enrolledCount = (course.enrolledStudents || []).length;

    card.innerHTML = `
      <div>
        <div class="course-card-top">
          <span class="course-code-pill">${escapeHtml(course.classCode)}</span>
          <span style="font-size: 0.8rem; color: var(--text-muted);">${enrolledCount} Students</span>
        </div>
        <div class="course-name">${escapeHtml(course.name)}</div>
        <div class="course-meta">
          <span>👨‍🏫 ${escapeHtml(course.facultyEmail)}</span>
        </div>
      </div>
      <button class="btn btn-primary btn-sm" style="width: 100%; margin-top: 1rem;">
        Manage Attendance Session →
      </button>
    `;

    card.querySelector("button").addEventListener("click", () => {
      openCourseSession(course);
    });

    coursesGrid.appendChild(card);
  });
}

// Create Course Modal
btnOpenCreateCourse.addEventListener("click", () => {
  hideAlert(createCourseAlert);
  formCreateCourse.reset();
  modalCreateCourse.classList.add("open");
});

formCreateCourse.addEventListener("submit", async (e) => {
  e.preventDefault();
  hideAlert(createCourseAlert);

  const name = newCourseName.value.trim();
  let code = newCourseCode.value.trim().toUpperCase();

  if (!name) {
    showAlert(createCourseAlert, "Course name is required.", "error");
    return;
  }

  if (!code) {
    code = (1..6).constructor.name ? Math.random().toString(36).substring(2, 8).toUpperCase() : "CS" + Math.floor(100 + Math.random() * 900);
  }

  try {
    const courseDocRef = doc(collection(db, "courses"));
    await setDoc(courseDocRef, {
      name,
      classCode: code,
      facultyEmail: currentFacultyUser.email,
      enrolledStudents: []
    });

    modalCreateCourse.classList.remove("open");
  } catch (err) {
    console.error("Create course failed:", err);
    showAlert(createCourseAlert, "Failed to create course: " + err.message, "error");
  }
});

// Close modal buttons
document.querySelectorAll("[data-close]").forEach((btn) => {
  btn.addEventListener("click", () => {
    const target = document.getElementById(btn.getAttribute("data-close"));
    if (target) target.classList.remove("open");
  });
});

// ─── 3. Session & Rotating QR Code ────────────────────────────────────────────
async function openCourseSession(course) {
  currentCourse = course;
  sessionCourseTitle.textContent = `${course.classCode} — ${course.name}`;
  sessionCourseSubtitle.textContent = `Class Code: ${course.classCode} | Enrolled: ${(course.enrolledStudents || []).length} students`;

  switchView("session");

  // Check if an active session already exists for this course
  try {
    const sessionDocRef = doc(db, "sessions", course.classCode);
    const sessionSnap = await getDoc(sessionDocRef);

    if (sessionSnap.exists() && sessionSnap.data().isActive === true && (sessionSnap.data().expiresAt || 0) > Date.now()) {
      const data = sessionSnap.data();
      activeSession = {
        sessionId: data.sessionId,
        courseCode: course.classCode,
        latitude: data.latitude,
        longitude: data.longitude,
        expiresAt: data.expiresAt,
        startedAt: data.startedAt,
        isActive: true
      };
      launchActiveSessionUI();
    } else {
      showPreSessionSetup();
    }
  } catch (err) {
    console.error("Error checking active session:", err);
    showPreSessionSetup();
  }
}

btnBackToCourses.addEventListener("click", () => {
  // If leaving view, stop rotation listeners but keep session alive in DB unless ended
  cleanUpActiveSession();
  switchView("dashboard");
});

function showPreSessionSetup() {
  sessionSetupPanel.style.display = "block";
  sessionActivePanel.style.display = "none";
  hideAlert(startSessionAlert);
}

btnUseCurrentLocation.addEventListener("click", () => {
  if (!navigator.geolocation) {
    showAlert(startSessionAlert, "Geolocation is not supported by your browser.", "error");
    return;
  }
  btnUseCurrentLocation.textContent = "📍 Detecting location...";
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      sessionLatInput.value = pos.coords.latitude.toFixed(6);
      sessionLonInput.value = pos.coords.longitude.toFixed(6);
      updateLocationBadge();
      btnUseCurrentLocation.textContent = "✅ Location detected";
      setTimeout(() => (btnUseCurrentLocation.textContent = "📍 Detect & Use My Laptop Location"), 2500);
    },
    (err) => {
      btnUseCurrentLocation.textContent = "📍 Detect & Use My Laptop Location";
      updateLocationBadge();
      showAlert(startSessionAlert, "Could not fetch GPS: " + err.message + ". You must enter valid classroom coordinates manually before starting.", "danger");
    },
    { enableHighAccuracy: true, timeout: 10000 }
  );
});

function updateLocationBadge() {
  const badge = document.getElementById("location-status-badge");
  if (!badge) return;
  const lat = parseFloat(sessionLatInput.value);
  const lon = parseFloat(sessionLonInput.value);
  if (!isNaN(lat) && !isNaN(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 && !(lat === 0 && lon === 0)) {
    badge.textContent = `✅ Configured (Lat: ${lat.toFixed(5)}, Lon: ${lon.toFixed(5)})`;
    badge.style.color = "#4ade80";
  } else {
    badge.textContent = "Not configured";
    badge.style.color = "#f87171";
  }
}

sessionLatInput.addEventListener("input", updateLocationBadge);
sessionLonInput.addEventListener("input", updateLocationBadge);

btnStartSession.addEventListener("click", async () => {
  hideAlert(startSessionAlert);

  const durationMinutes = parseInt(sessionDurationSelect.value, 10) || 10;
  const latStr = (sessionLatInput.value || "").trim();
  const lonStr = (sessionLonInput.value || "").trim();

  if (!latStr || !lonStr) {
    showAlert(startSessionAlert, "Cannot start attendance session: Classroom GPS coordinates are not configured. A valid physical location is required so student proximity can be verified against the 50m classroom geofence. Please click 'Detect & Use My Laptop Location' or enter your classroom's coordinates.", "danger");
    return;
  }

  const latitude = parseFloat(latStr);
  const longitude = parseFloat(lonStr);

  if (isNaN(latitude) || isNaN(longitude) || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 || (latitude === 0 && longitude === 0)) {
    showAlert(startSessionAlert, "Cannot start attendance session: Please enter valid non-zero classroom coordinates (Latitude [-90, 90], Longitude [-180, 180]).", "danger");
    return;
  }

  btnStartSession.disabled = true;
  btnStartSession.textContent = "Starting Session...";

  try {
    // Call backend Cloud Function for atomic session creation
    const startFn = httpsCallable(functions, "startAttendanceSession");
    const res = await startFn({
      courseCode: currentCourse.classCode,
      durationMinutes,
      latitude,
      longitude
    });

    activeSession = {
      sessionId: res.data.sessionId,
      courseCode: currentCourse.classCode,
      latitude: res.data.latitude || latitude,
      longitude: res.data.longitude || longitude,
      expiresAt: res.data.expiresAt,
      startedAt: res.data.startedAt,
      isActive: true
    };

    launchActiveSessionUI();
  } catch (err) {
    console.error("Start session failed:", err);
    showAlert(startSessionAlert, "Could not start session: " + (err.message || "Failed to start attendance session on server."), "error");
  } finally {
    btnStartSession.disabled = false;
    btnStartSession.textContent = "🚀 Start Attendance Session";
  }
});

function launchActiveSessionUI() {
  sessionSetupPanel.style.display = "none";
  sessionActivePanel.style.display = "grid";

  // Display active geofence coordinates
  const coordsEl = document.getElementById("active-session-coords");
  if (coordsEl && activeSession) {
    const lat = activeSession.latitude !== undefined && activeSession.latitude !== null ? Number(activeSession.latitude).toFixed(5) : null;
    const lon = activeSession.longitude !== undefined && activeSession.longitude !== null ? Number(activeSession.longitude).toFixed(5) : null;
    coordsEl.textContent = (lat && lon) ? `Lat: ${lat}, Lon: ${lon}` : "Configured";
  }

  // Trigger immediate QR token generation
  refreshQrToken();

  // Start 25-second rotation cadence
  clearInterval(qrRotationTimer);
  qrRotationTimer = setInterval(refreshQrToken, 25000);

  // Start smooth progress and countdown timer
  startCountdownTimers();

  // Attach live Firestore listener to attendance records
  listenToLiveAttendance();
}

async function refreshQrToken() {
  if (!activeSession || !activeSession.isActive) return;

  try {
    const genQrFn = httpsCallable(functions, "generateQrToken");
    const res = await genQrFn({
      courseCode: activeSession.courseCode,
      sessionId: activeSession.sessionId
    });

    const tokenId = res.data.tokenId;
    renderQrCode(tokenId);
    resetRotationCountdown();
  } catch (err) {
    console.error("Error refreshing QR token:", err);
    if (err.message && err.message.includes("Session is inactive")) {
      stopSessionLocal();
    }
  }
}

function renderQrCode(rawOpaqueToken) {
  qrCanvas.innerHTML = "";
  qrCodeInstance = new QRCode(qrCanvas, {
    text: rawOpaqueToken,
    width: 320,
    height: 320,
    colorDark: "#000000",
    colorLight: "#ffffff",
    correctLevel: QRCode.CorrectLevel.H
  });
}

function resetRotationCountdown() {
  let secondsLeft = 25;
  qrRotationCountdown.textContent = `${secondsLeft}s`;
  rotationProgressBar.style.width = "100%";

  clearInterval(qrCountdownInterval);
  qrCountdownInterval = setInterval(() => {
    secondsLeft -= 1;
    if (secondsLeft < 0) secondsLeft = 0;
    qrRotationCountdown.textContent = `${secondsLeft}s`;
    const pct = (secondsLeft / 25) * 100;
    rotationProgressBar.style.width = `${pct}%`;

    if (secondsLeft <= 0) {
      clearInterval(qrCountdownInterval);
    }
  }, 1000);
}

function startCountdownTimers() {
  clearInterval(sessionDurationInterval);

  function updateSessionClock() {
    if (!activeSession) return;
    const now = Date.now();
    const remainingMs = Math.max(0, activeSession.expiresAt - now);

    if (remainingMs <= 0) {
      sessionTimeRemaining.textContent = "00:00 (EXPIRED)";
      clearInterval(sessionDurationInterval);
      stopSessionLocal();
      return;
    }

    const totalSeconds = Math.floor(remainingMs / 1000);
    const m = Math.floor(totalSeconds / 60);
    const s = totalSeconds % 60;
    sessionTimeRemaining.textContent = `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
  }

  updateSessionClock();
  sessionDurationInterval = setInterval(updateSessionClock, 1000);
}

btnStopSession.addEventListener("click", async () => {
  if (!confirm("Are you sure you want to end this attendance session? Students will no longer be able to scan or submit.")) {
    return;
  }
  btnStopSession.disabled = true;
  btnStopSession.textContent = "Ending Session...";

  try {
    const stopFn = httpsCallable(functions, "stopAttendanceSession");
    await stopFn({ courseCode: activeSession.courseCode });
    stopSessionLocal();
  } catch (err) {
    console.error("Backend stop call failed:", err);
    alert("Could not end attendance session on server: " + (err.message || "Failed to stop session."));
  } finally {
    btnStopSession.disabled = false;
    btnStopSession.textContent = "⏹ End Attendance Session";
  }
});

function stopSessionLocal() {
  cleanUpActiveSession();
  showPreSessionSetup();
}

function cleanUpActiveSession() {
  clearInterval(qrRotationTimer);
  clearInterval(qrCountdownInterval);
  clearInterval(sessionDurationInterval);
  if (recordsUnsubscribe) recordsUnsubscribe();
  if (manualRequestsUnsubscribe) manualRequestsUnsubscribe();
  qrCanvas.innerHTML = "";
  activeSession = null;
  manualRequests = [];
}

// ─── 4. Live Attendance Dashboard ─────────────────────────────────────────────
function listenToLiveAttendance() {
  if (!activeSession) return;
  if (recordsUnsubscribe) recordsUnsubscribe();
  if (manualRequestsUnsubscribe) manualRequestsUnsubscribe();

  // 1. Listen to verified attendance records
  const qRecords = query(
    collection(db, "attendance_records"),
    where("courseCode", "==", activeSession.courseCode)
  );

  recordsUnsubscribe = onSnapshot(qRecords, (snapshot) => {
    attendanceRecords = snapshot.docs
      .map((d) => ({ id: d.id, ...d.data(), isManualRequest: false }))
      .filter((r) => r.sessionId === activeSession.sessionId);

    updateStatsAndTable();
  }, (err) => {
    console.error("Error listening to attendance records:", err);
  });

  // 2. Listen to unverified manual attendance requests
  const qManual = query(
    collection(db, "manual_attendance_requests"),
    where("courseCode", "==", activeSession.courseCode)
  );

  manualRequestsUnsubscribe = onSnapshot(qManual, (snapshot) => {
    manualRequests = snapshot.docs
      .map((d) => ({ id: d.id, ...d.data(), isManualRequest: true }))
      .filter((r) => r.sessionId === activeSession.sessionId);

    updateStatsAndTable();
  }, (err) => {
    console.error("Error listening to manual requests:", err);
  });
}

function updateStatsAndTable() {
  const enrolledStudents = (currentCourse && currentCourse.enrolledStudents) || [];
  const enrolledCount = enrolledStudents.length;

  // Final records in attendance_records take precedence if a student has both
  const verifiedStudentUids = new Set(attendanceRecords.map((r) => r.studentUid));
  const activeManualRequests = manualRequests.filter((m) => !verifiedStudentUids.has(m.studentUid));
  const combinedEntries = [...attendanceRecords, ...activeManualRequests];

  const approvedRecords = combinedEntries.filter((r) => r.status === "APPROVED");
  const reviewRecords = combinedEntries.filter((r) => r.status === "REVIEW" || r.status === "PENDING");
  const rejectedRecords = combinedEntries.filter((r) => r.status === "REJECTED");

  // Submitted student emails
  const submittedEmails = new Set(combinedEntries.map((r) => (r.studentEmail || "").toLowerCase()));
  const unsubmittedStudents = enrolledStudents.filter((e) => !submittedEmails.has(e.toLowerCase()));

  // Stats cards
  statEnrolled.textContent = enrolledCount;
  statApproved.textContent = approvedRecords.length;
  statReview.textContent = reviewRecords.length;
  statRejected.textContent = rejectedRecords.length;

  // Tab counters
  countTabAll.textContent = combinedEntries.length;
  countTabReview.textContent = reviewRecords.length;
  countTabApproved.textContent = approvedRecords.length;
  countTabRejected.textContent = rejectedRecords.length;
  countTabUnsubmitted.textContent = unsubmittedStudents.length;

  renderRecordsTable(unsubmittedStudents, combinedEntries);
}

tabButtons.forEach((btn) => {
  btn.addEventListener("click", () => {
    tabButtons.forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    currentTab = btn.getAttribute("data-tab");
    updateStatsAndTable();
  });
});

function renderRecordsTable(unsubmittedStudents = [], combinedEntries = []) {
  recordsTableBody.innerHTML = "";

  if (currentTab === "unsubmitted") {
    if (unsubmittedStudents.length === 0) {
      tableEmptyState.style.display = "block";
      tableEmptyState.textContent = "All enrolled students have submitted attendance!";
      return;
    }
    tableEmptyState.style.display = "none";
    unsubmittedStudents.forEach((email) => {
      const row = document.createElement("tr");
      row.innerHTML = `
        <td><strong style="color: var(--text-muted);">Enrolled Student</strong></td>
        <td><code>${escapeHtml(email)}</code></td>
        <td style="color: var(--text-muted);">—</td>
        <td style="color: var(--text-muted);">—</td>
        <td><span class="badge badge-unsubmitted">Not Submitted</span></td>
        <td style="color: var(--text-muted); font-size: 0.8rem;">Awaiting scan</td>
      `;
      recordsTableBody.appendChild(row);
    });
    return;
  }

  let filtered = combinedEntries;
  if (currentTab === "review") filtered = combinedEntries.filter((r) => r.status === "REVIEW" || r.status === "PENDING");
  if (currentTab === "approved") filtered = combinedEntries.filter((r) => r.status === "APPROVED");
  if (currentTab === "rejected") filtered = combinedEntries.filter((r) => r.status === "REJECTED");

  if (filtered.length === 0) {
    tableEmptyState.style.display = "block";
    tableEmptyState.textContent = "No attendance submissions matching this category.";
    return;
  }

  tableEmptyState.style.display = "none";

  filtered.forEach((rec) => {
    const row = document.createElement("tr");
    const isManual = rec.isManualRequest || rec.matchResult === "MANUAL_REQUEST";
    const formattedTime = rec.timestamp ? new Date(rec.timestamp).toLocaleTimeString() : "Just now";

    let badgeClass = "badge-review";
    if (rec.status === "APPROVED") badgeClass = "badge-approved";
    if (rec.status === "REJECTED") badgeClass = "badge-rejected";

    let actionsHtml = `<span style="color: var(--text-muted); font-size: 0.8rem;">None required</span>`;
    if (rec.status === "REVIEW" || rec.status === "PENDING") {
      actionsHtml = `
        <div style="display: flex; gap: 0.4rem;">
          <button class="btn btn-success btn-sm btn-approve" data-id="${escapeHtml(rec.id)}" data-manual="${isManual ? "true" : "false"}">Approve</button>
          <button class="btn btn-danger btn-sm btn-reject" data-id="${escapeHtml(rec.id)}" data-manual="${isManual ? "true" : "false"}">Reject</button>
        </div>
      `;
    }

    row.innerHTML = `
      <td>
        <strong>${escapeHtml(rec.studentName || "Student")}</strong>
        ${isManual ? `<span class="badge badge-manual" style="margin-left: 0.3rem;">Manual Request (No QR)</span>` : ""}
        ${rec.rejectionReason ? `<div style="font-size: 0.75rem; color: #f87171; margin-top: 0.2rem;">Reason: ${escapeHtml(rec.rejectionReason)}</div>` : ""}
      </td>
      <td><code>${escapeHtml(rec.studentEmail || "")}</code></td>
      <td>${formattedTime}</td>
      <td>
        ${isManual ? `<strong style="color: #f59e0b;">0% (Unverified)</strong>` : `<strong>${rec.overallScore || 0}%</strong>`}
        ${rec.matchPercentage ? `<span style="font-size: 0.75rem; color: var(--text-muted); display: block;">Match: ${rec.matchPercentage}%</span>` : ""}
      </td>
      <td>
        <span class="badge ${badgeClass}">${escapeHtml(rec.status)}</span>
      </td>
      <td>${actionsHtml}</td>
    `;

    // Action button listeners
    const btnApprove = row.querySelector(".btn-approve");
    if (btnApprove) {
      btnApprove.addEventListener("click", () => approveRecord(rec.id, isManual));
    }

    const btnReject = row.querySelector(".btn-reject");
    if (btnReject) {
      btnReject.addEventListener("click", () => openRejectModal(rec.id, isManual));
    }

    recordsTableBody.appendChild(row);
  });
}

async function approveRecord(recordId, isManual = false) {
  try {
    if (isManual) {
      const manualDoc = manualRequests.find((m) => m.id === recordId);
      if (!manualDoc) {
        throw new Error("Manual request document not found.");
      }
      if (manualDoc.status === "APPROVED") {
        console.warn("Manual request is already approved.");
        return;
      }

      // Atomic batch write: simultaneously updates manual request status to APPROVED
      // and writes the final verified attendance record.
      // Guarantees no partial state where one succeeds while the other fails.
      const batch = writeBatch(db);
      const manualRef = doc(db, "manual_attendance_requests", recordId);
      const recordRef = doc(db, "attendance_records", recordId);

      batch.update(manualRef, {
        status: "APPROVED"
      });
      batch.set(recordRef, {
        sessionId: manualDoc.sessionId,
        studentUid: manualDoc.studentUid,
        studentName: manualDoc.studentName || "Student",
        studentEmail: manualDoc.studentEmail,
        courseCode: manualDoc.courseCode,
        status: "APPROVED",
        matchResult: "MANUAL_REQUEST_APPROVED",
        overallScore: 100,
        timestamp: Date.now(),
        rejectionReason: null
      });

      await batch.commit();
    } else {
      await updateDoc(doc(db, "attendance_records", recordId), {
        status: "APPROVED"
      });
    }
  } catch (err) {
    console.error("Approve failed:", err);
    alert("Could not approve record: " + err.message);
  }
}

function openRejectModal(recordId, isManual = false) {
  rejectRecordId.value = recordId;
  currentRejectIsManual = isManual;
  rejectReasonText.value = "";
  modalRejectReason.classList.add("open");
}

formRejectReason.addEventListener("submit", async (e) => {
  e.preventDefault();
  const id = rejectRecordId.value;
  const reason = rejectReasonText.value.trim() || "Rejected by faculty";

  try {
    const collName = currentRejectIsManual ? "manual_attendance_requests" : "attendance_records";
    await updateDoc(doc(db, collName, id), {
      status: "REJECTED",
      rejectionReason: reason
    });
    modalRejectReason.classList.remove("open");
  } catch (err) {
    console.error("Reject failed:", err);
    alert("Could not reject record: " + err.message);
  }
});

// ─── Utility ──────────────────────────────────────────────────────────────────
function escapeHtml(str) {
  if (!str) return "";
  return String(str)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}
