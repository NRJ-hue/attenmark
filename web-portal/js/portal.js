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
  httpsCallable
} from "./firebase-config.js";

// ─── Application State ────────────────────────────────────────────────────────
let currentFacultyUser = null;
let courses = [];
let currentCourse = null;
let activeSession = null;
let attendanceRecords = [];
let currentTab = "all";

let qrRotationTimer = null;
let qrCountdownInterval = null;
let sessionDurationInterval = null;
let recordsUnsubscribe = null;
let coursesUnsubscribe = null;
let qrCodeInstance = null;

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
      btnUseCurrentLocation.textContent = "✅ Location detected";
      setTimeout(() => (btnUseCurrentLocation.textContent = "📍 Detect & Use My Laptop Location"), 2500);
    },
    (err) => {
      btnUseCurrentLocation.textContent = "📍 Detect & Use My Laptop Location";
      showAlert(startSessionAlert, "Could not fetch GPS: " + err.message + ". You may enter coordinates manually.", "info");
    },
    { enableHighAccuracy: true, timeout: 10000 }
  );
});

btnStartSession.addEventListener("click", async () => {
  hideAlert(startSessionAlert);
  btnStartSession.disabled = true;
  btnStartSession.textContent = "Starting Session...";

  const durationMinutes = parseInt(sessionDurationSelect.value, 10) || 10;
  const latitude = parseFloat(sessionLatInput.value) || 0.0;
  const longitude = parseFloat(sessionLonInput.value) || 0.0;

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
      expiresAt: res.data.expiresAt,
      startedAt: res.data.startedAt,
      isActive: true
    };

    launchActiveSessionUI();
  } catch (err) {
    console.error("Start session failed:", err);
    // Client fallback batch write
    try {
      const sessionId = crypto.randomUUID();
      const startedAt = Date.now();
      const expiresAt = startedAt + durationMinutes * 60 * 1000;
      const sessionPayload = {
        sessionId,
        courseCode: currentCourse.classCode,
        facultyEmail: currentFacultyUser.email,
        latitude,
        longitude,
        isActive: true,
        durationMinutes,
        startedAt,
        expiresAt
      };

      await setDoc(doc(db, "attendance_sessions", sessionId), sessionPayload);
      await setDoc(doc(db, "sessions", currentCourse.classCode), sessionPayload);

      activeSession = {
        sessionId,
        courseCode: currentCourse.classCode,
        expiresAt,
        startedAt,
        isActive: true
      };

      launchActiveSessionUI();
    } catch (fallbackErr) {
      console.error("Fallback start failed:", fallbackErr);
      showAlert(startSessionAlert, "Could not start session: " + err.message, "error");
    }
  } finally {
    btnStartSession.disabled = false;
    btnStartSession.textContent = "🚀 Start Attendance Session";
  }
});

function launchActiveSessionUI() {
  sessionSetupPanel.style.display = "none";
  sessionActivePanel.style.display = "grid";

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
  } catch (err) {
    console.warn("Backend stop call failed, running atomic Firestore update:", err);
    try {
      const now = Date.now();
      await updateDoc(doc(db, "sessions", activeSession.courseCode), { isActive: false, stoppedAt: now });
      if (activeSession.sessionId) {
        await updateDoc(doc(db, "attendance_sessions", activeSession.sessionId), { isActive: false, stoppedAt: now });
      }
    } catch (e) {
      console.error("Direct stop update error:", e);
    }
  }

  stopSessionLocal();
  btnStopSession.disabled = false;
  btnStopSession.textContent = "⏹ End Attendance Session";
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
  qrCanvas.innerHTML = "";
  activeSession = null;
}

// ─── 4. Live Attendance Dashboard ─────────────────────────────────────────────
function listenToLiveAttendance() {
  if (!activeSession) return;
  if (recordsUnsubscribe) recordsUnsubscribe();

  const q = query(
    collection(db, "attendance_records"),
    where("courseCode", "==", activeSession.courseCode)
  );

  recordsUnsubscribe = onSnapshot(q, (snapshot) => {
    // Filter strictly to current session
    attendanceRecords = snapshot.docs
      .map((d) => ({ id: d.id, ...d.data() }))
      .filter((r) => r.sessionId === activeSession.sessionId);

    updateStatsAndTable();
  }, (err) => {
    console.error("Error listening to attendance records:", err);
  });
}

