import sqlite3
import threading
import time
import uuid

from . import config

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE COLLATE NOCASE,
  email TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash TEXT NOT NULL,
  uuid TEXT NOT NULL UNIQUE,
  model TEXT NOT NULL DEFAULT 'classic',
  skin_hash TEXT,
  cape_hash TEXT,
  created REAL NOT NULL,
  last_login REAL
);
CREATE TABLE IF NOT EXISTS textures (
  hash TEXT PRIMARY KEY,
  kind TEXT NOT NULL,
  owner_id INTEGER,
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  frames INTEGER NOT NULL DEFAULT 1,
  fps INTEGER NOT NULL DEFAULT 10,
  hd INTEGER NOT NULL DEFAULT 0,
  created REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
  access_token TEXT PRIMARY KEY,
  client_token TEXT NOT NULL,
  user_id INTEGER NOT NULL,
  created REAL NOT NULL,
  expires REAL NOT NULL,
  server_id TEXT,
  server_time REAL
);
CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);
CREATE TABLE IF NOT EXISTS servers (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  ip TEXT NOT NULL,
  port INTEGER NOT NULL DEFAULT 25565,
  version TEXT NOT NULL DEFAULT '',
  motd TEXT NOT NULL DEFAULT '',
  icon_hash TEXT,
  sort INTEGER NOT NULL DEFAULT 0,
  enabled INTEGER NOT NULL DEFAULT 1,
  created REAL NOT NULL,
  updated REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS cosmetics (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  category TEXT NOT NULL CHECK (category IN ('hats','wings','back','pets','arms','legs','auras','costumes')),
  price INTEGER NOT NULL DEFAULT 0,
  model_hash TEXT,
  scale REAL NOT NULL DEFAULT 1.0,
  off_x REAL NOT NULL DEFAULT 0,
  off_y REAL NOT NULL DEFAULT 0,
  off_z REAL NOT NULL DEFAULT 0,
  sort INTEGER NOT NULL DEFAULT 0,
  enabled INTEGER NOT NULL DEFAULT 1,
  legacy_id INTEGER,
  created REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS cosmetic_variants (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  cosmetic_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  texture_hash TEXT NOT NULL,
  legacy_id INTEGER,
  created REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS variants_cosmetic ON cosmetic_variants(cosmetic_id);
CREATE TABLE IF NOT EXISTS ownership (
  user_id INTEGER NOT NULL,
  cosmetic_id INTEGER NOT NULL,
  equipped INTEGER NOT NULL DEFAULT 0,
  variant_id INTEGER,
  created REAL NOT NULL,
  UNIQUE(user_id, cosmetic_id)
);
CREATE TABLE IF NOT EXISTS friends (
  user_id INTEGER NOT NULL,
  friend_id INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('pending','accepted')),
  created REAL NOT NULL,
  UNIQUE(user_id, friend_id)
);
CREATE INDEX IF NOT EXISTS friends_friend ON friends(friend_id);
CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  from_id INTEGER NOT NULL,
  to_id INTEGER NOT NULL,
  text TEXT NOT NULL,
  ts INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS messages_pair ON messages(from_id, to_id, id);
"""

# колонки, добавленные после первого релиза: (таблица, колонка, DDL)
MIGRATIONS = [("users", "is_admin", "INTEGER NOT NULL DEFAULT 0"),
              ("users", "balance", "INTEGER NOT NULL DEFAULT 0"),
              ("users", "legacy_password", "TEXT"),
              ("users", "mc_license_nick", "TEXT"),
              ("users", "legacy_id", "INTEGER")]

_lock = threading.Lock()
_conn = None


def _migrate(c):
    for table, col, ddl in MIGRATIONS:
        have = {r["name"] for r in c.execute(f"PRAGMA table_info({table})")}
        if col not in have:
            c.execute(f"ALTER TABLE {table} ADD COLUMN {col} {ddl}")
    c.commit()


def conn() -> sqlite3.Connection:
    global _conn
    if _conn is None:
        config.ensure_dirs()
        c = sqlite3.connect(config.DB_FILE, check_same_thread=False)
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA journal_mode=WAL")
        c.executescript(SCHEMA)
        _migrate(c)
        _conn = c
    return _conn


def q(sql, args=()):
    with _lock:
        cur = conn().execute(sql, args)
        rows = cur.fetchall()
        conn().commit()
        return rows


def one(sql, args=()):
    rows = q(sql, args)
    return rows[0] if rows else None


# ---- users

def user_by_id(uid):
    return one("SELECT * FROM users WHERE id=?", (uid,))


def user_by_login(login):
    return one("SELECT * FROM users WHERE username=? OR email=?", (login, login))


def user_by_name(name):
    return one("SELECT * FROM users WHERE username=?", (name,))


def user_by_uuid(u):
    return one("SELECT * FROM users WHERE uuid=?", (u.replace("-", "").lower(),))


def create_user(username, email, password_hash):
    uid = uuid.uuid4().hex
    q("INSERT INTO users(username,email,password_hash,uuid,created) VALUES(?,?,?,?,?)",
      (username, email, password_hash, uid, time.time()))
    return user_by_name(username)


def update_user(uid, **fields):
    if not fields:
        return
    sets = ", ".join(f"{k}=?" for k in fields)
    q(f"UPDATE users SET {sets} WHERE id=?", (*fields.values(), uid))


def is_admin(u) -> bool:
    return bool(u) and (bool(u["is_admin"]) or u["username"].lower() in config.ADMINS)


def search_users(text="", limit=50):
    like = f"%{text}%"
    return q("SELECT * FROM users WHERE username LIKE ? OR email LIKE ? ORDER BY created DESC LIMIT ?",
             (like, like, max(1, min(200, int(limit)))))


def public_id(u) -> int:
    """Число, под которым игрока видит мод (старый id с onii.ink, если он переехал)."""
    return int(u["legacy_id"] or u["id"])


def user_by_public_id(pid):
    try:
        pid = int(pid)
    except (TypeError, ValueError):
        return None
    return one("SELECT * FROM users WHERE legacy_id=?", (pid,)) or \
        one("SELECT * FROM users WHERE id=? AND legacy_id IS NULL", (pid,))


def user_by_any_nick(nick):
    """Ник сайта либо привязанный ник лицензии (из старого сайта)."""
    return user_by_name(nick) or one("SELECT * FROM users WHERE mc_license_nick=? COLLATE NOCASE", (nick,))


# ---- cosmetics (гардероб мода rurukaSuka)

CATEGORIES = ("hats", "wings", "back", "pets", "arms", "legs", "auras", "costumes")
COSMETIC_FIELDS = ("name", "category", "price", "model_hash", "scale", "off_x", "off_y", "off_z", "sort", "enabled",
                   "legacy_id")


def cosmetics(only_enabled=True):
    where = "WHERE enabled=1" if only_enabled else ""
    return q(f"SELECT * FROM cosmetics {where} ORDER BY sort, id")


def cosmetic(cid):
    return one("SELECT * FROM cosmetics WHERE id=?", (cid,))


def create_cosmetic(cid=None, **fields):
    cols = [k for k in COSMETIC_FIELDS if k in fields]
    vals = [fields[k] for k in cols]
    if cid is not None:
        cols.append("id")
        vals.append(int(cid))
    if "sort" not in fields:
        cols.append("sort")
        vals.append((one("SELECT COALESCE(MAX(sort), -1) AS m FROM cosmetics")["m"] or 0) + 1)
    q(f"INSERT INTO cosmetics({','.join(cols)},created) VALUES({','.join('?' * len(cols))},?)", (*vals, time.time()))
    return one("SELECT * FROM cosmetics ORDER BY id DESC LIMIT 1") if cid is None else cosmetic(cid)


def update_cosmetic(cid, **fields):
    fields = {k: v for k, v in fields.items() if k in COSMETIC_FIELDS}
    if fields:
        sets = ", ".join(f"{k}=?" for k in fields)
        q(f"UPDATE cosmetics SET {sets} WHERE id=?", (*fields.values(), cid))
    return cosmetic(cid)


def delete_cosmetic(cid):
    q("DELETE FROM ownership WHERE cosmetic_id=?", (cid,))
    q("DELETE FROM cosmetic_variants WHERE cosmetic_id=?", (cid,))
    q("DELETE FROM cosmetics WHERE id=?", (cid,))


def variants(cid):
    return q("SELECT * FROM cosmetic_variants WHERE cosmetic_id=? ORDER BY id", (cid,))


def variant(vid):
    return one("SELECT * FROM cosmetic_variants WHERE id=?", (vid,))


def create_variant(cid, name, texture_hash, vid=None, legacy_id=None):
    if vid is None:
        q("INSERT INTO cosmetic_variants(cosmetic_id,name,texture_hash,legacy_id,created) VALUES(?,?,?,?,?)",
          (cid, name, texture_hash, legacy_id, time.time()))
        return one("SELECT * FROM cosmetic_variants ORDER BY id DESC LIMIT 1")
    q("INSERT INTO cosmetic_variants(id,cosmetic_id,name,texture_hash,legacy_id,created) VALUES(?,?,?,?,?,?)",
      (vid, cid, name, texture_hash, legacy_id, time.time()))
    return variant(vid)


def delete_variant(vid):
    q("UPDATE ownership SET variant_id=NULL WHERE variant_id=?", (vid,))
    q("DELETE FROM cosmetic_variants WHERE id=?", (vid,))


def ownership(uid):
    return q("""SELECT o.*, c.category, c.name FROM ownership o JOIN cosmetics c ON c.id=o.cosmetic_id
                WHERE o.user_id=? ORDER BY c.sort, c.id""", (uid,))


def owned(uid, cid):
    return one("SELECT * FROM ownership WHERE user_id=? AND cosmetic_id=?", (uid, cid))


def add_ownership(uid, cid, equipped=0, variant_id=None):
    q("INSERT OR IGNORE INTO ownership(user_id,cosmetic_id,equipped,variant_id,created) VALUES(?,?,?,?,?)",
      (uid, cid, int(equipped), variant_id, time.time()))


def set_equipped(uid, cid, flag):
    if flag:   # один слот — одна косметика той же категории
        q("""UPDATE ownership SET equipped=0 WHERE user_id=? AND cosmetic_id!=? AND cosmetic_id IN
             (SELECT id FROM cosmetics WHERE category=(SELECT category FROM cosmetics WHERE id=?))""", (uid, cid, cid))
    q("UPDATE ownership SET equipped=? WHERE user_id=? AND cosmetic_id=?", (1 if flag else 0, uid, cid))


def set_variant(uid, cid, vid):
    q("UPDATE ownership SET variant_id=? WHERE user_id=? AND cosmetic_id=?", (vid, uid, cid))


def add_balance(uid, delta):
    q("UPDATE users SET balance=balance+? WHERE id=?", (int(delta), uid))
    return user_by_id(uid)["balance"]


# ---- friends / messages

def friend_row(a, b):
    return one("SELECT * FROM friends WHERE (user_id=? AND friend_id=?) OR (user_id=? AND friend_id=?)", (a, b, b, a))


def friends_of(uid):
    """(accepted, incoming, outgoing) — списки пользователей."""
    rows = q("""SELECT f.*, u.id AS uid FROM friends f JOIN users u ON u.id = CASE WHEN f.user_id=? THEN f.friend_id ELSE f.user_id END
                WHERE f.user_id=? OR f.friend_id=? ORDER BY f.created""", (uid, uid, uid))
    acc, inc, out = [], [], []
    for r in rows:
        other = user_by_id(r["uid"])
        if not other:
            continue
        if r["status"] == "accepted":
            acc.append(other)
        elif r["user_id"] == uid:
            out.append(other)
        else:
            inc.append(other)
    return acc, inc, out


def are_friends(a, b):
    r = friend_row(a, b)
    return bool(r) and r["status"] == "accepted"


def request_friend(uid, other):
    q("INSERT OR IGNORE INTO friends(user_id,friend_id,status,created) VALUES(?,?,'pending',?)", (uid, other, time.time()))


def accept_friend(uid, other):
    q("UPDATE friends SET status='accepted' WHERE user_id=? AND friend_id=? AND status='pending'", (other, uid))


def remove_friend(uid, other):
    q("DELETE FROM friends WHERE (user_id=? AND friend_id=?) OR (user_id=? AND friend_id=?)", (uid, other, other, uid))


def messages(uid, other, after=0, limit=200):
    return q("""SELECT * FROM messages WHERE id>? AND ((from_id=? AND to_id=?) OR (from_id=? AND to_id=?))
                ORDER BY id LIMIT ?""", (int(after), uid, other, other, uid, limit))


def add_message(uid, other, text):
    q("INSERT INTO messages(from_id,to_id,text,ts) VALUES(?,?,?,?)", (uid, other, text, int(time.time())))
    return one("SELECT * FROM messages ORDER BY id DESC LIMIT 1")


# ---- servers (список серверов для лаунчера)

SERVER_FIELDS = ("name", "ip", "port", "version", "motd", "icon_hash", "sort", "enabled")


def servers(only_enabled=True):
    where = "WHERE enabled=1" if only_enabled else ""
    return q(f"SELECT * FROM servers {where} ORDER BY sort, name COLLATE NOCASE, id")


def server(sid):
    return one("SELECT * FROM servers WHERE id=?", (sid,))


def create_server(**fields):
    now = time.time()
    cols = [k for k in SERVER_FIELDS if k in fields]
    if "sort" not in fields:
        cols.append("sort")
        fields["sort"] = (one("SELECT COALESCE(MAX(sort), -1) AS m FROM servers")["m"] or 0) + 1
    q(f"INSERT INTO servers({','.join(cols)},created,updated) VALUES({','.join('?' * len(cols))},?,?)",
      (*[fields[k] for k in cols], now, now))
    return one("SELECT * FROM servers ORDER BY id DESC LIMIT 1")


def update_server(sid, **fields):
    fields = {k: v for k, v in fields.items() if k in SERVER_FIELDS}
    if not fields:
        return server(sid)
    sets = ", ".join(f"{k}=?" for k in fields)
    q(f"UPDATE servers SET {sets}, updated=? WHERE id=?", (*fields.values(), time.time(), sid))
    return server(sid)


def delete_server(sid):
    q("DELETE FROM servers WHERE id=?", (sid,))


def reorder_servers(ids):
    for i, sid in enumerate(ids):
        q("UPDATE servers SET sort=?, updated=? WHERE id=?", (i, time.time(), sid))


# ---- textures

def texture(h):
    return one("SELECT * FROM textures WHERE hash=?", (h,))


def save_texture(h, kind, owner_id, width, height, frames, fps, hd):
    q("INSERT OR REPLACE INTO textures(hash,kind,owner_id,width,height,frames,fps,hd,created) VALUES(?,?,?,?,?,?,?,?,?)",
      (h, kind, owner_id, width, height, frames, fps, int(hd), time.time()))


# ---- yggdrasil sessions

def create_session(user_id, client_token):
    token = uuid.uuid4().hex
    now = time.time()
    q("INSERT INTO sessions(access_token,client_token,user_id,created,expires) VALUES(?,?,?,?,?)",
      (token, client_token, user_id, now, now + config.SESSION_DAYS * 86400))
    q("DELETE FROM sessions WHERE expires < ?", (now,))
    return token


def session(token):
    s = one("SELECT * FROM sessions WHERE access_token=?", (token,))
    if s and s["expires"] < time.time():
        q("DELETE FROM sessions WHERE access_token=?", (token,))
        return None
    return s


def delete_session(token):
    q("DELETE FROM sessions WHERE access_token=?", (token,))


def delete_user_sessions(user_id):
    q("DELETE FROM sessions WHERE user_id=?", (user_id,))


def set_server(token, server_id):
    q("UPDATE sessions SET server_id=?, server_time=? WHERE access_token=?", (server_id, time.time(), token))


def find_join(username, server_id):
    return one("""SELECT s.*, u.username FROM sessions s JOIN users u ON u.id=s.user_id
                  WHERE u.username=? AND s.server_id=? AND s.server_time > ? ORDER BY s.server_time DESC LIMIT 1""",
               (username, server_id, time.time() - 30))
