"""Hinge Opener for Windows.

Mirror your iPhone to the laptop (AirPlay receiver app), scroll Hinge on the phone,
press Ctrl+Alt+C to capture the mirrored window (up to 6 times), then Ctrl+Alt+A to get
the best opener from Claude. Ctrl+Alt+X clears captures.
"""

from __future__ import annotations

import base64
import ctypes
import io
import json
import os
import queue
import sys
import threading
import tkinter as tk
from dataclasses import dataclass
from pathlib import Path
from tkinter import messagebox, ttk

import anthropic
import mss
from PIL import Image

MODEL = "claude-sonnet-5-5"  # latest Claude Sonnet
FALLBACK_BETA = "server-side-fallback-2026-07-01"
MAX_CAPTURES = 6
MAX_WIDTH = 1080
MAX_RUNNER_UPS = 4

HOTKEY_CAPTURE = "<ctrl>+<alt>+c"
HOTKEY_ANALYZE = "<ctrl>+<alt>+a"
HOTKEY_CLEAR = "<ctrl>+<alt>+x"

IS_WINDOWS = sys.platform == "win32"

# ---------------------------------------------------------------------------- prompts

DEFAULT_STYLE = """- Sarcastic, playful, flirty, confident. Never needy or try-hard.
- One or two lines max, casual texting tone. Indian context references are welcome (RTO, GST, chai, Mumbai rains).
- Pattern I like: absurd official-sounding setup → flirty flip ("Do you charge rent? Because the way you've been living in my head...").
- Play along with her jokes; never explain them or ask "what do you mean by X".
- About me, only when it fits naturally: curly hair, ride a motorcycle, go to the gym (very light touch), still figuring out what I'm looking for.
- Never comment on her body. No generic pickup lines; it must be about her."""

SYSTEM_TEMPLATE = """You write Hinge openers for one specific person (the user). On Hinge, every opener is a comment attached to ONE specific photo or ONE specific written prompt on her profile, so every reply you write must target exactly one of those.

You will receive 1–6 screenshots of a single woman's Hinge profile, in scroll order. The screenshots may come from a phone screen mirrored to a computer, so ignore any window borders or desktop around the phone screen. Follow these steps.

1. EXTRACT. Read every screenshot. List each photo (number them Photo 1, Photo 2, ... in the order they appear on the profile, top to bottom) with its setting, activity, vibe, and anything distinctive (objects, places, pets, food, text on signs or clothing, expressions). List each written prompt with its exact title and her exact answer. The screenshots overlap because she scrolled: the same photo or prompt cut across two screenshots is ONE item, so deduplicate. Ignore Hinge UI (buttons, like/heart icons, nav bars, "Send a like", status bar, ads). Profile basics (job, hometown, height, etc.) are context only, never targets. Voice or video prompts count only if a transcript or caption is visible. Never invent details you cannot see.

2. RATE HER PROMPTS. Give each written prompt a quality score 1–10. Short, generic, incomplete, or hard to riff on = low ("I love travelling", "Ask me", one word). Specific, funny, opinionated, or with a hook = high. Compute avg_prompt_quality as the mean (use 0 if she has no written prompts).

3. GENERATE. Write one candidate reply for every photo and every prompt.

4. SCORE. Score each candidate 1–10 on: specificity (clearly about HER and this exact photo/prompt), flirtiness, ease_of_reply (she can answer instantly and wants to), originality (she hasn't heard it 50 times), style_fit (matches the user's style below). raw_score = the mean of the five.

5. WEIGHT. weighted_score = raw_score, then: if avg_prompt_quality < 5, multiply photo-based candidates by 1.3. If avg_prompt_quality >= 7, multiply prompt-based candidates by 1.2. Otherwise no multiplier. Round to 2 decimals.

6. PICK. best_reply = the candidate with the highest weighted_score. runner_ups = the next best ones, in order, each on a DIFFERENT target than the best reply and each other where possible, and exactly as many as the user asks for (fewer only if there aren't enough candidates). target is "photo N" or "prompt: <exact prompt title>". reason = one short line on why it works for her specifically.

Writing rules for every reply (these override everything except the facts in the screenshots):
- Sounds like a real text from a confident guy, not an AI. No emojis unless they add the joke. No hashtags. No quotation marks around the reply.
- Reply text only, ready to paste. Don't prefix with "Re: photo 3" or similar; Hinge already shows what it's attached to.
- Keep it to one or two short lines.

THE USER'S STYLE:
{style}"""


