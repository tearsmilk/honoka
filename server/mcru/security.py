import base64
import os
import threading
import time

import bcrypt
import jwt
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from . import config


def hash_password(pw: str) -> str:
    return bcrypt.hashpw(pw.encode("utf-8"), bcrypt.gensalt()).decode()


def check_password(pw: str, h: str) -> bool:
    try:
        return bcrypt.checkpw(pw.encode("utf-8"), h.encode())
    except ValueError:
        return False


def make_jwt(user_id: int) -> str:
    now = int(time.time())
    return jwt.encode({"sub": str(user_id), "iat": now, "exp": now + config.JWT_DAYS * 86400}, config.secret(),
                      algorithm="HS256")


def read_jwt(token: str):
    try:
        return int(jwt.decode(token, config.secret(), algorithms=["HS256"])["sub"])
    except (jwt.PyJWTError, KeyError, ValueError):
        return None


# ---- RSA key for Yggdrasil texture signatures

_key = None


def private_key():
    global _key
    if _key is None:
        config.ensure_dirs()
        if config.KEY_FILE.exists():
            _key = serialization.load_pem_private_key(config.KEY_FILE.read_bytes(), password=None)
        else:
            _key = rsa.generate_private_key(public_exponent=65537, key_size=4096)
            config.KEY_FILE.write_bytes(_key.private_bytes(serialization.Encoding.PEM,
                                                           serialization.PrivateFormat.PKCS8,
                                                           serialization.NoEncryption()))
            os.chmod(config.KEY_FILE, 0o600)
    return _key


def public_key_pem() -> str:
    return private_key().public_key().public_bytes(serialization.Encoding.PEM,
                                                   serialization.PublicFormat.SubjectPublicKeyInfo).decode()


def sign(value: str) -> str:
    sig = private_key().sign(value.encode("utf-8"), padding.PKCS1v15(), hashes.SHA1())
    return base64.b64encode(sig).decode()


# ---- simple in-memory rate limit per IP

class RateLimit:
    def __init__(self, limit=10, window=600):
        self.limit, self.window = limit, window
        self.hits = {}
        self.lock = threading.Lock()

    def hit(self, key) -> bool:
        """True when the request is allowed."""
        now = time.time()
        with self.lock:
            lst = [t for t in self.hits.get(key, []) if t > now - self.window]
            if len(lst) >= self.limit:
                self.hits[key] = lst
                return False
            lst.append(now)
            self.hits[key] = lst
            if len(self.hits) > 5000:
                for k in [k for k, v in self.hits.items() if not v or v[-1] < now - self.window]:
                    self.hits.pop(k, None)
            return True
