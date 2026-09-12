# ScamGuard Spike — Setup

## 1. Google Cloud project (Gmail API)

1. Go to https://console.cloud.google.com/ → create a new project, e.g. "ScamGuard-Spike".
2. **APIs & Services → Library** → search "Gmail API" → **Enable**.
3. **APIs & Services → OAuth consent screen**:
   - User type: **External**
   - App name: "ScamGuard Spike", support email: your Gmail address
   - **Scopes**: Add or Remove Scopes → filter "gmail" → select `.../auth/gmail.readonly` → Update
   - **Test users**: add the Gmail account(s) you'll sign in with during testing (required while the app is in "Testing" publishing status — restricted scopes only work for accounts on this list)
4. **APIs & Services → Credentials → Create Credentials → OAuth client ID**:
   - Application type: **Android**
   - Package name: `com.scamguard.spike`
   - SHA-1 certificate fingerprint (from the shared debug keystore checked into this repo at `app/debug.keystore`):
     ```
     77:8D:65:91:1D:40:0C:84:C9:C9:58:39:E6:02:37:AD:DA:8E:A7:2E
     ```
   - Create. (No client ID string needs to go in the app's code — Google Play services on the device validates the calling app by package name + signing certificate against this registered client.)

**Note:** `app/build.gradle.kts` points the `debug` build type's `signingConfig` at `app/debug.keystore`, which is checked into this repo. That means everyone who clones the repo and builds the debug variant automatically signs with the exact same key/SHA-1 above — no manual keystore copying needed, and Gmail OAuth works out of the box for anyone whose email is added as a test user (see step 3). If you ever need to regenerate/inspect it:
```
keytool -list -v -keystore app/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

## 2. Build and install

```
./gradlew :app:installDebug
```
(or open the project root in Android Studio and hit Run — it will use the same emulator/device.)

## 3. Testing SMS on the emulator

The emulator has no real telecom radio, so simulate incoming SMS with:
```
adb emu sms send 15555215554 "Your package could not be delivered, confirm details at http://bit.ly/abc"
```
This lands in the emulator's SMS content provider, so opening the SMS screen (or tapping
"Refresh inbox") picks it up. It does **not** show up live/instantly on its own — see
"Background monitoring" below for why, and how new messages actually get checked when the
app isn't open.

## 4. Testing Email

Open the Email screen, tap "Authorize Gmail access", pick the Google account you added as a test user, and grant the Gmail read-only permission when prompted. The list should show your real recent + older Gmail messages.

## 5. Backend integration (Task 4)

Both screens POST every message to the backend's `/check-message` and show the
returned risk level + reason under each item ("Checking with ScamGuard…" while in
flight). To make that work:

1. **Run the backend** (see `backend/README.md`) — `uvicorn app.main:app --reload`,
   default port 8000.
2. **Point the app at your backend**: `app/src/main/java/com/scamguard/spike/backend/BackendConfig.kt`
   → `BASE_URL` defaults to `http://10.0.2.2:8000`, which is the Android emulator's
   alias for your machine's `localhost` — works out of the box for the emulator.
   Testing on a **physical device**? Use your machine's LAN IP instead (device and
   machine must be on the same Wi-Fi), e.g. `http://192.168.1.23:8000`.
3. **Sign up on first launch**: the app now shows a registration screen the first time
   it runs on a device (username/phone/gmail, plus an optional family member to alert).
   Submitting it calls `POST /users` (and `POST /family-members` if filled in) and
   stores the returned `user_id` on-device via `UserSession` — no manual Swagger-UI
   step or hardcoded id needed anymore. It only asks once; subsequent launches skip
   straight to the home screen. To re-trigger it (e.g. to register as a different
   user), clear the app's data or uninstall/reinstall.
4. Grant the extra **Contacts** permission when prompted on the SMS screen — it's now
   requested alongside SMS access, used to compute `is_known_sender`.

`is_known_sender` is computed on-device before each check: for SMS, whether the
sender's number matches a saved Contact; for email, whether the message is part of a
Gmail thread with more than one message (i.e. there's been a reply already).

## 6. Background monitoring (SMS)

New messages are checked even when the app isn't open, via a periodic background job
(`PollSmsWorker`, runs every 15 minutes -- Android's enforced minimum for periodic work,
not a choice made here). It re-reads the SMS inbox and checks whatever hasn't been seen
before; results are stored on-device (`CheckedMessageStore`) so the SMS screen shows them
immediately next time it's opened, with no re-check needed.

**Why polling and not instant/live:** we originally built this as a broadcast-driven
receiver (fires the instant a text arrives) but confirmed via Android's own broadcast
dispatch log that `SMS_RECEIVED` is delivered exclusively to the default SMS app on this
platform -- no third-party app's receiver gets it, dynamic or manifest-declared. Polling
was the fallback that's actually provable to work.

Needs READ_SMS + READ_CONTACTS granted at least once (visiting the SMS screen and
accepting the permission prompt) before it can do anything -- until then it silently
no-ops rather than failing.

Email background monitoring doesn't exist yet (only manual "Refresh emails" for now) --
Gmail has no simple on-device push either, so it needs the same polling approach, plus a
still-open question about whether the Gmail OAuth token can silently refresh in the
background without prompting the user again.

Currently, checking a message doesn't alert the user on-device (no push notification) --
that's separate in-progress work; it does still log/alert family via Telegram once
that's configured (Task 2).
