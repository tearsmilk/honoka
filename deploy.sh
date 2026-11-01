#!/usr/bin/env bash
# Разворачивает mc-ru.org на чистом Ubuntu/Debian VPS: nginx + сайт + API (аккаунты, скины) + HTTPS.
# Запуск от root:  bash deploy.sh
set -euo pipefail

DOMAIN="mc-ru.org"
REPO="https://github.com/tearsmilk/honoka.git"
BRANCH="${BRANCH:-main}"
WEBROOT="/var/www/mc-ru"
APIDIR="/opt/mc-ru-api"
APIDATA="/var/lib/mc-ru"
EMAIL="${EMAIL:-}"   # для Let's Encrypt, можно оставить пустым

[ "$(id -u)" = 0 ] || { echo "Запусти от root: sudo bash deploy.sh"; exit 1; }

echo "==> Ставлю nginx, git, certbot"
apt-get update -q
DEBIAN_FRONTEND=noninteractive apt-get install -y -q nginx git rsync certbot python3-certbot-nginx python3-venv python3-pip

echo "==> Клонирую сайт в $WEBROOT"
if [ -d "$WEBROOT/.git" ]; then
  git -C "$WEBROOT" pull -q
else
  rm -rf "$WEBROOT"
  git clone -q -b "$BRANCH" "$REPO" "$WEBROOT"
fi
chown -R www-data:www-data "$WEBROOT"

echo "==> Ставлю API (аккаунты, скины, плащи) в $APIDIR"
mkdir -p "$APIDIR" "$APIDATA"
rsync -a --delete "$WEBROOT/server/" "$APIDIR/server/" 2>/dev/null || { rm -rf "$APIDIR/server"; cp -r "$WEBROOT/server" "$APIDIR/server"; }
[ -d "$APIDIR/venv" ] || python3 -m venv "$APIDIR/venv"
"$APIDIR/venv/bin/pip" install -q --upgrade pip
"$APIDIR/venv/bin/pip" install -q -r "$APIDIR/server/requirements.txt"
chown -R www-data:www-data "$APIDATA"
cp "$APIDIR/server/mc-ru-api.service" /etc/systemd/system/mc-ru-api.service
systemctl daemon-reload
systemctl enable --now mc-ru-api
systemctl restart mc-ru-api

echo "==> Пишу конфиг nginx"
cat > /etc/nginx/sites-available/$DOMAIN <<NGINX
server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN www.$DOMAIN;
    root $WEBROOT;
    index index.html;

    location / {
        try_files \$uri \$uri/ =404;
    }

    location = /account {
        try_files /account.html =404;
    }

    location = /admin {
        try_files /admin.html =404;
    }

    location = /launcher {
        try_files /launcher.html =404;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8787/api/;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        client_max_body_size 8m;
    }

    location ~* \.(css|js|svg|png|jpg|webp|woff2)\$ {
        expires 7d;
        add_header Cache-Control "public";
    }

    location ~ /\.(git|github) { deny all; }
    location /server/ { deny all; }
}
NGINX
ln -sf /etc/nginx/sites-available/$DOMAIN /etc/nginx/sites-enabled/$DOMAIN
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl enable --now nginx
systemctl reload nginx

echo "==> Проверяю, что DNS смотрит на этот сервер"
MYIP=$(curl -s -4 https://ifconfig.me || true)
DNSIP=$(getent ahostsv4 "$DOMAIN" | awk '{print $1; exit}' || true)
if [ -n "$MYIP" ] && [ "$MYIP" != "$DNSIP" ]; then
  echo "!! $DOMAIN сейчас резолвится в '$DNSIP', а IP этого сервера '$MYIP'."
  echo "!! Пропиши A-записи @ и www на $MYIP у регистратора, подожди и запусти:"
  echo "   certbot --nginx -d $DOMAIN -d www.$DOMAIN"
  echo "Сайт уже доступен по http://$MYIP"
  exit 0
fi

echo "==> Выпускаю HTTPS-сертификат"
if [ -n "$EMAIL" ]; then
  certbot --nginx -n --agree-tos -m "$EMAIL" --redirect -d "$DOMAIN" -d "www.$DOMAIN"
else
  certbot --nginx -n --agree-tos --register-unsafely-without-email --redirect -d "$DOMAIN" -d "www.$DOMAIN"
fi

echo "==> Автообновление сайта из GitHub раз в 5 минут (API перезапускается, если изменился server/)"
cat > /usr/local/bin/mc-ru-update <<UPD
#!/usr/bin/env bash
cd $WEBROOT || exit 1
before=\$(git rev-parse HEAD)
git pull -q || exit 1
if ! git diff --quiet "\$before" HEAD -- server/; then
  rsync -a --delete $WEBROOT/server/ $APIDIR/server/
  $APIDIR/venv/bin/pip install -q -r $APIDIR/server/requirements.txt
  systemctl restart mc-ru-api
fi
UPD
chmod +x /usr/local/bin/mc-ru-update
( crontab -l 2>/dev/null | grep -v "$WEBROOT" | grep -v mc-ru-update; echo "*/5 * * * * /usr/local/bin/mc-ru-update" ) | crontab -

echo
echo "Готово: https://$DOMAIN"
