# AttendMark — How to Build & Test on Your Phone

## Project is at:
`c:\Users\nilab\OneDrive\Desktop\atten\attendaceanti\AttendMark\`

---

## Step 1: Install Android Studio

1. Download from: https://developer.android.com/studio
2. Install with default settings
3. During first launch, complete the **Android Studio Setup Wizard** — this installs the Android SDK automatically

---

## Step 2: Open the Project

1. Open **Android Studio**
2. Click **"Open"** (NOT "New Project")
3. Navigate to: `C:\Users\nilab\OneDrive\Desktop\atten\attendaceanti\AttendMark`
4. Click **OK**
5. Wait for **Gradle sync** to complete (first time takes 5-10 minutes, downloads all dependencies)

> If you see "Gradle sync failed", click "Try Again" or check internet connection

---

## Step 3: Enable Developer Mode on Your Android Phone

1. Go to **Settings → About Phone**
2. Tap **Build Number** 7 times rapidly
3. You'll see "You are now a developer!"
4. Go to **Settings → Developer Options**
5. Enable **USB Debugging**

---

## Step 4: Connect Phone to Laptop

1. Connect your phone via **USB cable**
2. On your phone, tap **"Allow"** when it asks about USB debugging
3. In Android Studio, you should see your phone's name in the device dropdown (top toolbar)

---

## Step 5: Build & Install APK

### Option A: Run directly on phone (easiest during development)
- In Android Studio, click the **▶ Run button** (green play button)
- Select your phone from the device list
- Android Studio will build and install automatically

### Option B: Build APK file to share
1. Go to **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. Wait for build to complete
3. Click **"locate"** in the notification that appears
4. APK is at: `app/build/outputs/apk/debug/app-debug.apk`
5. Transfer this APK to your phone and install it
   - You may need to enable "Install from unknown sources" on your phone

---

## Step 6: Test the App

### Student Flow:
1. Login: `student@test.com` / `student123`
2. First time → **Face Enrollment** screen appears
3. Position face in the oval → tap Capture
4. On success → Student Dashboard
5. Tap **"Mark Attendance"** button (FAB)
6. **Step 1**: A random liveness challenge appears (Blink / Smile / Turn Left / Turn Right)
7. Perform the action → tap **"Check Now"**
8. **Step 2**: BeReal-style classroom capture with your selfie thumbnail in corner
9. Point back camera at something → tap capture button
10. **Processing**: Face match runs against your enrolled face
11. **Result screen**: Shows match % + animated score bar + approval status

### Faculty Flow:
1. Login: `faculty@test.com` / `faculty123`
2. Faculty Dashboard shows all student submissions
3. Filter by: All / Review / Approved / Rejected tabs
4. Tap a "Under Review" card to expand it → Approve or Reject

---

## Common Issues

| Problem | Solution |
|---|---|
| Gradle sync fails | Check internet, click "Try Again" |
| "No devices found" | Enable USB debugging, try different USB cable |
| Camera permission denied | Go to phone Settings → Apps → AttendMark → Permissions → Allow Camera |
| Build fails with "minSdk" error | Your phone is below Android 8.0 (API 26). The app requires Android 8+ |
| Face enrollment: "No face detected" | Improve lighting, move closer to camera, remove glasses |
| Liveness: challenge never passes | Make the action more pronounced; ML Kit needs clear detection |

---

## Dummy Credentials

| Role | Email | Password |
|---|---|---|
| Student | student@test.com | student123 |
| Faculty | faculty@test.com | faculty123 |

---

## What Each File Does

```
AttendMark/
├── app/src/main/
│   ├── assets/
│   │   └── mobilefacenet.tflite    ← AI face matching model (5MB, already downloaded)
│   ├── java/com/attendmark/app/
│   │   ├── data/
│   │   │   ├── DummyAuth.kt         ← Hardcoded credentials
│   │   │   ├── FaceRecognitionService.kt  ← TFLite model loader + cosine similarity
│   │   │   ├── LivenessDetectionService.kt ← ML Kit liveness challenges
│   │   │   ├── UserPreferences.kt   ← Saves login + face embedding locally
│   │   │   └── AttendanceRecord.kt  ← Attendance data model (in-memory)
│   │   ├── ui/screens/
│   │   │   ├── login/LoginScreen.kt     ← Login UI
│   │   │   ├── enrollment/EnrollmentScreen.kt  ← Face enrollment camera
│   │   │   ├── student/
│   │   │   │   ├── StudentDashboardScreen.kt   ← Student home
│   │   │   │   ├── AttendanceFlowScreen.kt     ← Liveness + BeReal capture + verify
│   │   │   │   └── AttendanceResultScreen.kt   ← Match % result
│   │   │   └── faculty/FacultyDashboardScreen.kt ← Faculty review
│   │   └── ui/navigation/Navigation.kt  ← Screen routing
```

---

## Notes on Face Matching

- Uses **MobileFaceNet** AI model (state-of-the-art face recognition, ~5MB)
- Compares using **cosine similarity** (0.0 = no match, 1.0 = identical)
- Thresholds:
  - ≥ 0.70 → **STRONG MATCH** → Approved ✅
  - ≥ 0.55 → **LIKELY MATCH** → Approved ✅
  - ≥ 0.40 → **WEAK MATCH** → Review ⚠️
  - < 0.40 → **NO MATCH** → Rejected ❌
- Everything runs **on-device** — no internet needed for face matching
