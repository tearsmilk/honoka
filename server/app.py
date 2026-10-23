"""mc-ru.org API: аккаунты, скины и плащи (HD + анимация), Yggdrasil для authlib-injector, CustomSkinLoader.
Запуск: uvicorn app:app --host 127.0.0.1 --port 8787   (данные — в $MCRU_DATA, см. mcru/config.py)."""
import base64
import json
import re
import time
import uuid

from fastapi import Depends, FastAPI, File, Form, Header, Query, Request, Response, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse

from mcru import config, db, launcher, security, textures

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
        d["is_admin"] = db.is_admin(u)
        d["balance"] = u["balance"]
        d["public_id"] = db.public_id(u)
        d["mc_license_nick"] = u["mc_license_nick"]
    return d


async def current_user(authorization: str = Header(default="")):
    if not authorization.lower().startswith("bearer "):
        raise ApiError("Нужно войти", 401)
    uid = security.read_jwt(authorization[7:].strip())
    u = db.user_by_id(uid) if uid else None
    if not u:
        raise ApiError("Сессия истекла, войдите заново", 401)
    return u


async def admin_user(u=Depends(current_user)):
    if not db.is_admin(u):
        raise ApiError("Только для администраторов", 403)
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
    if not u:
        return None
    pw = pw or ""
    if u["password_hash"]:
        if not security.check_password(pw, u["password_hash"]):
            return None
    elif security.check_legacy_password(pw, u["legacy_password"]):
        # аккаунт переехал с onii.ink: первый вход — пароль перехэшируем в bcrypt
        db.update_user(u["id"], password_hash=security.hash_password(pw), legacy_password=None)
    else:
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


# ============================================================ servers (список серверов в лаунчере)

HOST_RE = re.compile(r"^(?=.{1,253}$)([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$")


def server_json(s):
    return {"id": s["id"], "name": s["name"], "ip": s["ip"], "port": s["port"], "version": s["version"],
            "motd": s["motd"], "sort": s["sort"], "enabled": bool(s["enabled"]),
            "icon_url": f"{config.PUBLIC_URL}/api/textures/{s['icon_hash']}.png" if s["icon_hash"] else None}


def _server_fields(d, partial=False):
    out = {}
    if "name" in d or not partial:
        name = str(d.get("name", "")).strip()
        if not 1 <= len(name) <= 48:
            raise ApiError("Название: от 1 до 48 символов")
        out["name"] = name
    if "ip" in d or not partial:
        ip = str(d.get("ip", "")).strip().lower()
        if "://" in ip or "/" in ip:
            raise ApiError("Адрес без http:// и путей, например play.mc-ru.org или 1.2.3.4:25566")
        port = None
        if ":" in ip:
            ip, p = ip.rsplit(":", 1)
            if not p.isdigit():
                raise ApiError("Порт после двоеточия должен быть числом")
            port = int(p)
        if not HOST_RE.match(ip):
            raise ApiError("Некорректный адрес сервера")
        out["ip"] = ip
        if port is not None and "port" not in d:
            d = dict(d, port=port)
    if "port" in d or not partial:
        try:
            port = int(d.get("port") or 25565)
        except (TypeError, ValueError):
            raise ApiError("Порт должен быть числом")
        if not 1 <= port <= 65535:
            raise ApiError("Порт: от 1 до 65535")
        out["port"] = port
    if "version" in d or not partial:
        v = str(d.get("version", "")).strip()
        if len(v) > 32:
            raise ApiError("Версия: до 32 символов")
        out["version"] = v
    if "motd" in d or not partial:
        m = str(d.get("motd", "")).replace("\r", "").strip()
        if len(m) > 300:
            raise ApiError("Описание: до 300 символов")
        out["motd"] = m
    if "sort" in d:
        try:
            out["sort"] = int(d.get("sort") or 0)
        except (TypeError, ValueError):
            raise ApiError("Порядок должен быть числом")
    if "enabled" in d:
        out["enabled"] = 1 if d.get("enabled") in (True, 1, "1", "true", "on") else 0
    return out


