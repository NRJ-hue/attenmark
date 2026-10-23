/**
 * scripts/setup_dev_users.js
 *
 * Seed script for setting up test accounts on the Firebase Emulator Suite or live Firebase project.
 * Uses standard Node.js fetch (Node 18+) with zero external dependencies.
 *
 * Test Accounts:
 * - faculty@test.com / faculty123  -> role: "faculty"
 * - student@test.com / student123  -> role: "student"
 * - student2@test.com / student123 -> role: "student"
 */

const AUTH_EMULATOR_HOST = process.env.FIREBASE_AUTH_EMULATOR_HOST || "localhost:9099";
const FIRESTORE_EMULATOR_HOST = process.env.FIRESTORE_EMULATOR_HOST || "localhost:8080";
const PROJECT_ID = process.env.GCLOUD_PROJECT || "attendmark-c2f07";

const TEST_USERS = [
  {
    email: "faculty@test.com",
    password: "faculty123",
    name: "Prof. Smith",
    role: "faculty"
  },
  {
    email: "student@test.com",
    password: "student123",
    name: "Alice Student",
    role: "student"
  },
  {
    email: "student2@test.com",
    password: "student123",
    name: "Bob Student",
    role: "student"
  }
];

async function createAuthUserInEmulator(user) {
  const url = `http://${AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=fake-api-key`;
  const response = await fetch(url, {
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
  if (response.ok) {
    console.log(`[Auth] Created user: ${user.email} (UID: ${data.localId})`);
    return data.localId;
  } else if (data.error && data.error.message === "EMAIL_EXISTS") {
    console.log(`[Auth] User already exists: ${user.email}`);
    // Lookup user to get localId
    const lookupUrl = `http://${AUTH_EMULATOR_HOST}/identitytoolkit.googleapis.com/v1/accounts:lookup?key=fake-api-key`;
    const lookupRes = await fetch(lookupUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: [user.email] })
    });
    const lookupData = await lookupRes.json();
    return lookupData.users?.[0]?.localId;
  } else {
    throw new Error(`Failed to create Auth user ${user.email}: ${JSON.stringify(data)}`);
  }
}

async function createFirestoreProfileInEmulator(uid, user) {
  const url = `http://${FIRESTORE_EMULATOR_HOST}/v1/projects/${PROJECT_ID}/databases/(default)/documents/users/${uid}`;
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
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(docBody)
  });

  if (response.ok) {
    console.log(`[Firestore] Successfully wrote profile for ${user.email} with role: ${user.role}`);
  } else {
    const data = await response.text();
    throw new Error(`Failed to write Firestore profile for ${uid}: ${data}`);
  }
}

async function main() {
  console.log("==================================================");
  console.log(" AttendMark Dev User Seeder (Firebase Emulator)");
  console.log(` Auth Host:      http://${AUTH_EMULATOR_HOST}`);
  console.log(` Firestore Host: http://${FIRESTORE_EMULATOR_HOST}`);
  console.log(` Project ID:     ${PROJECT_ID}`);
  console.log("==================================================\n");

  try {
    for (const user of TEST_USERS) {
      console.log(`Processing ${user.email}...`);
      const uid = await createAuthUserInEmulator(user);
      if (uid) {
        await createFirestoreProfileInEmulator(uid, user);
      }
    }
    console.log("\nAll test accounts successfully seeded!");
  } catch (err) {
    console.error("\n[Error seeding accounts]:", err.message);
    console.log("\nNote: Make sure the Firebase Emulator Suite is running (auth and firestore emulators).");
    process.exit(1);
  }
}

main();
