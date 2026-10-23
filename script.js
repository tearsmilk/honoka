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
  esc(s) { return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c])); },
  // MOTD с §-кодами Minecraft → HTML (цвета §0–§f, §l §o §n §m §k, §r сброс); текст сначала экранируется
  motdHtml(text) {
    const colors = { 0: '#000', 1: '#0000aa', 2: '#00aa00', 3: '#00aaaa', 4: '#aa0000', 5: '#aa00aa', 6: '#ffaa00', 7: '#aaa',
      8: '#555', 9: '#5555ff', a: '#55ff55', b: '#55ffff', c: '#ff5555', d: '#ff55ff', e: '#ffff55', f: '#fff' };
    let color = '', styles = new Set(), out = '', buf = '';
    const flush = () => {
      if (!buf) return;
      const st = [];
      if (color) st.push(`color:${color}`);
      if (styles.has('l')) st.push('font-weight:700');
      if (styles.has('o')) st.push('font-style:italic');
      if (styles.has('n')) st.push('text-decoration:underline');
      if (styles.has('m')) st.push('text-decoration:line-through');
      if (styles.has('k')) st.push('opacity:.5');
      out += st.length ? `<span style="${st.join(';')}">${mcru.esc(buf)}</span>` : mcru.esc(buf);
      buf = '';
    };
    const s = String(text ?? '');
    for (let i = 0; i < s.length; i++) {
      if ((s[i] === '§' || s[i] === '&') && i + 1 < s.length && /[0-9a-fk-or]/i.test(s[i + 1])) {
        const code = s[++i].toLowerCase();
        flush();
        if (code === 'r') { color = ''; styles.clear(); }
        else if (colors[code]) { color = colors[code]; styles.clear(); }
        else styles.add(code);
      } else if (s[i] === '\n') { flush(); out += '<br>'; }
      else buf += s[i];
    }
    flush();
    return out;
  },
  // валюта гардероба: 1 рур, 2 рура, 5 руров
  rurs(n) {
    n = Math.abs(Math.trunc(+n || 0));
    const m10 = n % 10, m100 = n % 100;
    const w = (m100 >= 11 && m100 <= 14) ? 'руров' : m10 === 1 ? 'рур' : (m10 >= 2 && m10 <= 4) ? 'рура' : 'руров';
    return `${n.toLocaleString('ru-RU')} ${w}`;
  },
  userChip(user) {
    const admin = user.is_admin ? `<a class="btn btn--small btn--admin" href="/admin" title="Админка">Админка</a>` : '';
    return `${admin}<a class="userchip" href="/account" title="Личный кабинет">${headHtml(user.username, 28)}<span>${user.username}</span></a>`;
  },
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
    authBox.innerHTML = mcru.userChip(user);
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
  // кнопки, ведущие на страницы «только для вошедших» (кабинет, админка)
  document.querySelectorAll('[data-need-login]').forEach(a => a.addEventListener('click', e => {
    if (!mcru.token()) { e.preventDefault(); sessionStorage.setItem('mcru_next', a.getAttribute('href')); openModal('login'); }
  }));
  if (location.hash === '#login') openModal('login');
  if (location.hash === '#register') openModal('register');
  window.addEventListener('hashchange', () => {
    if (location.hash === '#login') openModal('login');
    if (location.hash === '#register') openModal('register');
  });
}
