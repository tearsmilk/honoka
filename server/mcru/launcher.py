"""Сборки лаунчера Эта: последний релиз GitHub (теги eta-v*) с кешем в памяти, либо файлы в $MCRU_DATA/launcher/."""
import json
import threading
import time
import urllib.error
import urllib.request

from . import config

CACHE_TTL = 600
_lock = threading.Lock()
_cache = {"at": 0.0, "data": None}
EMPTY = {"version": None, "tag": None, "published": None, "notes": "", "windows": None, "macos": None}


def _fetch_github():
    url = f"https://api.github.com/repos/{config.GITHUB_REPO}/releases?per_page=20"
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": "mc-ru.org"})
    if config.GITHUB_TOKEN:
        req.add_header("Authorization", f"Bearer {config.GITHUB_TOKEN}")
    with urllib.request.urlopen(req, timeout=10) as r:
        releases = json.loads(r.read().decode("utf-8"))
    for rel in releases:
        tag = rel.get("tag_name") or ""
        if rel.get("draft") or not tag.startswith("eta-v"):
            continue
        out = dict(EMPTY, version=tag[len("eta-v"):], tag=tag, published=rel.get("published_at"),
                   notes=(rel.get("body") or "")[:4000])
        for a in rel.get("assets") or []:
            name = a.get("name") or ""
            item = {"url": a.get("browser_download_url"), "name": name, "size": a.get("size", 0)}
            if name.lower().endswith(".exe") and not out["windows"]:
                out["windows"] = item
            elif name.lower().endswith(".dmg") and not out["macos"]:
                out["macos"] = item
        return out
    return dict(EMPTY)


def latest():
    """Последний релиз; при ошибке GitHub — предыдущее закешированное значение или пустая форма."""
    with _lock:
        now = time.time()
        if _cache["data"] is not None and now - _cache["at"] < CACHE_TTL:
            return _cache["data"]
        try:
            data = _fetch_github()
            _cache.update(at=now, data=data)
        except (urllib.error.URLError, OSError, ValueError, KeyError):
            if _cache["data"] is None:
                _cache.update(at=now - CACHE_TTL + 60, data=dict(EMPTY))   # повторить через минуту
        return _cache["data"]


MOD_EMPTY = {"version": None, "tag": None, "published": None, "mc_versions": [], "loaders": [], "requires": [],
             "jar": None}
_mod_cache = {"at": 0.0, "data": None}


def _fetch_mod():
    """Последний релиз мода rurukaSuka (теги rurukasuka-v*): jar + rurukasuka-mod.json с поддерживаемыми версиями."""
    url = f"https://api.github.com/repos/{config.MOD_GITHUB_REPO}/releases?per_page=30"
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": "mc-ru.org"})
    if config.GITHUB_TOKEN:
        req.add_header("Authorization", f"Bearer {config.GITHUB_TOKEN}")
    with urllib.request.urlopen(req, timeout=10) as r:
        releases = json.loads(r.read().decode("utf-8"))
    for rel in releases:
        tag = rel.get("tag_name") or ""
        if rel.get("draft") or not tag.startswith("rurukasuka-v"):
            continue
        out = dict(MOD_EMPTY, version=tag[len("rurukasuka-v"):], tag=tag, published=rel.get("published_at"))
        meta_url = None
        for a in rel.get("assets") or []:
            name = a.get("name") or ""
            if name.endswith(".jar") and "sources" not in name and not out["jar"]:
                digest = a.get("digest") or ""
                out["jar"] = {"url": a.get("browser_download_url"), "name": name, "size": a.get("size", 0),
                              "sha256": digest.split(":", 1)[1] if digest.startswith("sha256:") else None}
            elif name.endswith(".json"):
                meta_url = a.get("browser_download_url")
        if meta_url:
            mreq = urllib.request.Request(meta_url, headers={"User-Agent": "mc-ru.org"})
            with urllib.request.urlopen(mreq, timeout=10) as r:
                meta = json.loads(r.read().decode("utf-8"))
            for k in ("mc_versions", "loaders", "requires"):
                if isinstance(meta.get(k), list):
                    out[k] = meta[k]
        return out
    return dict(MOD_EMPTY)


def mod_latest():
    with _lock:
        now = time.time()
        if _mod_cache["data"] is not None and now - _mod_cache["at"] < CACHE_TTL:
            return _mod_cache["data"]
        try:
            data = _fetch_mod()
            _mod_cache.update(at=now, data=data)
        except (urllib.error.URLError, OSError, ValueError, KeyError):
            if _mod_cache["data"] is None:
                _mod_cache.update(at=now - CACHE_TTL + 60, data=dict(MOD_EMPTY))
        return _mod_cache["data"]


def local_file(platform: str):
    name = config.LAUNCHER_FILES.get(platform)
    if not name:
        return None
    p = config.LAUNCHER_DIR / name
    return p if p.is_file() else None


def download_url(platform: str):
    """Абсолютная ссылка для сайта/лаунчера: всегда через наш /api, чтобы работали и локальные файлы."""
    return f"{config.PUBLIC_URL}/api/launcher/download/{platform}"
