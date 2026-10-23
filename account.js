// Личный кабинет: 3D-превью, загрузка скина/плаща (HD, анимация), смена пароля.
(async () => {
  const $ = s => document.querySelector(s);
  const $$ = s => [...document.querySelectorAll(s)];

  if (!mcru.token()) { sessionStorage.setItem('mcru_next', '/account'); location.replace('/#login'); return; }
  let user = await mcru.me();
  if (!user) { sessionStorage.setItem('mcru_next', '/account'); location.replace('/#login'); return; }

  $('[data-loading]').hidden = true;
  $('[data-acc]').hidden = false;

  // ---------- 3D viewer ----------
  const canvas = $('#viewer');
  let viewer = null;
  if (window.skinview3d) {
    viewer = new skinview3d.SkinViewer({ canvas, width: canvas.width, height: canvas.height });
    viewer.autoRotate = true;
    viewer.autoRotateSpeed = 0.6;
    viewer.animation = new skinview3d.WalkingAnimation();
    viewer.animation.speed = 0.8;
    viewer.zoom = 0.85;
    viewer.camera.position.set(28, 10, 48);
    viewer.controls.enableZoom = true;
  }
  $$('[data-anim]').forEach(b => b.addEventListener('click', () => {
    if (!viewer) return;
    const k = b.dataset.anim;
    viewer.animation = k === 'walk' ? new skinview3d.WalkingAnimation() : k === 'run' ? new skinview3d.RunningAnimation() : new skinview3d.IdleAnimation();
  }));
  $('[data-rotate]').addEventListener('change', e => { if (viewer) viewer.autoRotate = e.target.checked; });

  // анимированные текстуры: рисуем кадр на canvas и подсовываем viewer'у
  const anim = { skin: null, cape: null };
  function stopAnim(kind) { if (anim[kind]) { clearInterval(anim[kind].timer); anim[kind] = null; } }

  function loadImage(url) {
    return new Promise((res, rej) => { const im = new Image(); im.crossOrigin = 'anonymous'; im.onload = () => res(im); im.onerror = rej; im.src = url; });
  }

  async function showTexture(kind, info, model) {
    stopAnim(kind);
    if (!viewer) return;
    if (!info) {
      if (kind === 'skin') await viewer.loadSkin(defaultSkin(), { model: model === 'slim' ? 'slim' : 'default' });
      else viewer.loadCape(null);
      return;
    }
    const im = await loadImage(info.url + '?t=' + info.hash.slice(0, 8));
    const fw = info.width, fh = info.height;
    const c = document.createElement('canvas');
    c.width = fw; c.height = fh;
    const ctx = c.getContext('2d');
    const draw = i => { ctx.clearRect(0, 0, fw, fh); ctx.drawImage(im, 0, i * fh, fw, fh, 0, 0, fw, fh); };
    draw(0);
    if (kind === 'skin') await viewer.loadSkin(c, { model: model === 'slim' ? 'slim' : 'default' });
    else await viewer.loadCape(c);
    if (info.frames > 1) {
      let i = 0;
      anim[kind] = { timer: setInterval(async () => {
        i = (i + 1) % info.frames;
        draw(i);
        if (kind === 'skin') await viewer.loadSkin(c, { model: model === 'slim' ? 'slim' : 'default' });
        else await viewer.loadCape(c);
      }, Math.round(1000 / info.fps)) };
    }
  }

  function defaultSkin() {
    // простой «Стив»: серо-синий, чтобы не тянуть лишние файлы
    const c = document.createElement('canvas'); c.width = 64; c.height = 64;
    const x = c.getContext('2d');
    x.fillStyle = '#5c8ad9'; x.fillRect(0, 0, 64, 64);
    x.fillStyle = '#d9a066'; x.fillRect(8, 8, 8, 8); x.fillRect(40, 20, 4, 12); x.fillRect(32, 52, 4, 12);
    x.fillStyle = '#3a2a1a'; x.fillRect(8, 8, 8, 2);
    x.fillStyle = '#3f3f3f'; x.fillRect(0, 20, 16, 12); x.fillRect(16, 52, 16, 12);
    return c;
  }

  // ---------- рендер данных пользователя ----------
  function badge(t, cls = '') { return `<span class="badge ${cls}">${t}</span>`; }
  function render() {
    $$('[data-name]').forEach(e => e.textContent = user.username);
    $$('[data-uuid]').forEach(e => e.textContent = user.uuid);
    $('[data-email]').textContent = user.email;
    $('[data-model-label]').textContent = user.model === 'slim' ? 'Slim' : 'Classic';
    $(`input[name=model][value=${user.model}]`).checked = true;
    const b = [];
    if (user.skin) {
      b.push(badge(`Скин ${user.skin.width}×${user.skin.height}`));
      if (user.skin.hd) b.push(badge('HD', 'badge--hd'));
      if (user.skin.animated) b.push(badge(`Анимация · ${user.skin.frames} кадров · ${user.skin.fps} fps`, 'badge--anim'));
    } else b.push(badge('Скин не загружен', 'badge--muted'));
    if (user.cape) {
      b.push(badge(`Плащ ${user.cape.width}×${user.cape.height}`));
      if (user.cape.hd) b.push(badge('HD', 'badge--hd'));
      if (user.cape.animated) b.push(badge(`Анимация · ${user.cape.frames} кадров`, 'badge--anim'));
    }
    $('[data-badges]').innerHTML = b.join('');
    for (const kind of ['skin', 'cape']) {
      const dl = $(`[data-download="${kind}"]`);
      dl.hidden = !user[kind];
      if (user[kind]) { dl.href = user[kind].url; dl.download = `${user.username}-${kind}.png`; }
      $(`[data-delete="${kind}"]`).disabled = !user[kind];
    }
    showTexture('skin', user.skin, user.model);
    showTexture('cape', user.cape);
    const auth = $('[data-auth]');
    if (auth) auth.innerHTML = mcru.userChip(user);
    const adminLink = $('[data-admin-link]');
    if (adminLink) adminLink.hidden = !user.is_admin;
  }
  render();

  // ---------- загрузка файлов ----------
  const picked = { skin: null, cape: null };

  async function inspect(kind, file) {
    // показать поле fps, если это PNG-полоса кадров
    const row = $(`[data-fps-row="${kind}"]`);
    row.hidden = true;
    if (file.type !== 'image/png') return;
    try {
      const im = await loadImage(URL.createObjectURL(file));
      const fh = kind === 'skin' ? im.width : im.width / 2;
      const legacy = kind === 'skin' && im.height % im.width !== 0 && im.height % (im.width / 2) === 0;
      const frames = im.height / (legacy ? im.width / 2 : fh);
      row.hidden = !(Number.isInteger(frames) && frames >= 2);
    } catch { /* сервер скажет, что не так */ }
  }

  function pick(kind, file) {
    picked[kind] = file;
    $(`[data-fname="${kind}"]`).textContent = file ? `${file.name} · ${(file.size / 1024).toFixed(0)} КБ` : '';
    $(`[data-upload="${kind}"]`).disabled = !file;
    $(`[data-err="${kind}"]`).textContent = '';
    if (file) inspect(kind, file);
  }

  for (const kind of ['skin', 'cape']) {
    const drop = $(`[data-drop="${kind}"]`);
    const input = $(`[data-file="${kind}"]`);
    drop.addEventListener('click', () => input.click());
    input.addEventListener('change', () => pick(kind, input.files[0] || null));
    ['dragenter', 'dragover'].forEach(ev => drop.addEventListener(ev, e => { e.preventDefault(); drop.classList.add('drop--over'); }));
    ['dragleave', 'drop'].forEach(ev => drop.addEventListener(ev, e => { e.preventDefault(); drop.classList.remove('drop--over'); }));
    drop.addEventListener('drop', e => { const f = e.dataTransfer.files[0]; if (f) pick(kind, f); });

    $(`[data-upload="${kind}"]`).addEventListener('click', async e => {
      const btn = e.currentTarget, err = $(`[data-err="${kind}"]`);
      if (!picked[kind]) return;
      const fd = new FormData();
      fd.append('file', picked[kind]);
      if (!$(`[data-fps-row="${kind}"]`).hidden) fd.append('fps', $(`[data-fps="${kind}"]`).value || '10');
      if (kind === 'skin') fd.append('model', $('input[name=model]:checked').value);
      btn.disabled = true; btn.textContent = 'Загружаем…'; err.textContent = '';
      try {
        user = await mcru.api(`/account/${kind}`, { method: 'POST', form: fd });
        pick(kind, null);
        render();
      } catch (ex) { err.textContent = ex.message; btn.disabled = false; }
      finally { btn.textContent = kind === 'skin' ? 'Загрузить скин' : 'Загрузить плащ'; }
    });

    $(`[data-delete="${kind}"]`).addEventListener('click', async () => {
      if (!user[kind] || !confirm(kind === 'skin' ? 'Удалить скин?' : 'Удалить плащ?')) return;
      try { user = await mcru.api(`/account/${kind}`, { method: 'DELETE' }); render(); }
      catch (ex) { $(`[data-err="${kind}"]`).textContent = ex.message; }
    });
  }

  // модель без перезагрузки скина
  $$('input[name=model]').forEach(r => r.addEventListener('change', async () => {
    if (picked.skin) return; // уйдёт вместе со скином
    try { user = await mcru.api('/account/model', { method: 'POST', json: { model: r.value } }); render(); }
    catch (ex) { $('[data-err="skin"]').textContent = ex.message; }
  }));

  // ---------- аккаунт ----------
  $('[data-copy-uuid]').addEventListener('click', async e => {
    try { await navigator.clipboard.writeText(user.uuid); e.target.textContent = 'скопировано'; setTimeout(() => e.target.textContent = 'копировать', 1500); } catch {}
  });
  $$('[data-copy-text]').forEach(pre => pre.addEventListener('click', async () => {
    try { await navigator.clipboard.writeText(pre.textContent.trim()); pre.classList.add('code--copied'); setTimeout(() => pre.classList.remove('code--copied'), 1200); } catch {}
  }));
  $('[data-pass-form]').addEventListener('submit', async e => {
    e.preventDefault();
    const f = e.target, err = $('[data-err="pass"]'), ok = $('[data-ok="pass"]');
    err.textContent = ''; ok.textContent = '';
    try {
      await mcru.api('/auth/password', { method: 'POST', json: Object.fromEntries(new FormData(f).entries()) });
      ok.textContent = 'Пароль изменён. В лаунчере нужно войти заново.';
      f.reset();
    } catch (ex) { err.textContent = ex.message; }
  });
  $('[data-logout]').addEventListener('click', () => { mcru.setToken(''); location.href = '/'; });
})();
