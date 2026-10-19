# Honoka — фейм-страница Minecraft сервера

Статичный сайт: `index.html` + `style.css` + `script.js`. Никакой сборки.

## Деплой
Закинь содержимое репы в корень веб-сервера (nginx / apache / любой хостинг со статикой).
Пример для nginx:

```nginx
server {
    listen 80;
    server_name honoka.ru www.honoka.ru;
    root /var/www/honoka;
    index index.html;
}
```

## Что поменять под себя
- `script.js` → `SERVER_IP` (адрес сервера) и `SPLASHES` (жёлтые надписи как в меню майна).
- `index.html` → ссылки на Discord / Telegram / VK / YouTube в блоке `#community`, тексты, правила.
- Онлайн тянется с `api.mcsrvstat.us` по IP — ничего настраивать не нужно.
