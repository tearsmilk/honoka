// Админка: серверы для списка в лаунчере + пользователи.
(async () => {
  const $ = s => document.querySelector(s);
  const $$ = s => [...document.querySelectorAll(s)];
  const esc = mcru.esc;

  if (!mcru.token()) { sessionStorage.setItem('mcru_next', '/admin'); location.replace('/#login'); return; }
  const user = await mcru.me();
  $('[data-loading]').hidden = true;
  if (!user) { sessionStorage.setItem('mcru_next', '/admin'); location.replace('/#login'); return; }
  const auth = $('[data-auth]');
  if (auth) auth.innerHTML = mcru.userChip(user);
  if (!user.is_admin) { $('[data-denied]').hidden = false; return; }
  $('[data-adm]').hidden = false;

  // ---------- вкладки ----------
  $$('[data-atab]').forEach(b => b.addEventListener('click', () => {
    $$('[data-atab]').forEach(x => x.classList.toggle('tab--on', x === b));
    $$('[data-apane]').forEach(p => p.hidden = p.dataset.apane !== b.dataset.atab);
    if (b.dataset.atab === 'users' && !usersLoaded) loadUsers();
    if (b.dataset.atab === 'cosmetics' && !cosLoaded) loadCosmetics();
    if (b.dataset.atab === 'rurs' && !rursLoaded) loadRurs();
  }));

  // ---------- серверы ----------
  let servers = [];
  const list = $('[data-servers]');
  const form = $('[data-server-form]');
  const err = k => $(`[data-err="${k}"]`);

  function rowHtml(s, i) {
    const icon = s.icon_url ? `<img class="srv__icon" src="${esc(s.icon_url)}?v=${Date.now() % 1e6}" alt="">` : `<div class="srv__icon"></div>`;
    return `<div class="srv__row ${s.enabled ? '' : 'srv__row--off'}" data-id="${s.id}">
      ${icon}
      <div>
        <div class="srv__name">${esc(s.name)}<small>${esc(s.version || '')}</small></div>
        <div class="srv__motd">${mcru.motdHtml(s.motd)}</div>
        <div class="srv__addr">${esc(s.ip)}${s.port !== 25565 ? ':' + s.port : ''}</div>
      </div>
      <div class="srv__act">
        <button class="btn btn--tiny" data-act="up" ${i === 0 ? 'disabled' : ''}>↑</button>
        <button class="btn btn--tiny" data-act="down" ${i === servers.length - 1 ? 'disabled' : ''}>↓</button>
        <button class="btn btn--tiny" data-act="toggle">${s.enabled ? 'Выключить' : 'Включить'}</button>
        <button class="btn btn--tiny" data-act="edit">Изменить</button>
        <button class="btn btn--tiny btn--red" data-act="del">Удалить</button>
      </div>
    </div>`;
  }
  function renderServers() {
    list.innerHTML = servers.length ? servers.map(rowHtml).join('') : '<p class="form__hint">Серверов пока нет — добавь первый ниже.</p>';
  }
  async function loadServers() {
    try { servers = (await mcru.api('/admin/servers')).servers; renderServers(); }
    catch (ex) { err('list').textContent = ex.message; }
  }
  await loadServers();

  list.addEventListener('click', async e => {
    const btn = e.target.closest('[data-act]');
    if (!btn) return;
    const row = btn.closest('[data-id]');
    const id = +row.dataset.id;
    const s = servers.find(x => x.id === id);
    const i = servers.indexOf(s);
    err('list').textContent = '';
    try {
      if (btn.dataset.act === 'up' || btn.dataset.act === 'down') {
        const j = btn.dataset.act === 'up' ? i - 1 : i + 1;
        if (j < 0 || j >= servers.length) return;
        const ids = servers.map(x => x.id);
        [ids[i], ids[j]] = [ids[j], ids[i]];
        servers = (await mcru.api('/admin/servers/reorder', { method: 'POST', json: { ids } })).servers;
        renderServers();
      } else if (btn.dataset.act === 'toggle') {
        const upd = await mcru.api(`/admin/servers/${id}`, { method: 'PUT', json: { enabled: !s.enabled } });
        servers[i] = upd; renderServers();
      } else if (btn.dataset.act === 'del') {
        if (!confirm(`Удалить сервер «${s.name}»?`)) return;
        await mcru.api(`/admin/servers/${id}`, { method: 'DELETE' });
        servers.splice(i, 1); renderServers();
        if (+form.id.value === id) resetForm();
      } else if (btn.dataset.act === 'edit') {
        fillForm(s);
        form.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
    } catch (ex) { err('list').textContent = ex.message; }
  });

  // ---------- форма + превью ----------
  const prev = { icon: $('[data-prev-icon]'), name: $('[data-prev-name]'), motd: $('[data-prev-motd]') };
  const EMPTY_ICON = prev.icon.src;
  function updatePreview() {
    prev.name.textContent = form.name.value.trim() || 'Название сервера';
    prev.motd.innerHTML = mcru.motdHtml(form.motd.value) || 'Описание';
  }
  form.addEventListener('input', updatePreview);
  // «host:port» в поле адреса → порт уезжает в своё поле
  form.ip.addEventListener('input', () => {
    const m = form.ip.value.trim().match(/^(.*):(\d{1,5})$/);
    if (m) { form.ip.value = m[1]; form.port.value = m[2]; }
  });
  form.icon.addEventListener('change', () => {
    const f = form.icon.files[0];
    prev.icon.src = f ? URL.createObjectURL(f) : (currentIcon || EMPTY_ICON);
  });
  let currentIcon = null;

  function fillForm(s) {
    form.id.value = s.id;
    form.name.value = s.name; form.ip.value = s.ip; form.port.value = s.port;
    form.version.value = s.version; form.motd.value = s.motd; form.enabled.checked = s.enabled;
    form.icon.value = '';
    currentIcon = s.icon_url;
    prev.icon.src = s.icon_url || EMPTY_ICON;
    $('[data-form-title]').textContent = `Изменить: ${s.name}`;
    $('[data-submit]').textContent = 'Сохранить';
    $('[data-cancel]').hidden = false;
    $('[data-icon-delete]').hidden = !s.icon_url;
    updatePreview();
  }
  function resetForm() {
    form.reset();
    form.id.value = '';
    currentIcon = null;
    prev.icon.src = EMPTY_ICON;
    $('[data-form-title]').textContent = 'Добавить сервер';
    $('[data-submit]').textContent = 'Добавить';
    $('[data-cancel]').hidden = true;
    $('[data-icon-delete]').hidden = true;
    err('form').textContent = '';
    updatePreview();
  }
  $('[data-cancel]').addEventListener('click', resetForm);
  $('[data-icon-delete]').addEventListener('click', async () => {
    const id = +form.id.value;
    if (!id) return;
    try {
      const upd = await mcru.api(`/admin/servers/${id}/icon`, { method: 'DELETE' });
      servers[servers.findIndex(x => x.id === id)] = upd; renderServers(); fillForm(upd);
    } catch (ex) { err('form').textContent = ex.message; }
  });

  form.addEventListener('submit', async e => {
    e.preventDefault();
    err('form').textContent = '';
    const btn = $('[data-submit]');
    btn.disabled = true;
    const id = +form.id.value;
    const json = { name: form.name.value, ip: form.ip.value, port: +form.port.value || 25565, version: form.version.value,
      motd: form.motd.value, enabled: form.enabled.checked };
    try {
      let s = id ? await mcru.api(`/admin/servers/${id}`, { method: 'PUT', json }) : await mcru.api('/admin/servers', { method: 'POST', json });
      if (form.icon.files[0]) {
        const fd = new FormData(); fd.append('file', form.icon.files[0]);
        s = await mcru.api(`/admin/servers/${s.id}/icon`, { method: 'POST', form: fd });
      }
      const k = servers.findIndex(x => x.id === s.id);
      if (k >= 0) servers[k] = s; else servers.push(s);
      renderServers();
      resetForm();
    } catch (ex) { err('form').textContent = ex.message; }
    finally { btn.disabled = false; }
  });
  updatePreview();

  // ---------- косметика (гардероб мода) ----------
  const CATS = { hats: 'Шапки', wings: 'Крылья', back: 'Спина', pets: 'Питомцы', arms: 'Руки', legs: 'Ноги', auras: 'Ауры', costumes: 'Костюмы' };
  let cosmetics = [], cosLoaded = false;
  const clist = $('[data-cosmetics]');
  const cform = $('[data-cosmetic-form]');

  function cosHtml(c, i) {
    const vars = c.variants.map(v => `<img class="vars__thumb" src="${esc(v.texture_url)}" title="${esc(v.name)}" alt="">`).join('');
    return `<div class="srv__row ${c.enabled ? '' : 'srv__row--off'}" data-cid="${c.id}">
      <div class="srv__icon cos__cat">${esc(CATS[c.category] || c.category)}</div>
      <div>
        <div class="srv__name">${esc(c.name)}<small>#${c.id} · ${mcru.rurs(c.price)}${c.has_model ? '' : ' · <span style="color:#f66">без модели</span>'}</small></div>
        <div class="srv__motd">масштаб ${c.scale} · сдвиг ${c.off_x} / ${c.off_y} / ${c.off_z}${c.variants.length ? ` · вариантов: ${c.variants.length}` : ''}</div>
        <div class="vars">${vars}</div>
      </div>
      <div class="srv__act">
        <button class="btn btn--tiny" data-cact="up" ${i === 0 ? 'disabled' : ''}>↑</button>
        <button class="btn btn--tiny" data-cact="down" ${i === cosmetics.length - 1 ? 'disabled' : ''}>↓</button>
        <button class="btn btn--tiny" data-cact="toggle">${c.enabled ? 'Выключить' : 'Включить'}</button>
        <button class="btn btn--tiny" data-cact="edit">Изменить</button>
        <button class="btn btn--tiny btn--red" data-cact="del">Удалить</button>
      </div>
    </div>`;
  }
  function renderCosmetics() {
    clist.innerHTML = cosmetics.length ? cosmetics.map(cosHtml).join('') : '<p class="form__hint">Косметики пока нет — добавь первую ниже.</p>';
  }
  async function loadCosmetics() {
    cosLoaded = true;
    try { cosmetics = (await mcru.api('/admin/cosmetics')).cosmetics; renderCosmetics(); }
    catch (ex) { err('clist').textContent = ex.message; }
  }
  const curCos = () => cosmetics.find(x => x.id === +cform.id.value);
  function fillCos(c) {
    cform.id.value = c.id;
    cform.name.value = c.name; cform.category.value = c.category; cform.price.value = c.price; cform.sort.value = c.sort;
    cform.scale.value = c.scale; cform.off_x.value = c.off_x; cform.off_y.value = c.off_y; cform.off_z.value = c.off_z;
    cform.enabled.checked = c.enabled; cform.model.value = '';
    $('[data-cform-title]').textContent = `Изменить: ${c.name}`;
    $('[data-csubmit]').textContent = 'Сохранить';
    $('[data-ccancel]').hidden = false;
    renderVars(c);
  }
  function resetCos() {
    cform.reset(); cform.id.value = '';
    $('[data-cform-title]').textContent = 'Добавить косметику';
    $('[data-csubmit]').textContent = 'Добавить';
    $('[data-ccancel]').hidden = true;
    $('[data-variants]').hidden = true;
    err('cform').textContent = '';
  }
  function renderVars(c) {
    $('[data-variants]').hidden = false;
    $('[data-vars-list]').innerHTML = c.variants.length ? c.variants.map(v => `<div class="vars__row" data-vid="${v.id}">
      <img class="vars__thumb" src="${esc(v.texture_url)}" alt=""><span>${esc(v.name)}</span>
      <button class="btn btn--tiny btn--red" type="button" data-var-del>Удалить</button></div>`).join('') : '<p class="form__hint">Вариантов нет — косметика рисуется своей текстурой из модели.</p>';
  }
  $('[data-ccancel]').addEventListener('click', resetCos);
  clist.addEventListener('click', async e => {
    const btn = e.target.closest('[data-cact]');
    if (!btn) return;
    const id = +btn.closest('[data-cid]').dataset.cid;
    const c = cosmetics.find(x => x.id === id), i = cosmetics.indexOf(c);
    err('clist').textContent = '';
    try {
      if (btn.dataset.cact === 'up' || btn.dataset.cact === 'down') {
        const j = btn.dataset.cact === 'up' ? i - 1 : i + 1;
        if (j < 0 || j >= cosmetics.length) return;
        const ids = cosmetics.map(x => x.id);
        [ids[i], ids[j]] = [ids[j], ids[i]];
        cosmetics = (await mcru.api('/admin/cosmetics/reorder', { method: 'POST', json: { ids } })).cosmetics;
        renderCosmetics();
      } else if (btn.dataset.cact === 'toggle') {
        cosmetics[i] = await mcru.api(`/admin/cosmetics/${id}`, { method: 'PUT', json: { enabled: !c.enabled } }); renderCosmetics();
      } else if (btn.dataset.cact === 'del') {
        if (!confirm(`Удалить «${c.name}»? У купивших она тоже пропадёт.`)) return;
        await mcru.api(`/admin/cosmetics/${id}`, { method: 'DELETE' });
        cosmetics.splice(i, 1); renderCosmetics();
        if (+cform.id.value === id) resetCos();
      } else if (btn.dataset.cact === 'edit') {
        fillCos(c); cform.scrollIntoView({ behavior: 'smooth', block: 'start' });
      }
    } catch (ex) { err('clist').textContent = ex.message; }
  });
  cform.addEventListener('submit', async e => {
    e.preventDefault();
    err('cform').textContent = '';
    const btn = $('[data-csubmit]');
    btn.disabled = true;
    const id = +cform.id.value;
    const json = { name: cform.name.value, category: cform.category.value, price: +cform.price.value || 0, sort: +cform.sort.value || 0,
      scale: +cform.scale.value || 1, off_x: +cform.off_x.value || 0, off_y: +cform.off_y.value || 0, off_z: +cform.off_z.value || 0,
      enabled: cform.enabled.checked };
    try {
      let c = id ? await mcru.api(`/admin/cosmetics/${id}`, { method: 'PUT', json }) : await mcru.api('/admin/cosmetics', { method: 'POST', json });
      if (cform.model.files[0]) {
        const fd = new FormData(); fd.append('file', cform.model.files[0]);
        c = await mcru.api(`/admin/cosmetics/${c.id}/model`, { method: 'POST', form: fd });
      } else if (!c.has_model) err('cform').textContent = 'Добавлено, но без модели — загрузи .bbmodel через «Изменить».';
      const k = cosmetics.findIndex(x => x.id === c.id);
      if (k >= 0) cosmetics[k] = c; else cosmetics.push(c);
      renderCosmetics();
      if (id) fillCos(c); else if (c.has_model) resetCos(); else fillCos(c);
    } catch (ex) { err('cform').textContent = ex.message; }
    finally { btn.disabled = false; }
  });
  $('[data-var-add]').addEventListener('click', async () => {
    const c = curCos();
    if (!c) return;
    const f = cform.vfile.files[0];
    if (!f) { err('cform').textContent = 'Выбери PNG варианта'; return; }
    const fd = new FormData(); fd.append('file', f); fd.append('name', cform.vname.value);
    try {
      const upd = await mcru.api(`/admin/cosmetics/${c.id}/variants`, { method: 'POST', form: fd });
      cosmetics[cosmetics.indexOf(c)] = upd; renderCosmetics(); renderVars(upd);
      cform.vname.value = ''; cform.vfile.value = ''; err('cform').textContent = '';
    } catch (ex) { err('cform').textContent = ex.message; }
  });
  $('[data-vars-list]').addEventListener('click', async e => {
    const btn = e.target.closest('[data-var-del]');
    if (!btn) return;
    const vid = +btn.closest('[data-vid]').dataset.vid, c = curCos();
    try {
      const upd = await mcru.api(`/admin/variants/${vid}`, { method: 'DELETE' });
      cosmetics[cosmetics.indexOf(c)] = upd; renderCosmetics(); renderVars(upd);
    } catch (ex) { err('cform').textContent = ex.message; }
  });

  // ---------- руры ----------
  let rursLoaded = false;
  const rbox = $('[data-rusers]');
  async function loadRurs() {
    rursLoaded = true;
    err('rurs').textContent = '';
    try {
      const q = $('[data-rsearch]').value.trim();
      const { users } = await mcru.api(`/admin/users?q=${encodeURIComponent(q)}&limit=50`);
      rbox.innerHTML = users.length ? users.map(u => `<div class="usr__row usr__row--rurs" data-ruser="${esc(u.username)}">
        ${headHtml(u.username, 36)}
        <div><b>${esc(u.username)}</b> <span class="badge badge--hd" data-rbal>${mcru.rurs(u.balance)}</span>
          <small>${esc(u.email)} · № ${String(u.public_id).padStart(7, '0')}${u.mc_license_nick ? ' · лицензия ' + esc(u.mc_license_nick) : ''}</small></div>
        <span class="form__row" style="margin:0"><input class="input--short" type="number" value="100" data-rdelta>
          <button class="btn btn--tiny" data-rgive>Начислить</button></span>
      </div>`).join('') : '<p class="form__hint">Никого не нашли.</p>';
    } catch (ex) { err('rurs').textContent = ex.message; }
  }
  $('[data-rsearch-btn]').addEventListener('click', loadRurs);
  $('[data-rsearch]').addEventListener('keydown', e => { if (e.key === 'Enter') loadRurs(); });
  rbox.addEventListener('click', async e => {
    const btn = e.target.closest('[data-rgive]');
    if (!btn) return;
    const row = btn.closest('[data-ruser]');
    const delta = +row.querySelector('[data-rdelta]').value;
    if (!delta) return;
    err('rurs').textContent = '';
    try {
      const r = await mcru.api(`/admin/users/${encodeURIComponent(row.dataset.ruser)}/balance`, { method: 'POST', json: { delta } });
      row.querySelector('[data-rbal]').textContent = mcru.rurs(r.balance);
    } catch (ex) { err('rurs').textContent = ex.message; }
  });

  // ---------- пользователи ----------
  let usersLoaded = false;
  const ubox = $('[data-users]');
  async function loadUsers() {
    usersLoaded = true;
    err('users').textContent = '';
    try {
      const q = $('[data-usearch]').value.trim();
      const { users } = await mcru.api(`/admin/users?q=${encodeURIComponent(q)}&limit=100`);
      ubox.innerHTML = users.length ? users.map(u => `<div class="usr__row" data-user="${esc(u.username)}">
        ${headHtml(u.username, 36)}
        <div><b>${esc(u.username)}</b>${u.is_admin ? ' <span class="badge badge--hd">админ</span>' : ''}${u.has_skin ? ' <span class="badge badge--anim">скин</span>' : ''}
          <small>${esc(u.email)} · с ${new Date(u.created * 1000).toLocaleDateString('ru-RU')} · ${mcru.rurs(u.balance)}</small></div>
        <button class="btn btn--tiny" data-uadmin="${u.is_admin ? 0 : 1}" ${u.env_admin ? 'disabled title="Задан в MCRU_ADMINS"' : ''}>${u.is_admin ? 'Снять' : 'Сделать админом'}</button>
      </div>`).join('') : '<p class="form__hint">Никого не нашли.</p>';
    } catch (ex) { err('users').textContent = ex.message; }
  }
  $('[data-usearch-btn]').addEventListener('click', loadUsers);
  $('[data-usearch]').addEventListener('keydown', e => { if (e.key === 'Enter') loadUsers(); });
  ubox.addEventListener('click', async e => {
    const btn = e.target.closest('[data-uadmin]');
    if (!btn) return;
    const name = btn.closest('[data-user]').dataset.user;
    const flag = btn.dataset.uadmin === '1';
    if (!confirm(flag ? `Сделать ${name} администратором?` : `Снять права администратора с ${name}?`)) return;
    try { await mcru.api(`/admin/users/${encodeURIComponent(name)}/admin`, { method: 'POST', json: { is_admin: flag } }); loadUsers(); }
    catch (ex) { err('users').textContent = ex.message; }
  });
})();
