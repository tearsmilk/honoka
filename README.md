# mc-ru.org — фейм-страница сообщества

Статичный сайт русскоязычного Minecraft-комьюнити: `index.html` + `style.css` + `script.js`. Никакой сборки.

## Деплой
Закинь содержимое репы в корень веб-сервера (nginx / apache / любой хостинг со статикой).
Пример для nginx:

```nginx
server {
    listen 80;
    server_name mc-ru.org www.mc-ru.org;
    root /var/www/mc-ru;
    index index.html;
}
```

## Что поменять под себя
- `index.html` → ссылки на Discord / Telegram / VK / YouTube (в хиро и в блоке `#community`), цифры в блоке «О нас», тексты, правила.
- `script.js` → `SPLASHES` (жёлтые надписи как в меню майна).
