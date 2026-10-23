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
В nginx внутри блока `server { listen 443 ... }` для mc-ru.org добавь содержимое `server/nginx-api.conf` (`location /api/` → `127.0.0.1:8787`, `location = /account` → `account.html`), затем:
```bash
nginx -t && systemctl reload nginx
```
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
