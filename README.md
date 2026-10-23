# mc-ru.org — фейм-страница сообщества

Сайт русскоязычного Minecraft-комьюнити: статика (`index.html`, `account.html`, `style.css`, `script.js`, `account.js`, `assets/`) + небольшой бэкенд `server/` для аккаунтов, скинов и плащей.

## Что поменять под себя
- `index.html` → ссылки на Telegram / VK / YouTube в блоке `#community`, цифры в блоке «О нас», тексты, правила. Discord уже проставлен.
- `script.js` → `SPLASHES` (жёлтые надписи как в меню майна).

---

# Аккаунты, скины и плащи (бэкенд)

`server/` — FastAPI + SQLite. Регистрация/вход на сайте, личный кабинет `/account` с 3D-превью (skinview3d), загрузка скина и плаща:
- **HD**: скин 64…1024 px по ширине (квадрат; старый 64×32 конвертируется в 64×64), плащ 64…2048 px (2:1, старый 22×17 тоже принимается).
- **Анимация**: GIF, APNG или PNG-полоса кадров (кадры друг под другом), до 64 кадров, 1–30 fps.
- Модель `classic` / `slim`.

Для игры отдаётся два варианта: обычное 64×64 / 64×32 (первый кадр) — через **Yggdrasil** для `authlib-injector`, и полное HD + полоса кадров — через **CustomSkinLoader API** (и лаунчер Эта).

## API (кратко)

| Метод | Путь | Что |
|---|---|---|
| POST | `/api/auth/register` | `{username, email, password}` → `{token, user}` |
| POST | `/api/auth/login` | `{login, password}` (ник или e-mail) → `{token, user}` |
| GET | `/api/auth/me` | профиль (Bearer-токен) |
| POST | `/api/auth/password` | `{old, new}` |
| POST / DELETE | `/api/account/skin` | multipart `file`, `model`, `fps` |
| POST / DELETE | `/api/account/cape` | multipart `file`, `fps` |
| POST | `/api/account/model` | `{model: classic\|slim}` |
| GET | `/api/users/{ник}` | публичный профиль |
| GET | `/api/users/{ник}/skin.png?frame=0&size=64` | кадр скина (`size=full` — вся полоса); без скина — Стив |
| GET | `/api/users/{ник}/cape.png` | то же для плаща |
| GET | `/api/textures/{hash}.png` | 64×64 первый кадр (Yggdrasil) |
| GET | `/api/textures/{hash}/full.png` | полная полоса кадров |
| GET | `/api/csl/{ник}.json`, `/api/csl/textures/{hash}` | CustomSkinLoader (CustomSkinAPI) |
| * | `/api/yggdrasil/...` | authlib-injector: `authserver/authenticate|refresh|validate|invalidate|signout`, `sessionserver/session/minecraft/join|hasJoined|profile/{uuid}`, `api/profiles/minecraft` |

Подключение лаунчеров: Эта — вкладка «mc-ru.org» при добавлении аккаунта; остальные — `-javaagent:authlib-injector.jar=https://mc-ru.org/api/yggdrasil`; HD/анимация в игре — CustomSkinLoader с корнем `https://mc-ru.org/api/csl/`.

## Админка и серверы

Страница `/admin` (только для администраторов): список серверов, который показывает лаунчер Эта в разделе «Серверы», и управление пользователями.

**Как стать админом.** Впиши свой ник в unit systemd: `Environment=MCRU_ADMINS=твойник` (несколько — через запятую) в `/etc/systemd/system/mc-ru-api.service`, затем `systemctl daemon-reload && systemctl restart mc-ru-api`. Такие ники — админы всегда; остальным права выдаются в админке на вкладке «Пользователи» (флаг `is_admin` в базе).

**Сервер** = название (до 48), адрес (домен или IPv4, можно `host:port`), порт (по умолчанию 25565), версия (до 32), MOTD (до 300 символов, поддерживаются `§`-коды цвета как в игре), иконка (PNG/JPG/WEBP → 64×64), флаг «показывать», порядок (стрелки). Выключенные серверы в `/api/servers` не попадают.

| Метод | Путь | Что |
|---|---|---|
| GET | `/api/servers` | публичный список для лаунчера: `{servers:[{id,name,ip,port,version,motd,icon_url,sort}]}` (кеш 60 с) |
| GET / POST | `/api/admin/servers` | все серверы / создать (`{name, ip, port, version, motd, sort, enabled}`) |
| PUT / DELETE | `/api/admin/servers/{id}` | изменить (любое подмножество полей) / удалить |
| POST / DELETE | `/api/admin/servers/{id}/icon` | multipart `file` → иконка / убрать |
| POST | `/api/admin/servers/reorder` | `{ids:[…]}` — новый порядок |
| GET | `/api/admin/users?q=&limit=` | поиск пользователей |
| POST | `/api/admin/users/{ник}/admin` | `{is_admin: true\|false}` |

