"""Проверка и нормализация скинов/плащей: PNG (статика или вертикальная полоса кадров), GIF, APNG.
Хранение: textures/<sha256>.png — полоса кадров в исходном разрешении, textures/<sha256>.std.png — первый кадр 64×64
(скин) / 64×32 (плащ) для ванильного клиента через Yggdrasil."""
import hashlib
import io

from PIL import Image, ImageSequence

from . import config, db

SKIN_WIDTHS = (64, 128, 256, 512, 1024)
CAPE_WIDTHS = (64, 128, 256, 512, 1024, 2048)
MAX_FRAMES = 64


class TextureError(ValueError):
    pass


def _frames_from_file(data: bytes):
    try:
        im = Image.open(io.BytesIO(data))
        im.load()
    except Exception as e:
        raise TextureError("Не удалось прочитать изображение — нужен PNG, GIF или APNG") from e
    if im.format not in ("PNG", "GIF"):
        raise TextureError("Поддерживаются только PNG, GIF и APNG")
    n = getattr(im, "n_frames", 1)
    if n > 1:
        frames, durations = [], []
        for fr in ImageSequence.Iterator(im):
            frames.append(fr.convert("RGBA"))
            durations.append(fr.info.get("duration") or 100)
            if len(frames) >= MAX_FRAMES:
                break
        avg = max(1.0, sum(durations) / len(durations))
        fps = max(1, min(30, round(1000 / avg)))
        return frames, fps, True
    return [im.convert("RGBA")], None, False


def _legacy_skin_to_modern(f: Image.Image) -> Image.Image:
    """64×32 → 64×64: левая рука и нога — зеркальные копии правых (как делает Mojang)."""
    w = f.width
    s = w // 64
    out = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    out.paste(f, (0, 0))

    def cp(x, y, cw, ch, dx, dy):
        part = f.crop((x * s, y * s, (x + cw) * s, (y + ch) * s)).transpose(Image.FLIP_LEFT_RIGHT)
        out.paste(part, (dx * s, dy * s))

    # leg: right (0,16) → left (16,48)
    cp(4, 16, 4, 4, 20, 48); cp(8, 16, 4, 4, 24, 48)
    cp(0, 20, 4, 12, 24, 52); cp(4, 20, 4, 12, 20, 52); cp(8, 20, 4, 12, 16, 52); cp(12, 20, 4, 12, 28, 52)
    # arm: right (40,16) → left (32,48)
    cp(44, 16, 4, 4, 36, 48); cp(48, 16, 4, 4, 40, 48)
    cp(40, 20, 4, 12, 40, 52); cp(44, 20, 4, 12, 36, 52); cp(48, 20, 4, 12, 32, 52); cp(52, 20, 4, 12, 44, 52)
    return out


def _split_strip(img: Image.Image, frame_h: int):
    n = img.height // frame_h
    return [img.crop((0, i * frame_h, img.width, (i + 1) * frame_h)) for i in range(n)]


