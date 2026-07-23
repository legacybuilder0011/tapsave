"""TapSave desktop — a small always-on-top floating circle button.

Copy a video link anywhere, then click the little floating button: it reads the
link from the clipboard, downloads the video through the TapSave backend, and
saves it to Downloads/TapSave. Drag to move it; right-click for the menu.

Standard library only (tkinter), so it packages into a single .exe.
"""

import json
import re
import threading
import time
import tkinter as tk
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from pathlib import Path

import os

DEFAULT_BACKEND = "https://tapsave-backend.onrender.com"
CONFIG_FILE = Path.home() / ".tapsave.json"
HISTORY_FILE = Path.home() / ".tapsave_history.json"
URL_RE = re.compile(r"https?://[^\s\"'<>]+", re.IGNORECASE)

# Update checking against the same GitHub release the phone app uses.
VERSION_JSON_URL = "https://github.com/legacybuilder0011/tapsave/releases/download/latest/version.json"
EXE_URL = "https://github.com/legacybuilder0011/tapsave/releases/download/latest/TapSave.exe"

# Build number is stamped in by CI (desktop/_build_version.py); 0 when running
# from source locally.
try:
    from _build_version import BUILD as LOCAL_BUILD
except Exception:
    LOCAL_BUILD = 0

SIZE = 64
TRANSPARENT = "#ff00ff"  # made see-through on Windows so the window looks round
COLOR_IDLE = "#6c4dff"
COLOR_BUSY = "#9aa0aa"
COLOR_OK = "#22c55e"
COLOR_ERR = "#ef4444"


def load_config() -> dict:
    try:
        return json.loads(CONFIG_FILE.read_text())
    except Exception:
        return {}


def save_config(cfg: dict) -> None:
    try:
        CONFIG_FILE.write_text(json.dumps(cfg))
    except Exception:
        pass


def load_history() -> list:
    try:
        return json.loads(HISTORY_FILE.read_text())
    except Exception:
        return []


def add_history(entry: dict) -> None:
    items = load_history()
    items.insert(0, entry)
    try:
        HISTORY_FILE.write_text(json.dumps(items[:200]))
    except Exception:
        pass


