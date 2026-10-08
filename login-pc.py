"""MiniSpot setup from a computer: logs into Spotify in the computer's browser and sends the session to the phone.

Needs Python 3, adb, the phone plugged in by USB with USB debugging on, and your own app on
https://developer.spotify.com/dashboard (owned by a Spotify Premium account) with this Redirect URI:
    http://127.0.0.1:8888/callback

Usage:  python login-pc.py [CLIENT_ID]
The Client ID is remembered in client_id.txt next to this script (not committed).
"""
import base64
import hashlib
import html
import http.server
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import urllib.parse
import urllib.request
import webbrowser

HERE = os.path.dirname(os.path.abspath(__file__))
CLIENT_ID_FILE = os.path.join(HERE, "client_id.txt")
PORT = 8888
REDIRECT = f"http://127.0.0.1:{PORT}/callback"
PACKAGE = "com.minispot.app"
SCOPES = (
    "user-read-private playlist-read-private playlist-read-collaborative playlist-modify-public "
    "user-library-read user-library-modify user-follow-read user-follow-modify "
    "user-read-recently-played user-read-playback-state user-modify-playback-state user-read-currently-playing"
)


def find_adb():
    candidates = [
        os.environ.get("ADB"),
        shutil.which("adb"),
        os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb.exe"),
        os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"),
        r"C:\Program Files (x86)\Minimal ADB and Fastboot\adb.exe",
        r"C:\platform-tools\adb.exe",
    ]
    for c in candidates:
        if c and os.path.isfile(c):
            return c
    sys.exit("adb introuvable / adb not found. Installe les Android platform-tools ou mets ADB=chemin\\adb.exe")


def get_client_id():
    cid = sys.argv[1] if len(sys.argv) > 1 else ""
    if not cid and os.path.isfile(CLIENT_ID_FILE):
        cid = open(CLIENT_ID_FILE, encoding="utf-8").read().strip()
    if not cid:
        cid = input("Client ID de ton app Spotify (developer.spotify.com) / your Spotify app Client ID: ").strip()
    if not re.fullmatch(r"[0-9a-fA-F]{32}", cid):
        sys.exit("Client ID invalide (32 caracteres 0-9 a-f) / invalid Client ID")
    with open(CLIENT_ID_FILE, "w", encoding="utf-8") as f:
        f.write(cid)
    return cid


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def adb(adb_path, *args):
    return subprocess.run([adb_path, *args], capture_output=True, text=True)


def main():
    adb_path = find_adb()
    devices = adb(adb_path, "devices").stdout.split("\n")[1:]
    if not any(line.strip().endswith("device") for line in devices):
        sys.exit("Aucun telephone detecte / no phone found: branche-le en USB avec le debogage USB actif")
    if PACKAGE not in adb(adb_path, "shell", "pm", "list", "packages", PACKAGE).stdout:
        sys.exit("MiniSpot n'est pas installe sur le telephone / MiniSpot is not installed")

    cid = get_client_id()
    verifier = b64url(secrets.token_bytes(48))
    challenge = b64url(hashlib.sha256(verifier.encode("ascii")).digest())
    state = secrets.token_urlsafe(16)
    url = "https://accounts.spotify.com/authorize?" + urllib.parse.urlencode({
        "client_id": cid,
        "response_type": "code",
        "redirect_uri": REDIRECT,
        "code_challenge_method": "S256",
        "code_challenge": challenge,
        "scope": SCOPES,
        "state": state,
    })

    result = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path != "/callback":
                self.send_response(404)
                self.end_headers()
                return
            query = urllib.parse.parse_qs(parsed.query)
            if query.get("state", [""])[0] != state:
                result["error"] = "state"
            elif "code" in query:
                result["code"] = query["code"][0]
            else:
                result["error"] = query.get("error", ["unknown"])[0]
            ok = "code" in result
            msg = ("Connexion reussie, tu peux fermer cette page. / Logged in, you can close this page." if ok
                   else "Connexion annulee / login cancelled: " + result["error"])
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(f"<h2 style='font-family:sans-serif'>{html.escape(msg)}</h2>".encode("utf-8"))

        def log_message(self, *args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", PORT), Handler)
    print("Ouverture du navigateur / opening the browser...")
    print("Si rien ne s'ouvre / if nothing opens:\n" + url)
    webbrowser.open(url)
    while not result:
        server.handle_request()
    server.server_close()
    if "code" not in result:
        sys.exit("Connexion annulee / login cancelled: " + result["error"])

    body = urllib.parse.urlencode({
        "grant_type": "authorization_code",
        "code": result["code"],
        "redirect_uri": REDIRECT,
        "client_id": cid,
        "code_verifier": verifier,
    }).encode("ascii")
    req = urllib.request.Request("https://accounts.spotify.com/api/token", data=body,
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(req, timeout=20) as resp:
        tokens = json.load(resp)

    # Lets MiniSpot see what the Spotify app is playing (Android notification access).
    adb(adb_path, "shell", "cmd", "notification", "allow_listener", f"{PACKAGE}/{PACKAGE}.NotifListener")

    print("Envoi au telephone / sending to the phone...")
    out = adb(adb_path, "shell", "am", "broadcast", "-n", f"{PACKAGE}/.TokenReceiver",
              "--es", "client_id", cid,
              "--es", "refresh", tokens["refresh_token"],
              "--es", "access", tokens["access_token"],
              "--ei", "expires", str(tokens.get("expires_in", 3600)))
    if out.returncode != 0 or "result=0" not in out.stdout:
        sys.exit("Envoi echoue / sending failed:\n" + out.stdout + out.stderr)
    print("Termine : MiniSpot est connecte. / Done: MiniSpot is logged in.")


if __name__ == "__main__":
    main()
