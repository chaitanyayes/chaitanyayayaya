# Hinge Opener for Windows (iPhone + laptop)

Hinge stays on your real iPhone. Its screen is mirrored to the laptop. A small always-on-top window on the laptop captures the mirrored screen while you scroll, then asks Claude (`claude-sonnet-5-5`) for the best opener. It uses the same prompt, scoring and style as the Android app.

```
iPhone (Hinge)  ──AirPlay──▶  mirroring window on laptop  ──Ctrl+Alt+C──▶  captures (max 6)
                                                            ──Ctrl+Alt+A──▶  Claude ──▶ best opener + runner-ups
```

## One-time setup

1. **Python:** install Python 3.12+ from python.org. In the installer, tick **"Add python.exe to PATH"**.
2. **Mirroring app:** install an AirPlay receiver for Windows, e.g. **LonelyScreen**, **AirServer** or **5KPlayer**. Open it and allow it through Windows Firewall when asked.
3. **Mirror the iPhone:** put the phone and laptop on the **same Wi-Fi**. On the iPhone, open **Control Center → Screen Mirroring** and pick the laptop. The iPhone screen now shows in a window.
4. **Start the tool:** double-click **`run.bat`**. The first run takes a minute to install its packages.
5. **Settings** opens automatically: paste your Anthropic API key (console.anthropic.com) and click **Save**. The key is encrypted with Windows DPAPI, so only your Windows account can read it.
6. Click **Select area** and drag a box over the mirrored iPhone screen. This is remembered. Redo it if you move or resize the mirroring window.

## Using it

1. Open a profile in Hinge on the iPhone.
2. Press **Ctrl+Alt+C** (or click Capture). The counter goes up, the window flashes and you hear a beep.
3. Scroll on the phone and press **Ctrl+Alt+C** again, up to 6 times.
4. Press **Ctrl+Alt+A** (or click Analyze). After roughly 10–40 seconds you get the best reply in big text, its target ("Photo 3" / "Prompt: …"), a one-line reason, and runner-ups, each with **Copy**.
5. On the iPhone, tap that photo or prompt in Hinge and send the reply. **Ctrl+Alt+X** clears the captures.

**Getting the text onto the phone:** Windows can't share a clipboard with an iPhone. The replies are 1–2 lines, so typing them works. Or click **Copy**, paste into WhatsApp Web's "Message yourself" chat, then copy it from WhatsApp on the phone.

Details:
- The hotkeys work while any app is focused, including the mirroring window.
- The tool hides itself for a moment during each capture, so it never ends up in a screenshot even if it overlaps.
- Captures stay in memory only, downscaled to ≤1080 px wide JPEG. They're never written to disk and are discarded after a successful analysis or on Clear. If analysis fails, they're kept so you can fix the problem and press Analyze again.
- Settings live in `%APPDATA%\HingeOpener\`. Setting an `ANTHROPIC_API_KEY` environment variable overrides the saved key.

## Test checklist

- [ ] `run.bat` opens the window. Without a key, Settings pops up. After saving, reopening Settings shows "Saved (…last4)".
- [ ] **Select area**: dragging a box saves it, Esc cancels, and a tiny box is rejected.
- [ ] **Ctrl+Alt+C** works with the mirroring window focused: the counter goes 1, 2, 3…, with a flash and beep. A 7th press says "Max 6 captures".
- [ ] **Ctrl+Alt+A** with 0 captures says "Capture her profile first".
- [ ] **Ctrl+Alt+A** with captures shows "Analyzing…", then the result. The counter resets to 0 afterwards.
- [ ] **Copy** puts the reply on the clipboard (paste into Notepad to check).
- [ ] Change runner-ups to 3 in Settings and analyze again. You get 3.
- [ ] Errors: a bogus key shows "Invalid API key". With Wi-Fi off you get a network error; turn it back on, press Analyze again, and it works without re-capturing.
- [ ] If a capture says "came out black", the mirroring app or Hinge is blocking capture. Try a different AirPlay receiver.

## Troubleshooting

- **Hotkeys do nothing:** another app may own Ctrl+Alt+C/A/X. Use the buttons, or change `HOTKEY_*` at the top of `hinge_opener.py`.
- **The captured area is offset:** this happens on scaled displays. Click **Select area** again after moving the mirroring window.
- **Re-install packages:** delete the `.venv` folder and run `run.bat` again.

## Developing

```
py -3 -m venv .venv && .venv\Scripts\pip install -r requirements.txt pytest
.venv\Scripts\python -m pytest -q tests
```
