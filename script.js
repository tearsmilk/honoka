// ===== НАСТРОЙКИ — поменяй под себя =====
const SERVER_IP = 'play.honoka.ru';
const SPLASHES = [
  'Теперь с кланами!',
  'Без доната на победу!',
  'Крипер, ау, минул!',
  '2 года без вайпа!',
  'Заходи, не бойся!',
  'TPS 20, как в сказке!',
  'Алмазы ещё остались!',
  'Жми F3 + A если лагает!',
];
// =========================================

// IP везде
document.querySelectorAll('[data-ip]').forEach(el => el.textContent = SERVER_IP);

// Случайный сплэш
const splash = document.querySelector('[data-splash]');
if (splash) splash.textContent = SPLASHES[Math.floor(Math.random() * SPLASHES.length)];

// Копирование IP
const toast = document.querySelector('[data-toast]');
document.querySelectorAll('[data-copy]').forEach(btn => {
  btn.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(SERVER_IP);
    } catch {
      const ta = document.createElement('textarea');
      ta.value = SERVER_IP; document.body.appendChild(ta); ta.select();
      document.execCommand('copy'); ta.remove();
    }
    toast.classList.add('toast--show');
    setTimeout(() => toast.classList.remove('toast--show'), 1800);
  });
});

// Статус сервера (mcsrvstat.us — бесплатный публичный API)
const status = document.querySelector('[data-status]');
if (status) {
  const text = status.querySelector('.status__text');
  fetch(`https://api.mcsrvstat.us/3/${SERVER_IP}`)
    .then(r => r.json())
    .then(d => {
      if (d.online) {
        status.classList.add('status--online');
        text.textContent = `Онлайн: ${d.players?.online ?? 0} / ${d.players?.max ?? '?'}`;
      } else {
        status.classList.add('status--offline');
        text.textContent = 'Сервер оффлайн';
      }
    })
    .catch(() => { text.textContent = 'Статус недоступен'; });
}

// Счётчики в "О сервере"
const counters = document.querySelectorAll('[data-count]');
const io = new IntersectionObserver(entries => {
  entries.forEach(e => {
    if (!e.isIntersecting) return;
    const el = e.target, target = +el.dataset.count, dur = 1200, start = performance.now();
    const tick = now => {
      const p = Math.min(1, (now - start) / dur);
      el.textContent = Math.floor(target * (1 - Math.pow(1 - p, 3)));
      if (p < 1) requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
    io.unobserve(el);
  });
}, { threshold: 0.5 });
counters.forEach(c => io.observe(c));

// Бургер-меню
const burger = document.querySelector('[data-burger]');
const nav = document.querySelector('.nav');
burger?.addEventListener('click', () => nav.classList.toggle('nav--open'));
nav?.querySelectorAll('a').forEach(a => a.addEventListener('click', () => nav.classList.remove('nav--open')));

// Год в футере
document.querySelector('[data-year]').textContent = new Date().getFullYear();