def _server_or_404(sid):
    s = db.server(sid)
    if not s:
        raise ApiError("Сервер не найден", 404)
    return s


@app.get("/api/servers")
async def servers_public():
    data = {"servers": [server_json(s) for s in db.servers(only_enabled=True)]}
    return JSONResponse(data, headers={"Cache-Control": "public, max-age=60"})


@app.get("/api/admin/servers")
async def admin_servers(_=Depends(admin_user)):
    return {"servers": [server_json(s) for s in db.servers(only_enabled=False)]}


@app.post("/api/admin/servers")
async def admin_server_create(req: Request, _=Depends(admin_user)):
    return server_json(db.create_server(**_server_fields(await body(req))))


@app.put("/api/admin/servers/{sid}")
async def admin_server_update(sid: int, req: Request, _=Depends(admin_user)):
    _server_or_404(sid)
    return server_json(db.update_server(sid, **_server_fields(await body(req), partial=True)))


@app.delete("/api/admin/servers/{sid}")
async def admin_server_delete(sid: int, _=Depends(admin_user)):
    _server_or_404(sid)
    db.delete_server(sid)
    return {"ok": True}


@app.post("/api/admin/servers/reorder")
async def admin_servers_reorder(req: Request, _=Depends(admin_user)):
    d = await body(req)
    ids = d.get("ids")
    if not isinstance(ids, list) or not all(isinstance(i, int) for i in ids):
        raise ApiError("ids: список чисел")
    db.reorder_servers(ids)
    return {"servers": [server_json(s) for s in db.servers(only_enabled=False)]}


@app.post("/api/admin/servers/{sid}/icon")
async def admin_server_icon(sid: int, file: UploadFile = File(...), u=Depends(admin_user)):
    _server_or_404(sid)
    h = textures.store_server_icon(await file.read(), u["id"])
    return server_json(db.update_server(sid, icon_hash=h))


@app.delete("/api/admin/servers/{sid}/icon")
async def admin_server_icon_delete(sid: int, _=Depends(admin_user)):
    _server_or_404(sid)
    return server_json(db.update_server(sid, icon_hash=None))


# ============================================================ admin: users

@app.get("/api/admin/users")
async def admin_users(q: str = "", limit: int = 50, _=Depends(admin_user)):
    return {"users": [{"username": x["username"], "email": x["email"], "created": x["created"],
                       "last_login": x["last_login"], "has_skin": bool(x["skin_hash"]), "has_cape": bool(x["cape_hash"]),
                       "is_admin": db.is_admin(x), "env_admin": x["username"].lower() in config.ADMINS,
                       "balance": x["balance"], "public_id": db.public_id(x), "mc_license_nick": x["mc_license_nick"]}
                      for x in db.search_users(q.strip(), limit)]}


@app.post("/api/admin/users/{name}/balance")
async def admin_user_balance(name: str, req: Request, _=Depends(admin_user)):
    target = _user_or_404(name)
    d = await body(req)
    try:
        delta = int(d.get("delta"))
    except (TypeError, ValueError):
        raise ApiError("delta: целое число руров (отрицательное — списать)")
    if abs(delta) > 1_000_000:
        raise ApiError("За раз — не больше миллиона руров")
    if target["balance"] + delta < 0:
        raise ApiError(f"У игрока только {target['balance']} руров, в минус нельзя")
    return {"username": target["username"], "balance": db.add_balance(target["id"], delta)}


@app.post("/api/admin/users/{name}/admin")
async def admin_user_flag(name: str, req: Request, me=Depends(admin_user)):
    target = _user_or_404(name)
    d = await body(req)
    flag = 1 if d.get("is_admin") in (True, 1, "1", "true") else 0
    if not flag and target["id"] == me["id"]:
        raise ApiError("Нельзя снять права с самого себя")
    db.update_user(target["id"], is_admin=flag)
    return {"username": target["username"], "is_admin": db.is_admin(db.user_by_id(target["id"]))}


