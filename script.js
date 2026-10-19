// ===== НАСТРОЙКИ — поменяй под себя =====
const SPLASHES = [
  'Привет, крафтер!',
  'Без срачей ваниль vs моды!',
  'Крипер, ау, минул!',
  'Гайды пишут люди!',
  'Заходи, не бойся!',
  'Новичкам тут рады!',
  'Алмазы ещё остались!',
  'Жми F3 + A если лагает!',
];
// =========================================

// Случайный сплэш
const splash = document.querySelector('[data-splash]');
if (splash) splash.textContent = SPLASHES[Math.floor(Math.random() * SPLASHES.length)];

// Счётчики в "О нас"
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
