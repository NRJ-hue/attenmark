const { ServiceError } = require("./qr_service");

/**
 * Exact role-classification rule:
 * - If the first two characters of the authenticated user's email are both digits ('0' to '9'),
 *   assign the 'student' role.
 * - Otherwise, assign the 'faculty' role.
 * - Do not check the email domain, full name, or any other part of the email.
 *
 * Known consequence: Any authenticated account whose email starts with two non-numeric characters
 * will be classified as faculty.
 *
 * @param {string} email
 * @returns {'student' | 'faculty'}
 */
function classifyRoleFromEmail(email) {
  if (typeof email !== "string" || email.length < 2) {
    return "faculty";
  }
  const c0 = email.charAt(0);
  const c1 = email.charAt(1);
  if (c0 >= "0" && c0 <= "9" && c1 >= "0" && c1 <= "9") {
    return "student";
  }
  return "faculty";
}

/**
 * Core implementation for bootstrapping / fetching user profile.
 *
 * Requirements:
 * - Creates missing profile using the authenticated user's UID.
 * - Calculates the role server-side using the exact two-character rule.
 * - Derives email strictly from verified token (authContext.email), never from request body.
 * - Ignores client attempts to supply or override `role`.
 * - Preserves an existing profile rather than overwriting its other fields on every login.
 * - Returns useful authentication and authorization errors.
 *
 * @param {FirebaseFirestore.Firestore} db
 * @param {{ uid: string, email: string } | null} authContext
 * @param {object} [data]
 * @returns {Promise<{ uid: string, email: string, name: string, role: string, isNew: boolean }>}
 */
async function bootstrapProfileCore(db, authContext, data = {}) {
  if (!authContext || !authContext.uid) {
    throw new ServiceError("unauthenticated", "User must be authenticated.");
  }

  const { uid } = authContext;
  const email = (authContext.email || "").trim().toLowerCase();

  if (!email) {
    throw new ServiceError("invalid-argument", "Authenticated user must have a valid email address.");
  }

  const userRef = db.collection("users").doc(uid);
  const userDoc = await userRef.get();

  if (userDoc.exists) {
    const existing = userDoc.data();
    return {
      uid,
      email: existing.email || email,
      name: existing.name || (data && data.name) || email.split("@")[0],
      role: existing.role || classifyRoleFromEmail(email),
      isNew: false
    };
  }

  // Calculate role using the exact two-character rule
  // Client-supplied `role` in `data` is strictly ignored!
  const assignedRole = classifyRoleFromEmail(email);

  // Derive name safely: client data.name (if provided and non-empty) or formatted email prefix
  const rawName = data && typeof data.name === "string" ? data.name.trim() : "";
  const name = rawName.length > 0
    ? rawName
    : email.split("@")[0].replace(/[._-]/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());

  const newProfile = {
    uid,
    email,
    name,
    role: assignedRole,
    createdAt: Date.now()
  };

  await userRef.set(newProfile);

  return {
    uid,
    email,
    name,
    role: assignedRole,
    isNew: true
  };
}

module.exports = {
  classifyRoleFromEmail,
  bootstrapProfileCore
};
