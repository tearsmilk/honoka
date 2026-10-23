"""mc-ru.org API: аккаунты, скины и плащи (HD + анимация), Yggdrasil для authlib-injector, CustomSkinLoader.
Запуск: uvicorn app:app --host 127.0.0.1 --port 8787   (данные — в $MCRU_DATA, см. mcru/config.py)."""
import base64
import json
import re
import time
import uuid

from fastapi import Depends, FastAPI, File, Form, Header, Request, Response, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from mcru import config, db, security, textures

app = FastAPI(title="mc-ru.org API", docs_url=None, redoc_url=None)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

NAME_RE = re.compile(r"^[A-Za-z0-9_]{3,16}$")
EMAIL_RE = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")
auth_limit = security.RateLimit(limit=15, window=600)


class ApiError(Exception):
    def __init__(self, message, status=400):
        self.message, self.status = message, status


class YggError(Exception):
    def __init__(self, error="ForbiddenOperationException", message="Invalid credentials. Invalid username or password.",
                 status=403):
        self.error, self.message, self.status = error, message, status


@app.exception_handler(ApiError)
async def _api_err(_, e: ApiError):
    return JSONResponse({"error": e.message}, status_code=e.status)


@app.exception_handler(YggError)
async def _ygg_err(_, e: YggError):
    return JSONResponse({"error": e.error, "errorMessage": e.message}, status_code=e.status)


@app.exception_handler(textures.TextureError)
async def _tex_err(_, e):
    return JSONResponse({"error": str(e)}, status_code=400)


def _ip(req: Request):
    return (req.headers.get("x-forwarded-for") or (req.client.host if req.client else "?")).split(",")[0].strip()


def user_json(u, private=False):
    d = {"id": u["id"], "username": u["username"], "uuid": u["uuid"], "model": u["model"],
         "skin": textures.info(u["skin_hash"]), "cape": textures.info(u["cape_hash"]), "created": u["created"]}
    if private:
        d["email"] = u["email"]
    return d


async def current_user(authorization: str = Header(default="")):
    if not authorization.lower().startswith("bearer "):
        raise ApiError("Нужно войти", 401)
    uid = security.read_jwt(authorization[7:].strip())
    u = db.user_by_id(uid) if uid else None
    if not u:
        raise ApiError("Сессия истекла, войдите заново", 401)
    return u


async def body(req: Request) -> dict:
    try:
        data = await req.json()
    except Exception:
        raise ApiError("Ожидался JSON")
    if not isinstance(data, dict):
        raise ApiError("Ожидался JSON-объект")
    return data


# ============================================================ auth

def _validate_name(name):
    if not NAME_RE.match(name or ""):
        raise ApiError("Ник: 3–16 символов, латиница, цифры и «_»")


@app.post("/api/auth/register")
async def register(req: Request):
    if not auth_limit.hit(("reg", _ip(req))):
        raise ApiError("Слишком много попыток, подождите 10 минут", 429)
    d = await body(req)
    name, email, pw = str(d.get("username", "")).strip(), str(d.get("email", "")).strip().lower(), str(d.get("password", ""))
    _validate_name(name)
    if not EMAIL_RE.match(email) or len(email) > 120:
        raise ApiError("Некорректный e-mail")
    if len(pw) < 8:
        raise ApiError("Пароль короче 8 символов")
    if db.user_by_name(name):
        raise ApiError("Этот ник уже занят", 409)
    if db.one("SELECT id FROM users WHERE email=?", (email,)):
        raise ApiError("Этот e-mail уже зарегистрирован", 409)
    u = db.create_user(name, email, security.hash_password(pw))
    db.update_user(u["id"], last_login=time.time())
    return {"token": security.make_jwt(u["id"]), "user": user_json(u, True)}


def _login(login, pw):
    u = db.user_by_login((login or "").strip())
    if not u or not security.check_password(pw or "", u["password_hash"]):
        return None
    db.update_user(u["id"], last_login=time.time())
    return u


@app.post("/api/auth/login")
async def login(req: Request):
    if not auth_limit.hit(("login", _ip(req))):
        raise ApiError("Слишком много попыток, подождите 10 минут", 429)
    d = await body(req)
    u = _login(d.get("login") or d.get("username"), d.get("password"))
    if not u:
        raise ApiError("Неверный ник/e-mail или пароль", 401)
    return {"token": security.make_jwt(u["id"]), "user": user_json(u, True)}


@app.get("/api/auth/me")
async def me(u=Depends(current_user)):
    return user_json(u, True)


@app.post("/api/auth/password")
async def change_password(req: Request, u=Depends(current_user)):
    d = await body(req)
    if not security.check_password(str(d.get("old", "")), u["password_hash"]):
        raise ApiError("Старый пароль неверный", 403)
    new = str(d.get("new", ""))
    if len(new) < 8:
        raise ApiError("Новый пароль короче 8 символов")
    db.update_user(u["id"], password_hash=security.hash_password(new))
    db.delete_user_sessions(u["id"])
    return {"ok": True}


