# Hinge Opener (personal Android app)

A floating bubble that shows up only while Hinge is open. Tap it to screenshot the profile (up to 6 times while you scroll), long-press it, and Claude (`claude-sonnet-5-5`) returns the best opener, what to attach it to, and runner-ups you can copy.

- Kotlin + Jetpack Compose, minSdk 29 (Android 10+), sideload only.
- **Android only.** iOS doesn't let apps draw over other apps, detect the foreground app, or capture another app's screen, so there is no iPhone version.
- No AccessibilityService, auto-scroll, or auto-typing. You paste replies yourself.

## How it works

| Piece | File |
|---|---|
| Onboarding, start/stop, settings | `MainActivity.kt` |
| Foreground service: Hinge detection (UsageStatsManager, every 0.7 s), overlays, capture, analysis | `BubbleService.kt` |
| One MediaProjection session → VirtualDisplay → ImageReader; JPEG ≤ 1080 px wide | `ScreenCapturer.kt` |
| Screenshots in app-private cache; deleted after a successful analysis, on Clear, and on stop | `CaptureStore.kt` |
| Anthropic Messages API over HTTPS (OkHttp), images as base64, structured JSON output | `ClaudeClient.kt` |
| System prompt, default style, JSON schema | `Prompts.kt` |
| Safe JSON parsing (strips code fences and stray text) | `OpenerResult.kt` |
| API key in EncryptedSharedPreferences | `Prefs.kt` |

Notes on behavior:

- **Consent once per session.** You approve screen capture when you tap **Start bubble** in the app. The session stays alive (Android shows its screen-sharing indicator) until you tap **Stop**, use the notification's Stop action, or Android ends it (some versions end it when the phone locks). When it ends you get a "tap to start again" notification.
- On Android 14+ the consent dialog is forced to **Entire screen**, because single-app sharing can't see Hinge while this app is in the background.
- The bubble and card hide themselves for 250 ms during each capture, so they never appear in screenshots.
- If analysis fails (network, rate limit, etc.), the captures are kept so **Retry** works. **Clear** deletes them.
- Long-press with no captures re-opens the last result.
- The model returns its full working (photos, prompt ratings, per-candidate scores with the 1.3×/1.2× weighting) in the JSON. The card only shows the best reply, target, reason, and runner-ups.
- Requests use `output_config.effort: "medium"` for speed. They also opt into Anthropic's server-side refusal fallback (`fallbacks: "default"`); if the API ever rejects that, the app retries once without it.
- If a capture comes out black, Hinge is blocking screenshots (FLAG_SECURE). The app shows a toast if that happens.

## Get the APK

### Option A: GitHub Actions (no computer needed)

Every push that touches `hinge-opener/` builds the APK.

1. On GitHub, open the repo → **Actions** → **Hinge Opener APK** → the latest green run.
2. Under **Artifacts**, download `hinge-opener-apk` (a zip containing `app-debug.apk`). From the phone's browser you need to be logged in to GitHub.
3. Unzip it on the phone (Files app → tap the zip → Extract), then tap `app-debug.apk`.
4. Android asks to allow installs from that app (Chrome/Files). Allow it, then tap **Install**. If Play Protect warns about an unknown app, choose **Install anyway**. That's expected for a sideloaded personal app.

To rebuild without code changes: Actions → Hinge Opener APK → **Run workflow**.

### Option B: Android Studio (Mac/Windows/Linux)

1. Install Android Studio (it bundles the SDK and JDK).
2. **File → Open** → select the `hinge-opener` folder. Let Gradle sync finish.
3. On the phone: **Settings → About phone** → tap **Build number** 7 times. Then **Settings → System → Developer options** → enable **USB debugging**.
4. Plug in the phone, accept the "Allow USB debugging?" prompt, pick the device in the toolbar, and press **Run ▶**.

### Option C: command line + adb

