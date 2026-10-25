/**
 * AttendIQ Course Management Service
 *
 * Implements authoritative server-side logic for:
 * - Student leaving a course (safely removing only caller's email)
 * - Faculty archiving a course (auto-stopping active sessions and preventing new ones)
 * - Faculty unarchiving a course
 */
const { ServiceError, stopAttendanceSessionCore } = require("./qr_service");

/**
 * Server-side core implementation of leaveCourse.
 * Authenticated student caller can remove strictly their own email from enrolledStudents.
 *
 * @param {FirebaseFirestore.Firestore} db
 * @param {{ uid: string, email: string } | null} authContext
 * @param {{ courseCode: string }} data
 */
async function leaveCourseCore(db, authContext, data) {
  if (!authContext || !authContext.uid) {
    throw new ServiceError("unauthenticated", "User must be authenticated.");
  }

  if (!data || !data.courseCode) {
    throw new ServiceError("invalid-argument", "Missing required field: courseCode.");
  }

  const courseCode = (data.courseCode || "").trim().toUpperCase();

  // Verify caller's role is student
  const userDoc = await db.collection("users").doc(authContext.uid).get();
  if (!userDoc.exists || userDoc.data().role !== "student") {
    throw new ServiceError("permission-denied", "Only students can leave a course.");
  }

  const studentEmail = (authContext.email || userDoc.data().email || "").toLowerCase();
  if (!studentEmail) {
    throw new ServiceError("failed-precondition", "Authenticated user is missing an email address.");
  }

  // Find course by classCode
  const coursesSnapshot = await db.collection("courses").where("classCode", "==", courseCode).get();
  if (coursesSnapshot.empty) {
    throw new ServiceError("not-found", `Course with code ${courseCode} not found.`);
  }

  const courseDoc = coursesSnapshot.docs[0];
  const courseData = courseDoc.data();

  // Check if course is archived
  if (courseData.isArchived === true) {
    throw new ServiceError("failed-precondition", "Cannot leave an archived course.");
  }

  const enrolledStudents = (courseData.enrolledStudents || []).map((e) => (e || "").toLowerCase());
  if (!enrolledStudents.includes(studentEmail)) {
    throw new ServiceError("failed-precondition", "Student is not enrolled in this course.");
  }

  // Remove strictly the caller's email, preserving all other enrolled students
  const updatedEnrolled = (courseData.enrolledStudents || []).filter(
    (e) => (e || "").toLowerCase() !== studentEmail
  );

  await courseDoc.ref.update({
    enrolledStudents: updatedEnrolled,
    updatedAt: Date.now()
  });

  return {
    success: true,
    courseCode,
    studentEmail,
    enrolledCount: updatedEnrolled.length
  };
}

/**
 * Server-side core implementation of archiveCourse.
 * Only the owning faculty member can archive a course.
 * Automatically stops any running attendance session for the course.
 *
 * @param {FirebaseFirestore.Firestore} db
 * @param {{ uid: string, email: string } | null} authContext
 * @param {{ courseCode: string }} data
 */
async function archiveCourseCore(db, authContext, data) {
  if (!authContext || !authContext.uid) {
    throw new ServiceError("unauthenticated", "User must be authenticated.");
  }

  if (!data || !data.courseCode) {
    throw new ServiceError("invalid-argument", "Missing required field: courseCode.");
  }

  const courseCode = (data.courseCode || "").trim().toUpperCase();

  // Verify caller's role is faculty
  const userDoc = await db.collection("users").doc(authContext.uid).get();
  if (!userDoc.exists || userDoc.data().role !== "faculty") {
    throw new ServiceError("permission-denied", "Only faculty members can archive a course.");
  }

  const facultyEmail = (authContext.email || userDoc.data().email || "").toLowerCase();

  // Find course by classCode
  const coursesSnapshot = await db.collection("courses").where("classCode", "==", courseCode).get();
  if (coursesSnapshot.empty) {
    throw new ServiceError("not-found", `Course with code ${courseCode} not found.`);
  }

  const courseDoc = coursesSnapshot.docs[0];
  const courseData = courseDoc.data();

  // Verify ownership
  if ((courseData.facultyEmail || "").toLowerCase() !== facultyEmail) {
    throw new ServiceError("permission-denied", "You are not authorized to archive this course.");
  }

  if (courseData.isArchived === true) {
    return {
      success: true,
      courseCode,
      isArchived: true,
      message: "Course is already archived."
    };
  }

  // If there is an active session for this course, automatically stop it
  const sessionDoc = await db.collection("sessions").doc(courseCode).get();
  if (sessionDoc.exists && sessionDoc.data().isActive === true) {
    try {
      await stopAttendanceSessionCore(db, authContext, { courseCode });
    } catch (err) {
      console.warn(`Auto-stopping session for archived course ${courseCode} returned:`, err.message);
    }
  }

  const now = Date.now();
  await courseDoc.ref.update({
    isArchived: true,
    archivedAt: now,
    updatedAt: now
  });

  return {
    success: true,
    courseCode,
    isArchived: true,
    archivedAt: now
  };
}

/**
 * Server-side core implementation of unarchiveCourse.
 * Only the owning faculty member can unarchive a course.
 *
 * @param {FirebaseFirestore.Firestore} db
 * @param {{ uid: string, email: string } | null} authContext
 * @param {{ courseCode: string }} data
 */
async function unarchiveCourseCore(db, authContext, data) {
  if (!authContext || !authContext.uid) {
    throw new ServiceError("unauthenticated", "User must be authenticated.");
  }

  if (!data || !data.courseCode) {
    throw new ServiceError("invalid-argument", "Missing required field: courseCode.");
  }

  const courseCode = (data.courseCode || "").trim().toUpperCase();

  // Verify caller's role is faculty
  const userDoc = await db.collection("users").doc(authContext.uid).get();
  if (!userDoc.exists || userDoc.data().role !== "faculty") {
    throw new ServiceError("permission-denied", "Only faculty members can unarchive a course.");
  }

  const facultyEmail = (authContext.email || userDoc.data().email || "").toLowerCase();

  // Find course by classCode
  const coursesSnapshot = await db.collection("courses").where("classCode", "==", courseCode).get();
  if (coursesSnapshot.empty) {
    throw new ServiceError("not-found", `Course with code ${courseCode} not found.`);
  }

  const courseDoc = coursesSnapshot.docs[0];
  const courseData = courseDoc.data();

  // Verify ownership
  if ((courseData.facultyEmail || "").toLowerCase() !== facultyEmail) {
    throw new ServiceError("permission-denied", "You are not authorized to unarchive this course.");
  }

  const now = Date.now();
  await courseDoc.ref.update({
    isArchived: false,
    unarchivedAt: now,
    updatedAt: now
  });

  return {
    success: true,
    courseCode,
    isArchived: false,
    unarchivedAt: now
  };
}

module.exports = {
  leaveCourseCore,
  archiveCourseCore,
  unarchiveCourseCore
};
