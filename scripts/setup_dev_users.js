/**
 * scripts/setup_dev_users.js
 *
 * Seed script for setting up test accounts on the Firebase Emulator Suite.
 * Uses standard Node.js fetch (Node 18+) with zero external dependencies.
 *
 * SAFETY GUARD:
 * This script is strictly restricted to local emulator hosts (localhost, 127.0.0.1, ::1).
 * It will refuse to run against any non-local or production environment.
 *
 * Test Accounts Seeded:
 * 1. faculty@test.com / faculty123
 *    - Role: "faculty"
 *    - Profile: users/{uid} created with role: "faculty"
 *    - Portal Access: ALLOWED (Verified Faculty)
 *
 * 2. student@test.com / student123
 *    - Role: "student"
 *    - Profile: users/{uid} created with role: "student"
 *    - Portal Access: DENIED (Students cannot enter faculty portal)
 *
 * 3. student2@test.com / student123
 *    - Role: "student"
 *    - Profile: users/{uid} created with role: "student"
 *    - Portal Access: DENIED
 *
 * 4. unverified-faculty@test.com / unverified123
 *    - Role: "unverified"
 *    - Profile: No Firestore faculty profile created
 *    - Portal Access: DENIED (Unverified faculty account benchmark)
 */

const AUTH_EMULATOR_HOST = process.env.FIREBASE_AUTH_EMULATOR_HOST || "localhost:9099";
const FIRESTORE_EMULATOR_HOST = process.env.FIRESTORE_EMULATOR_HOST || "localhost:8080";
const PROJECT_ID = process.env.GCLOUD_PROJECT || "attendmark-c2f07";

function assertLocalEmulator(host, serviceName) {
  if (!host) {
    throw new Error(`${serviceName} host is undefined.`);
  }
  const clean = host.replace(/^https?:\/\//, "").split(":")[0];
  const isLocal =
    clean === "localhost" ||
    clean === "127.0.0.1" ||
    clean === "::1" ||
    clean === "0.0.0.0";
  if (!isLocal) {
    throw new Error(
      `[Safety Violation] ${serviceName} host '${host}' is NOT a local emulator! Refusing to run dev seeder against non-local host.`
    );
  }
}

const TEST_USERS = [
  {
    email: "faculty@test.com",
    password: "faculty123",
    name: "Prof. Smith",
    role: "faculty",
    verified: true
  },
  {
    email: "student@test.com",
    password: "student123",
    name: "Alice Student",
    role: "student",
    verified: true
  },
  {
    email: "student2@test.com",
    password: "student123",
    name: "Bob Student",
    role: "student",
    verified: true
  },
  {
    email: "unverified-faculty@test.com",
    password: "unverified123",
    name: "Dr. Unverified",
    role: "unverified",
    verified: false
  }
];

async function createOrGetAuthUserInEmulator(user) {
  const signUpUrl = `http://${AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-api-key`;
  const response = await fetch(signUpUrl, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      email: user.email,
      password: user.password,
      displayName: user.name,
      returnSecureToken: true
    })
  });

  const data = await response.json();
  if (response.ok && data.localId) {
    console.log(`[Auth] Created user: ${user.email} (UID: ${data.localId})`);
    return data.localId;
  }

  if (data.error && data.error.message === "EMAIL_EXISTS") {
    console.log(`[Auth] User already exists: ${user.email}, resolving UID via signInWithPassword...`);
    const signInUrl = `http://${AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=fake-api-key`;
    const signInRes = await fetch(signInUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        email: user.email,
        password: user.password,
        returnSecureToken: true
      })
    });
    const signInData = await signInRes.json();
    if (signInRes.ok && signInData.localId) {
      console.log(`[Auth] Resolved existing UID for ${user.email}: ${signInData.localId}`);
      return signInData.localId;
    }
    throw new Error(`Failed to resolve UID for existing user ${user.email}: ${JSON.stringify(signInData)}`);
  }

  throw new Error(`Failed to create Auth user ${user.email}: ${JSON.stringify(data)}`);
}