## Лаунчер на сайте

Страница `/launcher` — скачивание Эты. Кнопки ведут на `/api/launcher/download/windows` и `/api/launcher/download/macos`:
- по умолчанию это 302 на последний релиз GitHub `tearsmilk/amkawa-lang` с тегом `eta-v*` (`.exe` для Windows, `.dmg` для macOS; список кешируется 10 минут, при недоступности GitHub отдаётся последнее известное);
- если положить файл в `$MCRU_DATA/launcher/` (`/var/lib/mc-ru/launcher/Eta-windows-x64.exe`, `…/Eta-macOS-x64.dmg`), он отдаётся напрямую с сайта — для хостинга лаунчера без GitHub. Не забудь `chown www-data`.

| Метод | Путь | Что |
|---|---|---|
| GET | `/api/launcher/latest` | `{version, tag, published, notes, windows:{url,name,size}, macos:{…}}` (без релиза — `version: null`) |
| GET | `/api/launcher/version` | компактно для проверки обновлений: `{version, windows, macos}` |
| GET | `/api/launcher/download/{windows\|macos}` | файл или 302 на GitHub |

Выпуск новой версии: тег `eta-vX.Y.Z` в репозитории лаунчера — CI сам соберёт и приложит `.exe`/`.dmg` к релизу, сайт подхватит его в течение 10 минут.

## Локально
```bash
cd server
python3 -m venv venv && . venv/bin/activate && pip install -r requirements.txt
uvicorn app:app --reload --port 8787
```
Данные (`mcru.db`, `textures/`, ключи) — в `server/data/` (или `$MCRU_DATA`). Публичный адрес для ссылок на текстуры — `$MCRU_PUBLIC_URL` (по умолчанию `https://mc-ru.org`). Статику открывай через любой сервер, который проксирует `/api/` на 8787 и отдаёт `account.html` по `/account`.

## На VPS, где сайт уже стоит (root /opt/minesite, конфиг /etc/nginx/sites-available/mc-ru.org)
Один раз:
```bash
apt install -y python3-venv rsync
mkdir -p /opt/mc-ru-api /var/lib/mc-ru
rsync -a --delete /opt/minesite/server/ /opt/mc-ru-api/server/
python3 -m venv /opt/mc-ru-api/venv
/opt/mc-ru-api/venv/bin/pip install -r /opt/mc-ru-api/server/requirements.txt
chown -R www-data:www-data /var/lib/mc-ru
cp /opt/mc-ru-api/server/mc-ru-api.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now mc-ru-api
curl -s 127.0.0.1:8787/api/health
```
В nginx внутри блока `server { listen 443 ... }` для mc-ru.org добавь содержимое `server/nginx-api.conf` (`location /api/` → `127.0.0.1:8787`, `location = /account|/admin|/launcher` → соответствующий html), затем:
```bash
nginx -t && systemctl reload nginx
```
Если `/api/` и `/account` уже вставлены раньше, добавь только два новых location одной командой (вставка после строки `location = /account`):
```bash
sed -i '/location = \/account {/,/}/{/}/a\    location = /admin { try_files /admin.html =404; }\n    location = /launcher { try_files /launcher.html =404; }
}' /etc/nginx/sites-available/mc-ru.org
nginx -t && systemctl reload nginx
```
Админ-доступ: `sed -i "s|^#Environment=MCRU_ADMINS=.*|Environment=MCRU_ADMINS=ТВОЙНИК|" /etc/systemd/system/mc-ru-api.service && systemctl daemon-reload && systemctl restart mc-ru-api` (ник — тот, под которым зарегистрирован на сайте).
Обновление после `git pull` / распаковки нового zip в `/opt/minesite`:
```bash
rsync -a --delete /opt/minesite/server/ /opt/mc-ru-api/server/
/opt/mc-ru-api/venv/bin/pip install -q -r /opt/mc-ru-api/server/requirements.txt
systemctl restart mc-ru-api
```
Логи: `journalctl -u mc-ru-api -f`. Бэкап = папка `/var/lib/mc-ru` (база, текстуры, ключ подписи — его терять нельзя, иначе клиенты перестанут верить скинам).

`deploy.sh` на чистом сервере делает всё это сам (включая крон-обновление, которое перезапускает API при изменении `server/`).

---

# Гайд по деплою

Pterodactyl-панель (где Velocity, Lobby и т.п.) под сайт не подходит: она для игровых серверов. Для сайта нужен один из вариантов ниже.

## Вариант A. Свой VPS (Ubuntu/Debian) + nginx + HTTPS

Самый «правильный». Нужен сервер с белым IP и доступом по SSH.