class FloatingButton:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.config = load_config()
        self.backend = self.config.get("backend", DEFAULT_BACKEND)
        self.busy = False
        self._start = (0, 0)
        self._orig = (0, 0)
        self._moved = False
        self._down = 0.0
        self.percent_id = None
        self.quality_var = tk.StringVar(value=self.config.get("quality", "high"))
        self.audio_var = tk.BooleanVar(value=self.config.get("audio", False))

        root.overrideredirect(True)
        root.attributes("-topmost", True)
        try:
            root.attributes("-transparentcolor", TRANSPARENT)
        except tk.TclError:
            pass
        sw = root.winfo_screenwidth()
        root.geometry(f"{SIZE}x{SIZE}+{sw - 120}+140")

        self.canvas = tk.Canvas(
            root, width=SIZE, height=SIZE, bg=TRANSPARENT, highlightthickness=0
        )
        self.canvas.pack()
        self.circle = self.canvas.create_oval(4, 4, SIZE - 4, SIZE - 4, fill=COLOR_IDLE, outline="")
        self._draw_arrow()

        self.canvas.bind("<ButtonPress-1>", self.on_press)
        self.canvas.bind("<B1-Motion>", self.on_drag)
        self.canvas.bind("<ButtonRelease-1>", self.on_release)
        self.canvas.bind("<ButtonPress-3>", self.show_menu)

        self.menu = tk.Menu(root, tearoff=0)
        quality_menu = tk.Menu(self.menu, tearoff=0)
        for label, val in (("High", "high"), ("720p", "medium"), ("480p", "low")):
            quality_menu.add_radiobutton(
                label=label, value=val, variable=self.quality_var, command=self.save_prefs
            )
        self.menu.add_cascade(label="Quality", menu=quality_menu)
        self.menu.add_checkbutton(
            label="Audio only (MP3)", variable=self.audio_var, command=self.save_prefs
        )
        self.menu.add_command(label="Download history…", command=self.show_history)
        self.menu.add_separator()
        self.menu.add_command(label="Server address…", command=self.edit_backend)
        self.menu.add_command(label="Check for updates", command=lambda: self.check_update(True))
        self.menu.add_separator()
        self.menu.add_command(label="Quit", command=root.destroy)

        # Quietly check for a newer version shortly after launch.
        root.after(1500, lambda: self.check_update(False))

    def check_update(self, user_initiated: bool):
        def work():
            latest = None
            try:
                req = urllib.request.Request(VERSION_JSON_URL, headers={"User-Agent": "TapSave"})
                with urllib.request.urlopen(req, timeout=15) as r:
                    latest = json.loads(r.read().decode("utf-8", "ignore"))
            except Exception:
                latest = None

            def done():
                if not latest:
                    if user_initiated:
                        self.toast("Couldn't check for updates.", COLOR_ERR)
                    return
                if int(latest.get("versionCode", 0)) > LOCAL_BUILD:
                    self.toast("Update available — opening download…", COLOR_IDLE)
                    try:
                        webbrowser.open(EXE_URL)
                    except Exception:
                        pass
                elif user_initiated:
                    self.toast("You're on the latest version.", COLOR_OK)

            self.root.after(0, done)

        threading.Thread(target=work, daemon=True).start()

    def _draw_arrow(self):
        cx = SIZE / 2
        self.canvas.delete("arrow")
        self.canvas.create_line(cx, 18, cx, 38, fill="#ffffff", width=4, tags="arrow")
        self.canvas.create_polygon(
            cx - 11, 34, cx + 11, 34, cx, 48, fill="#ffffff", outline="", tags="arrow"
        )
        self.canvas.create_line(
            cx - 13, 52, cx + 13, 52, fill="#ffffff", width=4, tags="arrow"
        )

    def save_prefs(self):
        self.config["backend"] = self.backend
        self.config["quality"] = self.quality_var.get()
        self.config["audio"] = bool(self.audio_var.get())
        save_config(self.config)

    def set_color(self, color: str):
        self.root.after(0, lambda: self.canvas.itemconfig(self.circle, fill=color))

    def set_percent(self, pct):
        """Show a percentage in the middle of the button (None clears it)."""
        def apply():
            if self.percent_id is not None:
                self.canvas.delete(self.percent_id)
                self.percent_id = None
            if pct is None:
                self.canvas.itemconfigure("arrow", state="normal")
            else:
                self.canvas.itemconfigure("arrow", state="hidden")
                self.percent_id = self.canvas.create_text(
                    SIZE / 2, SIZE / 2, text=f"{pct}%", fill="#ffffff",
                    font=("Segoe UI", 11, "bold"),
                )
        self.root.after(0, apply)

    # --- drag vs click ---
    def on_press(self, e):
        self._start = (e.x_root, e.y_root)
        self._orig = (self.root.winfo_x(), self.root.winfo_y())
        self._moved = False
        self._down = time.time()

    def on_drag(self, e):
        dx = e.x_root - self._start[0]
        dy = e.y_root - self._start[1]
        if abs(dx) + abs(dy) > 4:
            self._moved = True
            self.root.geometry(f"+{self._orig[0] + dx}+{self._orig[1] + dy}")

    def on_release(self, e):
        if not self._moved and (time.time() - self._down) < 0.6:
            self.on_click()

    def show_menu(self, e):
        try:
            self.menu.tk_popup(e.x_root, e.y_root)
        finally:
            self.menu.grab_release()

    # --- toast feedback ---
    def toast(self, text: str, color: str = "#222"):
        def show():
            tip = tk.Toplevel(self.root)
            tip.overrideredirect(True)
            tip.attributes("-topmost", True)
            tk.Label(
                tip, text=text, bg=color, fg="#fff", padx=10, pady=6,
                font=("Segoe UI", 9), wraplength=240, justify="left",
            ).pack()
            x = self.root.winfo_x() - 250
            y = self.root.winfo_y()
            tip.geometry(f"+{max(x, 10)}+{y}")
            tip.after(3500, tip.destroy)

        self.root.after(0, show)

    def edit_backend(self):
        top = tk.Toplevel(self.root)
        top.title("Server address")
        top.attributes("-topmost", True)
        tk.Label(top, text="TapSave backend URL:").pack(padx=12, pady=(12, 4))
        entry = tk.Entry(top, width=42)
        entry.insert(0, self.backend)
        entry.pack(padx=12)

        def save():
            self.backend = entry.get().strip()
            self.save_prefs()
            top.destroy()

        tk.Button(top, text="Save", command=save).pack(pady=12)

    def show_history(self):
        top = tk.Toplevel(self.root)
        top.title("TapSave downloads")
        top.attributes("-topmost", True)
        top.geometry("460x320")

        listbox = tk.Listbox(top, font=("Segoe UI", 10))
        listbox.pack(fill="both", expand=True, padx=10, pady=10)
        entries = load_history()
        for e in entries:
            tag = "🎵" if e.get("audio") else "🎬"
            listbox.insert("end", f"{tag}  {e.get('name', '')}")

        def selected_path():
            sel = listbox.curselection()
            if not sel:
                return None
            return entries[sel[0]].get("path")

        def open_file():
            path = selected_path()
            if not path:
                return
            try:
                os.startfile(path)  # type: ignore[attr-defined]
            except Exception:
                webbrowser.open("file://" + str(path))

        def open_folder():
            path = selected_path()
            if not path:
                return
            folder = str(Path(path).parent)
            try:
                os.startfile(folder)  # type: ignore[attr-defined]
            except Exception:
                webbrowser.open("file://" + folder)

        bar = tk.Frame(top)
        bar.pack(fill="x", padx=10, pady=(0, 10))
        tk.Button(bar, text="Open file", command=open_file).pack(side="left")
        tk.Button(bar, text="Open folder", command=open_folder).pack(side="left", padx=6)
        tk.Button(bar, text="Close", command=top.destroy).pack(side="right")

    def clipboard_text(self) -> str:
        try:
            return self.root.clipboard_get()
        except Exception:
            return ""

    def on_click(self):
        if self.busy:
            return
        match = URL_RE.search(self.clipboard_text())
        if not match:
            self.toast("No link in clipboard — copy a video link first.", COLOR_ERR)
            return
        self.busy = True
        self.set_color(COLOR_BUSY)
        self.toast("Downloading…")
        threading.Thread(target=self.download, args=(match.group(0),), daemon=True).start()

    def download(self, video_url: str):
        audio = bool(self.audio_var.get())
        quality = self.quality_var.get()
        try:
            base = self.backend.strip().rstrip("/")
            if not base:
                self.toast("Set the server address (right-click → Server address).", COLOR_ERR)
                return
            endpoint = (
                base + "/download?url=" + urllib.parse.quote(video_url, safe="")
                + "&quality=" + quality + ("&audio=1" if audio else "")
            )
            out_dir = Path.home() / "Downloads" / "TapSave"
            out_dir.mkdir(parents=True, exist_ok=True)
            ext = ".mp3" if audio else ".mp4"
            out_file = out_dir / f"{'audio' if audio else 'video'}_{int(time.time())}{ext}"

            request = urllib.request.Request(endpoint, headers={"User-Agent": "TapSave-Desktop"})
            with urllib.request.urlopen(request, timeout=600) as resp, open(out_file, "wb") as f:
                total = int(resp.headers.get("Content-Length") or 0)
                received = 0
                last = -1
                while True:
                    chunk = resp.read(65536)
                    if not chunk:
                        break
                    f.write(chunk)
                    received += len(chunk)
                    if total:
                        pct = int(received * 100 / total)
                        if pct != last:
                            last = pct
                            self.set_percent(pct)
            self.set_percent(None)
            self.set_color(COLOR_OK)
            add_history({
                "name": out_file.name,
                "path": str(out_file),
                "audio": audio,
                "time": int(time.time()),
            })
            self.toast(f"Saved to {out_file.parent}", COLOR_OK)
        except urllib.error.HTTPError as e:
            try:
                body = e.read().decode("utf-8", "ignore")
            except Exception:
                body = ""
            self.set_percent(None)
            self.set_color(COLOR_ERR)
            self.toast(f"Error {e.code}: {body[:160]}", COLOR_ERR)
        except Exception as e:
            self.set_percent(None)
            self.set_color(COLOR_ERR)
            self.toast("Error: " + str(e)[:160], COLOR_ERR)
        finally:
            self.busy = False
            self.root.after(1600, lambda: self.set_color(COLOR_IDLE))


def main():
    root = tk.Tk()
    FloatingButton(root)
    root.mainloop()


if __name__ == "__main__":
    main()