# ============================================================ rurukaSuka: косметика, гардероб, друзья (API мода)
# Формы ответов — контракт SiteApi.java мода: ссылки относительные (мод сам подставляет SITE_URL), коды ошибок
# invalid_credentials / insufficient_funds / already_owned / unauthorized / not_found.

class ModError(Exception):
    def __init__(self, code, status=400, **extra):
        self.code, self.status, self.extra = code, status, extra


@app.exception_handler(ModError)
async def _mod_err(_, e: ModError):
    return JSONResponse({"error": e.code, **e.extra}, status_code=e.status)


def _mod_user_or_401(authorization):
    if not authorization.lower().startswith("bearer "):
        raise ModError("unauthorized", 401)
    uid = security.read_jwt(authorization[7:].strip())
    u = db.user_by_id(uid) if uid else None
    if not u:
        raise ModError("unauthorized", 401)
    return u


async def mod_user(authorization: str = Header(default="")):
    return _mod_user_or_401(authorization)


def mod_uuid(u):
    return f"{db.public_id(u):07d}"


def _skin_rel(u):
    return f"/api/users/{u['username']}/skin.png?frame=0" if u["skin_hash"] else None


def mod_profile(u):
    own = db.ownership(u["id"])
    return {"uuid": mod_uuid(u), "nick": u["username"], "balance": u["balance"], "skin": _skin_rel(u),
            "skin_model": "slim" if u["model"] == "slim" else "wide",
            "owned": [o["cosmetic_id"] for o in own], "equipped": [o["cosmetic_id"] for o in own if o["equipped"]],
            "variants": {str(o["cosmetic_id"]): o["variant_id"] for o in own if o["variant_id"]}}


def cosmetic_json(c, admin=False):
    d = {"id": c["id"], "name": c["name"], "category": c["category"], "price": c["price"], "scale": c["scale"],
         "off_x": c["off_x"], "off_y": c["off_y"], "off_z": c["off_z"],
         "variants": [{"id": v["id"], "name": v["name"], "texture_url": f"/api/variants/{v['id']}/texture"}
                      for v in db.variants(c["id"])]}
    if admin:
        d.update(sort=c["sort"], enabled=bool(c["enabled"]), has_model=bool(c["model_hash"]),
                 model_url=f"/api/cosmetics/{c['id']}/model" if c["model_hash"] else None, legacy_id=c["legacy_id"])
    return d


@app.post("/api/login")
async def mod_login(req: Request):
    if not auth_limit.hit(("login", _ip(req))):
        raise ModError("Слишком много попыток, подождите 10 минут", 429)
    d = await body(req)
    u = _login(d.get("email") or d.get("login") or d.get("username"), d.get("password"))
    if not u:
        raise ModError("invalid_credentials", 401)
    return {"token": security.make_jwt(u["id"]), **mod_profile(u)}


@app.get("/api/me")
async def mod_me(u=Depends(mod_user)):
    return mod_profile(u)


@app.get("/api/cosmetics")
async def mod_cosmetics():
    return {"cosmetics": [cosmetic_json(c) for c in db.cosmetics(only_enabled=True)]}


@app.get("/api/cosmetics/{cid}/model")
async def mod_cosmetic_model(cid: int):
    c = db.cosmetic(cid)
    p = textures.bbmodel_path(c["model_hash"]) if c else None
    if not p:
        raise ModError("not_found", 404)
    return FileResponse(p, media_type="application/json", filename=f"{cid}.bbmodel",
                        headers={"Cache-Control": "public, max-age=3600"})


@app.get("/api/variants/{vid}/texture")
async def mod_variant_texture(vid: int):
    v = db.variant(vid)
    p = textures.path(v["texture_hash"]) if v else None
    if not p:
        raise ModError("not_found", 404)
    return _png(p.read_bytes(), cache=3600)


def _cid(d):
    try:
        return int(d.get("cosmetic_id"))
    except (TypeError, ValueError):
        raise ModError("not_found", 404)


def _owned_or_403(u, cid):
    o = db.owned(u["id"], cid)
    if not o:
        raise ModError("not_owned", 403)
    return o