def _normalize_skin(frames):
    w = frames[0].width
    if w not in SKIN_WIDTHS:
        raise TextureError(f"Ширина скина должна быть 64, 128, 256, 512 или 1024 px (у вас {w})")
    out = []
    for f in frames:
        if f.width != w:
            raise TextureError("Все кадры должны быть одного размера")
        h = f.height
        if h % w == 0:
            out += _split_strip(f, w)
        elif h % (w // 2) == 0:
            out += [_legacy_skin_to_modern(x) for x in _split_strip(f, w // 2)]
        else:
            raise TextureError(f"Высота скина должна быть {w} (или {w // 2} для старого формата) px либо кратна ей "
                               f"для анимации (у вас {h})")
    return out, w, w


def _normalize_cape(frames):
    w = frames[0].width
    if (w, frames[0].height) == (22, 17) or (frames[0].height == 17 and w == 22):
        fixed = []
        for f in frames:
            c = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
            c.paste(f, (0, 0))
            fixed.append(c)
        frames, w = fixed, 64
    if w not in CAPE_WIDTHS:
        raise TextureError(f"Ширина плаща должна быть 64, 128, 256, 512, 1024 или 2048 px (у вас {w})")
    fh = w // 2
    out = []
    for f in frames:
        if f.width != w:
            raise TextureError("Все кадры должны быть одного размера")
        if f.height % fh:
            raise TextureError(f"Высота плаща должна быть {fh} px либо кратна ей для анимации (у вас {f.height})")
        out += _split_strip(f, fh)
    return out, w, fh


def store(data: bytes, kind: str, owner_id: int, fps=None):
    """Validate + save. Returns the texture row."""
    if len(data) > config.MAX_UPLOAD:
        raise TextureError("Файл больше 8 МБ")
    frames, auto_fps, animated_file = _frames_from_file(data)
    frames, w, fh = (_normalize_skin if kind == "skin" else _normalize_cape)(frames)
    if len(frames) > MAX_FRAMES:
        raise TextureError(f"Слишком много кадров (максимум {MAX_FRAMES})")
    if fps is None:
        fps = auto_fps or 10
    fps = max(1, min(30, int(fps)))
    strip = Image.new("RGBA", (w, fh * len(frames)), (0, 0, 0, 0))
    for i, f in enumerate(frames):
        strip.paste(f, (0, i * fh))
    buf = io.BytesIO()
    strip.save(buf, "PNG", optimize=True)
    raw = buf.getvalue()
    h = hashlib.sha256(raw).hexdigest()
    config.ensure_dirs()
    (config.TEXTURES / f"{h}.png").write_bytes(raw)
    std_size = (64, 64) if kind == "skin" else (64, 32)
    first = frames[0]
    std = first if first.size == std_size else first.resize(std_size, Image.LANCZOS)
    std.save(config.TEXTURES / f"{h}.std.png", "PNG", optimize=True)
    db.save_texture(h, kind, owner_id, w, fh, len(frames), fps, w > 64)
    return db.texture(h)


def store_server_icon(data: bytes, owner_id: int):
    """Иконка сервера: PNG/JPG/WEBP любого размера → 64×64 PNG (как в списке серверов игры)."""
    if len(data) > config.MAX_UPLOAD:
        raise TextureError("Файл больше 8 МБ")
    try:
        im = Image.open(io.BytesIO(data))
        im.load()
    except Exception as e:
        raise TextureError("Не удалось прочитать изображение — нужен PNG, JPG или WEBP") from e
    if im.format not in ("PNG", "JPEG", "WEBP", "GIF"):
        raise TextureError("Поддерживаются PNG, JPG и WEBP")
    im = im.convert("RGBA")
    side = min(im.size)
    left, top = (im.width - side) // 2, (im.height - side) // 2
    im = im.crop((left, top, left + side, top + side))
    im = im.resize((64, 64), Image.NEAREST if side <= 64 else Image.LANCZOS)
    buf = io.BytesIO()
    im.save(buf, "PNG", optimize=True)
    raw = buf.getvalue()
    h = hashlib.sha256(raw).hexdigest()
    config.ensure_dirs()
    (config.TEXTURES / f"{h}.png").write_bytes(raw)
    (config.TEXTURES / f"{h}.std.png").write_bytes(raw)
    db.save_texture(h, "server_icon", owner_id, 64, 64, 1, 1, False)
    return h


_default = {}


def default_png(kind: str) -> bytes:
    """Скин «Стив» / пустой плащ для игроков без текстуры."""
    if kind not in _default:
        if kind == "skin":
            im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
            skin, hair, shirt, pants, shoes = (224, 172, 128, 255), (58, 38, 24, 255), (0, 160, 170, 255), (60, 60, 140, 255), (80, 80, 80, 255)
            boxes = [((8, 0, 24, 8), hair), ((0, 8, 32, 16), skin), ((8, 8, 24, 10), hair),  # head + fringe
                     ((20, 16, 36, 20), shirt), ((16, 20, 40, 32), shirt),  # body
                     ((44, 16, 52, 20), skin), ((40, 20, 56, 32), skin),  # right arm
                     ((36, 48, 44, 52), skin), ((32, 52, 48, 64), skin),  # left arm
                     ((4, 16, 12, 20), pants), ((0, 20, 16, 30), pants), ((0, 30, 16, 32), shoes),  # right leg
                     ((20, 48, 28, 52), pants), ((16, 52, 32, 62), pants), ((16, 62, 32, 64), shoes)]  # left leg
            for box, col in boxes:
                im.paste(col, box)
            for x in (10, 13):  # eyes
                im.paste((255, 255, 255, 255), (x, 12, x + 1, 13)); im.paste((60, 40, 120, 255), (x + 1, 12, x + 2, 13))
            im.paste((160, 100, 70, 255), (11, 14, 13, 15))  # mouth
        else:
            im = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
        buf = io.BytesIO()
        im.save(buf, "PNG")
        _default[kind] = buf.getvalue()
    return _default[kind]


def path(h: str, std=False):
    p = config.TEXTURES / (f"{h}.std.png" if std else f"{h}.png")
    return p if p.exists() else None


def frame_png(h: str, frame: int, size=None) -> bytes | None:
    t = db.texture(h)
    p = path(h)
    if not t or not p:
        return None
    im = Image.open(p).convert("RGBA")
    fh = t["height"]
    frame = max(0, min(t["frames"] - 1, frame))
    f = im.crop((0, frame * fh, t["width"], (frame + 1) * fh))
    if size and size != f.width:
        f = f.resize((size, size * fh // t["width"]), Image.LANCZOS if size < f.width else Image.NEAREST)
    buf = io.BytesIO()
    f.save(buf, "PNG")
    return buf.getvalue()


def info(h: str | None):
    if not h:
        return None
    t = db.texture(h)
    if not t:
        return None
    return {"hash": h, "url": f"{config.PUBLIC_URL}/api/textures/{h}/full.png",
            "std_url": f"{config.PUBLIC_URL}/api/textures/{h}.png", "width": t["width"], "height": t["height"],
            "frames": t["frames"], "fps": t["fps"], "hd": bool(t["hd"]), "animated": t["frames"] > 1}
