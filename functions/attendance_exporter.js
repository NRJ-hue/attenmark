/**
 * AttendIQ Attendance Export Engine
 *
 * Generates standards-compliant OpenXML (.xlsx) Excel workbooks containing
 * course attendance matrices with Roll Numbers and session date columns.
 */

const MONTH_NAMES = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/**
 * Extracts a student roll number from an email address (portion before @).
 * E.g., "25bcs254@iiitdwd.ac.in" -> "25bcs254".
 *
 * @param {string} email
 * @returns {string}
 */
function extractRollNo(email) {
  if (!email || typeof email !== "string") return "UNKNOWN";
  const trimmed = email.trim().toLowerCase();
  const atIndex = trimmed.indexOf("@");
  if (atIndex > 0) {
    return trimmed.substring(0, atIndex);
  }
  return trimmed || "UNKNOWN";
}

/**
 * Formats a timestamp into a readable date string: DD-MMM-YYYY.
 * E.g., 1761465600000 -> "26-Oct-2026".
 *
 * @param {number|Date} timestamp
 * @returns {string}
 */
function formatDateKey(timestamp) {
  const d = new Date(timestamp);
  if (isNaN(d.getTime())) return "Unknown Date";
  const day = String(d.getDate()).padStart(2, "0");
  const month = MONTH_NAMES[d.getMonth()] || "Jan";
  const year = d.getFullYear();
  return `${day}-${month}-${year}`;
}

/**
 * Formats a timestamp into 12-hour time: e.g. "10:30 AM".
 *
 * @param {number|Date} timestamp
 * @returns {string}
 */
function formatTimePart(timestamp) {
  const d = new Date(timestamp);
  if (isNaN(d.getTime())) return "";
  let hours = d.getHours();
  const minutes = String(d.getMinutes()).padStart(2, "0");
  const ampm = hours >= 12 ? "PM" : "AM";
  hours = hours % 12;
  hours = hours ? hours : 12;
  return `${hours}:${minutes} ${ampm}`;
}

/**
 * Formats a session column header:
 * - If only one session on that date: "26-Oct-2026"
 * - If multiple sessions on the same date: "26-Oct-2026 (10:00 AM)"
 *
 * @param {object} session
 * @param {Array<object>} allSessions
 * @returns {string}
 */
function formatSessionDateHeader(session, allSessions = []) {
  const ts = session.startedAt || session.timestamp || Date.now();
  const dateStr = formatDateKey(ts);

  const sameDateSessions = allSessions.filter((s) => {
    const sTs = s.startedAt || s.timestamp || 0;
    return formatDateKey(sTs) === dateStr;
  });

  if (sameDateSessions.length > 1) {
    const timeStr = formatTimePart(ts);
    return `${dateStr} (${timeStr})`;
  }

  return dateStr;
}

/**
 * Detects all pending / unresolved reviews across records and manual requests.
 *
 * @param {Array<object>} records
 * @param {Array<object>} manualRequests
 * @returns {{ count: number, pendingList: Array<object> }}
 */
function detectPendingReviews(records = [], manualRequests = []) {
  const pendingRecords = records.filter((r) => r.status === "REVIEW" || r.status === "PENDING");
  const pendingManual = manualRequests.filter((m) => m.status === "REVIEW" || m.status === "PENDING");

  const combined = [...pendingRecords, ...pendingManual];
  // Deduplicate by student and session if both exist
  const seen = new Set();
  const uniquePending = [];
  combined.forEach((item) => {
    const key = `${item.sessionId}_${item.studentUid || item.studentEmail}`;
    if (!seen.has(key)) {
      seen.add(key);
      uniquePending.push(item);
    }
  });

  return {
    count: uniquePending.length,
    pendingList: uniquePending
  };
}

/**
 * Builds the attendance matrix for export.
 *
 * @param {object} course
 * @param {Array<object>} sessions
 * @param {Array<object>} records
 * @param {Array<object>} manualRequests
 * @param {object} [options]
 * @returns {object}
 */