function updateStatsAndTable() {
  const enrolledStudents = (currentCourse && currentCourse.enrolledStudents) || [];
  const enrolledCount = enrolledStudents.length;

  const approvedRecords = attendanceRecords.filter((r) => r.status === "APPROVED");
  const reviewRecords = attendanceRecords.filter((r) => r.status === "REVIEW" || r.status === "PENDING");
  const rejectedRecords = attendanceRecords.filter((r) => r.status === "REJECTED");

  // Submitted student emails
  const submittedEmails = new Set(attendanceRecords.map((r) => (r.studentEmail || "").toLowerCase()));
  const unsubmittedStudents = enrolledStudents.filter((e) => !submittedEmails.has(e.toLowerCase()));

  // Stats cards
  statEnrolled.textContent = enrolledCount;
  statApproved.textContent = approvedRecords.length;
  statReview.textContent = reviewRecords.length;
  statRejected.textContent = rejectedRecords.length;

  // Tab counters
  countTabAll.textContent = attendanceRecords.length;
  countTabReview.textContent = reviewRecords.length;
  countTabApproved.textContent = approvedRecords.length;
  countTabRejected.textContent = rejectedRecords.length;
  countTabUnsubmitted.textContent = unsubmittedStudents.length;

  renderRecordsTable(unsubmittedStudents);
}

tabButtons.forEach((btn) => {
  btn.addEventListener("click", () => {
    tabButtons.forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    currentTab = btn.getAttribute("data-tab");
    updateStatsAndTable();
  });
});

function renderRecordsTable(unsubmittedStudents = []) {
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

  let filtered = attendanceRecords;
  if (currentTab === "review") filtered = attendanceRecords.filter((r) => r.status === "REVIEW" || r.status === "PENDING");
  if (currentTab === "approved") filtered = attendanceRecords.filter((r) => r.status === "APPROVED");
  if (currentTab === "rejected") filtered = attendanceRecords.filter((r) => r.status === "REJECTED");

  if (filtered.length === 0) {
    tableEmptyState.style.display = "block";
    tableEmptyState.textContent = "No attendance submissions matching this category.";
    return;
  }

  tableEmptyState.style.display = "none";

  filtered.forEach((rec) => {
    const row = document.createElement("tr");
    const isManual = rec.matchResult === "MANUAL_REQUEST";
    const formattedTime = rec.timestamp ? new Date(rec.timestamp).toLocaleTimeString() : "Just now";

    let badgeClass = "badge-review";
    if (rec.status === "APPROVED") badgeClass = "badge-approved";
    if (rec.status === "REJECTED") badgeClass = "badge-rejected";

    let actionsHtml = `<span style="color: var(--text-muted); font-size: 0.8rem;">None required</span>`;
    if (rec.status === "REVIEW" || rec.status === "PENDING") {
      actionsHtml = `
        <div style="display: flex; gap: 0.4rem;">
          <button class="btn btn-success btn-sm btn-approve" data-id="${escapeHtml(rec.id)}">Approve</button>
          <button class="btn btn-danger btn-sm btn-reject" data-id="${escapeHtml(rec.id)}">Reject</button>
        </div>
      `;
    }

    row.innerHTML = `
      <td>
        <strong>${escapeHtml(rec.studentName || "Student")}</strong>
        ${isManual ? `<span class="badge badge-manual" style="margin-left: 0.3rem;">Manual Request</span>` : ""}
        ${rec.rejectionReason ? `<div style="font-size: 0.75rem; color: #f87171; margin-top: 0.2rem;">Reason: ${escapeHtml(rec.rejectionReason)}</div>` : ""}
      </td>
      <td><code>${escapeHtml(rec.studentEmail || "")}</code></td>
      <td>${formattedTime}</td>
      <td>
        <strong>${rec.overallScore || 0}%</strong>
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
      btnApprove.addEventListener("click", () => approveRecord(rec.id));
    }

    const btnReject = row.querySelector(".btn-reject");
    if (btnReject) {
      btnReject.addEventListener("click", () => openRejectModal(rec.id));
    }

    recordsTableBody.appendChild(row);
  });
}

async function approveRecord(recordId) {
  try {
    await updateDoc(doc(db, "attendance_records", recordId), {
      status: "APPROVED"
    });
  } catch (err) {
    console.error("Approve failed:", err);
    alert("Could not approve record: " + err.message);
  }
}

function openRejectModal(recordId) {
  rejectRecordId.value = recordId;
  rejectReasonText.value = "";
  modalRejectReason.classList.add("open");
}

formRejectReason.addEventListener("submit", async (e) => {
  e.preventDefault();
  const id = rejectRecordId.value;
  const reason = rejectReasonText.value.trim() || "Rejected by faculty";

  try {
    await updateDoc(doc(db, "attendance_records", id), {
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
