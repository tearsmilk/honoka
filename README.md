# mc-ru.org — фейм-страница сообщества

Статичный сайт русскоязычного Minecraft-комьюнити: `index.html` + `style.css` + `script.js` + `assets/`. Никакой сборки, никакого бэкенда.

## Что поменять под себя
- `index.html` → ссылки на Telegram / VK / YouTube в блоке `#community`, цифры в блоке «О нас», тексты, правила. Discord уже проставлен.
- `script.js` → `SPLASHES` (жёлтые надписи как в меню майна).

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
Скрипт `deploy.sh` ставит nginx и certbot, клонирует сайт в `/var/www/mc-ru`, пишет конфиг, выпускает HTTPS и вешает автообновление из GitHub на крон. Если DNS ещё не доехал, скрипт поднимет сайт по http и скажет, какую команду запустить для HTTPS позже.

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