@app.post("/api/purchase")
async def mod_purchase(req: Request, u=Depends(mod_user)):
    d = await body(req)
    c = db.cosmetic(_cid(d))
    if not c or not c["enabled"]:
        raise ModError("not_found", 404)
    if db.owned(u["id"], c["id"]):
        raise ModError("already_owned", 409, balance=u["balance"])
    if u["balance"] < c["price"]:
        raise ModError("insufficient_funds", 402, balance=u["balance"])
    balance = db.add_balance(u["id"], -c["price"])
    db.add_ownership(u["id"], c["id"])
    return {"ok": True, "balance": balance, "owned": [o["cosmetic_id"] for o in db.ownership(u["id"])]}


@app.post("/api/equip")
async def mod_equip(req: Request, u=Depends(mod_user)):
    d = await body(req)
    cid = _cid(d)
    _owned_or_403(u, cid)
    db.set_equipped(u["id"], cid, bool(d.get("equipped")))
    return {"ok": True, "equipped": [o["cosmetic_id"] for o in db.ownership(u["id"]) if o["equipped"]]}


@app.post("/api/variant")
async def mod_variant(req: Request, u=Depends(mod_user)):
    d = await body(req)
    cid = _cid(d)
    _owned_or_403(u, cid)
    try:
        vid = int(d.get("variant_id") or 0) or None
    except (TypeError, ValueError):
        raise ModError("bad_variant", 400)
    if vid:
        v = db.variant(vid)
        if not v or v["cosmetic_id"] != cid:
            raise ModError("bad_variant", 400)
    db.set_variant(u["id"], cid, vid)
    return {"ok": True, "variants": mod_profile(u)["variants"]}


@app.get("/api/equipped/{nick}")
async def mod_equipped(nick: str):
    """Что надето у игрока — чтобы другие игроки с модом видели его косметику."""
    u = db.user_by_any_nick(nick.strip())
    if not u:
        return {"nick": nick, "equipped": []}
    eq = [o for o in db.ownership(u["id"]) if o["equipped"]]
    return {"nick": u["username"], "uuid": mod_uuid(u), "skin": _skin_rel(u),
            "skin_model": "slim" if u["model"] == "slim" else "wide",
            "equipped": [{"id": o["cosmetic_id"], "category": o["category"], "variant_id": o["variant_id"],
                          "model_url": f"/api/cosmetics/{o['cosmetic_id']}/model"} for o in eq]}


# ---- друзья и чат

def _friend_json(x):
    return {"id": db.public_id(x), "nick": x["username"]}


def _other_or_404(d):
    o = db.user_by_public_id(d.get("user_id"))
    if not o:
        raise ModError("not_found", 404)
    return o


@app.get("/api/friends")
async def mod_friends(u=Depends(mod_user)):
    acc, inc, out = db.friends_of(u["id"])
    return {"friends": [_friend_json(x) for x in acc], "incoming": [_friend_json(x) for x in inc],
            "outgoing": [_friend_json(x) for x in out]}


@app.post("/api/friends/request")
async def mod_friend_request(req: Request, u=Depends(mod_user)):
    d = await body(req)
    o = db.user_by_any_nick(str(d.get("nick", "")).strip())
    if not o:
        raise ModError("Игрок с таким ником не найден", 404)
    if o["id"] == u["id"]:
        raise ModError("Себя в друзья добавить нельзя", 400)
    r = db.friend_row(u["id"], o["id"])
    if r and r["status"] == "accepted":
        raise ModError("Вы уже друзья", 409)
    if r and r["user_id"] == o["id"]:   # он уже звал нас — принимаем
        db.accept_friend(u["id"], o["id"])
    elif not r:
        db.request_friend(u["id"], o["id"])
    return {"ok": True}


@app.post("/api/friends/accept")
async def mod_friend_accept(req: Request, u=Depends(mod_user)):
    o = _other_or_404(await body(req))
    r = db.friend_row(u["id"], o["id"])
    if not r or r["status"] != "pending" or r["friend_id"] != u["id"]:
        raise ModError("Заявки от этого игрока нет", 404)
    db.accept_friend(u["id"], o["id"])
    return {"ok": True}


