#!/usr/bin/env python3
"""Импорт экспорта старого сайта rurukaSuka (tools/export_cutiemi.py) в базу mc-ru.org.

На новом сервере (API можно не останавливать, но лучше остановить, чтобы не пересекаться):
    MCRU_DATA=/var/lib/mc-ru /opt/mc-ru-api/venv/bin/python /opt/mc-ru-api/server/tools/import_cutiemi.py cutiemi-export.json
    chown -R www-data:www-data /var/lib/mc-ru

Что делает: пользователь → ник сайта = его «свой» ник (иначе ник лицензии, иначе user<id>), e-mail, старый pbkdf2-пароль
(работает при первом входе и перехэшируется), баланс руров, номер со старого сайта (legacy_id — его видит мод),
скин → хранилище текстур; косметика и варианты — с теми же номерами, если они свободны; покупки и надетое.
Повторный запуск безопасен: уже импортированные (по e-mail / legacy_id) пропускаются.
"""
import base64
import json
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from mcru import db, textures  # noqa: E402

NAME_RE = re.compile(r"^[A-Za-z0-9_]{3,16}$")


def sanitize(name, fallback):
    name = re.sub(r"[^A-Za-z0-9_]", "_", name or "")[:16]
    if len(name) < 3 or not NAME_RE.match(name):
        name = fallback
    base, i = name, 1
    while db.user_by_name(name):
        i += 1
        suffix = f"_{i}"
        name = base[:16 - len(suffix)] + suffix
    return name


def main():
    if len(sys.argv) < 2:
        sys.exit("Использование: import_cutiemi.py cutiemi-export.json")
    data = json.loads(Path(sys.argv[1]).read_text("utf-8"))
    if data.get("format") != "cutiemi-export-1":
        sys.exit("Это не файл export_cutiemi.py")
    nicks = {}
    for n in data["nicknames"]:
        nicks.setdefault(n["user_id"], {})[n["source"]] = n["nick"]

    # ---- косметика (старый id сохраняем, если свободен)
    cos_map, done_c = {}, 0
    for c in data["cosmetics"]:
        old = int(c["id"])
        existing = db.one("SELECT id FROM cosmetics WHERE legacy_id=?", (old,))
        if existing:
            cos_map[old] = existing["id"]
            continue
        h = textures.store_bbmodel(base64.b64decode(c["model_b64"]), None) if c.get("model_b64") else None
        fields = dict(name=c["name"], category=c["category"], price=int(c["price"] or 0), model_hash=h,
                      scale=float(c.get("scale") or 1), off_x=float(c.get("off_x") or 0), off_y=float(c.get("off_y") or 0),
                      off_z=float(c.get("off_z") or 0), legacy_id=old, enabled=1)
        row = db.create_cosmetic(cid=None if db.cosmetic(old) else old, **fields)
        cos_map[old] = row["id"]
        done_c += 1
    var_map, done_v = {}, 0
    for v in data["variants"]:
        old = int(v["id"])
        existing = db.one("SELECT id FROM cosmetic_variants WHERE legacy_id=?", (old,))
        if existing:
            var_map[old] = existing["id"]
            continue
        cid = cos_map.get(int(v["cosmetic_id"]))
        if not cid or not v.get("texture_b64"):
            continue
        h = textures.store_png(base64.b64decode(v["texture_b64"]), "variant", None)
        row = db.create_variant(cid, v["name"] or "variant", h, vid=None if db.variant(old) else old, legacy_id=old)
        var_map[old] = row["id"]
        done_v += 1

    # ---- пользователи
    user_map, done_u = {}, 0
    for u in data["users"]:
        old = int(u["id"])
        email = (u["email"] or "").strip().lower()
        existing = db.one("SELECT * FROM users WHERE legacy_id=?", (old,)) or (db.one("SELECT * FROM users WHERE email=?", (email,)) if email else None)
        if existing:
            user_map[old] = existing["id"]
            if existing["legacy_id"] is None:
                db.update_user(existing["id"], legacy_id=old)
            continue
        nn = nicks.get(old, {})
        username = sanitize(nn.get("self") or nn.get("license"), f"user{old}")
        if not email:
            email = f"user{old}@import.onii.ink"
        db.q("INSERT INTO users(username,email,password_hash,uuid,created,balance,legacy_password,mc_license_nick,legacy_id,model)"
             " VALUES(?,?,?,?,?,?,?,?,?,?)",
             (username, email, "", __import__("uuid").uuid4().hex, float(u.get("created_at") or time.time()),
              int(u.get("balance") or 0), u.get("password"), nn.get("license"), old,
              "slim" if u.get("skin_model") == "slim" else "classic"))
        row = db.user_by_name(username)
        user_map[old] = row["id"]
        if u.get("skin_png"):
            try:
                t = textures.store(base64.b64decode(u["skin_png"]), "skin", row["id"])
                db.update_user(row["id"], skin_hash=t["hash"])
            except textures.TextureError as e:
                print(f"  скин {username}: {e}")
        done_u += 1

    # ---- покупки
    done_o = 0
    for o in data["ownership"]:
        uid, cid = user_map.get(int(o["user_id"])), cos_map.get(int(o["cosmetic_id"]))
        if not uid or not cid or db.owned(uid, cid):
            continue
        vid = var_map.get(int(o["variant_id"])) if o.get("variant_id") else None
        db.add_ownership(uid, cid, 1 if o.get("equipped") in (True, 1, "1", "t", "true") else 0, vid)
        done_o += 1
    print(f"Импортировано: пользователей {done_u}, косметики {done_c}, вариантов {done_v}, покупок {done_o} "
          f"(в файле: {len(data['users'])}/{len(data['cosmetics'])}/{len(data['variants'])}/{len(data['ownership'])})")


if __name__ == "__main__":
    main()
