import os
import secrets
from pathlib import Path

PUBLIC_URL = os.environ.get("MCRU_PUBLIC_URL", "https://mc-ru.org").rstrip("/")
DATA = Path(os.environ.get("MCRU_DATA", Path(__file__).resolve().parent.parent / "data"))
TEXTURES = DATA / "textures"
DB_FILE = DATA / "mcru.db"
KEY_FILE = DATA / "yggdrasil_key.pem"
SECRET_FILE = DATA / "secret.key"

SKIN_DOMAINS = ["mc-ru.org", "www.mc-ru.org"]
JWT_DAYS = 30
SESSION_DAYS = 7
MAX_UPLOAD = 8 * 1024 * 1024

# админы, которым не нужен флаг в базе (чтобы владелец мог войти в админку с нуля): MCRU_ADMINS=nick1,nick2
ADMINS = {a.strip().lower() for a in os.environ.get("MCRU_ADMINS", "").split(",") if a.strip()}

# откуда брать сборки лаунчера: релизы GitHub (теги eta-v*) либо файлы, положенные в $MCRU_DATA/launcher/
GITHUB_REPO = os.environ.get("MCRU_GITHUB_REPO", "tearsmilk/amkawa-lang")
GITHUB_TOKEN = os.environ.get("MCRU_GITHUB_TOKEN", "")
LAUNCHER_DIR = DATA / "launcher"
LAUNCHER_FILES = {"windows": "Eta-windows-x64.exe", "macos": "Eta-macOS-x64.dmg"}


def ensure_dirs():
    TEXTURES.mkdir(parents=True, exist_ok=True)
    LAUNCHER_DIR.mkdir(parents=True, exist_ok=True)


def secret() -> bytes:
    ensure_dirs()
    if not SECRET_FILE.exists():
        SECRET_FILE.write_bytes(secrets.token_bytes(48))
        os.chmod(SECRET_FILE, 0o600)
    return SECRET_FILE.read_bytes()