@app.post("/api/friends/remove")
async def mod_friend_remove(req: Request, u=Depends(mod_user)):
    o = _other_or_404(await body(req))
    db.remove_friend(u["id"], o["id"])
    return {"ok": True}


def _message_json(m):
    sender = db.user_by_id(m["from_id"])
    return {"id": m["id"], "from": db.public_id(sender) if sender else m["from_id"], "text": m["text"], "ts": m["ts"]}


@app.get("/api/messages")
async def mod_messages(with_: str = Query(default="", alias="with"), after: int = 0, u=Depends(mod_user)):
    o = db.user_by_public_id(with_)
    if not o:
        raise ModError("not_found", 404)
    if not db.are_friends(u["id"], o["id"]):
        raise ModError("Писать можно только друзьям", 403)
    return {"messages": [_message_json(m) for m in db.messages(u["id"], o["id"], after)]}


@app.post("/api/messages")
async def mod_message_send(req: Request, u=Depends(mod_user)):
    d = await body(req)
    o = db.user_by_public_id(d.get("to"))
    if not o:
        raise ModError("not_found", 404)
    if not db.are_friends(u["id"], o["id"]):
        raise ModError("Писать можно только друзьям", 403)
    text = str(d.get("text", "")).strip()
    if not text:
        raise ModError("Пустое сообщение", 400)
    if len(text) > 500:
        raise ModError("Сообщение длиннее 500 символов", 400)
    return {"id": db.add_message(u["id"], o["id"], text)["id"]}


# ---- кабинет: гардероб (только посмотреть; надевают в игре)

@app.get("/api/account/wardrobe")
async def account_wardrobe(u=Depends(current_user)):
    own = db.ownership(u["id"])
    return {"balance": u["balance"], "public_id": db.public_id(u),
            "owned": [{"id": o["cosmetic_id"], "name": o["name"], "category": o["category"],
                       "equipped": bool(o["equipped"]), "variant_id": o["variant_id"]} for o in own]}


# ---- админка: каталог косметики

def _cosmetic_fields(d, partial=False):
    out = {}
    if "name" in d or not partial:
        name = str(d.get("name", "")).strip()
        if not 1 <= len(name) <= 64:
            raise ApiError("Название: от 1 до 64 символов")
        out["name"] = name
    if "category" in d or not partial:
        cat = str(d.get("category", "")).strip().lower()
        if cat not in db.CATEGORIES:
            raise ApiError("Категория: " + ", ".join(db.CATEGORIES))
        out["category"] = cat
    if "price" in d or not partial:
        try:
            price = int(d.get("price") or 0)
        except (TypeError, ValueError):
            raise ApiError("Цена должна быть числом")
        if not 0 <= price <= 1_000_000:
            raise ApiError("Цена: от 0 до 1 000 000 руров")
        out["price"] = price
    for k, lo, hi in (("scale", 0.05, 20.0), ("off_x", -64.0, 64.0), ("off_y", -64.0, 64.0), ("off_z", -64.0, 64.0)):
        if k in d:
            try:
                v = float(d.get(k))
            except (TypeError, ValueError):
                raise ApiError(f"{k}: число")
            out[k] = max(lo, min(hi, v))
    if "sort" in d:
        try:
            out["sort"] = int(d.get("sort") or 0)
        except (TypeError, ValueError):
            raise ApiError("Порядок должен быть числом")
    if "enabled" in d:
        out["enabled"] = 1 if d.get("enabled") in (True, 1, "1", "true", "on") else 0
    return out


def _cosmetic_or_404(cid):
    c = db.cosmetic(cid)
    if not c:
        raise ApiError("Косметика не найдена", 404)
    return c


@app.get("/api/admin/cosmetics")
async def admin_cosmetics(_=Depends(admin_user)):
    return {"cosmetics": [cosmetic_json(c, admin=True) for c in db.cosmetics(only_enabled=False)]}


@app.post("/api/admin/cosmetics")
async def admin_cosmetic_create(req: Request, _=Depends(admin_user)):
    return cosmetic_json(db.create_cosmetic(**_cosmetic_fields(await body(req))), admin=True)