function buildAttendanceMatrix(course, sessions = [], records = [], manualRequests = [], options = {}) {
  const { dateFrom, dateTo, isProvisional = false } = options;

  // 1. Filter sessions by date range if provided
  let filteredSessions = [...sessions];
  if (dateFrom) {
    const fromMs = new Date(dateFrom).setHours(0, 0, 0, 0);
    filteredSessions = filteredSessions.filter((s) => (s.startedAt || s.timestamp || 0) >= fromMs);
  }
  if (dateTo) {
    const toMs = new Date(dateTo).setHours(23, 59, 59, 999);
    filteredSessions = filteredSessions.filter((s) => (s.startedAt || s.timestamp || 0) <= toMs);
  }

  // Sort sessions chronologically (oldest to newest)
  filteredSessions.sort((a, b) => (a.startedAt || a.timestamp || 0) - (b.startedAt || b.timestamp || 0));

  // 2. Filter records to target sessions
  const targetSessionIds = new Set(filteredSessions.map((s) => s.sessionId));
  const relevantRecords = records.filter((r) => targetSessionIds.has(r.sessionId));
  const relevantManual = manualRequests.filter((m) => targetSessionIds.has(m.sessionId));

  // 3. Detect pending reviews
  const { count: pendingReviewsCount, pendingList } = detectPendingReviews(relevantRecords, relevantManual);
  const hasPendingReviews = pendingReviewsCount > 0;

  // 4. Collect student roster
  // Combine course.enrolledStudents with students who submitted records or requests
  const studentMap = new Map(); // email -> rollNo

  (course.enrolledStudents || []).forEach((email) => {
    if (email && typeof email === "string") {
      const cleanEmail = email.trim().toLowerCase();
      studentMap.set(cleanEmail, extractRollNo(cleanEmail));
    }
  });

  relevantRecords.forEach((r) => {
    if (r.studentEmail) {
      const cleanEmail = r.studentEmail.trim().toLowerCase();
      if (!studentMap.has(cleanEmail)) {
        studentMap.set(cleanEmail, extractRollNo(cleanEmail));
      }
    }
  });

  relevantManual.forEach((m) => {
    if (m.studentEmail) {
      const cleanEmail = m.studentEmail.trim().toLowerCase();
      if (!studentMap.has(cleanEmail)) {
        studentMap.set(cleanEmail, extractRollNo(cleanEmail));
      }
    }
  });

  // Sort students alphabetically by roll number
  const sortedStudents = Array.from(studentMap.entries())
    .map(([email, rollNo]) => ({ email, rollNo }))
    .sort((a, b) => a.rollNo.localeCompare(b.rollNo, undefined, { numeric: true, sensitivity: "base" }));

  // 5. Build headers
  const headers = ["Roll No"];
  filteredSessions.forEach((session) => {
    headers.push(formatSessionDateHeader(session, filteredSessions));
  });

  // 6. Map fast lookup for attendance: key = `${sessionId}_${cleanEmail}` -> record
  const recordLookup = new Map();

  // Manual requests mapped first
  relevantManual.forEach((m) => {
    if (m.sessionId && m.studentEmail) {
      const key = `${m.sessionId}_${m.studentEmail.trim().toLowerCase()}`;
      recordLookup.set(key, m);
    }
  });

  // Verified records take precedence over unverified manual requests
  relevantRecords.forEach((r) => {
    if (r.sessionId && r.studentEmail) {
      const key = `${r.sessionId}_${r.studentEmail.trim().toLowerCase()}`;
      recordLookup.set(key, r);
    }
  });

  // 7. Build data rows
  const rows = [];
  sortedStudents.forEach(({ email, rollNo }) => {
    const row = [rollNo];

    filteredSessions.forEach((session) => {
      const key = `${session.sessionId}_${email}`;
      const rec = recordLookup.get(key);

      if (!rec) {
        // No record exists for this session -> absent (0)
        row.push(0);
      } else if (rec.status === "APPROVED") {
        // Approved attendance (QR auto-approved or faculty approved) -> present (1)
        row.push(1);
      } else if (rec.status === "REJECTED") {
        // Explicitly rejected attendance -> absent (0)
        row.push(0);
      } else if (rec.status === "REVIEW" || rec.status === "PENDING") {
        // Unresolved review record
        if (isProvisional) {
          // In clearly labeled provisional export, indicate review status explicitly
          row.push("REVIEW");
        } else {
          // If exporting in standard mode with unreviewed submissions, represent as pending string
          row.push("PENDING");
        }
      } else {
        row.push(0);
      }
    });

    rows.push(row);
  });

  const enrollmentLimitationNote =
    "Note on Enrollment History: AttendIQ maintains the active course enrollment roster. " +
    "Absences prior to a student's enrollment date cannot be retroactively distinguished from missed classes.";

  return {
    headers,
    rows,
    sessions: filteredSessions,
    studentsCount: sortedStudents.length,
    hasPendingReviews,
    pendingReviewsCount,
    isProvisional,
    enrollmentLimitationNote
  };
}

