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
   - SHA-1 certificate fingerprint (debug keystore on this machine):
     ```
     77:8D:65:91:1D:40:0C:84:C9:C9:58:39:E6:02:37:AD:DA:8E:A7:2E
     ```
   - Create. (No client ID string needs to go in the app's code — Google Play services on the device validates the calling app by package name + signing certificate against this registered client.)

If you ever re-generate the debug keystore or build with a different machine/keystore, regenerate the SHA-1 with:
```
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
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
This fires a real `SMS_RECEIVED` broadcast (caught live by the app if the SMS screen is open) and also lands in the emulator's SMS content provider (so "Refresh inbox" picks it up too). Send a few of these before opening the SMS screen to prove historical inbox reads, not just live capture.

## 4. Testing Email

Open the Email screen, tap "Authorize Gmail access", pick the Google account you added as a test user, and grant the Gmail read-only permission when prompted. The list should show your real recent + older Gmail messages.