# ============================================================ account: skin / cape / model

def _model(v):
    v = (v or "classic").lower()
    if v not in ("classic", "slim"):
        raise ApiError("Модель: classic или slim")
    return v


@app.post("/api/account/skin")
async def upload_skin(file: UploadFile = File(...), model: str | None = Form(default=None),
                      fps: int | None = Form(default=None), u=Depends(current_user)):
    data = await file.read()
    t = textures.store(data, "skin", u["id"], fps)
    fields = {"skin_hash": t["hash"]}
    if model:
        fields["model"] = _model(model)
    db.update_user(u["id"], **fields)
    return user_json(db.user_by_id(u["id"]), True)


@app.delete("/api/account/skin")
async def delete_skin(u=Depends(current_user)):
    db.update_user(u["id"], skin_hash=None)
    return user_json(db.user_by_id(u["id"]), True)


@app.post("/api/account/cape")
async def upload_cape(file: UploadFile = File(...), fps: int | None = Form(default=None), u=Depends(current_user)):
    data = await file.read()
    t = textures.store(data, "cape", u["id"], fps)
    db.update_user(u["id"], cape_hash=t["hash"])
    return user_json(db.user_by_id(u["id"]), True)


@app.delete("/api/account/cape")
async def delete_cape(u=Depends(current_user)):
    db.update_user(u["id"], cape_hash=None)
    return user_json(db.user_by_id(u["id"]), True)


@app.post("/api/account/model")
async def set_model(req: Request, u=Depends(current_user)):
    d = await body(req)
    db.update_user(u["id"], model=_model(d.get("model")))
    return user_json(db.user_by_id(u["id"]), True)


# ============================================================ public

def _png(data: bytes | None, cache=300):
    if data is None:
        raise ApiError("Текстура не найдена", 404)
    return Response(data, media_type="image/png", headers={"Cache-Control": f"public, max-age={cache}"})


def _user_or_404(name):
    u = db.user_by_name(name)
    if not u:
        raise ApiError("Игрок не найден", 404)
    return u


@app.get("/api/users/{name}")
async def public_profile(name: str):
    return user_json(_user_or_404(name))


def _texture_response(h, frame: int | None, size: str | None, kind="skin"):
    if not h:
        return _png(textures.default_png(kind), cache=3600)
    if size == "full" and frame is None:
        p = textures.path(h)
        return _png(p.read_bytes() if p else None)
    px = None
    if size and size != "full":
        try:
            px = int(size)
        except ValueError:
            raise ApiError("size: число или full")
        if px not in (8, 16, 32, 64, 128, 256, 512, 1024, 2048):
            raise ApiError("size: 64, 128, 256, 512, 1024 или full")
    return _png(textures.frame_png(h, frame or 0, px))


@app.get("/api/users/{name}/skin.png")
async def user_skin(name: str, frame: int | None = None, size: str | None = None):
    return _texture_response(_user_or_404(name)["skin_hash"], frame, size)


@app.get("/api/users/{name}/cape.png")
async def user_cape(name: str, frame: int | None = None, size: str | None = None):
    return _texture_response(_user_or_404(name)["cape_hash"], frame, size, "cape")


@app.get("/api/textures/{h}.png")
async def texture_std(h: str):
    p = textures.path(h, std=True)
    return _png(p.read_bytes() if p else None, cache=86400)


@app.get("/api/textures/{h}/full.png")
async def texture_full(h: str):
    p = textures.path(h)
    return _png(p.read_bytes() if p else None, cache=86400)


# ============================================================ CustomSkinLoader (HD + анимация в игре)

@app.get("/api/csl/{name}.json")
async def csl_profile(name: str):
    u = db.user_by_name(name)
    if not u:
        raise ApiError("Игрок не найден", 404)
    out = {"username": u["username"]}
    if u["skin_hash"]:
        out["skins"] = {("slim" if u["model"] == "slim" else "default"): u["skin_hash"]}
    if u["cape_hash"]:
        out["cape"] = u["cape_hash"]
    return out


@app.get("/api/csl/textures/{h}")
async def csl_texture(h: str):
    p = textures.path(h)
    return _png(p.read_bytes() if p else None, cache=86400)


# ============================================================ Yggdrasil (authlib-injector)

Y = "/api/yggdrasil"


def _profile(u):
    return {"id": u["uuid"], "name": u["username"]}