async function createFirestoreProfileInEmulator(uid, user) {
  const url = `http://${FIRESTORE_EMULATOR_HOST}/v1/projects/${PROJECT_ID}/databases/(default)/documents/users/${uid}`;

  if (!user.verified) {
    // For unverified test accounts, ensure no faculty profile exists in users/{uid}
    const checkRes = await fetch(url, {
      method: "GET",
      headers: { "Authorization": "Bearer owner" }
    });
    if (checkRes.ok) {
      await fetch(url, {
        method: "DELETE",
        headers: { "Authorization": "Bearer owner" }
      });
      console.log(`[Firestore] Cleaned up profile for unverified user ${user.email} (UID: ${uid})`);
    } else {
      console.log(`[Firestore] Skipped profile creation for unverified user ${user.email} (UID: ${uid})`);
    }
    return;
  }

  const docBody = {
    fields: {
      email: { stringValue: user.email },
      name: { stringValue: user.name },
      role: { stringValue: user.role },
      createdAt: { integerValue: String(Date.now()) }
    }
  };

  const response = await fetch(url, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
      "Authorization": "Bearer owner" // Administrator token to bypass client write restrictions on role: faculty
    },
    body: JSON.stringify(docBody)
  });

  if (response.ok) {
    console.log(`[Firestore] Successfully wrote profile for ${user.email} (UID: ${uid}) with role: ${user.role}`);
  } else {
    const errText = await response.text();
    throw new Error(`Failed to write Firestore profile for ${uid}: ${errText}`);
  }
}

async function seedDevCourseIfMissing(facultyEmail) {
  const courseId = "course-dev-cs101";
  const url = `http://${FIRESTORE_EMULATOR_HOST}/v1/projects/${PROJECT_ID}/databases/(default)/documents/courses/${courseId}`;
  const docBody = {
    fields: {
      name: { stringValue: "Computer Science 101" },
      classCode: { stringValue: "CS101" },
      facultyEmail: { stringValue: facultyEmail },
      enrolledStudents: {
        arrayValue: {
          values: [
            { stringValue: "student@test.com" },
            { stringValue: "student2@test.com" }
          ]
        }
      },
      createdAt: { integerValue: String(Date.now()) }
    }
  };

  const response = await fetch(url, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
      "Authorization": "Bearer owner"
    },
    body: JSON.stringify(docBody)
  });

  if (response.ok) {
    console.log(`[Firestore] Ensured dev course CS101 for ${facultyEmail}`);
  } else {
    const errText = await response.text();
    console.warn(`[Firestore] Notice: Could not seed dev course: ${errText}`);
  }
}

async function main() {
  console.log("==================================================");
  console.log(" AttendMark Dev User Seeder (Firebase Emulator)");
  console.log(` Auth Host:      http://${AUTH_EMULATOR_HOST}`);
  console.log(` Firestore Host: http://${FIRESTORE_EMULATOR_HOST}`);
  console.log(` Project ID:     ${PROJECT_ID}`);
  console.log("==================================================\n");

  assertLocalEmulator(AUTH_EMULATOR_HOST, "Auth");
  assertLocalEmulator(FIRESTORE_EMULATOR_HOST, "Firestore");

  try {
    for (const user of TEST_USERS) {
      console.log(`Processing ${user.email}...`);
      const uid = await createOrGetAuthUserInEmulator(user);
      if (uid) {
        await createFirestoreProfileInEmulator(uid, user);
      }
    }

    // Ensure initial course for verified faculty
    await seedDevCourseIfMissing("faculty@test.com");

    console.log("\nAll test accounts successfully seeded!");
    console.log("\nTest Account Credentials Summary:");
    console.log("----------------------------------------------------------------------");
    console.log(" 1. Verified Faculty:   faculty@test.com            / faculty123    [ALLOWED]");
    console.log(" 2. Enrolled Student:   student@test.com            / student123    [DENIED]");
    console.log(" 3. Enrolled Student 2: student2@test.com           / student123    [DENIED]");
    console.log(" 4. Unverified Faculty: unverified-faculty@test.com / unverified123 [DENIED]");
    console.log("----------------------------------------------------------------------");
  } catch (err) {
    console.error("\n[Error seeding accounts]:", err.message);
    console.log("\nNote: Make sure the Firebase Emulator Suite is running (auth and firestore emulators).");
    process.exit(1);
  }
}

main();