def _obj(required: dict) -> dict:
    return {
        "type": "object",
        "additionalProperties": False,
        "required": list(required),
        "properties": required,
    }


OUTPUT_SCHEMA = _obj({
    "photos": {"type": "array", "items": _obj({
        "number": {"type": "integer"},
        "description": {"type": "string"},
    })},
    "prompts": {"type": "array", "items": _obj({
        "title": {"type": "string"},
        "answer": {"type": "string"},
        "quality": {"type": "integer"},
    })},
    "avg_prompt_quality": {"type": "number"},
    "candidates": {"type": "array", "items": _obj({
        "target": {"type": "string"},
        "kind": {"type": "string", "enum": ["photo", "prompt"]},
        "reply": {"type": "string"},
        "specificity": {"type": "integer"},
        "flirtiness": {"type": "integer"},
        "ease_of_reply": {"type": "integer"},
        "originality": {"type": "integer"},
        "style_fit": {"type": "integer"},
        "raw_score": {"type": "number"},
        "weighted_score": {"type": "number"},
    })},
    "best_reply": {"type": "string"},
    "target": {"type": "string"},
    "reason": {"type": "string"},
    "runner_ups": {"type": "array", "items": _obj({
        "reply": {"type": "string"},
        "target": {"type": "string"},
    })},
})

# ---------------------------------------------------------------------------- result parsing


@dataclass
class Reply:
    text: str
    target: str


@dataclass
class OpenerResult:
    best: Reply
    reason: str
    runner_ups: list[Reply]


class OpenerError(Exception):
    """An error with a message that's fine to show the user."""


def extract_json_object(raw: str) -> str | None:
    s = raw.strip()
    if s.startswith("```"):  # strip ``` / ```json fences
        s = s.split("\n", 1)[1] if "\n" in s else ""
        end = s.rfind("```")
        if end >= 0:
            s = s[:end]
        s = s.strip()
    start, end = s.find("{"), s.rfind("}")
    if start < 0 or end <= start:
        return None
    return s[start:end + 1]


def pretty_target(t: str) -> str:
    t = (t or "").strip()
    return t[:1].upper() + t[1:] if t else ""


def parse_result(raw: str) -> OpenerResult:
    blob = extract_json_object(raw)
    if blob is None:
        raise OpenerError("Claude didn't return JSON. Try again.")
    try:
        obj = json.loads(blob)
    except json.JSONDecodeError:
        raise OpenerError("Couldn't read Claude's answer (bad JSON). Try again.") from None
    if not isinstance(obj, dict):
        raise OpenerError("Couldn't read Claude's answer. Try again.")
    best = str(obj.get("best_reply") or "").strip()
    if not best:
        raise OpenerError("Claude's answer had no best_reply. Try again.")
    runner_ups = []
    for item in obj.get("runner_ups") or []:
        if isinstance(item, dict) and str(item.get("reply") or "").strip():
            runner_ups.append(Reply(str(item["reply"]).strip(), pretty_target(str(item.get("target") or ""))))
    return OpenerResult(
        best=Reply(best, pretty_target(str(obj.get("target") or ""))),
        reason=str(obj.get("reason") or "").strip(),
        runner_ups=runner_ups,
    )

# ---------------------------------------------------------------------------- Claude call


