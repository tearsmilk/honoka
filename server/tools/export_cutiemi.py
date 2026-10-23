#!/usr/bin/env python3
"""Экспорт старого сайта rurukaSuka (cutiemi, onii.ink) в один JSON для переезда на mc-ru.org.

Запускать НА СТАРОМ СЕРВЕРЕ из папки сайта (/opt/cutiemi):
    DATABASE_URL=postgresql+psycopg://cutiemi:пароль@localhost/cutiemi python3 tools/export_cutiemi.py
или для SQLite:
    DATABASE_URL=sqlite:///./cutiemi.db python3 tools/export_cutiemi.py
Параметры: --data ./data (папка со skins/, models/, variants/), --out cutiemi-export.json.
Нужен только stdlib; для Postgres — psycopg (v3) или psycopg2 (они уже стоят у сайта).

Результат: users (пароли — старые pbkdf2-хэши, они продолжат работать), nicknames, cosmetics (+ .bbmodel в base64),
variants (+ PNG в base64), ownership. Потом на новом сервере: tools/import_cutiemi.py cutiemi-export.json
"""
import argparse
import base64
import json
import os
import sys
from pathlib import Path


def connect(url):
    if url.startswith("sqlite"):
        import sqlite3
        path = url.split("///", 1)[1] if "///" in url else url.split("sqlite:", 1)[1]
        c = sqlite3.connect(path)
        c.row_factory = sqlite3.Row
        return c, lambda r: dict(r)
    dsn = url
    for prefix in ("postgresql+psycopg://", "postgresql+psycopg2://", "postgres://"):
        if dsn.startswith(prefix):
            dsn = "postgresql://" + dsn[len(prefix):]
    try:
        import psycopg
        from psycopg.rows import dict_row
        return psycopg.connect(dsn, row_factory=dict_row), dict
    except ImportError:
        import psycopg2
        import psycopg2.extras
        c = psycopg2.connect(dsn, cursor_factory=psycopg2.extras.RealDictCursor)
        return c, dict


def rows(conn, to_dict, sql):
    cur = conn.cursor()
    cur.execute(sql)
    return [to_dict(r) for r in cur.fetchall()]


def b64file(path: Path):
    try:
        return base64.b64encode(path.read_bytes()).decode()
    except OSError:
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="./data")
    ap.add_argument("--out", default="cutiemi-export.json")
    a = ap.parse_args()
    url = os.environ.get("DATABASE_URL")
    if not url:
        env = Path(".env")
        if env.exists():
            for line in env.read_text().splitlines():
                if line.startswith("DATABASE_URL="):
                    url = line.split("=", 1)[1].strip().strip('"').strip("'")
    if not url:
        sys.exit("Задай DATABASE_URL (как у сайта) — например sqlite:///./cutiemi.db")
    data = Path(a.data)
    conn, to_dict = connect(url)
    users = rows(conn, to_dict, "SELECT id, email, password, skin_file, skin_model, balance, created_at FROM users ORDER BY id")
    for u in users:
        u["skin_png"] = b64file(data / "skins" / u["skin_file"]) if u.get("skin_file") else None
    nicks = rows(conn, to_dict, "SELECT id, nick, user_id, source, created_at FROM nicknames ORDER BY id")
    cosmetics = rows(conn, to_dict, "SELECT id, name, category, price, model_file, scale, off_x, off_y, off_z, created_at "
                                    "FROM cosmetics ORDER BY id")
    for c in cosmetics:
        c["model_b64"] = b64file(data / "models" / c["model_file"]) if c.get("model_file") else None
    variants = rows(conn, to_dict, "SELECT id, cosmetic_id, name, texture_file, created_at FROM cosmetic_variants ORDER BY id")
    for v in variants:
        v["texture_b64"] = b64file(data / "variants" / v["texture_file"]) if v.get("texture_file") else None
    ownership = rows(conn, to_dict, "SELECT user_id, cosmetic_id, equipped, variant_id, created_at FROM ownership ORDER BY id")
    out = {"format": "cutiemi-export-1", "users": users, "nicknames": nicks, "cosmetics": cosmetics,
           "variants": variants, "ownership": ownership}
    Path(a.out).write_text(json.dumps(out, ensure_ascii=False, default=str), "utf-8")
    print(f"{a.out}: пользователей {len(users)}, ников {len(nicks)}, косметики {len(cosmetics)}, "
          f"вариантов {len(variants)}, покупок {len(ownership)}")


if __name__ == "__main__":
    main()
