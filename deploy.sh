#!/usr/bin/env bash
# Разворачивает mc-ru.org на чистом Ubuntu/Debian VPS: nginx + сайт + HTTPS.
# Запуск от root:  bash deploy.sh
set -euo pipefail

DOMAIN="mc-ru.org"
REPO="https://github.com/tearsmilk/honoka.git"
BRANCH="${BRANCH:-main}"
WEBROOT="/var/www/mc-ru"
EMAIL="${EMAIL:-}"   # для Let's Encrypt, можно оставить пустым

[ "$(id -u)" = 0 ] || { echo "Запусти от root: sudo bash deploy.sh"; exit 1; }

echo "==> Ставлю nginx, git, certbot"
apt-get update -q
DEBIAN_FRONTEND=noninteractive apt-get install -y -q nginx git certbot python3-certbot-nginx

echo "==> Клонирую сайт в $WEBROOT"
if [ -d "$WEBROOT/.git" ]; then
  git -C "$WEBROOT" pull -q
else
  rm -rf "$WEBROOT"
  git clone -q -b "$BRANCH" "$REPO" "$WEBROOT"
fi
chown -R www-data:www-data "$WEBROOT"

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

    location ~* \.(css|js|svg|png|jpg|webp|woff2)\$ {
        expires 7d;
        add_header Cache-Control "public";
    }

    location ~ /\.(git|github) { deny all; }
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

echo "==> Автообновление сайта из GitHub раз в 5 минут"
( crontab -l 2>/dev/null | grep -v "$WEBROOT"; echo "*/5 * * * * git -C $WEBROOT pull -q" ) | crontab -

echo
echo "Готово: https://$DOMAIN"
