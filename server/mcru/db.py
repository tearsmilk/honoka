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
"""

_lock = threading.Lock()
_conn = None


def conn() -> sqlite3.Connection:
    global _conn
    if _conn is None:
        config.ensure_dirs()
        c = sqlite3.connect(config.DB_FILE, check_same_thread=False)
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA journal_mode=WAL")
        c.executescript(SCHEMA)
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
