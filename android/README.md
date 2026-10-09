# Design Workshop Android App

Kotlin + Jetpack Compose Android client for the same FastAPI backend used by the web app.

## Capabilities

- Login with `POST /api/auth/login`
- Google sign-in through Android Credential Manager
- Persist JWT locally
- Load dashboard stats with `GET /api/dashboard/stats`
- Create craft records with `POST /api/crafts`
- Create artisan records with `POST /api/artisans`
- Create workshop records with `POST /api/workshops`
- Create product records with `POST /api/products`
- Create tool records with `POST /api/tools`
- Document processes with ordered steps (each step has media + an optional "record additional information" notes box) via `POST /api/processes`; the form cascades artisan → that artisan's products
- "Document using grid": length + breadth from one top-down photo, height from a side-on photo (`POST /api/media/analyze-measurement`), auto-filling the fields
- Split long audio/video into `PART_1`, `PART_2`, … (re-mux at sync frames) before streaming upload, so each part stays under the transcription/upload limits and large videos never exhaust the heap
- Preview previously-uploaded media with uploader/date provenance and a **Save to device** download
- Create questionnaire interviews with `POST /api/questionnaire/interviews`
- Send `Authorization: Bearer <token>` on every protected API call
- Requests camera, audio and location permissions for field capture workflows

## Run

1. Start backend from the repo root:

```powershell
docker compose up -d
cd backend
.\.venv\Scripts\Activate.ps1
uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

2. Open `android/` in Android Studio.
3. Sync Gradle.
4. Run the `app` configuration on an emulator.
5. Log in with the admin email and password from your private backend `.env`, or use Google sign-in after OAuth is configured.

A build with no override talks to production (the CloudFront default in `app/build.gradle.kts`). To point the emulator at the backend on your computer, add an ignored local override in `android/local.properties`; `10.0.2.2` routes from the Android emulator to the host computer:

```properties
apiBaseUrl=http://10.0.2.2:8000/api/
```

For a physical device, keep source code unchanged and use your computer's LAN address instead:

```properties
apiBaseUrl=http://YOUR_COMPUTER_LAN_IP:8000/api/
```

Run the backend on `0.0.0.0` so the phone can reach it over the same Wi-Fi network. Plain HTTP is allowed only to the emulator and loopback hosts, so a LAN address also needs a temporary `<domain>` entry in `app/src/main/res/xml/network_security_config.xml` — that file says how, and neither change is to be committed.

**On Android 17 (the app targets API 37 since 2026-10-09), a debug build asks for the "Nearby devices" permission at first launch when `apiBaseUrl` is a LAN or emulator address.** Android 17 blocks an app's traffic to the local network until `ACCESS_LOCAL_NETWORK` is granted, and the connection does not fail, it times out — so allow it, or the sign-in will spin and fail. Only the debug manifest (`app/src/debug/AndroidManifest.xml`) declares that permission, so a release build pointed at a LAN host cannot reach it on Android 17; `localhost` through `adb reverse` is loopback and needs nothing.

Command-line debug build:

```powershell
.\gradlew.bat :app:assembleDebug
```

## Google OAuth

The Android application ID and OAuth package name are:

```text
com.designprototype.workshop
```

Create an Android OAuth client in Google Cloud Console with that package name and the SHA-1 fingerprint for the certificate used to sign the build. For a local debug build, get the SHA-1 with:

```powershell
keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
```

The app uses `GOOGLE_WEB_CLIENT_ID` from `app/build.gradle.kts` as Credential Manager's server client ID. The backend must also have the same web client ID set as `GOOGLE_CLIENT_ID` so it can verify Google ID tokens.

The Android OAuth client ID configured for the package is:

```text
614092441670-5rckig6t1al6plbfll8irn9prcmp446t.apps.googleusercontent.com
```

## Required password change

Accounts are provisioned on the web only (Users page); this app has no screens for creating accounts, setting someone's password or requiring a change. An account carrying `mustChangePassword` is held on the gate screen after sign-in — "Set your own password" on builds 0.0.6 to 0.0.15, "Set a new password" in the source since 2026-10-09; the gate screen dates from build 0.0.6. Since 2026-10-09 the server enforces the flag: while it is set, every route outside a short allow-list answers `401` with `X-Password-Change-Required: 1`. Treating that `401` as a live session rather than an expired one — keeping the token and every queued record, scan and photo, pausing the outbox, design-workshop and join-card sends and resuming them as soon as the new password is saved, re-reading `/me` and showing the gate — is in the source since 2026-10-09 and arrives with the next build published after 0.0.15, the latest published version as of that date (`appVersionName` in `app/build.gradle.kts`, tag `v0.0.15`). Builds 0.0.2 to 0.0.5 have no gate screen at all, so their users change the password on the web first.

A password change ends every other session of the account (since 2026-10-09): every token is bound to the password it was opened with. `POST /api/auth/change-password` still answers `{"ok": true}`, the body every build decodes, and returns a fresh session token for the session that made the change in the `X-Session-Token` response header. The source since 2026-10-09 stores that header's token before it re-reads `/me`, reading it off a successful answer only (`WorkshopRepository.changeOwnPassword`, with `SESSION_TOKEN_HEADER` beside `ChangePasswordResponse` in `ApiModels.kt`, pinned by `app/src/test/java/com/designprototype/workshop/data/ChangePasswordSessionTest.kt`); an answer without the header leaves the stored token alone, and a token in the body is never read. The gate's rule line says what happens, in the web's words: "You stay signed in here, and are signed out everywhere else." That arrives with the next build published after 0.0.15 too. **Builds 0.0.6 to 0.0.15 never read the header**: the change works as it always did and the gate closes, but the token the handset holds was opened with the old password, so its next request is refused with a plain `401`. Those builds read a session's end only at launch, so nothing signs the person out until the app is next started or they sign out by hand — the queues keep their work and retry with the dead token meanwhile — and they then sign in with the password they have just chosen. No token appears on screen: the body carries none, which is why the token rides in a header (those builds decode the body as `Map<String, Boolean>`, which a string beside `ok` breaks). See `docs/SECURITY.md` §3.6 and `docs/OPEN_FINDINGS.md`.

**A session that ends somewhere else is noticed at the next request** (in the source since 2026-10-09). A password changed on the web, on another phone, by an administrator or through a redeemed link retires every session opened with the old one. `ApiClient.sessionInterceptor` raises `SessionEndedSignal` (`isSessionEnded` in `app/src/main/java/com/designprototype/workshop/data/PasswordChangeRequired.kt`) when a request sent to the API with a token is answered by a `401` without `X-Password-Change-Required` and the handset still holds that same token — a `401` for a token swapped while the request was in flight is ignored. The root in `MainActivity.kt` then re-reads `/me` with the current token and, when that is refused too, signs out with "This sign-in has ended. If your password was changed on another device or by an administrator, sign in with the new one." (`SESSION_ENDED_SENTENCE` in `app/src/main/java/com/designprototype/workshop/ui/PasswordSetupCopy.kt`, which the launch check now says as well, in place of "Your session expired"). Every queue keeps its work: how the record outbox and the design-workshop sync treat a `401` is unchanged, and a sign-out clears the token, never a queue. While the password gate is on screen the root leaves the signal to the gate, so a session that ends while somebody sits on the gate is noticed when they submit or press "Sign out instead". The admin-only transcription-provider settings use an HTTP client of their own and raise neither signal; the next request through the main client does. Pinned by `app/src/test/java/com/designprototype/workshop/data/SessionEndedSignalTest.kt`, which runs the app's own `ApiClient.httpClient` against canned answers.

**The gate does not report a change whose answer was lost as a change that failed** (in the source since 2026-10-09). The server stores the new password before it answers, so a change that fails with no answer, a `5xx` or a plain `401` may already have landed — and retired the session it was sent with. The gate asks `GET /me` with the session it still holds before it says anything, its button disabled meanwhile (`passwordGateAfterFailure` in `app/src/main/java/com/designprototype/workshop/ui/PasswordSetupCopy.kt`): a plain `401` signs out with "Your new password may already be in effect. Sign in with it; if it is refused, use the one you were given."; an account that still owes a password gets the message the gate has always shown; one that no longer owes one closes the gate; anything else keeps the gate up, saying the phone could not tell whether the password was saved, with the same two-password advice. A refusal the route itself answered — `400`, `403`, `422`, `429` or the gate's own `401` — is shown at once, with no question asked. And the three credential writes — `POST /api/auth/change-password`, `/api/auth/set-password` and `/api/auth/password-links` — go out with one-shot bodies, so OkHttp's connection-failure retry never sends one a second time; signing in and withdrawing a link stay ordinary requests, because repeating either does no harm. Pinned by `app/src/test/java/com/designprototype/workshop/data/ChangePasswordSessionTest.kt` and `app/src/test/java/com/designprototype/workshop/ui/PasswordSetupCopyTest.kt`. Neither change has reached a published build as of 2026-10-09: both arrive with the next build after 0.0.15.

## Capture Notes

The Android manifest includes permissions for precise location, camera, audio recording and Android 13 media reads. The compact Compose UI supports field data entry, craft assignment, dimensions, UTC record timestamps and questionnaire submission through the same backend used by the web app. The web record forms provide the complete embedded batch upload, waveform recording, transcription and Gemini grid-measurement workflow.