### Быстрый путь: одна команда
Сначала пропиши DNS (шаг 1 ниже), потом на VPS от root:
```bash
curl -fsSL https://raw.githubusercontent.com/tearsmilk/honoka/main/deploy.sh | bash
```
Скрипт `deploy.sh` ставит nginx и certbot, клонирует сайт в `/var/www/mc-ru`, поднимает API аккаунтов в `/opt/mc-ru-api` (systemd `mc-ru-api`), пишет конфиг, выпускает HTTPS и вешает автообновление из GitHub на крон. Если DNS ещё не доехал, скрипт поднимет сайт по http и скажет, какую команду запустить для HTTPS позже.

### Ручной путь

### 1. Домен → сервер (DNS)
В панели регистратора, где куплен `mc-ru.org`, добавь записи:

| Тип | Имя | Значение         |
|-----|-----|------------------|
| A   | @   | IP твоего VPS    |
| A   | www | IP твоего VPS    |

Подождать 5–30 минут. Проверить: `ping mc-ru.org` должен отдавать IP VPS.

### 2. Поставить nginx и залить сайт
```bash
ssh root@IP_VPS
apt update && apt install -y nginx git
git clone https://github.com/tearsmilk/honoka.git /var/www/mc-ru
chown -R www-data:www-data /var/www/mc-ru
```

### 3. Конфиг nginx
```bash
cat > /etc/nginx/sites-available/mc-ru.org <<'NGINX'
server {
    listen 80;
    listen [::]:80;
    server_name mc-ru.org www.mc-ru.org;
    root /var/www/mc-ru;
    index index.html;

    location / {
        try_files $uri $uri/ =404;
    }

    location ~* \.(css|js|svg|png|jpg|webp|woff2)$ {
        expires 7d;
        add_header Cache-Control "public";
    }
}
NGINX
ln -s /etc/nginx/sites-available/mc-ru.org /etc/nginx/sites-enabled/
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx
```
Открой `http://mc-ru.org` — сайт должен быть виден.

### 4. HTTPS (Let's Encrypt, бесплатно)
```bash
apt install -y certbot python3-certbot-nginx
certbot --nginx -d mc-ru.org -d www.mc-ru.org
```
Certbot сам перепишет конфиг под 443 и добавит редирект с http на https. Сертификат обновляется автоматически.

### 5. Обновление сайта
```bash
cd /var/www/mc-ru && git pull
```
Если хочешь, чтобы обновлялось само при пуше в GitHub, добавь в крон раз в 5 минут:
```bash
echo "*/5 * * * * cd /var/www/mc-ru && git pull -q" | crontab -
```

Для аккаунтов и скинов нужен ещё бэкенд — см. раздел выше; на вариантах B и C (без своего сервера) он работать не будет, сайт останется просто страницей.

## Вариант B. Обычный веб-хостинг (панель ISPmanager / cPanel / Beget / Timeweb и т.п.)

1. В панели хостинга: **Домены → Добавить домен** `mc-ru.org`. Хостинг покажет свой IP или NS-серверы.
2. У регистратора: либо прописать NS-серверы хостинга, либо A-записи `@` и `www` на IP хостинга (как в варианте A, шаг 1).
3. Скачай репу как ZIP (GitHub → Code → Download ZIP), распакуй.
4. Через файловый менеджер панели или FTP залей **содержимое** папки (`index.html`, `style.css`, `script.js`, `assets/`) в корень домена. Обычно это `public_html/` или `www/mc-ru.org/`.
5. В панели включи SSL (Let's Encrypt) для домена, обычно одна кнопка.

## Вариант C. GitHub Pages (бесплатно, без своего сервера)

Подходит идеально: сайт статичный.

1. В репе на GitHub: **Settings → Pages → Build and deployment → Source: Deploy from a branch**, ветка `main`, папка `/ (root)`. Сохранить.
2. Там же в поле **Custom domain** вписать `mc-ru.org`, сохранить. GitHub создаст файл `CNAME` в репе.
3. У регистратора прописать:

| Тип   | Имя | Значение              |
|-------|-----|-----------------------|
| A     | @   | 185.199.108.153       |
| A     | @   | 185.199.109.153       |
| A     | @   | 185.199.110.153       |
| A     | @   | 185.199.111.153       |
| CNAME | www | tearsmilk.github.io   |

4. Подождать до часа, вернуться в Settings → Pages, поставить галку **Enforce HTTPS**.
5. Обновление сайта: просто пуш в `main`.

## Проверка после любого варианта
- `https://mc-ru.org` открывается, замок в адресной строке зелёный.
- `http://mc-ru.org` и `www.mc-ru.org` редиректят на https.
- Кнопка Discord ведёт на `https://discord.gg/2tGDC7bsye`.