@app.put("/api/admin/cosmetics/{cid}")
async def admin_cosmetic_update(cid: int, req: Request, _=Depends(admin_user)):
    _cosmetic_or_404(cid)
    return cosmetic_json(db.update_cosmetic(cid, **_cosmetic_fields(await body(req), partial=True)), admin=True)


@app.delete("/api/admin/cosmetics/{cid}")
async def admin_cosmetic_delete(cid: int, _=Depends(admin_user)):
    _cosmetic_or_404(cid)
    db.delete_cosmetic(cid)
    return {"ok": True}


@app.post("/api/admin/cosmetics/{cid}/model")
async def admin_cosmetic_model(cid: int, file: UploadFile = File(...), u=Depends(admin_user)):
    _cosmetic_or_404(cid)
    h = textures.store_bbmodel(await file.read(), u["id"])
    return cosmetic_json(db.update_cosmetic(cid, model_hash=h), admin=True)


@app.post("/api/admin/cosmetics/{cid}/variants")
async def admin_variant_create(cid: int, file: UploadFile = File(...), name: str = Form(default=""),
                               u=Depends(admin_user)):
    _cosmetic_or_404(cid)
    name = name.strip()[:64] or "variant"
    h = textures.store_png(await file.read(), "variant", u["id"])
    db.create_variant(cid, name, h)
    return cosmetic_json(db.cosmetic(cid), admin=True)


@app.delete("/api/admin/variants/{vid}")
async def admin_variant_delete(vid: int, _=Depends(admin_user)):
    v = db.variant(vid)
    if not v:
        raise ApiError("Вариант не найден", 404)
    db.delete_variant(vid)
    return cosmetic_json(db.cosmetic(v["cosmetic_id"]), admin=True)


@app.post("/api/admin/cosmetics/reorder")
async def admin_cosmetics_reorder(req: Request, _=Depends(admin_user)):
    d = await body(req)
    ids = d.get("ids")
    if not isinstance(ids, list) or not all(isinstance(i, int) for i in ids):
        raise ApiError("ids: список чисел")
    for i, cid in enumerate(ids):
        db.update_cosmetic(cid, sort=i)
    return {"cosmetics": [cosmetic_json(c, admin=True) for c in db.cosmetics(only_enabled=False)]}


# ============================================================ launcher downloads

def _launcher_latest():
    data = dict(launcher.latest())
    for plat in ("windows", "macos"):
        p = launcher.local_file(plat)
        if p:
            data[plat] = {"url": launcher.download_url(plat), "name": p.name, "size": p.stat().st_size}
        elif data.get(plat):
            data[plat] = dict(data[plat], url=launcher.download_url(plat), github=data[plat]["url"])
    return data


@app.get("/api/launcher/latest")
async def launcher_latest():
    return JSONResponse(_launcher_latest(), headers={"Cache-Control": "public, max-age=300"})


@app.get("/api/launcher/version")
async def launcher_version():
    d = _launcher_latest()
    return {"version": d["version"], "windows": d["windows"]["url"] if d["windows"] else None,
            "macos": d["macos"]["url"] if d["macos"] else None}


@app.get("/api/launcher/download/{platform}")
async def launcher_download(platform: str):
    if platform not in config.LAUNCHER_FILES:
        raise ApiError("Платформа: windows или macos", 404)
    p = launcher.local_file(platform)
    if p:
        return FileResponse(p, filename=p.name, media_type="application/octet-stream")
    asset = launcher.latest().get(platform)
    if not asset or not asset.get("url"):
        raise ApiError("Сборка для этой платформы ещё не опубликована", 404)
    return RedirectResponse(asset["url"], status_code=302)


@app.get("/api/health")
async def health():
    return {"ok": True, "users": db.one("SELECT COUNT(*) AS n FROM users")["n"],
            "servers": db.one("SELECT COUNT(*) AS n FROM servers WHERE enabled=1")["n"],
            "cosmetics": db.one("SELECT COUNT(*) AS n FROM cosmetics WHERE enabled=1")["n"]}