/**
 * Generates an actual XLSX workbook object using SheetJS.
 *
 * @param {object} course
 * @param {Array<object>} sessions
 * @param {Array<object>} records
 * @param {Array<object>} manualRequests
 * @param {object} options
 * @param {object} XLSX SheetJS instance
 * @returns {object}
 */
function generateAttendanceWorkbook(course, sessions = [], records = [], manualRequests = [], options = {}, XLSX) {
  if (!XLSX) {
    throw new Error("XLSX library instance is required to generate workbook.");
  }

  const matrix = buildAttendanceMatrix(course, sessions, records, manualRequests, options);

  // If no sessions exist for the course, provide clear roster sheet
  const data = [matrix.headers, ...matrix.rows];
  if (matrix.sessions.length === 0) {
    data.length = 0;
    data.push(["Roll No", "Course Status"]);
    if (matrix.rows.length === 0) {
      data.push(["No Enrolled Students", "No attendance sessions have been conducted yet."]);
    } else {
      matrix.rows.forEach((r) => {
        data.push([r[0], "No sessions conducted"]);
      });
    }
  }

  const wb = XLSX.utils.book_new();
  const ws = XLSX.utils.aoa_to_sheet(data);

  // Set sensible column widths
  const colWidths = [{ wch: 18 }]; // Roll No column
  matrix.sessions.forEach(() => {
    colWidths.push({ wch: 22 }); // Date columns
  });
  ws["!cols"] = colWidths;

  // Freeze header row and Roll No column (supported by OpenXML)
  ws["!freeze"] = { xSplit: "1", ySplit: "1", topLeftCell: "B2", activePane: "bottomRight", state: "frozen" };
  ws["!views"] = [{ state: "frozen", xSplit: 1, ySplit: 1 }];

  // Sheet name (max 31 chars, sanitized for Excel)
  const cleanCode = (course.classCode || "Course").replace(/[\\/?*[\]:]/g, "").substring(0, 15);
  const sheetName = `${cleanCode} Attendance`.substring(0, 31);
  XLSX.utils.book_append_sheet(wb, ws, sheetName);

  // Set workbook properties and metadata
  const title = matrix.isProvisional
    ? `[PROVISIONAL] ${course.classCode} Attendance`
    : `${course.classCode} Attendance`;

  wb.Props = {
    Title: title,
    Subject: `${course.name || ""} (${course.classCode || ""})`,
    Author: course.facultyEmail || "AttendIQ Faculty",
    Company: "AttendIQ Multi-Factor Attendance Verification",
    Comments:
      `Course: ${course.name} (${course.classCode}). ` +
      `${matrix.enrollmentLimitationNote} ` +
      (matrix.isProvisional ? `PROVISIONAL EXPORT: contains ${matrix.pendingReviewsCount} unresolved review(s).` : "Certified Final Export."),
    CreatedDate: new Date()
  };

  const safeCode = (course.classCode || "COURSE").replace(/[^a-zA-Z0-9_-]/g, "_");
  const dateTag = new Date().toISOString().slice(0, 10);
  const provTag = matrix.isProvisional ? "_PROVISIONAL" : "";
  const filename = `${safeCode}_Attendance_${dateTag}${provTag}.xlsx`;

  return {
    wb,
    ws,
    matrix,
    hasPendingReviews: matrix.hasPendingReviews,
    pendingReviewsCount: matrix.pendingReviewsCount,
    isProvisional: matrix.isProvisional,
    filename
  };
}

module.exports = {
  extractRollNo,
  formatDateKey,
  formatTimePart,
  formatSessionDateHeader,
  detectPendingReviews,
  buildAttendanceMatrix,
  generateAttendanceWorkbook
};
