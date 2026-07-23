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
from pathlib import Path

DEFAULT_BACKEND = "https://tapsave-backend.onrender.com"
CONFIG_FILE = Path.home() / ".tapsave.json"
URL_RE = re.compile(r"https?://[^\s\"'<>]+", re.IGNORECASE)

SIZE = 64
TRANSPARENT = "#ff00ff"  # made see-through on Windows so the window looks round
COLOR_IDLE = "#6c4dff"
COLOR_BUSY = "#9aa0aa"
COLOR_OK = "#22c55e"
COLOR_ERR = "#ef4444"


def load_backend() -> str:
    try:
        return json.loads(CONFIG_FILE.read_text()).get("backend", DEFAULT_BACKEND)
    except Exception:
        return DEFAULT_BACKEND


def save_backend(value: str) -> None:
    try:
        CONFIG_FILE.write_text(json.dumps({"backend": value}))
    except Exception:
        pass


class FloatingButton:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.backend = load_backend()
        self.busy = False
        self._start = (0, 0)
        self._orig = (0, 0)
        self._moved = False
        self._down = 0.0

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
        self.menu.add_command(label="Server address…", command=self.edit_backend)
        self.menu.add_separator()
        self.menu.add_command(label="Quit", command=root.destroy)

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

    def set_color(self, color: str):
        self.root.after(0, lambda: self.canvas.itemconfig(self.circle, fill=color))

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
            save_backend(self.backend)
            top.destroy()

        tk.Button(top, text="Save", command=save).pack(pady=12)

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
        try:
            base = self.backend.strip().rstrip("/")
            if not base:
                self.toast("Set the server address (right-click → Server address).", COLOR_ERR)
                return
            endpoint = base + "/download?url=" + urllib.parse.quote(video_url, safe="")
            out_dir = Path.home() / "Downloads" / "TapSave"
            out_dir.mkdir(parents=True, exist_ok=True)
            out_file = out_dir / f"video_{int(time.time())}.mp4"

            request = urllib.request.Request(endpoint, headers={"User-Agent": "TapSave-Desktop"})
            with urllib.request.urlopen(request, timeout=600) as resp, open(out_file, "wb") as f:
                while True:
                    chunk = resp.read(65536)
                    if not chunk:
                        break
                    f.write(chunk)
            self.set_color(COLOR_OK)
            self.toast(f"Saved to {out_file.parent}", COLOR_OK)
        except urllib.error.HTTPError as e:
            try:
                body = e.read().decode("utf-8", "ignore")
            except Exception:
                body = ""
            self.set_color(COLOR_ERR)
            self.toast(f"Error {e.code}: {body[:160]}", COLOR_ERR)
        except Exception as e:
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
