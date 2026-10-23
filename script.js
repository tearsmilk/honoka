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
  'HD-скины — бесплатно!',
];
// =========================================

// ---------- общее: API и сессия (используется и на /account) ----------
const API = '/api';
const TOKEN_KEY = 'mcru_token';
const mcru = {
  token() { return localStorage.getItem(TOKEN_KEY) || ''; },
  setToken(t) { t ? localStorage.setItem(TOKEN_KEY, t) : localStorage.removeItem(TOKEN_KEY); },
  async api(path, { method = 'GET', json, form, headers = {} } = {}) {
    const h = { ...headers };
    const t = mcru.token();
    if (t) h.Authorization = 'Bearer ' + t;
    let body;
    if (json !== undefined) { h['Content-Type'] = 'application/json'; body = JSON.stringify(json); }
    else if (form) body = form;
    let res;
    try { res = await fetch(API + path, { method, headers: h, body }); }
    catch { throw new Error('Нет связи с сервером. Проверь интернет и попробуй ещё раз.'); }
    const ct = res.headers.get('content-type') || '';
    const data = ct.includes('json') ? await res.json() : null;
    if (!res.ok) {
      if (res.status === 401 && path.startsWith('/auth/me')) mcru.setToken('');
      throw new Error((data && data.error) || `Ошибка ${res.status}`);
    }
    return data;
  },
  async me() {
    if (!mcru.token()) return null;
    try { return await mcru.api('/auth/me'); } catch { return null; }
  },
  headUrl(name, size = 64) { return `${API}/users/${encodeURIComponent(name)}/skin.png?frame=0&size=${size}`; },
};
window.mcru = mcru;

// ---------- главная ----------
const splash = document.querySelector('[data-splash]');
if (splash) splash.textContent = SPLASHES[Math.floor(Math.random() * SPLASHES.length)];

const counters = document.querySelectorAll('[data-count]');
if (counters.length) {
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
}

const burger = document.querySelector('[data-burger]');
const nav = document.querySelector('.nav');
burger?.addEventListener('click', () => nav.classList.toggle('nav--open'));
nav?.querySelectorAll('a').forEach(a => a.addEventListener('click', () => nav.classList.remove('nav--open')));

const year = document.querySelector('[data-year]');
if (year) year.textContent = new Date().getFullYear();

// ---------- вход / регистрация ----------
const modal = document.querySelector('[data-modal]');
const authBox = document.querySelector('[data-auth]');

function headHtml(name, size) {
  // лицо 8×8 + слой шапки из текстуры 64×64, масштаб через background-size
  const url = mcru.headUrl(name);
  return `<span class="head" style="--head:${size}px">
    <span class="head__layer" style="background-image:url('${url}')"></span>
    <span class="head__layer head__layer--hat" style="background-image:url('${url}')"></span></span>`;
}
window.headHtml = headHtml;

function renderAuth(user) {
  if (!authBox) return;
  if (user) {
    authBox.innerHTML = `<a class="userchip" href="/account" title="Личный кабинет">${headHtml(user.username, 28)}<span>${user.username}</span></a>`;
  } else {
    authBox.innerHTML = `<button class="btn btn--small" data-open-login>Войти</button>`;
  }
}

function openModal(tab = 'login') {
  if (!modal) return;
  modal.hidden = false;
  document.body.classList.add('no-scroll');
  switchTab(tab);
  modal.querySelector(`[data-form="${tab}"] input`)?.focus();
}
function closeModal() {
  if (!modal) return;
  modal.hidden = true;
  document.body.classList.remove('no-scroll');
  if (location.hash === '#login' || location.hash === '#register') history.replaceState(null, '', ' ');
}
function switchTab(tab) {
  modal.querySelectorAll('[data-tab]').forEach(b => b.classList.toggle('tab--on', b.dataset.tab === tab));
  modal.querySelectorAll('[data-form]').forEach(f => { f.hidden = f.dataset.form !== tab; f.querySelector('[data-err]').textContent = ''; });
}

if (modal) {
  document.addEventListener('click', e => {
    if (e.target.closest('[data-open-login]')) openModal('login');
    if (e.target.closest('[data-modal-close]') || e.target === modal) closeModal();
    const tab = e.target.closest('[data-tab]');
    if (tab) switchTab(tab.dataset.tab);
  });
  document.addEventListener('keydown', e => { if (e.key === 'Escape' && !modal.hidden) closeModal(); });

  modal.querySelectorAll('[data-form]').forEach(form => {
    form.addEventListener('submit', async e => {
      e.preventDefault();
      const err = form.querySelector('[data-err]');
      const btn = form.querySelector('button[type=submit]');
      const fd = Object.fromEntries(new FormData(form).entries());
      err.textContent = '';
      btn.disabled = true;
      try {
        const r = await mcru.api(form.dataset.form === 'login' ? '/auth/login' : '/auth/register', { method: 'POST', json: fd });
        mcru.setToken(r.token);
        closeModal();
        const next = sessionStorage.getItem('mcru_next');
        sessionStorage.removeItem('mcru_next');
        if (next) location.href = next; else renderAuth(r.user);
      } catch (ex) {
        err.textContent = ex.message;
      } finally {
        btn.disabled = false;
      }
    });
  });

  // кнопка «Открыть кабинет» без входа → сначала вход
  document.querySelector('[data-account-link]')?.addEventListener('click', e => {
    if (!mcru.token()) { e.preventDefault(); sessionStorage.setItem('mcru_next', '/account'); openModal('login'); }
  });

  mcru.me().then(renderAuth);
  if (location.hash === '#login') openModal('login');
  if (location.hash === '#register') openModal('register');
  window.addEventListener('hashchange', () => {
    if (location.hash === '#login') openModal('login');
    if (location.hash === '#register') openModal('register');
  });
}