```bash
cd hinge-opener
./gradlew assembleDebug                     # needs JDK 17 and ANDROID_HOME pointing at an SDK
adb devices                                  # phone must show as "device", not "unauthorized"
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`adb` is in the SDK's `platform-tools`. Without USB you can use Wireless debugging (Developer options → Wireless debugging → Pair device with pairing code, then `adb pair <ip:port>` and `adb connect <ip:port>`).

All builds are signed with the same checked-in debug key (`app/debug.keystore`). That means an APK from GitHub and one from Android Studio can be installed over each other without losing your settings.

## First-run setup

Open **Hinge Opener** and work through the Setup list. Each row turns into a ✓ when you return to the app.

1. **Draw over other apps** → Grant → toggle on for Hinge Opener.
2. **Usage access** → Grant → find Hinge Opener → toggle on.
3. **Notifications** → Grant → Allow. If you denied it before, tapping Grant opens the app's notification settings.
4. **API key** → paste your key from console.anthropic.com → **Save key**.
5. Tap **Start bubble** → Android's screen-recording dialog → **Start** (choose "Entire screen" if offered).

> Android 13+ can block some special permissions for apps installed from a browser or file manager ("Restricted setting"). If any toggle above is greyed out or shows that message: **Settings → Apps → Hinge Opener → ⋮ (top right) → Allow restricted settings**, confirm with your PIN, then grant again.

## Test checklist

**Permissions**
- [ ] Fresh install: all four Setup rows show ✗ and **Start bubble** is disabled.
- [ ] Overlay: Grant → toggle on → back. Row shows ✓.
- [ ] Usage access: Grant → toggle on → back. Row shows ✓.
- [ ] Notifications: Grant → Allow. Row shows ✓. A persistent "Hinge Opener is on" notification appears after Start.
- [ ] API key: save a key. The row shows ✓ and the field says "Saved (…last4)". Force-stop the app, reopen, and the key is still there.
- [ ] MediaProjection: Start bubble → dialog → **Cancel**. You get the toast "Screen capture permission is needed" and the bubble stays off. Repeat with **Start** and it shows "● Bubble is running".

**Bubble visibility**
- [ ] With the bubble running, go to the home screen. No bubble.
- [ ] Open Hinge. The bubble appears within about 1 s.
- [ ] Switch to another app (or Recents). The bubble disappears. Back to Hinge and it reappears where you left it.
- [ ] Open Hinge Opener itself. No bubble.
- [ ] Drag the bubble around. It follows your finger and stays on screen.

**Capture → analyze → copy**
- [ ] In Hinge, on a profile: tap the bubble. You see a subtle white flash, a haptic tick, and the badge shows **1**.
- [ ] Scroll and tap again until the badge shows 2, 3… At 6, another tap shows "Max 6 captures".
- [ ] Long-press with 0 captures shows "Tap the bubble to capture her profile first".
- [ ] Long-press with captures: the bubble shows a spinner and taps are ignored. In roughly 10–40 s the card appears with the big best reply, the target chip ("Photo 3" / "Prompt: …"), the reason, **Copy**, and 2 runner-ups each with **Copy**.
- [ ] After a successful result the badge resets (screenshots deleted).
- [ ] Tap **Copy**, then in Hinge tap the matching photo/prompt's comment box and paste. The text matches.
- [ ] Drag the card by its purple header. **✕** closes it. Long-press the bubble (with 0 captures) and it comes back.
- [ ] **Clear captures** removes the card and resets the badge.
- [ ] Change **Runner-ups** to 3 in settings and analyze again. You get 3 runner-ups.

**Errors**
- [ ] Remove the API key, capture, long-press. The card says "No API key…" with Retry and Clear.
- [ ] Save a bogus key (e.g. `sk-ant-wrong`) and analyze. "Invalid API key. Check it in Settings."
- [ ] Airplane mode, then analyze. "No internet connection." Turn the network back on, tap **Retry**, and it works without re-capturing.

**Stopping**
- [ ] The notification's **Stop** action removes the bubble, and the app shows **Start bubble** again.
- [ ] Lock the phone while running. If your Android version ends the capture session, you get a "Hinge Opener stopped – tap to start again" notification, and tapping it opens the app.