def generate(api_key: str, images: list[bytes], style: str, runner_ups: int,
             client: anthropic.Anthropic | None = None) -> OpenerResult:
    if not api_key:
        raise OpenerError("No API key. Open Settings and paste your Anthropic API key.")
    if not images:
        raise OpenerError("No captures yet. Press Ctrl+Alt+C to capture first.")

    content: list[dict] = []
    for i, jpeg in enumerate(images, 1):
        content.append({"type": "text", "text": f"Screenshot {i}:"})
        content.append({"type": "image", "source": {
            "type": "base64",
            "media_type": "image/jpeg",
            "data": base64.standard_b64encode(jpeg).decode("ascii"),
        }})
    content.append({"type": "text", "text": (
        f"Those are {len(images)} screenshot(s) of her profile, in scroll order. "
        f"Return exactly {runner_ups} runner_ups. Respond with the JSON object only."
    )})

    request = dict(
        model=MODEL,
        max_tokens=16000,
        system=SYSTEM_TEMPLATE.format(style=style),
        output_config={"effort": "medium", "format": {"type": "json_schema", "schema": OUTPUT_SCHEMA}},
        messages=[{"role": "user", "content": content}],
    )
    client = client or anthropic.Anthropic(api_key=api_key, timeout=180.0, max_retries=1)
    try:
        try:
            response = client.beta.messages.create(
                betas=[FALLBACK_BETA], fallbacks="default", **request,
            )
        except anthropic.BadRequestError as e:
            # If the server-side fallback option is ever rejected, retry once without it.
            if "fallback" not in str(e.message).lower():
                raise
            response = client.messages.create(**request)
    except anthropic.AuthenticationError:
        raise OpenerError("Invalid API key. Check it in Settings.") from None
    except anthropic.PermissionDeniedError:
        raise OpenerError(f"This API key isn't allowed to use {MODEL}.") from None
    except anthropic.RateLimitError as e:
        wait = e.response.headers.get("retry-after")
        raise OpenerError("Rate limited by Anthropic" + (f" — try again in {wait}s." if wait else " — wait a minute and try again.")) from None
    except anthropic.BadRequestError as e:
        raise OpenerError(f"API error: {e.message}") from None
    except anthropic.APIStatusError as e:
        if e.status_code == 529:
            raise OpenerError("Anthropic is overloaded right now. Try again in a moment.") from None
        if e.status_code >= 500:
            raise OpenerError(f"Anthropic server error ({e.status_code}). Try again.") from None
        raise OpenerError(f"API error ({e.status_code}): {e.message}") from None
    except anthropic.APITimeoutError:
        raise OpenerError("Request timed out. Check your connection and try again.") from None
    except anthropic.APIConnectionError:
        raise OpenerError("Network error. Check your internet connection.") from None

    if response.stop_reason == "refusal":
        raise OpenerError("Claude declined to answer for this profile. Try different screenshots.")
    if response.stop_reason == "max_tokens":
        raise OpenerError("Claude's answer got cut off. Try fewer screenshots.")
    text = "".join(b.text for b in response.content if getattr(b, "type", None) == "text")
    return parse_result(text)

# ---------------------------------------------------------------------------- screen capture


def capture_region(region: dict) -> bytes:
    """Grabs the region, downscales to ≤1080 px wide, returns JPEG bytes (never written to disk)."""
    with mss.mss() as sct:
        shot = sct.grab(region)
    img = Image.frombytes("RGB", shot.size, shot.rgb)
    if max(img.convert("L").getextrema()) < 12:
        raise OpenerError("Capture came out black. The mirroring app or Hinge may be blocking it.")
    if img.width > MAX_WIDTH:
        img = img.resize((MAX_WIDTH, round(img.height * MAX_WIDTH / img.width)), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=85)
    return buf.getvalue()


def virtual_screen() -> dict:
    with mss.mss() as sct:
        return dict(sct.monitors[0])

# ---------------------------------------------------------------------------- settings storage

APP_DIR = Path(os.environ.get("APPDATA", Path.home())) / "HingeOpener"
CONFIG_FILE = APP_DIR / "config.json"
KEY_FILE = APP_DIR / "api_key.bin"


class _Blob(ctypes.Structure):
    _fields_ = [("cbData", ctypes.c_ulong), ("pbData", ctypes.POINTER(ctypes.c_char))]


def _dpapi(data: bytes, encrypt: bool) -> bytes:
    """Windows DPAPI: only your Windows user account can decrypt the API key."""
    crypt32, kernel32 = ctypes.windll.crypt32, ctypes.windll.kernel32
    buf = ctypes.create_string_buffer(data, len(data))
    blob_in = _Blob(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))
    blob_out = _Blob()
    fn = crypt32.CryptProtectData if encrypt else crypt32.CryptUnprotectData
    if not fn(ctypes.byref(blob_in), None, None, None, None, 0, ctypes.byref(blob_out)):
        raise OSError("DPAPI call failed")
    try:
        return ctypes.string_at(blob_out.pbData, blob_out.cbData)
    finally:
        kernel32.LocalFree(blob_out.pbData)


