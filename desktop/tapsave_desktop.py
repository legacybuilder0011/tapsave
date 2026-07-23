"""TapSave desktop — a small always-on-top floating window.

Copy a video link anywhere, then click the button: it reads the link from the
clipboard, downloads the video through the TapSave backend, and saves it to
your Downloads/TapSave folder. Stays on top of other windows like the phone's
floating button.

Standard library only (tkinter ships with Python), so it packages cleanly into
a single .exe with PyInstaller.
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


class TapSaveApp:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.backend = load_backend()
        self.busy = False

        root.title("TapSave")
        root.attributes("-topmost", True)
        root.geometry("280x170")
        root.resizable(False, False)
        root.configure(bg="#12121a")

        tk.Label(
            root, text="TapSave", fg="#ffffff", bg="#12121a",
            font=("Segoe UI", 15, "bold"),
        ).pack(pady=(12, 2))

        self.status = tk.Label(
            root, text="Copy a link, then click below.",
            fg="#b8b8c6", bg="#12121a", wraplength=250, font=("Segoe UI", 9),
        )
        self.status.pack()

        self.button = tk.Button(
            root, text="⬇  Paste link & download", command=self.on_click,
            bg="#6c4dff", fg="#ffffff", activebackground="#5a3fd6",
            activeforeground="#ffffff", relief="flat", height=2,
            font=("Segoe UI", 11, "bold"), cursor="hand2",
        )
        self.button.pack(fill="x", padx=16, pady=12)

        tk.Button(
            root, text="Server address…", command=self.edit_backend,
            bg="#12121a", fg="#8a8a9a", activebackground="#12121a",
            relief="flat", font=("Segoe UI", 8), cursor="hand2",
        ).pack()

    def edit_backend(self):
        top = tk.Toplevel(self.root)
        top.title("Server address")
        top.attributes("-topmost", True)
        top.configure(bg="#12121a")
        tk.Label(
            top, text="TapSave backend URL:", fg="#ffffff", bg="#12121a",
            font=("Segoe UI", 10),
        ).pack(padx=12, pady=(12, 4))
        entry = tk.Entry(top, width=42)
        entry.insert(0, self.backend)
        entry.pack(padx=12)

        def save():
            self.backend = entry.get().strip()
            save_backend(self.backend)
            top.destroy()

        tk.Button(top, text="Save", command=save).pack(pady=12)

    def set_status(self, text: str):
        self.root.after(0, lambda: self.status.config(text=text))

    def set_busy(self, busy: bool):
        self.busy = busy
        self.root.after(
            0, lambda: self.button.config(state="disabled" if busy else "normal")
        )

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
            self.set_status("No link in clipboard — copy a video link first.")
            return
        self.set_busy(True)
        self.set_status("Downloading… this can take a bit.")
        threading.Thread(target=self.download, args=(match.group(0),), daemon=True).start()

    def download(self, video_url: str):
        try:
            base = self.backend.strip().rstrip("/")
            if not base:
                self.set_status("Set the server address first (Server address…).")
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
            self.set_status(f"Saved to {out_file.parent} ✔")
        except urllib.error.HTTPError as e:
            try:
                body = e.read().decode("utf-8", "ignore")
            except Exception:
                body = ""
            self.set_status(f"Server error {e.code}: {body[:120]}")
        except Exception as e:
            self.set_status("Error: " + str(e)[:140])
        finally:
            self.set_busy(False)


def main():
    root = tk.Tk()
    TapSaveApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