def _signed_profile(u, unsigned=False):
    tex = {}
    if u["skin_hash"]:
        tex["SKIN"] = {"url": f"{config.PUBLIC_URL}/api/textures/{u['skin_hash']}.png"}
        if u["model"] == "slim":
            tex["SKIN"]["metadata"] = {"model": "slim"}
    if u["cape_hash"]:
        tex["CAPE"] = {"url": f"{config.PUBLIC_URL}/api/textures/{u['cape_hash']}.png"}
    value = base64.b64encode(json.dumps({"timestamp": int(time.time() * 1000), "profileId": u["uuid"],
                                         "profileName": u["username"], "textures": tex}).encode()).decode()
    prop = {"name": "textures", "value": value}
    if not unsigned:
        prop["signature"] = security.sign(value)
    return {"id": u["uuid"], "name": u["username"], "properties": [prop]}


def _ygg_user(u):
    return {"id": u["uuid"], "properties": [{"name": "preferredLanguage", "value": "ru"}]}


@app.get(Y)
async def ygg_meta():
    return {"meta": {"serverName": "mc-ru.org", "implementationName": "mc-ru", "implementationVersion": "1.0",
                     "links": {"homepage": config.PUBLIC_URL, "register": f"{config.PUBLIC_URL}/#register"},
                     "feature.non_email_login": True},
            "skinDomains": config.SKIN_DOMAINS, "signaturePublickey": security.public_key_pem()}


@app.post(f"{Y}/authserver/authenticate")
async def ygg_authenticate(req: Request):
    if not auth_limit.hit(("ygg", _ip(req))):
        raise YggError("ForbiddenOperationException", "Too many login attempts, try again later.", 429)
    d = await req.json()
    u = _login(d.get("username"), d.get("password"))
    if not u:
        raise YggError()
    client = d.get("clientToken") or uuid.uuid4().hex
    token = db.create_session(u["id"], client)
    out = {"accessToken": token, "clientToken": client, "availableProfiles": [_profile(u)], "selectedProfile": _profile(u)}
    if d.get("requestUser"):
        out["user"] = _ygg_user(u)
    return out


def _session_of(d, check_client=True):
    s = db.session(str(d.get("accessToken", "")))
    if not s or (check_client and d.get("clientToken") and d["clientToken"] != s["client_token"]):
        raise YggError("ForbiddenOperationException", "Invalid token.")
    return s


@app.post(f"{Y}/authserver/refresh")
async def ygg_refresh(req: Request):
    d = await req.json()
    s = _session_of(d)
    u = db.user_by_id(s["user_id"])
    db.delete_session(s["access_token"])
    token = db.create_session(u["id"], s["client_token"])
    out = {"accessToken": token, "clientToken": s["client_token"], "selectedProfile": _profile(u)}
    if d.get("requestUser"):
        out["user"] = _ygg_user(u)
    return out


@app.post(f"{Y}/authserver/validate")
async def ygg_validate(req: Request):
    _session_of(await req.json())
    return Response(status_code=204)


@app.post(f"{Y}/authserver/invalidate")
async def ygg_invalidate(req: Request):
    d = await req.json()
    db.delete_session(str(d.get("accessToken", "")))
    return Response(status_code=204)


@app.post(f"{Y}/authserver/signout")
async def ygg_signout(req: Request):
    d = await req.json()
    u = _login(d.get("username"), d.get("password"))
    if not u:
        raise YggError()
    db.delete_user_sessions(u["id"])
    return Response(status_code=204)


@app.post(f"{Y}/sessionserver/session/minecraft/join")
async def ygg_join(req: Request):
    d = await req.json()
    s = _session_of(d, check_client=False)
    u = db.user_by_id(s["user_id"])
    sel = str(d.get("selectedProfile", "")).replace("-", "").lower()
    if sel and sel != u["uuid"]:
        raise YggError("ForbiddenOperationException", "Invalid profile.")
    db.set_server(s["access_token"], str(d.get("serverId", "")))
    return Response(status_code=204)


@app.get(f"{Y}/sessionserver/session/minecraft/hasJoined")
async def ygg_has_joined(username: str = "", serverId: str = "", ip: str | None = None):
    s = db.find_join(username, serverId)
    if not s:
        return Response(status_code=204)
    return _signed_profile(db.user_by_id(s["user_id"]))


@app.get(f"{Y}/sessionserver/session/minecraft/profile/{{uid}}")
async def ygg_profile(uid: str, unsigned: bool = True):
    u = db.user_by_uuid(uid)
    if not u:
        return Response(status_code=204)
    return _signed_profile(u, unsigned=unsigned)


@app.post(f"{Y}/api/profiles/minecraft")
async def ygg_profiles(req: Request):
    names = await req.json()
    if not isinstance(names, list):
        raise YggError("IllegalArgumentException", "Expected a JSON array of names.", 400)
    out = []
    for n in names[:100]:
        u = db.user_by_name(str(n))
        if u:
            out.append(_profile(u))
    return out


@app.get("/api/health")
async def health():
    return {"ok": True, "users": db.one("SELECT COUNT(*) AS n FROM users")["n"]}