class Settings:
    def __init__(self) -> None:
        APP_DIR.mkdir(parents=True, exist_ok=True)
        try:
            data = json.loads(CONFIG_FILE.read_text("utf-8"))
        except (OSError, ValueError):
            data = {}
        self.runner_ups: int = int(data.get("runner_ups", 2))
        self.style: str = data.get("style") or DEFAULT_STYLE
        self.region: dict | None = data.get("region")

    def save(self) -> None:
        CONFIG_FILE.write_text(json.dumps({
            "runner_ups": self.runner_ups, "style": self.style, "region": self.region,
        }, indent=2), "utf-8")

    @property
    def api_key(self) -> str:
        env = os.environ.get("ANTHROPIC_API_KEY", "").strip()
        if env:
            return env
        try:
            raw = KEY_FILE.read_bytes()
        except OSError:
            return ""
        try:
            return (_dpapi(raw, encrypt=False) if IS_WINDOWS else raw).decode("utf-8")
        except (OSError, UnicodeDecodeError):
            return ""

    @api_key.setter
    def api_key(self, value: str) -> None:
        value = value.strip()
        if not value:
            KEY_FILE.unlink(missing_ok=True)
            return
        raw = value.encode("utf-8")
        KEY_FILE.write_bytes(_dpapi(raw, encrypt=True) if IS_WINDOWS else raw)

# ---------------------------------------------------------------------------- UI

BG = "#1c1a22"
PANEL = "#26232e"
TEXT = "#f4f1f8"
MUTED = "#b3adbf"
ACCENT = "#7b2cbf"
FLASH = "#e0457b"


class App:
    def __init__(self) -> None:
        self.settings = Settings()
        self.captures: list[bytes] = []
        self.busy = False
        self.events: queue.Queue = queue.Queue()

        self.root = tk.Tk()
        self.root.title("Hinge Opener")
        self.root.configure(bg=BG)
        self.root.attributes("-topmost", True)
        self.root.geometry("420x560+40+80")
        self.root.minsize(340, 260)

        style = ttk.Style(self.root)
        style.theme_use("clam")
        style.configure("TButton", background=PANEL, foreground=TEXT, borderwidth=0, padding=6)
        style.map("TButton", background=[("active", ACCENT), ("disabled", BG)])
        style.configure("Accent.TButton", background=ACCENT, foreground="white")

        self._build()
        self._start_hotkeys()
        self.root.after(50, self._drain_events)
        self._refresh()
        if not self.settings.api_key:
            self.root.after(300, self.open_settings)
        elif not self.settings.region:
            self.root.after(300, lambda: self.status_var.set(
                "First, click 'Select area' and drag over the mirrored iPhone screen."))

    # -- layout

    def _build(self) -> None:
        top = tk.Frame(self.root, bg=BG)
        top.pack(fill="x", padx=12, pady=(12, 6))
        self.count_label = tk.Label(top, text="0", bg=ACCENT, fg="white", width=3,
                                    font=("Segoe UI", 14, "bold"))
        self.count_label.pack(side="left")
        self.status_var = tk.StringVar()
        tk.Label(top, textvariable=self.status_var, bg=BG, fg=MUTED, anchor="w", justify="left",
                 wraplength=320, font=("Segoe UI", 9)).pack(side="left", padx=10, fill="x", expand=True)

        buttons = tk.Frame(self.root, bg=BG)
        buttons.pack(fill="x", padx=12)
        self.capture_btn = ttk.Button(buttons, text="Capture  (Ctrl+Alt+C)", command=self.capture)
        self.capture_btn.grid(row=0, column=0, sticky="ew", padx=(0, 4), pady=2)
        self.analyze_btn = ttk.Button(buttons, text="Analyze  (Ctrl+Alt+A)", style="Accent.TButton",
                                      command=self.analyze)
        self.analyze_btn.grid(row=0, column=1, sticky="ew", padx=(4, 0), pady=2)
        ttk.Button(buttons, text="Clear  (Ctrl+Alt+X)", command=self.clear).grid(
            row=1, column=0, sticky="ew", padx=(0, 4), pady=2)
        ttk.Button(buttons, text="Select area", command=self.select_area).grid(
            row=1, column=1, sticky="ew", padx=(4, 0), pady=2)
        ttk.Button(buttons, text="Settings", command=self.open_settings).grid(
            row=2, column=0, columnspan=2, sticky="ew", pady=2)
        buttons.columnconfigure(0, weight=1)
        buttons.columnconfigure(1, weight=1)

        # Scrollable result area.
        outer = tk.Frame(self.root, bg=BG)
        outer.pack(fill="both", expand=True, padx=12, pady=12)
        canvas = tk.Canvas(outer, bg=BG, highlightthickness=0)
        scroll = ttk.Scrollbar(outer, orient="vertical", command=canvas.yview)
        self.result_frame = tk.Frame(canvas, bg=BG)
        self.result_frame.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        window = canvas.create_window((0, 0), window=self.result_frame, anchor="nw")
        canvas.bind("<Configure>", lambda e: canvas.itemconfigure(window, width=e.width))
        canvas.configure(yscrollcommand=scroll.set)
        canvas.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        canvas.bind_all("<MouseWheel>", lambda e: canvas.yview_scroll(int(-e.delta / 120), "units"))
        self.wrap = 360
        canvas.bind("<Configure>", lambda e: setattr(self, "wrap", max(200, e.width - 30)), add="+")

    def _clear_results(self) -> None:
        for w in self.result_frame.winfo_children():
            w.destroy()

    def _chip(self, parent: tk.Widget, text: str) -> None:
        if text:
            tk.Label(parent, text=f" {text} ", bg=ACCENT, fg="white", font=("Segoe UI", 9, "bold")).pack(anchor="w")

    def show_result(self, r: OpenerResult) -> None:
        self._clear_results()
        f = self.result_frame
        tk.Label(f, text="BEST OPENER", bg=BG, fg=MUTED, font=("Segoe UI", 9, "bold")).pack(anchor="w")
        self._chip(f, r.best.target)
        tk.Label(f, text=r.best.text, bg=BG, fg=TEXT, wraplength=self.wrap, justify="left", anchor="w",
                 font=("Segoe UI", 16, "bold")).pack(anchor="w", pady=(6, 4), fill="x")
        if r.reason:
            tk.Label(f, text=r.reason, bg=BG, fg=MUTED, wraplength=self.wrap, justify="left", anchor="w",
                     font=("Segoe UI", 9, "italic")).pack(anchor="w", fill="x")
        ttk.Button(f, text="Copy", style="Accent.TButton",
                   command=lambda t=r.best.text: self.copy(t)).pack(fill="x", pady=(8, 12))
        if r.runner_ups:
            tk.Label(f, text="RUNNER-UPS", bg=BG, fg=MUTED, font=("Segoe UI", 9, "bold")).pack(anchor="w")
        for ru in r.runner_ups:
            box = tk.Frame(f, bg=PANEL)
            box.pack(fill="x", pady=4)
            inner = tk.Frame(box, bg=PANEL)
            inner.pack(side="left", fill="x", expand=True, padx=8, pady=6)
            self._chip(inner, ru.target)
            tk.Label(inner, text=ru.text, bg=PANEL, fg=TEXT, wraplength=self.wrap - 80, justify="left", anchor="w",
                     font=("Segoe UI", 11)).pack(anchor="w", pady=(4, 0))
            ttk.Button(box, text="Copy", command=lambda t=ru.text: self.copy(t)).pack(side="right", padx=6)

    def show_error(self, msg: str) -> None:
        self._clear_results()
        f = self.result_frame
        tk.Label(f, text="Something went wrong", bg=BG, fg=FLASH, font=("Segoe UI", 11, "bold")).pack(anchor="w")
        tk.Label(f, text=msg, bg=BG, fg=TEXT, wraplength=self.wrap, justify="left", anchor="w",
                 font=("Segoe UI", 10)).pack(anchor="w", pady=6, fill="x")
        if self.captures:
            tk.Label(f, text="Your captures are kept. Fix the issue, then press Analyze to retry.",
                     bg=BG, fg=MUTED, wraplength=self.wrap, justify="left", font=("Segoe UI", 9)).pack(anchor="w")

    def _refresh(self) -> None:
        n = len(self.captures)
        self.count_label.configure(text=str(n))
        if self.busy:
            self.status_var.set("Analyzing… this usually takes 10–40 seconds.")
        elif not self.settings.region:
            self.status_var.set("Click 'Select area' and drag over the mirrored iPhone screen.")
        else:
            self.status_var.set(f"{n}/{MAX_CAPTURES} captured. Scroll on the phone, then capture again, "
                                f"or Analyze." if n else "Open a profile in Hinge and press Ctrl+Alt+C.")
        state = "disabled" if self.busy else "normal"
        self.capture_btn.configure(state=state)
        self.analyze_btn.configure(state=state)

    # -- actions

    def capture(self) -> None:
        if self.busy:
            return
        if not self.settings.region:
            self.status_var.set("Select the capture area first.")
            self.select_area()
            return
        if len(self.captures) >= MAX_CAPTURES:
            self.status_var.set(f"Max {MAX_CAPTURES} captures. Press Analyze (Ctrl+Alt+A).")
            self._bell()
            return
        # Make our window invisible during the grab in case it overlaps the mirrored screen.
        self.root.attributes("-alpha", 0.0)
        self.root.after(120, self._grab)

    def _grab(self) -> None:
        try:
            self.captures.append(capture_region(self.settings.region))
        except OpenerError as e:
            self.status_var.set(str(e))
            self._bell()
            return
        except Exception as e:  # noqa: BLE001 - surface anything from mss/PIL
            self.status_var.set(f"Capture failed: {e}")
            self._bell()
            return
        finally:
            self.root.attributes("-alpha", 1.0)
        self._refresh()
        self._flash()

    def _flash(self) -> None:
        self.count_label.configure(bg=FLASH)
        self.root.configure(bg=FLASH)
        self.root.after(140, lambda: (self.count_label.configure(bg=ACCENT), self.root.configure(bg=BG)))
        if IS_WINDOWS:
            import winsound
            winsound.MessageBeep(winsound.MB_OK)

    def _bell(self) -> None:
        self.root.bell()

    def analyze(self) -> None:
        if self.busy:
            return
        if not self.captures:
            self.status_var.set("Capture her profile first (Ctrl+Alt+C).")
            self._bell()
            return
        self.busy = True
        self._refresh()
        self._clear_results()
        images = list(self.captures)
        key, style, n = self.settings.api_key, self.settings.style, self.settings.runner_ups

        def work() -> None:
            try:
                self.events.put(("result", generate(key, images, style, n)))
            except OpenerError as e:
                self.events.put(("error", str(e)))
            except Exception as e:  # noqa: BLE001
                self.events.put(("error", f"Unexpected error: {e}"))

        threading.Thread(target=work, daemon=True).start()

    def clear(self) -> None:
        if self.busy:
            return
        self.captures.clear()
        self._clear_results()
        self._refresh()

    def copy(self, text: str) -> None:
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        self.root.update()
        self.status_var.set("Copied to clipboard.")

    # -- events from hotkey / worker threads

    def _drain_events(self) -> None:
        try:
            while True:
                kind, payload = self.events.get_nowait()
                if kind == "hotkey":
                    {"capture": self.capture, "analyze": self.analyze, "clear": self.clear}[payload]()
                elif kind == "result":
                    self.busy = False
                    self.captures.clear()  # screenshots are discarded after a successful analysis
                    self._refresh()
                    self.show_result(payload)
                    self.root.lift()
                elif kind == "error":
                    self.busy = False
                    self._refresh()
                    self.show_error(payload)
                    self.root.lift()
        except queue.Empty:
            pass
        self.root.after(50, self._drain_events)

    def _start_hotkeys(self) -> None:
        try:
            from pynput import keyboard
        except Exception:  # noqa: BLE001
            self.status_var.set("Hotkeys unavailable (pynput not installed). Use the buttons.")
            return
        hotkeys = keyboard.GlobalHotKeys({
            HOTKEY_CAPTURE: lambda: self.events.put(("hotkey", "capture")),
            HOTKEY_ANALYZE: lambda: self.events.put(("hotkey", "analyze")),
            HOTKEY_CLEAR: lambda: self.events.put(("hotkey", "clear")),
        })
        hotkeys.daemon = True
        hotkeys.start()

    # -- capture area picker

    def select_area(self) -> None:
        vs = virtual_screen()
        overlay = tk.Toplevel(self.root)
        overlay.overrideredirect(True)
        overlay.attributes("-topmost", True)
        overlay.attributes("-alpha", 0.3)
        overlay.geometry(f"{vs['width']}x{vs['height']}+{vs['left']}+{vs['top']}")
        cv = tk.Canvas(overlay, bg="black", highlightthickness=0, cursor="crosshair")
        cv.pack(fill="both", expand=True)
        cv.create_text(vs["width"] // 2, 60, fill="white", font=("Segoe UI", 20, "bold"),
                       text="Drag over the mirrored iPhone screen  ·  Esc to cancel")
        state: dict = {}

        def down(e: tk.Event) -> None:
            state["x0"], state["y0"] = e.x_root, e.y_root
            state["rect"] = cv.create_rectangle(e.x, e.y, e.x, e.y, outline=FLASH, width=3)

        def move(e: tk.Event) -> None:
            if "rect" in state:
                cv.coords(state["rect"], state["x0"] - vs["left"], state["y0"] - vs["top"], e.x, e.y)

        def up(e: tk.Event) -> None:
            if "x0" not in state:
                return
            x1, y1 = sorted((state["x0"], e.x_root)), sorted((state["y0"], e.y_root))
            overlay.destroy()
            if x1[1] - x1[0] < 80 or y1[1] - y1[0] < 120:
                self.status_var.set("That area was too small. Try 'Select area' again.")
                return
            self.settings.region = {"left": x1[0], "top": y1[0],
                                    "width": x1[1] - x1[0], "height": y1[1] - y1[0]}
            self.settings.save()
            self._refresh()

        cv.bind("<ButtonPress-1>", down)
        cv.bind("<B1-Motion>", move)
        cv.bind("<ButtonRelease-1>", up)
        overlay.bind("<Escape>", lambda e: overlay.destroy())
        overlay.focus_force()

    # -- settings window

    def open_settings(self) -> None:
        win = tk.Toplevel(self.root)
        win.title("Hinge Opener settings")
        win.configure(bg=BG)
        win.attributes("-topmost", True)
        win.geometry("520x560")

        def label(text: str, sub: str = "") -> None:
            tk.Label(win, text=text, bg=BG, fg=TEXT, font=("Segoe UI", 10, "bold")).pack(anchor="w", padx=14, pady=(12, 0))
            if sub:
                tk.Label(win, text=sub, bg=BG, fg=MUTED, font=("Segoe UI", 8), wraplength=480,
                         justify="left").pack(anchor="w", padx=14)

        current = self.settings.api_key
        label("Anthropic API key",
              f"Saved (…{current[-4:]}), encrypted for your Windows account. Paste a new one to replace it."
              if current else "Get one at console.anthropic.com. Stored encrypted for your Windows account.")
        key_var = tk.StringVar()
        tk.Entry(win, textvariable=key_var, show="•", bg=PANEL, fg=TEXT, insertbackground=TEXT,
                 relief="flat").pack(fill="x", padx=14, pady=4, ipady=4)

        label("Runner-ups", "Extra options shown under the best reply (0–4).")
        n_var = tk.IntVar(value=self.settings.runner_ups)
        tk.Spinbox(win, from_=0, to=MAX_RUNNER_UPS, textvariable=n_var, width=4, bg=PANEL, fg=TEXT,
                   buttonbackground=PANEL, relief="flat").pack(anchor="w", padx=14, pady=4)

        label("My style", "Sent to Claude with every request.")
        style_box = tk.Text(win, height=12, wrap="word", bg=PANEL, fg=TEXT, insertbackground=TEXT,
                            relief="flat", font=("Segoe UI", 9))
        style_box.insert("1.0", self.settings.style)
        style_box.pack(fill="both", expand=True, padx=14, pady=4)

        row = tk.Frame(win, bg=BG)
        row.pack(fill="x", padx=14, pady=12)

        def reset_style() -> None:
            style_box.delete("1.0", "end")
            style_box.insert("1.0", DEFAULT_STYLE)

        def save() -> None:
            if key_var.get().strip():
                try:
                    self.settings.api_key = key_var.get()
                except OSError as e:
                    messagebox.showerror("Hinge Opener", f"Couldn't save the key: {e}", parent=win)
                    return
            try:
                self.settings.runner_ups = max(0, min(MAX_RUNNER_UPS, int(n_var.get())))
            except (tk.TclError, ValueError):
                pass
            self.settings.style = style_box.get("1.0", "end").strip() or DEFAULT_STYLE
            self.settings.save()
            win.destroy()
            self._refresh()

        ttk.Button(row, text="Reset style to default", command=reset_style).pack(side="left")
        ttk.Button(row, text="Save", style="Accent.TButton", command=save).pack(side="right")

    def run(self) -> None:
        self.root.mainloop()


def main() -> None:
    if IS_WINDOWS:
        # Use physical pixels so the selected area matches what mss captures on scaled displays.
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except (AttributeError, OSError):
            ctypes.windll.user32.SetProcessDPIAware()
    App().run()


if __name__ == "__main__":
    main()
