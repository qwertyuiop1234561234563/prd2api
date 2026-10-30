﻿(() => {
  'use strict';

  const $ = (sel, root = document) => root.querySelector(sel);
  const has = (sel) => !!$(sel);
  /** 按元素存在性绑定事件，跨页共用同一份脚本 */
  const on = (sel, ev, fn) => { const el = $(sel); if (el) el.addEventListener(ev, fn); return el; };
  const PAGE = document.body.dataset.page || 'workspace';
  const LS_KEY = 'prd2api.console.settings';
  const AUTH_KEY = 'prd2api.console.auth';
  const STATUS_TEXT = { INIT: 'QUEUED', PROCESS: 'RUNNING', SUCCESS: 'DONE', FAIL: 'FAILED' };

  const state = {
    settings: { base: 'http://localhost:8080', tenant: 'default', user: 'demo', role: 'USER', model: 'default', chunk: 'semantic' },
    token: '',
    user: null,
    vip: null,
    tasks: [],
    filter: '',
    selectedId: null,
    detail: null,
    fileMetaId: null,
    queueSig: '',
    queueLoading: false,
    queuePending: false,
    polling: null,
    detailPolling: null,
    detailSeq: 0,
    detailLoading: null,
    uploading: false,
    exporting: false,
    mutating: false,
    fufuPos: null,
    fcSessionId: '',
    fcTaskId: '',
    toastSig: '',
    toastAt: 0,
    couponLoading: false,
    couponBusy: new Set(),
    adminCoupons: [],
    plans: []
  };

  /* ── 星尘 / 音符粒子 ─────────────────────── */
  const canvas = $('#stars');
  const ctx = canvas ? canvas.getContext('2d') : null;
  const particles = [];
  const NOTES = ['♪', '♫', '✦', '·'];
  let dpr = 1;
  let rafId = 0;
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  function resizeCanvas() {
    if (!canvas || !ctx) return;
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.floor(innerWidth * dpr);
    canvas.height = Math.floor(innerHeight * dpr);
    canvas.style.width = innerWidth + 'px';
    canvas.style.height = innerHeight + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  function seedParticles() {
    particles.length = 0;
    const count = Math.max(26, Math.round(innerWidth / 46));
    for (let i = 0; i < count; i++) {
      particles.push({
        x: Math.random() * innerWidth,
        y: Math.random() * innerHeight,
        size: 6 + Math.random() * 13,
        vy: -.16 - Math.random() * .38,
        vx: (Math.random() - .5) * .18,
        alpha: .1 + Math.random() * .42,
        phase: Math.random() * Math.PI * 2,
        glyph: NOTES[Math.floor(Math.random() * NOTES.length)],
        hue: Math.random() > .62 ? '255,111,181' : '107,247,234'
      });
    }
  }

  function drawParticles(t) {
    if (!ctx) return;
    ctx.clearRect(0, 0, innerWidth, innerHeight);
    for (const p of particles) {
      p.y += p.vy;
      p.x += p.vx + Math.sin((t / 2600) + p.phase) * .28;
      if (p.y < -30) { p.y = innerHeight + 24; p.x = Math.random() * innerWidth; }
      if (p.x < -30) p.x = innerWidth + 20;
      if (p.x > innerWidth + 30) p.x = -20;
      const twinkle = .55 + Math.sin(t / 700 + p.phase) * .45;
      ctx.save();
      ctx.globalAlpha = p.alpha * twinkle;
      ctx.fillStyle = 'rgba(' + p.hue + ',1)';
      ctx.shadowColor = 'rgba(' + p.hue + ',.9)';
      ctx.shadowBlur = 12;
      ctx.font = p.size + 'px "M PLUS Rounded 1c", sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(p.glyph, p.x, p.y);
      ctx.restore();
    }
    rafId = requestAnimationFrame(drawParticles);
  }

  function bootParticles() {
    if (!canvas || !ctx) return;
    resizeCanvas();
    seedParticles();
    if (!reduceMotion && innerWidth > 700) {
      cancelAnimationFrame(rafId);
      rafId = requestAnimationFrame(drawParticles);
    }
  }

  /* ── 节流 / 防抖 ─────────────────────────── */
  /** 尾部防抖：停止触发 wait 毫秒后才执行一次 */
  function debounce(fn, wait) {
    let timer = 0;
    return function (...args) {
      clearTimeout(timer);
      timer = setTimeout(() => fn.apply(this, args), wait);
    };
  }

  /** 帧节流：每帧最多执行一次，高频事件合并到下一帧 */
  function rafThrottle(fn) {
    let queued = false;
    let lastArgs = null;
    return function (...args) {
      lastArgs = args;
      if (queued) return;
      queued = true;
      requestAnimationFrame(() => {
        queued = false;
        fn.apply(this, lastArgs);
      });
    };
  }

  /* ── 通用 ───────────────────────────────── */
  function loadSettings() {
    try {
      const raw = localStorage.getItem(LS_KEY);
      if (raw) state.settings = Object.assign(state.settings, JSON.parse(raw));
    } catch (e) { /* 忽略损坏的本地配置 */ }
    // 只有接入设置页有这些输入框，其余页面安全跳过
    const fill = (sel, value) => { const el = $(sel); if (el) el.value = value; };
    fill('#cfgBase', state.settings.base);
    fill('#cfgTenant', state.settings.tenant);
    fill('#cfgUser', state.settings.user);
    fill('#cfgRole', state.settings.role);
    fill('#cfgModel', state.settings.model);
    fill('#cfgChunk', state.settings.chunk);
    const tenant = $('#tenantText');
    if (tenant) tenant.textContent = state.settings.tenant;
  }

  function saveSettings() {
    const read = (sel) => { const el = $(sel); return el ? el.value : null; };
    const base = read('#cfgBase');
    const tenant = read('#cfgTenant');
    const user = read('#cfgUser');
    const role = read('#cfgRole');
    const model = read('#cfgModel');
    const chunk = read('#cfgChunk');
    if (base !== null) state.settings.base = (base.trim() || 'http://localhost:8080').replace(/\/+$/, '');
    if (tenant !== null) state.settings.tenant = tenant.trim() || 'default';
    if (user !== null) state.settings.user = user.trim() || 'demo';
    if (role !== null) state.settings.role = role;
    if (model !== null) state.settings.model = model;
    if (chunk !== null) state.settings.chunk = chunk;
    localStorage.setItem(LS_KEY, JSON.stringify(state.settings));
    const tenantEl = $('#tenantText');
    if (tenantEl) tenantEl.textContent = state.settings.tenant;
  }

  /** 新建任务用的模型 / 分块：设置页有输入框时以其为准，否则用本地缓存 */
  const modelCode = () => { const el = $('#cfgModel'); return el ? el.value : (state.settings.model || 'default'); };
  const chunkStrategy = () => { const el = $('#cfgChunk'); return el ? el.value : (state.settings.chunk || 'semantic'); };

  /* ── 登录态 / VIP ───────────────────────── */
  function loadAuth() {
    try {
      const token = localStorage.getItem(AUTH_KEY);
      if (token) state.token = token;
    } catch (e) { /* 忽略本地存储异常 */ }
    updateAuthUI();
  }

  function clearAuth() {
    state.token = '';
    state.user = null;
    try { localStorage.removeItem(AUTH_KEY); } catch (e) { /* 忽略 */ }
    updateAuthUI();
    loadCoupons();
  }

  function updateAuthUI() {
    const user = state.user;
    const userText = $('#userText');
    if (userText) userText.textContent = user ? (user.displayName || user.username) : '未登录';
    const userDot = $('#userDot');
    if (userDot) {
      userDot.classList.toggle('on', !!user);
      userDot.classList.toggle('off', !user);
    }
    const tenant = $('#tenantText');
    if (tenant) tenant.textContent = user && user.tenantId ? user.tenantId : state.settings.tenant;
    const authBtn = $('#authToggle');
    if (authBtn) {
      authBtn.textContent = user ? '登出' : '登录 / 注册';
      authBtn.title = user ? '退出当前账号' : '前往登录页';
    }
    updateAdminUI();
  }

  /** 登录/注册走独立页面，登录后回到当前页 */
  function goLogin() {
    const next = location.pathname.split('/').pop() + location.search;
    location.href = 'login.html?next=' + encodeURIComponent(next || 'index.html');
  }

  function isAdmin() {
    return !!(state.user && state.user.role === 'ADMIN');
  }

  function updateAdminUI() {
    const toggle = $('#couponToggle');
    if (toggle) toggle.hidden = !isAdmin();
    const drawer = $('#adminDrawer');
    if (drawer && !isAdmin()) {
      if (drawer.tagName === 'DETAILS') drawer.open = false;
      else drawer.classList.remove('open');
    }
  }

  async function loadMe() {
    if (!state.token) return;
    try {
      state.user = await api('/api/auth/me');
      updateAuthUI();
    } catch (e) { /* 令牌失效已由 api() 统一处理 */ }
  }

  async function doLogout() {
    if (!state.token) { toast('当前未登录'); return; }
    try { await api('/api/auth/logout', { method: 'POST' }); } catch (e) { /* 忽略登出请求异常 */ }
    clearAuth();
    toast('已登出');
    refreshVip();
    loadQueue(true);
  }

  function fmtNum(value) {
    const n = Number(value || 0);
    if (n >= 1e6) return (n / 1e6).toFixed(n % 1e6 === 0 ? 0 : 1) + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(n % 1e3 === 0 ? 0 : 1) + 'K';
    return String(n);
  }

  /** 渲染额度卡片：VIP 生效展示套餐额度，否则展示租户配额 */
  function renderVip(quota) {
    const vip = !!quota && quota.source === 'VIP';
    const planCode = quota && quota.planCode ? quota.planCode : '';
    $('#vipPlan').textContent = quota
      ? (quota.planName || (vip ? 'VIP' : '租户配额'))
      : '额度不可用';
    const badge = $('#vipBadge');
    badge.textContent = vip ? planCode : (state.token ? 'FREE' : 'TENANT');
    badge.classList.toggle('pro', vip && planCode === 'PRO');
    $('#vipUpload').textContent = quota
      ? (quota.usedUploadCount || 0) + '/' + fmtNum(quota.dailyUploadLimit) : '—';
    $('#vipToken').textContent = quota
      ? fmtNum(quota.usedTokenCount) + '/' + fmtNum(quota.dailyTokenLimit) : '—';
    $('#vipConcurrent').textContent = quota ? String(quota.concurrentTaskLimit) : '—';
    let tip;
    if (!quota) {
      tip = '额度获取失败，请检查服务状态';
    } else if (vip) {
      const exp = quota.expireTime ? String(quota.expireTime).replace('T', ' ').slice(0, 16) : '—';
      tip = '到期 ' + exp + (quota.remainDays ? ' · 剩余 ' + quota.remainDays + ' 天' : '');
    } else if (state.token) {
      tip = '未开通 VIP，当前使用所属租户配额';
    } else {
      tip = '未登录，当前使用租户配额 · 登录后可用兑换码';
    }
    $('#vipExpire').textContent = tip;
  }

  async function refreshVip() {
    try {
      if (state.token) {
        const my = await api('/api/vip/my');
        my.source = my.active ? 'VIP' : 'FREE';
        state.vip = my;
      } else {
        state.vip = await api('/api/vip/quota');
      }
    } catch (e) {
      state.vip = null;
    }
    // 额度卡只在账户页存在；其他页面仅缓存数据，供 fufu 回答额度
    if (has('#vipCard')) renderVip(state.vip);
    return state.vip;
  }

  async function doRedeem() {
    if (!state.token) {
      toast('请先登录后再兑换', 'warn');
      goLogin();
      return;
    }
    const input = $('#redeemInput');
    if (!input) { location.href = 'account.html'; return; }
    const code = input.value.trim();
    if (!code) { toast('请输入兑换码', 'warn'); return; }
    try {
      await api('/api/vip/redeem', { method: 'POST', body: { code } });
      input.value = '';
      toast('兑换成功，VIP 已生效 ♪');
      refreshVip();
      loadMe();
    } catch (e) {
      toast(e.message, 'warn');
    }
  }

  /* ── 优惠券 ─────────────────────────────── */
  const COUPON_STATUS_TEXT = { UNUSED: '待使用', USED: '已使用', EXPIRED: '已过期' };

  function couponDesc(c) {
    const plan = c.planName || c.planCode || '—';
    return plan + ' · ' + (c.durationDays || 0) + ' 天 · 每人限领 ' + (c.perUserLimit || 1);
  }

  /** 我的优惠券里可用数量，显示在 Tab 上 */
  function updateCouponCount(mine) {
    const el = $('#couponCount');
    if (!el) return;
    const n = (mine || []).filter((c) => (c.status || 'UNUSED') === 'UNUSED').length;
    el.textContent = n ? String(n) : '';
  }

  function renderCoupons(available, mine) {
    updateCouponCount(mine);
    const list = $('#couponList');
    if (!available.length) {
      list.innerHTML = '<div class="coupon-empty">暂无正在发放的优惠券，等管理员发券吧 ♪</div>';
    } else {
      list.innerHTML = available.map((c) => {
        const sold = Number(c.remainStock) <= 0;
        const capped = Number(c.claimedByMe) >= Number(c.perUserLimit || 1);
        const disabled = sold || capped;
        const label = sold ? '已领完' : capped ? '已达上限' : '领取';
        return `
          <div class="coupon-item${sold ? ' sold' : ''}">
            <div>
              <div class="coupon-name">${escapeHtml(c.couponName)}</div>
              <div class="coupon-desc">${escapeHtml(couponDesc(c))}</div>
              <div class="coupon-stock">剩余 ${c.remainStock}/${c.totalStock} · 我已领 ${c.claimedByMe || 0}${c.expireTime ? ' · ' + escapeHtml(fmtTime(c.expireTime)) + ' 截止' : ''}</div>
            </div>
            <button class="btn ${disabled ? 'ghost' : 'solid'} js-claim" data-id="${c.couponId}" ${disabled ? 'disabled' : ''}>${label}</button>
          </div>`;
      }).join('');
    }

    const mineBox = $('#couponMine');
    if (!mine.length) {
      mineBox.innerHTML = '<div class="coupon-empty">暂无优惠券，先去领取一张吧</div>';
      return;
    }
    mineBox.innerHTML = mine.map((c) => {
      const status = c.status || 'UNUSED';
      const tag = COUPON_STATUS_TEXT[status] || status;
      const expire = c.expireTime ? ' · 到期 ' + fmtTime(c.expireTime) : ' · 长期有效';
      const action = status === 'UNUSED'
        ? `<button class="btn solid js-use" data-id="${c.id}">使用</button>`
        : `<span class="coupon-tag ${status === 'USED' ? 'used' : 'expired'}">${tag}</span>`;
      return `
        <div class="mine-item ${status === 'USED' ? 'used' : status === 'EXPIRED' ? 'expired' : ''}">
          <div>
            <div class="n">${escapeHtml(c.couponName || '优惠券')}</div>
            <div class="m">${escapeHtml(c.planName || c.planCode || '—')} · ${c.durationDays || 0} 天${escapeHtml(expire)}</div>
          </div>
          ${action}
        </div>`;
    }).join('');
  }

  async function loadCoupons() {
    if (!has('#couponList')) return;   // 账户页之外不拉优惠券
    const refresh = $('#btnCouponRefresh');
    if (refresh) refresh.hidden = !state.token;   // 未登录时刷新无意义，直接收起
    if (!state.token) {
      updateCouponCount([]);
      $('#couponList').innerHTML = '<div class="coupon-empty">登录后可领取限量优惠券</div>';
      $('#couponMine').innerHTML = '<div class="coupon-empty">登录后查看我的优惠券</div>';
      return;
    }
    if (state.couponLoading) return;
    state.couponLoading = true;
    try {
      const [available, mine] = await Promise.all([
        api('/api/coupon/available'),
        api('/api/coupon/my')
      ]);
      renderCoupons(available || [], mine || []);
    } catch (e) {
      $('#couponList').innerHTML = '<div class="coupon-empty">' + escapeHtml(e.message) + '</div>';
    } finally {
      state.couponLoading = false;
    }
  }

  async function claimCoupon(couponId) {
    if (!state.token) {
      toast('请先登录再领取', 'warn');
      goLogin();
      return;
    }
    const key = 'claim:' + couponId;
    if (state.couponBusy.has(key)) return;   // 连点只发一次请求
    state.couponBusy.add(key);
    try {
      await api('/api/coupon/' + encodeURIComponent(couponId) + '/claim', { method: 'POST' });
      toast('领取成功，去「我的优惠券」使用吧 ♪');
      speak('优惠券到手，记得用掉哦 ♪', 'happy');
      await loadCoupons();
    } catch (e) {
      toast(e.message, 'warn');
      await loadCoupons();
    } finally {
      state.couponBusy.delete(key);
    }
  }

  async function useCoupon(userCouponId) {
    const key = 'use:' + userCouponId;
    if (state.couponBusy.has(key)) return;
    state.couponBusy.add(key);
    try {
      await api('/api/coupon/' + encodeURIComponent(userCouponId) + '/use', { method: 'POST' });
      toast('核销成功，VIP 已开通 ♪');
      speak('额度升级完成，继续唱需求吧 ♪', 'happy');
      refreshVip();
      loadMe();
      await loadCoupons();
    } catch (e) {
      toast(e.message, 'warn');
      await loadCoupons();
    } finally {
      state.couponBusy.delete(key);
    }
  }

  /* ── 管理员发券 ─────────────────────────── */
  async function loadPlans() {
    if (state.plans.length || !has('#cpPlan')) return;
    try {
      state.plans = await api('/api/vip/plans') || [];
      $('#cpPlan').innerHTML = state.plans.map((p) =>
        `<option value="${escapeHtml(p.planCode)}">${escapeHtml(p.planName)} · ${p.durationDays || 0} 天</option>`).join('');
    } catch (e) { /* 套餐加载失败不阻断面板 */ }
  }

  async function loadAdminCoupons() {
    if (!has('#adminCouponList')) return;
    try {
      const page = await api('/api/admin/coupon/page?pageNum=1&pageSize=20');
      state.adminCoupons = page.records || [];
      renderAdminCoupons();
    } catch (e) {
      const box = $('#adminCouponList');
      if (box) box.innerHTML = '<div class="admin-hint">' + escapeHtml(e.message) + '</div>';
    }
  }

  function renderAdminCoupons() {
    const box = $('#adminCouponList');
    if (!box) return;
    if (!state.adminCoupons.length) {
      box.innerHTML = '<div class="admin-hint">尚未发放优惠券</div>';
      return;
    }
    box.innerHTML = state.adminCoupons.map((c) => `
      <div class="admin-row">
        <b>${escapeHtml(c.couponName)}</b>
        <span>${escapeHtml(c.planCode)}</span>
        <span>${c.claimedCount || 0}/${c.totalStock}</span>
        <span>${c.status === 1 ? '领取中' : '已下架'}</span>
        ${c.status === 1 ? `<button class="btn js-offline" data-id="${c.couponId}">下架</button>` : '<span>—</span>'}
      </div>`).join('');
  }

  async function createCoupon() {
    const couponName = $('#cpName').value.trim();
    if (!couponName) { toast('请输入优惠券名称', 'warn'); return; }
    if (!state.plans.length) { toast('套餐列表尚未加载，请稍后重试', 'warn'); return; }
    const totalStock = Number($('#cpStock').value);
    const perUserLimit = Number($('#cpPerUser').value);
    if (!Number.isInteger(totalStock) || totalStock < 1) { toast('发放总量至少为 1', 'warn'); return; }
    if (!Number.isInteger(perUserLimit) || perUserLimit < 1) { toast('每人限领至少为 1', 'warn'); return; }
    if (perUserLimit > totalStock) { toast('每人限领不能大于发放总量', 'warn'); return; }
    const btn = $('#btnCreateCoupon');
    btn.disabled = true;
    try {
      await api('/api/admin/coupon', {
        method: 'POST',
        body: {
          couponName,
          planCode: $('#cpPlan').value,
          durationDays: Number($('#cpDays').value) || 0,
          totalStock,
          perUserLimit,
          expireDays: Number($('#cpExpire').value) || 0,
          validDays: Number($('#cpValid').value) || 0
        }
      });
      toast('优惠券已发放，库存由 Lua 脚本原子扣减 ♪');
      $('#cpName').value = '';
      await loadAdminCoupons();
      await loadCoupons();
    } catch (e) {
      toast(e.message, 'warn');
    } finally {
      btn.disabled = false;
    }
  }

  async function offlineCoupon(couponId) {
    if (!confirm('下架该优惠券？已领取的用户仍可正常使用')) return;
    try {
      await api('/api/admin/coupon/' + encodeURIComponent(couponId), { method: 'DELETE' });
      toast('优惠券已下架');
      await loadAdminCoupons();
      await loadCoupons();
    } catch (e) {
      toast(e.message, 'warn');
    }
  }

  async function api(path, opts = {}) {
    const headers = {};
    if (state.token) {
      headers['Authorization'] = 'Bearer ' + state.token;
    } else {
      headers['X-Tenant-Id'] = state.settings.tenant;
      headers['X-User-Key'] = state.settings.user;
      headers['X-User-Role'] = state.settings.role;
    }
    let body;
    if (opts.form) {
      body = opts.form;
    } else if (opts.body) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(opts.body);
    }
    const limit = opts.timeout || 15000;
    const ctrl = new AbortController();
    const timeout = setTimeout(() => ctrl.abort(), limit);
    try {
      const res = await fetch(state.settings.base + path, {
        method: opts.method || 'GET', headers, body, signal: ctrl.signal
      });
      const text = await res.text();
      let json;
      try { json = text ? JSON.parse(text) : {}; } catch (e) { throw new Error('响应不是合法 JSON（HTTP ' + res.status + '）'); }
      if (json.code === 401) {
        clearAuth();
        toast('登录已失效，请重新登录', 'warn');
      }
      if (json.code !== 0) throw new Error(json.message || ('请求失败（HTTP ' + res.status + '）'));
      return json.data;
    } catch (e) {
      if (e.name === 'AbortError') {
        throw new Error('请求超时（' + Math.round(limit / 1000) + 's），请检查服务状态');
      }
      if (e instanceof TypeError) {
        throw new Error('无法连接 ' + state.settings.base + '，请检查服务是否启动');
      }
      throw e;
    } finally {
      clearTimeout(timeout);
    }
  }

  function toast(message, type = 'info') {
    // 相同消息 1.5s 内只弹一次，避免轮询失败时堆叠刷屏
    const sig = type + '|' + message;
    const now = Date.now();
    if (sig === state.toastSig && now - state.toastAt < 1500) return;
    state.toastSig = sig;
    state.toastAt = now;

    const el = document.createElement('div');
    el.className = 'toast' + (type === 'error' ? ' error' : type === 'warn' ? ' warn' : '');
    el.textContent = message;
    const box = $('#toasts');
    if (!box) return;
    box.appendChild(el);
    while (box.children.length > 4) box.firstElementChild.remove();
    setTimeout(() => {
      el.style.transition = '.3s'; el.style.opacity = '0'; el.style.transform = 'translateX(16px)';
      setTimeout(() => el.remove(), 320);
    }, 4200);
  }

  let moodTimer = null;
  function setMood(mood) {
    const box = $('#fufu');
    if (!box) return;
    box.classList.remove('mood-happy', 'mood-sad');
    clearTimeout(moodTimer);
    if (mood) {
      box.classList.add('mood-' + mood);
      moodTimer = setTimeout(() => box.classList.remove('mood-happy', 'mood-sad'), 2000);
    }
  }

  function setSinging(on) {
    const el = $('#fufu');
    if (el) el.classList.toggle('sing', !!on);
  }

  function speak(text, mood) {
    const box = $('#speech');
    if (!box) { if (mood) setMood(mood); return; }
    box.textContent = text;
    box.style.animation = 'none';
    void box.offsetWidth;
    box.style.animation = 'pop .3s ease both';
    if (mood) setMood(mood);
  }

  function setEq(playing) {
    const el = $('#eq');
    if (el) el.classList.toggle('play', !!playing);
  }

  function escapeHtml(str) {
    return String(str == null ? '' : str)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function fmtTime(value) {
    if (!value) return '—';
    return String(value).replace('T', ' ').slice(5, 16);
  }

  function fmtSize(bytes) {
    if (bytes == null) return '—';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1024 / 1024).toFixed(2) + ' MB';
  }

  function animateNumber(el, target) {
    const from = Number(el.dataset.val || 0);
    if (from === target) { el.textContent = target; return; }
    el.dataset.val = target;
    const start = performance.now();
    const dur = 420;
    function step(now) {
      const k = Math.min(1, (now - start) / dur);
      el.textContent = Math.round(from + (target - from) * (1 - Math.pow(1 - k, 3)));
      if (k < 1) requestAnimationFrame(step);
    }
    requestAnimationFrame(step);
  }

  /* ── Markdown ───────────────────────────── */
  function renderInline(text) {
    return text
      .replace(/`([^`]+)`/g, (m, code) => '<code>' + code + '</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/(^|[\s(])\*([^*\n]+)\*/g, '$1<em>$2</em>')
      .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>');
  }

  function renderMarkdown(markdown) {
    const lines = String(markdown || '').replace(/\r\n/g, '\n').split('\n');
    const out = [];
    let listType = null, inCode = false, codeLines = [], para = [];

    const flushPara = () => {
      if (para.length) { out.push('<p>' + renderInline(para.join(' ')) + '</p>'); para = []; }
    };
    const closeList = () => {
      if (listType) { out.push('</' + listType + '>'); listType = null; }
    };

    for (let i = 0; i < lines.length; i++) {
      const raw = lines[i];
      const line = raw.replace(/\s+$/, '');

      if (/^```/.test(line)) {
        if (inCode) {
          out.push('<pre><code>' + escapeHtml(codeLines.join('\n')) + '</code></pre>');
          inCode = false; codeLines = [];
        } else { flushPara(); closeList(); inCode = true; }
        continue;
      }
      if (inCode) { codeLines.push(raw); continue; }
      if (!line.trim()) { flushPara(); closeList(); continue; }

      const heading = /^(#{1,4})\s+(.*)$/.exec(line);
      if (heading) {
        flushPara(); closeList();
        const level = heading[1].length;
        out.push('<h' + level + '>' + renderInline(heading[2]) + '</h' + level + '>');
        continue;
      }

      if (/^(\s*)(-{3,}|\*{3,})\s*$/.test(line)) { flushPara(); closeList(); out.push('<hr>'); continue; }

      if (line.includes('|') && i + 1 < lines.length && /^\s*\|?[\s:-]+\|[\s:|-]*$/.test(lines[i + 1])) {
        flushPara(); closeList();
        const cells = (row) => row.replace(/^\s*\|/, '').replace(/\|\s*$/, '').split('|').map(c => c.trim());
        out.push('<table><thead><tr>' + cells(line).map(c => '<th>' + renderInline(c) + '</th>').join('') + '</tr></thead><tbody>');
        i++;
        while (i + 1 < lines.length && lines[i + 1].includes('|') && lines[i + 1].trim()) {
          i++;
          out.push('<tr>' + cells(lines[i]).map(c => '<td>' + renderInline(c) + '</td>').join('') + '</tr>');
        }
        out.push('</tbody></table>');
        continue;
      }

      if (/^>\s?/.test(line)) {
        flushPara(); closeList();
        out.push('<blockquote>' + renderInline(line.replace(/^>\s?/, '')) + '</blockquote>');
        continue;
      }

      const ol = /^\s*\d+[.)]\s+(.*)$/.exec(line);
      const ul = /^\s*[-*+]\s+(.*)$/.exec(line);
      if (ol || ul) {
        flushPara();
        const type = ol ? 'ol' : 'ul';
        if (listType !== type) { closeList(); out.push('<' + type + '>'); listType = type; }
        out.push('<li>' + renderInline(ol ? ol[1] : ul[1]) + '</li>');
        continue;
      }

      closeList();
      para.push(line.trim());
    }

    if (inCode) out.push('<pre><code>' + escapeHtml(codeLines.join('\n')) + '</code></pre>');
    flushPara(); closeList();
    return out.join('\n');
  }

  function buildToc(markdown) {
    const headings = [];
    String(markdown || '').split('\n').forEach((line) => {
      const m = /^(#{1,4})\s+(.*)$/.exec(line.replace(/\s+$/, ''));
      if (m) headings.push({ level: m[1].length, text: m[2].replace(/[`*]/g, '') });
    });
    return headings;
  }

  /* ── 队列 / 统计 ─────────────────────────── */
  function renderStats() {
    const total = state.tasks.length;
    const ok = state.tasks.filter(t => t.taskStatus === 'SUCCESS').length;
    const running = state.tasks.filter(t => t.taskStatus === 'PROCESS' || t.taskStatus === 'INIT').length;
    const tasksEl = $('#statTasks');
    const okEl = $('#statOk');
    const runEl = $('#statRun');
    if (tasksEl) animateNumber(tasksEl, total);
    if (okEl) animateNumber(okEl, ok);
    if (runEl) animateNumber(runEl, running);
    setEq(running > 0);
    setSinging(running > 0);
  }

  function taskCard(task) {
    const active = task.taskId === state.selectedId ? ' active' : '';
    const progress = Math.max(0, Math.min(100, Number(task.progress || 0)));
    return `
      <div class="task${active}" data-status="${escapeHtml(task.taskStatus)}" data-id="${escapeHtml(task.taskId)}">
        <div class="task-top">
          <span class="task-id">${escapeHtml(task.taskId)}</span>
          <span class="pill ${escapeHtml(task.taskStatus)}">${STATUS_TEXT[task.taskStatus] || escapeHtml(task.taskStatus)}</span>
        </div>
        <div class="task-meta">
          <span>v<b>${task.versionNo == null ? '—' : escapeHtml(task.versionNo)}</b></span>
          <span>${escapeHtml(task.modelCode || '—')}</span>
          <span>${escapeHtml(fmtTime(task.createTime))}</span>
        </div>
        <div class="bar"><i style="width:${progress}%"></i></div>
      </div>`;
  }

  function renderQueue() {
    const queue = $('#queue');
    if (!state.tasks.length) {
      if (queue) {
        const html = '<div class="empty">该筛选条件下暂无任务<br>换个筛选，或上传一份新的 PRD ♪</div>';
        if (queue.dataset.sig !== 'empty') { queue.innerHTML = html; queue.dataset.sig = 'empty'; }
      }
      state.queueSig = '';
      renderStats();
      return;
    }
    // 数据签名比对：避免轮询重绘打断点击与动画
    const sig = String(state.selectedId) + '::' + state.tasks
      .map(t => [t.taskId, t.taskStatus, t.progress, t.finishTime, t.versionNo].join('|'))
      .join(';');
    if (queue && sig !== state.queueSig) {
      queue.innerHTML = state.tasks.map(taskCard).join('');
      queue.dataset.sig = sig;
      state.queueSig = sig;
    }
    renderStats();
  }

  /* ── 报告 ───────────────────────────────── */
  function setReportActions(enabled, canRetry) {
    ['#btnExportMd', '#btnExportHtml', '#btnExportDocx', '#btnDelete'].forEach(sel => {
      const el = $(sel);
      if (el) el.disabled = !enabled;
    });
    const retry = $('#btnRetry');
    if (retry) retry.disabled = !canRetry;
    const input = $('#chatInput');
    if (input) input.disabled = !enabled;
    const chat = $('#btnChat');
    if (chat) chat.disabled = !enabled;
  }

  function renderEmptyReport() {
    if (!has('#reportBody')) return;
    $('#reportId').textContent = '—';
    $('#reportStatus').style.display = 'none';
    $('#reportBody').innerHTML = `
      <div class="report-empty">
        <div class="glyph">♪ ♫ ♪</div>
        <p>选择左侧任务，查看评审报告<br>业务梳理 / 疑点清单 / DDL / 接口契约 / 任务清单</p>
      </div>`;
    $('#toc').innerHTML = '';
    $('#scrollBar').style.width = '0%';
    setReportActions(false, false);
  }

  function renderReport(task) {
    if (!has('#reportBody')) return;
    const statusEl = $('#reportStatus');
    $('#reportId').textContent = task.taskId;
    statusEl.style.display = '';
    statusEl.className = 'pill ' + task.taskStatus;
    statusEl.textContent = STATUS_TEXT[task.taskStatus] || task.taskStatus;

    const body = $('#reportBody');
    const toc = $('#toc');

    if (task.taskStatus === 'FAIL') {
      body.innerHTML = `
        <div class="report-empty">
          <div class="glyph" style="color:rgba(255,92,122,.55)">✕</div>
          <p style="color:var(--danger)">任务失败了…</p>
          <p>${escapeHtml(task.errorMsg || '未知错误')}</p>
          <p>点右上角「重试」，我会重新唱一遍 ♪</p>
        </div>`;
      toc.innerHTML = '';
      setReportActions(false, true);
      speak('呜…解析失败了，要再来一次吗？', 'sad');
      return;
    }

    if (task.taskStatus === 'INIT' || task.taskStatus === 'PROCESS') {
      const progress = Number(task.progress || 0);
      body.innerHTML = `
        <div class="report-empty">
          <div class="loading-rings"><i></i><i></i></div>
          <p style="color:var(--aqua-lit);font-size:13px;letter-spacing:.2em">${String(progress).padStart(3, '0')}%</p>
          <p>四阶段流水线运行中<br>业务理解 → 风险扫描 → 结构生成 → 汇总格式化</p>
        </div>`;
      toc.innerHTML = '';
      setReportActions(false, true);
      speak('正在解析… 已推进到 ' + progress + '% ♪');
      return;
    }

    const markdown = task.resultMarkdown || '';
    if (!markdown.trim()) {
      body.innerHTML = '<div class="empty">任务已完成，但报告内容为空</div>';
      toc.innerHTML = '';
      setReportActions(false, false);
      return;
    }

    body.innerHTML = '<article class="md">' + renderMarkdown(markdown) + '</article>';
    const headings = buildToc(markdown);
    toc.innerHTML = '<div class="toc-label">Contents</div>' + headings.map((h, i) =>
      `<a class="lv${h.level}" href="#h-${i}" data-index="${i}">${escapeHtml(h.text)}</a>`).join('');
    body.querySelectorAll('h1,h2,h3,h4').forEach((node, i) => { node.id = 'h-' + i; });
    toc.querySelectorAll('a').forEach(a => {
      a.addEventListener('click', (ev) => {
        ev.preventDefault();
        const target = $('#h-' + a.dataset.index);
        if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
      });
    });
    setReportActions(true, false);
    speak('报告完成啦！右上角可以导出 MD / HTML / DOCX ♪', 'happy');
    flashTitle('♪ 解析完成');
  }

  let titleTimer = null;
  function flashTitle(text) {
    if (document.hidden) {
      const original = document.title;
      document.title = text + ' · PRD2API';
      clearTimeout(titleTimer);
      titleTimer = setTimeout(() => { document.title = original; }, 4000);
    }
  }

  /* ── 数据 ───────────────────────────────── */
  /** on：true=在线，false=本地降级，null=不可达 */
  function renderRagUI(on) {
    const dot = $('#ragDot');
    if (dot) dot.className = 'dot ' + (on ? 'on' : 'off');
    const text = $('#ragText');
    if (text) text.textContent = on === null ? 'RAG 不可达' : on ? 'RAG ONLINE' : 'RAG LOCAL';
    const chroma = $('#statChroma');
    if (chroma) {
      chroma.textContent = on === null ? '离线' : on ? '在线' : '降级';
      chroma.style.color = on === null ? 'var(--danger)' : on ? 'var(--aqua-lit)' : 'var(--pink)';
    }
  }

  async function refreshRag() {
    let detail;
    try {
      const data = await api('/api/rag/status');
      const on = !!data.chromaAvailable;
      renderRagUI(on);
      detail = on ? 'RAG ONLINE · Chroma 向量库可用' : 'RAG LOCAL · Chroma 不可用，已降级本地检索';
    } catch (e) {
      renderRagUI(null);
      detail = 'RAG 不可达 · 请检查服务地址与网络';
    }
    // 设置页的详情文本（与顶栏 #ragText 区分，避免重复 id）
    const detailEl = $('#ragDetail');
    if (detailEl) detailEl.textContent = detail;
  }

  async function loadQueue(silent) {
    if (!has('#queue')) return;   // 非工作台页面不拉队列
    if (state.queueLoading) {
      // 在途请求未返回（轮询/刷新/筛选），标记待重跑，合并为一次尾部请求
      state.queuePending = true;
      return;
    }
    state.queueLoading = true;
    try {
      const query = new URLSearchParams({ pageNum: '1', pageSize: '30' });
      if (state.filter) query.set('taskStatus', state.filter);
      const page = await api('/api/task/page?' + query.toString());
      state.tasks = page.records || [];
      renderQueue();
      if (!silent) toast('队列已刷新 · ' + state.tasks.length + ' 条 ♪');
    } catch (e) {
      if (!silent) toast(e.message, 'error');
      if (!state.tasks.length) {
        $('#queue').innerHTML = '<div class="empty">队列加载失败<br>' + escapeHtml(e.message) + '</div>';
      }
    } finally {
      state.queueLoading = false;
      if (state.queuePending) {
        state.queuePending = false;
        loadQueue(true);
      }
    }
  }

  async function loadDetail(taskId, silent) {
    if (silent && state.detailLoading === taskId) return;  // 同一任务轮询未返回时跳过本轮
    const seq = ++state.detailSeq;
    state.detailLoading = taskId;
    try {
      const task = await api('/api/task/' + encodeURIComponent(taskId));
      if (seq !== state.detailSeq) return;   // 期间已有更新请求，丢弃过期响应
      state.detail = task;
      state.selectedId = taskId;
      renderReport(task);
      const idx = state.tasks.findIndex(t => t.taskId === taskId);
      if (idx >= 0) {
        state.tasks[idx] = Object.assign({}, state.tasks[idx], {
          taskStatus: task.taskStatus, progress: task.progress, finishTime: task.finishTime
        });
        renderQueue();
      }
      clearTimeout(state.detailPolling);
      if (task.taskStatus === 'INIT' || task.taskStatus === 'PROCESS') {
        state.detailPolling = setTimeout(() => {
          if (state.selectedId === taskId) loadDetail(taskId, true);
        }, 2500);
      }
    } catch (e) {
      if (seq === state.detailSeq && !silent) toast(e.message, 'error');
    } finally {
      if (seq === state.detailSeq) state.detailLoading = null;
    }
  }

  /* ── 文件接入 ───────────────────────────── */
  function setDzBusy(busy, text) {
    const dz = $('#dropzone');
    const meta = $('#dzMeta');
    if (dz) dz.classList.toggle('busy', busy);
    if (meta) meta.textContent = text || (busy ? '上传中…' : '点击或拖入文件');
  }

  async function uploadFile(file) {
    if (!file) return;
    if (state.uploading) { toast('上一个文件还在上传，请稍候', 'warn'); return; }
    if (file.size > 20 * 1024 * 1024) { toast('文件超过 20MB 限制', 'error'); return; }
    state.uploading = true;
    setDzBusy(true, '上传中 · ' + fmtSize(file.size));
    speak('正在接收文档… 稍等一下 ♪');
    try {
      const form = new FormData();
      form.append('file', file);
      const data = await api('/api/file/upload', { method: 'POST', form, timeout: 120000 });
      state.fileMetaId = data.fileMetaId;
      const flags = [];
      if (data.duplicated) flags.push('<span class="ur-flag">md5 命中，已复用历史文件</span>');
      if (data.similarFileMetaId) flags.push('<span class="ur-flag">检测到相似文档 · 相似度 ' + Number(data.similarity || 0).toFixed(3) + '</span>');
      $('#urName').textContent = data.fileName + '　' + fmtSize(data.fileSize);
      $('#urMeta').innerHTML = 'fileMetaId · ' + escapeHtml(data.fileMetaId) + (flags.length ? '<br>' + flags.join('<br>') : '');
      $('#uploadResult').classList.add('show');
      toast('文件已入库，可以开始解析啦');
      speak('收到！点「开始解析 ♪」我就开工', 'happy');
      setDzBusy(false, '已入库 · 可继续上传');
    } catch (e) {
      setDzBusy(false, '点击或拖入文件');
      toast(e.message, 'error');
      speak('这个文件我读不了… 换一个试试？', 'sad');
    } finally {
      state.uploading = false;
    }
  }

  async function createTask() {
    if (!state.fileMetaId) { toast('请先上传文档', 'warn'); return; }
    const btn = $('#btnCreateTask');
    btn.disabled = true;
    const oldText = btn.textContent;
    btn.textContent = '排队中…';
    try {
      const task = await api('/api/task/create', {
        method: 'POST',
        body: { fileMetaId: state.fileMetaId, modelCode: modelCode(), chunkStrategy: chunkStrategy() }
      });
      toast('任务已创建 · ' + task.taskId);
      state.fileMetaId = null;
      $('#uploadResult').classList.remove('show');
      state.selectedId = task.taskId;
      await loadQueue(true);
      await loadDetail(task.taskId, true);
      speak('四阶段流水线启动！我会盯着进度的 ♪');
    } catch (e) {
      toast(e.message, 'error');
      speak('任务没排上… 可能是配额或限流，稍后再试', 'sad');
    } finally {
      btn.disabled = false;
      btn.textContent = oldText;
    }
  }

  /* ── 操作 ───────────────────────────────── */
  async function exportReport(format) {
    if (!state.selectedId || state.exporting) return;
    state.exporting = true;
    try {
      const data = await api('/api/task/' + encodeURIComponent(state.selectedId) + '/export?format=' + format);
      window.open(state.settings.base + (data.downloadUrl || ''), '_blank', 'noopener');
      toast('导出就绪 · ' + data.fileName + '（' + data.expireSeconds + 's 内有效）');
    } catch (e) { toast(e.message, 'error'); }
    finally { state.exporting = false; }
  }

  async function retryTask() {
    if (!state.selectedId || state.mutating) return;
    state.mutating = true;
    try {
      await api('/api/task/retry', { method: 'POST', body: { taskId: state.selectedId, modelCode: modelCode() } });
      toast('任务已重新排队');
      speak('再来一次！这次一定行 ♪', 'happy');
      await loadDetail(state.selectedId, true);
      await loadQueue(true);
    } catch (e) { toast(e.message, 'error'); }
    finally { state.mutating = false; }
  }

  async function deleteTask() {
    if (!state.selectedId || state.mutating) return;
    if (!confirm('删除任务 ' + state.selectedId + '？报告与向量索引将一并清理')) return;
    state.mutating = true;
    try {
      await api('/api/task/' + encodeURIComponent(state.selectedId), { method: 'DELETE' });
      toast('任务已删除');
      state.selectedId = null;
      state.detail = null;
      renderEmptyReport();
      await loadQueue(true);
      speak('已清理干净～', 'happy');
    } catch (e) { toast(e.message, 'error'); }
    finally { state.mutating = false; }
  }

  async function sendChat() {
    const input = $('#chatInput');
    const btn = $('#btnChat');
    if (btn.disabled) return;                 // 连续 Ctrl+Enter 时不重复提交
    const question = input.value.trim();
    if (!question || !state.selectedId) return;
    btn.disabled = true;
    btn.textContent = '思考中…';
    speak('让我看看你的问题…');
    try {
      await api('/api/task/chat', { method: 'POST', body: { taskId: state.selectedId, question }, timeout: 120000 });
      input.value = '';
      toast('追问已追加到报告末尾');
      await loadDetail(state.selectedId, true);
      $('#reportBody').scrollTop = $('#reportBody').scrollHeight;
    } catch (e) {
      toast(e.message, 'error');
    } finally {
      btn.disabled = false;
      btn.textContent = '追问';
    }
  }

  /* ── 事件 ───────────────────────────────── */
  function addRipple(el) {
    el.addEventListener('click', (ev) => {
      const rect = el.getBoundingClientRect();
      const span = document.createElement('span');
      span.className = 'ripple';
      const size = Math.max(rect.width, rect.height);
      span.style.width = span.style.height = size + 'px';
      span.style.left = (ev.clientX - rect.left) + 'px';
      span.style.top = (ev.clientY - rect.top) + 'px';
      el.appendChild(span);
      setTimeout(() => span.remove(), 600);
    });
  }

  /* ── 可拖拽 fufu 玩偶 ───────────────────── */
  const FUFU_POS_KEY = 'prd2api.fufu.pos';

  function fufuHome() {
    const el = $('#fufu');
    const w = el ? el.offsetWidth : 148;
    const h = el ? el.offsetHeight : 148;
    return { x: Math.max(8, innerWidth - w - 20), y: Math.max(8, innerHeight - h - 96) };
  }

  function placeFufu(pos) {
    const el = $('#fufu');
    if (!el || !pos) return;
    const w = el.offsetWidth || 148;
    const h = el.offsetHeight || 148;
    const x = Math.min(Math.max(0, pos.x), Math.max(0, innerWidth - w));
    const y = Math.min(Math.max(0, pos.y), Math.max(0, innerHeight - h));
    state.fufuPos = { x, y };
    el.style.transform = 'translate3d(' + Math.round(x) + 'px,' + Math.round(y) + 'px,0)';
  }

  function saveFufuPos() {
    try { localStorage.setItem(FUFU_POS_KEY, JSON.stringify(state.fufuPos)); } catch (e) { /* 隐私模式忽略 */ }
  }

  /* ── fufu 问答 Agent（纯前端本地知识库 + 界面操作） ── */
  let fufuChatOpen = false;
  let fufuChatGreeted = false;

  const FC_WELCOME = '在的 ♪ 我是 MIKU，Prd2Api 的看板助手。平台能力、上传解析、报告导出、额度配置都可以问我，也可以让我帮你直接操作。';

  function fufuChatPosition() {
    const panel = $('#fufuChat');
    const fufu = $('#fufu');
    if (!panel || !fufu) return;
    const rect = fufu.getBoundingClientRect();
    const pw = panel.offsetWidth || 316;
    const ph = panel.offsetHeight || 320;
    const gap = 12;
    // 优先贴在玩偶左侧；横向放不下时（窄屏）改放到玩偶正上方，避免盖住玩偶
    const leftOk = rect.left - pw - gap >= 8;
    const aboveOk = rect.top - ph - gap >= 8;
    let x = leftOk ? rect.left - pw - gap : rect.right - pw;
    let y = leftOk ? rect.bottom - ph : (aboveOk ? rect.top - ph - gap : rect.bottom - ph);
    x = Math.min(Math.max(8, x), Math.max(8, innerWidth - pw - 8));
    y = Math.min(Math.max(8, y), Math.max(8, innerHeight - ph - 8));
    panel.style.left = Math.round(x) + 'px';
    panel.style.top = Math.round(y) + 'px';
    panel.style.transformOrigin = leftOk ? '100% 100%' : '50% 100%';
  }

  function fcScroll() {
    const body = $('#fcBody');
    if (body) body.scrollTop = body.scrollHeight;
  }

  function fcNode(role, html, acts, traceHtml) {
    const body = $('#fcBody');
    if (!body) return null;
    const msg = document.createElement('div');
    msg.className = 'fc-msg ' + role;
    msg.innerHTML = (role === 'bot' ? '<span class="fc-who">MIKU</span>' : '') +
      '<div class="fc-bubble">' + html + '</div>';
    if (acts && acts.length) {
      const row = document.createElement('div');
      row.className = 'fc-acts';
      acts.forEach((id) => {
        const def = FC_ACTS[id];
        if (!def) return;
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'fc-act';
        btn.dataset.act = id;
        btn.textContent = def.label;
        row.appendChild(btn);
      });
      if (row.children.length) msg.lastElementChild.appendChild(row);
    }
    if (role === 'bot' && traceHtml) {
      msg.insertAdjacentHTML('beforeend', traceHtml);
    }
    body.appendChild(msg);
    fcScroll();
    fufuChatPosition();
    return msg;
  }

  function fcSay(html, acts, traceHtml) { fcNode('bot', html, acts, traceHtml); }

  // Agent 轨迹：折叠展示工具调用、知识检索与记忆写入，体现每一步可观测
  function fcTrace(steps, mode, degraded, cacheHit, grounded, groundingNote) {
    if (!steps || !steps.length) return '';
    const labels = {
      TOOL: '工具', KNOWLEDGE: '知识库', MEMORY: '记忆', THINK: '推理',
      CONTEXT: '上下文压缩', SELF_CHECK: '接地自检', CACHE: '语义缓存'
    };
    const items = steps.map((s) => {
      const kind = labels[s.type] || s.type || '步骤';
      const fail = s.success === false;
      const out = String(s.result || '').slice(0, 120);
      return '<li' + (fail ? ' data-fail="1"' : '') + '>' +
        '<span class="fc-tool">' + (fail ? '✕ ' : '✓ ') + escapeHtml(s.title || s.toolName || kind) + '</span>' +
        '<em>' + escapeHtml(kind) + ' · ' + (Number(s.latencyMs) || 0) + 'ms</em>' +
        (out ? '<p>' + escapeHtml(out) + '</p>' : '') +
        '</li>';
    }).join('');
    const modeChip = mode
      ? '<span class="fc-mode ' + escapeHtml(String(mode).toLowerCase()) + '">' + escapeHtml(mode) + '</span>'
      : '';
    const cacheChip = cacheHit ? '<span class="fc-chip ok">缓存命中</span>' : '';
    const groundChip = grounded === false
      ? '<span class="fc-chip bad" title="' + escapeHtml(groundingNote || '') + '">未接地</span>'
      : '';
    return '<details class="fc-trace"><summary>Agent 轨迹 ' + steps.length + ' 步 ' + modeChip +
      cacheChip + groundChip + (degraded ? '<i>降级</i>' : '') +
      '</summary><ul>' + items + '</ul></details>';
  }

  function fcAskUser(text) { fcNode('me', escapeHtml(text)); }

  // 打字中的三个点，延时后替换成正式回复
  function fcThink(reply) {
    const body = $('#fcBody');
    if (!body) return;
    const node = document.createElement('div');
    node.className = 'fc-msg bot typing';
    node.innerHTML = '<span class="fc-who">MIKU</span><div class="fc-bubble"><i></i><i></i><i></i></div>';
    body.appendChild(node);
    fcScroll();
    fufuChatPosition();
    setSinging(true);
    setTimeout(() => {
      if (!node.isConnected) return;
      node.remove();
      reply();
      setSinging(state.tasks.some((t) => t.taskStatus === 'INIT' || t.taskStatus === 'PROCESS'));
    }, 480);
  }

  function fcQueueText() {
    if (!has('#queue')) return '队列在「工作台」页，我带你过去。';
    const total = state.tasks.length;
    const run = state.tasks.filter((t) => t.taskStatus === 'INIT' || t.taskStatus === 'PROCESS').length;
    const ok = state.tasks.filter((t) => t.taskStatus === 'SUCCESS').length;
    const fail = state.tasks.filter((t) => t.taskStatus === 'FAIL').length;
    if (!total) return '队列还是空的，先上传一份 PRD 吧 ♪';
    return '队列里共 <b>' + total + '</b> 个任务：运行中 <b>' + run + '</b>，已完成 <b>' + ok + '</b>，失败 <b>' + fail + '</b>。点左侧卡片就能看报告详情。';
  }

  function fcQuotaText() {
    const q = state.vip;
    if (!q) return '额度在「账户中心」页可以看，登录后还能用兑换码开通。';
    const vip = q.source === 'VIP';
    const plan = q.planName || (vip ? 'VIP' : '租户配额');
    const up = (q.usedUploadCount || 0) + '/' + fmtNum(q.dailyUploadLimit);
    const tk = fmtNum(q.usedTokenCount) + '/' + fmtNum(q.dailyTokenLimit);
    const cc = q.concurrentTaskLimit == null ? '—' : String(q.concurrentTaskLimit);
    return '当前套餐 <b>' + escapeHtml(plan) + '</b>，上传/日 <b>' + escapeHtml(up) + '</b>，Token/日 <b>' + escapeHtml(tk) + '</b>，并发 <b>' + escapeHtml(cc) + '</b>。' +
      '<br>BASIC ¥19/30 天（100 上传/日、5 并发、20MB）；PRO ¥99/30 天（500 上传/日、20 并发、50MB）。';
  }

  // 可点击的执行动作：按钮文案 + 真正的前端操作 + 操作后的回应
  const FC_ACTS = {
    upload: {
      label: '选择文件',
      run: () => { if (!has('#fileInput')) { location.href = 'index.html'; return; } $('#fileInput').click(); },
      say: () => has('#fileInput') ? '文件选择器打开了，选一份 PRD 丢给我 ♪' : '已带你去工作台，选一份 PRD 丢给我 ♪'
    },
    parse: {
      label: '开始解析 ♪',
      run: () => {
        if (!has('#fileInput')) { location.href = 'index.html'; return; }
        state.fileMetaId ? createTask() : $('#fileInput').click();
      },
      say: () => state.fileMetaId ? '已经排进四阶段流水线，我会盯着进度～' : '还没有入库的文档，先选一份 PRD 吧。'
    },
    refresh: {
      label: '刷新队列',
      run: () => { if (!has('#queue')) { location.href = 'index.html'; return; } loadQueue(false); },
      say: () => '队列刷新好了。' + fcQueueText()
    },
    retry: {
      label: '重试当前任务',
      run: () => {
        if (!has('#btnRetry')) { location.href = 'index.html'; return; }
        if (state.selectedId) retryTask(); else toast('请先在队列中选择一个任务', 'warn');
      },
      say: '重试请求已发出，盯着进度条就好 ♪'
    },
    detail: {
      label: '打开任务详情',
      run: () => {
        if (!has('#reportBody')) { location.href = 'index.html'; return; }
        if (!state.fcTaskId) { toast('还没有拿到任务 ID，先刷新队列吧', 'warn'); return; }
        loadDetail(state.fcTaskId);
        loadQueue(true);
      },
      say: () => state.fcTaskId ? '已经帮你打开任务详情面板 ♪' : '没有可打开的任务，先刷新队列。'
    },
    md: {
      label: '导出 MD',
      run: () => { if (!has('#btnExportMd')) { location.href = 'index.html'; return; } exportReport('md'); },
      say: () => state.selectedId ? '导出链接已生成，浏览器里就能保存。' : '先在工作台队列点一份报告，我才能导出。'
    },
    html: {
      label: '导出 HTML',
      run: () => { if (!has('#btnExportHtml')) { location.href = 'index.html'; return; } exportReport('html'); },
      say: () => state.selectedId ? '导出链接已生成，浏览器里就能保存。' : '先在工作台队列点一份报告，我才能导出。'
    },
    docx: {
      label: '导出 DOCX',
      run: () => { if (!has('#btnExportDocx')) { location.href = 'index.html'; return; } exportReport('docx'); },
      say: () => state.selectedId ? '导出链接已生成，浏览器里就能保存。' : '先在工作台队列点一份报告，我才能导出。'
    },
    settings: {
      label: '打开接入设置',
      run: () => { if (PAGE !== 'settings') { location.href = 'settings.html'; return; } const base = $('#cfgBase'); if (base) base.focus(); },
      say: '在接入设置里填 API Base 和透传身份，保存后我会重新连一次。'
    },
    goAccount: {
      label: '去账户中心',
      run: () => { if (PAGE !== 'account') { location.href = 'account.html'; return; } refreshVip(); },
      say: '额度、兑换码、优惠券都在账户中心。'
    },
    goSettings: {
      label: '去接入设置',
      run: () => { if (PAGE !== 'settings') { location.href = 'settings.html'; return; } const base = $('#cfgBase'); if (base) base.focus(); },
      say: '在接入设置里改 API Base、透传身份和模型。'
    },
    auth: { label: '去登录', run: () => goLogin(), say: '令牌有效期 12 小时，活动会自动续期。' },
    vip: {
      label: '刷新额度',
      run: () => { if (!has('#vipCard')) { location.href = 'account.html'; return; } refreshVip(); },
      say: '额度信息已刷新，账户中心的额度 Tab 也是最新的。'
    },
    redeem: {
      label: '去兑换',
      run: () => { if (!has('#redeemInput')) { location.href = 'account.html'; return; } $('#redeemInput').focus(); },
      say: '在账户中心输入兑换码就能开通 VIP。'
    },
    rag: { label: '检测 RAG', run: () => refreshRag(), say: '已重新探测向量服务。' },
    home: { label: '回到右下角', run: () => { placeFufu(fufuHome()); saveFufuPos(); fufuChatPosition(); }, say: '回到右下角啦，随时点我 ♪' },
    clear: { label: '清空对话', run: () => { const body = $('#fcBody'); if (body) body.innerHTML = ''; state.fcSessionId = ''; state.fcTaskId = ''; }, say: '对话清空啦，我们重新开始～' }
  };

  // 关键词 → 回答。按命中关键词长度累计打分，分数最高者生效
  const FC_INTENTS = [
    { k: ['你好', 'hello', '嗨', '在吗', '在不在', '早安', '早上好', '晚上好'],
      ans: () => '在的 ♪ 想聊聊平台，还是让我帮你上传、刷新或导出？' },

    { k: ['能做什么', '会什么', '功能', '帮助', '怎么用', '使用说明', '介绍', '你是谁'],
      ans: () => '我能做三件事：<br>1. 回答平台问题（格式、流程、额度、安全…）<br>2. 帮你操作界面（上传、解析、刷新、导出、打开设置）<br>3. 陪你聊两句，顺便把需求唱成结构 ♪',
      acts: () => ['upload', 'refresh', 'vip'] },

    { k: ['账户中心', '账户页', '页面', '导航', '切页', '去哪页', '换个页面'],
      ans: () => '页面有三块：<b>工作台</b>（上传 / 队列 / 报告）、<b>账户中心</b>（额度 / 兑换码 / 优惠券）、<b>接入设置</b>（API Base / 模型）。想去哪我直接带你跳。',
      acts: () => ['goAccount', 'goSettings'] },

    { k: ['上传', '导入', '传文件', '拖入', '选文件', '选择文件', '丢文件'],
      ans: () => '把文件拖进左侧「文档接入」，或者点我下面的按钮打开选择器。<b>md / txt / docx / pdf</b>，单文件 20MB 以内（PRO 50MB）。',
      acts: () => ['upload'] },

    { k: ['格式', '文件类型', '后缀', '支持什么', '支持哪些', 'pdf', 'docx', 'txt', '大小', 'mb'],
      ans: () => '支持 <b>md / txt / docx / pdf</b>，单文件 20MB（PRO 50MB）。上传时会做后缀 + 魔数双校验、md5 去重和相似度查重。',
      acts: () => ['upload'] },

    { k: ['开始解析', '创建任务', '解析一下', '开工', '跑起来'],
      ans: () => state.fileMetaId ? '文档已在库，我这就排进流水线 ♪' : '还没有待解析的文档哦，先选一份 PRD 吧。',
      acts: () => state.fileMetaId ? ['parse'] : ['upload'] },

    { k: ['失败', '报错', '错误', '重试', '没成功'],
      ans: () => state.selectedId ? '当前任务失败可以点右上角「重试」，我用同一份文档再跑一遍。' : '哪个任务出问题了？先在队列里选中它，我帮你重试。',
      acts: () => ['retry'] },

    { k: ['队列', '任务', '进度', '在跑', '状态', '刷新'],
      ans: fcQueueText,
      acts: () => ['refresh', 'retry'] },

    { k: ['输出', '产出', '包含什么', '报告里', '生成什么', '五块', '内容'],
      ans: () => '一份报告包含五块：<b>业务梳理</b>、<b>需求疑点清单</b>、<b>MySQL DDL</b>、<b>REST 接口契约</b>、<b>研发任务清单</b>。只出评审产出，不生成业务功能代码。' },

    { k: ['流程', '原理', '阶段', '流水线', '怎么实现', '怎么跑'],
      ans: () => '流水线固定 4 阶段串行：<b>业务理解 → 风险扫描 → 结构生成 → 汇总格式化</b>。LLM 超时走指数退避重试 + Sentinel 熔断，单阶段失败标记 FAIL，可人工重试。' },

    { k: ['导出', '下载', '导出md', '导出html', '导出docx', '导pdf', 'word', '生成文件'],
      ans: () => state.selectedId ? '当前报告可以导出成 <b>MD / HTML / DOCX</b>，链接一次性临时有效。' : '先在左侧队列点一份已完成的报告，我才能帮你导出。',
      acts: () => state.selectedId ? ['md', 'html', 'docx'] : ['refresh'] },

    { k: ['设置', '配置', '接口地址', '服务地址', 'apibase', '接入地址'],
      ans: () => '在「接入设置」页填 API Base（默认 http://localhost:8080），以及未登录时透传的租户 / 用户 / 角色，保存后我会重新拉一次状态。',
      acts: () => ['goSettings'] },

    { k: ['登录', '注册', '账号', '密码', '令牌', '身份'],
      ans: () => '账号密码登录用 BCrypt 校验 + Redis 不透明令牌，令牌 12 小时滑动续期；连续 5 次密码错误锁定 15 分钟。',
      acts: () => ['auth'] },

    { k: ['额度', '会员', 'vip', '套餐', '兑换', '并发', '配额', '上限', '价格', '多少钱'],
      ans: fcQuotaText,
      acts: () => ['vip'] },

    { k: ['rag', '向量', 'chroma', '检索', '召回', '知识库'],
      ans: () => 'RAG 当前状态：<b>' + escapeHtml(has('#ragText') ? $('#ragText').textContent : '未知') + '</b>。每个任务独立 Chroma collection，租户 + 任务双重隔离；召回失败自动降级本地相似度。',
      acts: () => ['rag'] },

    { k: ['模型', 'deepseek', 'qwen', '分块', 'semantic', 'fixed', 'chunk'],
      ans: () => '当前模型 <b>' + escapeHtml(modelCode()) + '</b>，分块策略 <b>' + escapeHtml(chunkStrategy()) + '</b>。semantic 按标点语义切，fixed 按固定长度切。',
      acts: () => ['goSettings'] },

    { k: ['技术栈', 'spring', 'mysql', 'redis', '架构', '后端', '依赖'],
      ans: () => 'SpringBoot 3.2.5 + MyBatis-Plus + MySQL 8 + Redis/Redisson + Sentinel + Chroma + OkHttp4，导出用 POI / PDFBox，指标走 Micrometer + Prometheus。' },

    { k: ['安全', '隐私', '隔离', '合规', '审计', '敏感词', '数据会不会'],
      ans: () => '租户 ID 全链路贯穿，数据库 / Redis / Chroma 三层隔离；敏感词过滤、操作审计、滑动窗口限流、IP 黑名单；密钥只从环境变量注入。' },

    { k: ['部署', '启动', 'docker', 'swagger', '健康', '接入服务'],
      ans: () => '后端 <b>mvn package</b> 后 <b>java -jar</b> 启动；健康探针 /actuator/health，接口文档 /swagger-ui/index.html；前端零依赖，静态托管即用。' },

    { k: ['追问', '继续问', '继续提问', '问答'],
      ans: () => '选中报告后，底部输入框可以基于报告与原文继续追问（Ctrl + Enter），回答会追加到报告末尾。' },

    { k: ['版本', 'diff', '对比', '差异', '待确认'],
      ans: () => '同一文档多次解析会生成版本快照，可以做报告 diff；疑点会被结构化成待确认清单。' },

    { k: ['关于我们', '你们', '团队', '公司', '谁做的', '开发者'],
      ans: () => '我们是 Prd2Api 团队——把 PRD 变成可评审的接口契约、DDL 和研发任务，让需求评审少一点扯皮、多一点依据。' },

    { k: ['拖动', '位置', '归位', '挡住', '角落', '消失'],
      ans: () => '我可以拖到屏幕任意角落，双击回到右下角；点我一下开这个对话框，再点一下收起。',
      acts: () => ['home'] },

    { k: ['再见', '拜拜', '关闭', '收起', '没事了', '退出'],
      ans: () => '好，有需要再点我，随时在岗 ♪',
      run: () => setTimeout(fufuChatHide, 900) },

    { k: ['谢谢', '感谢', 'thanks', 'thx'],
      ans: () => '不客气，随时喊我 ♪' },

    { k: ['清空', '重置', '重新开始'],
      ans: () => '好，把对话清干净～',
      run: () => { const body = $('#fcBody'); if (body) body.innerHTML = ''; } },

    { k: ['可爱', '喜欢', '好看', '厉害', '漂亮'],
      ans: () => '诶嘿，被你夸到了 ♪ 那要不要试试让我帮你上传一份 PRD？',
      acts: () => ['upload'] }
  ];

  function fcRespond(raw) {
    const q = String(raw || '').toLowerCase().replace(/\s+/g, '');
    if (!q) return;
    let best = null;
    let score = 0;
    FC_INTENTS.forEach((it) => {
      let s = 0;
      it.k.forEach((key) => { if (q.indexOf(key) >= 0) s += key.length; });
      if (s > score) { score = s; best = it; }
    });
    if (!best) {
      fcThink(() => fcSay('这个我还没学会… 你可以问我：平台能做什么 / 支持哪些格式 / 任务进度 / 额度怎么算。', ['upload', 'refresh', 'vip']));
      return;
    }
    if (best.run) best.run();
    const html = best.ans();
    const acts = typeof best.acts === 'function' ? best.acts() : (best.acts || []);
    fcThink(() => fcSay(html, acts));
  }

  /* ── 后端问答 Agent ──────────────────────── */
  // 后端返回的动作类型 → 界面可执行按钮，保持“问答 + 一键操作”闭环
  function fcActsFor(action) {
    if (!action || !action.type || action.type === 'NONE') return [];
    switch (action.type) {
      case 'TASK_LIST': return ['refresh', 'upload'];
      case 'TASK_DETAIL': return state.fcTaskId ? ['detail', 'refresh'] : ['refresh'];
      case 'QUOTA':
      case 'VIP': return ['vip'];
      case 'FILE_LIST': return ['upload', 'refresh'];
      case 'ADMIN_STATS': return ['refresh'];
      case 'NAVIGATE':
        if (action.target === '/upload') return ['upload'];
        if (action.target === '/vip') return ['vip'];
        return ['refresh'];
      default: return [];
    }
  }

  // 先走 SSE 流式通道：边执行边展示「思考 / 工具步骤」，失败再回落普通接口
  function fcAsk(text) {
    fcThink(() => {
      fcAskStreamed(text).catch((err) => {
        if (/登录|401/.test(String(err && err.message))) {
          fcSay('这个要读你的账号数据，先登录一下我吧 ♪', ['auth']);
          return;
        }
        fcAskBackend(text)
          .then((res) => fcSay(res.html, res.acts, res.trace))
          .catch((e) => {
            if (/登录|401/.test(String(e && e.message))) {
              fcSay('这个要读你的账号数据，先登录一下我吧 ♪', ['auth']);
              return;
            }
            fcRespond(text);
          });
      });
    });
  }

  // 流式对话：逐步点亮 Agent 过程，结束后再给最终答复与可执行动作
  async function fcAskStreamed(text) {
    if (!state.token) throw new Error('请先登录');
    const res = await fetch(state.settings.base + '/api/assistant/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + state.token },
      body: JSON.stringify({ sessionId: state.fcSessionId || undefined, message: text })
    });
    if (!res.ok || !res.body) throw new Error('流式接口不可用 HTTP ' + res.status);

    const live = fcLiveStart();
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let final = null;
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      buffer += decoder.decode(chunk.value, { stream: true });
      const frames = buffer.split('\n\n');
      buffer = frames.pop() || '';
      for (const frame of frames) {
        let name = 'message';
        let dataText = '';
        frame.split('\n').forEach((line) => {
          if (line.startsWith('event:')) name = line.slice(6).trim();
          else if (line.startsWith('data:')) dataText += line.slice(5).trim();
        });
        if (!dataText) continue;
        let payload;
        try { payload = JSON.parse(dataText); } catch (e) { continue; }
        if (name === 'phase') {
          fcLivePush(live, '第 ' + (payload.stepNo || 1) + ' 步：模型推理中…');
        } else if (name === 'step') {
          fcLiveStep(live, payload);
        } else if (name === 'final') {
          final = payload;
        } else if (name === 'error') {
          throw new Error(payload.message || '流式对话失败');
        }
      }
    }
    if (!final) throw new Error('流式对话未返回结果');
    state.fcSessionId = final.sessionId || state.fcSessionId || '';
    const action = final.action || null;
    if (action && action.type === 'TASK_DETAIL') {
      state.fcTaskId = String(action.target || '').split('/').pop();
    }
    fcLiveDone(live);
    const html = escapeHtml(final.reply || '').replace(/\n/g, '<br>');
    fcSay(html, fcActsFor(action),
      fcTrace(final.steps, final.mode, final.degraded, final.cacheHit, final.grounded, final.groundingNote));
    if (action && action.type === 'CONFIRM') {
      const body = $('#fcBody');
      if (body && body.lastElementChild) body.lastElementChild.appendChild(fcConfirmCard(action));
      fcScroll();
    }
    return final;
  }

  // 运行中的步骤流气泡：让用户看到 Agent 正在做什么，而不是干等
  function fcLiveStart() {
    const body = $('#fcBody');
    if (!body) return null;
    const node = document.createElement('div');
    node.className = 'fc-msg bot';
    node.innerHTML = '<span class="fc-who">MIKU</span><div class="fc-bubble">' +
      '<div class="fc-live"><b class="fc-live-title">Agent 运行中…</b><ul></ul></div></div>';
    body.appendChild(node);
    fcScroll();
    fufuChatPosition();
    return node.querySelector('ul');
  }

  function fcLivePush(list, text) {
    if (!list) return;
    const li = document.createElement('li');
    li.className = 'fc-live-item';
    li.textContent = text;
    list.appendChild(li);
    fcScroll();
  }

  function fcLiveStep(list, step) {
    if (!list) return;
    const bad = step.success === false;
    const kind = {
      TOOL: '工具', KNOWLEDGE: '知识库', MEMORY: '记忆', CONFIRM: '待确认', GUARD: '安全检查',
      CONTEXT: '上下文压缩', SELF_CHECK: '接地自检', CACHE: '语义缓存'
    }[step.type] || '步骤';
    const line = (bad ? '✕ ' : '✓ ') + (step.title || step.toolName || kind) +
      '（' + kind + (Number(step.latencyMs) ? ' · ' + step.latencyMs + 'ms' : '') + '）';
    const li = document.createElement('li');
    li.className = 'fc-live-item' + (bad ? ' fail' : '');
    li.textContent = line;
    list.appendChild(li);
    fcScroll();
  }

  function fcLiveDone(list) {
    if (!list) return;
    const title = list.parentElement && list.parentElement.querySelector('.fc-live-title');
    if (title) title.textContent = 'Agent 执行过程';
  }

  // 危险操作确认卡：必须人工点确认，后端才会消费票据并执行
  function fcConfirmCard(action) {
    const data = action.data || {};
    const wrap = document.createElement('div');
    wrap.className = 'fc-confirm';
    wrap.innerHTML = '<p class="fc-confirm-title">' + escapeHtml(action.label || '需要你确认') + '</p>' +
      '<p class="fc-confirm-desc">工具：' + escapeHtml(data.toolTitle || data.toolName || '') +
      ' · 风险：' + escapeHtml(data.risk || 'DANGER') + '</p>' +
      '<p class="fc-confirm-desc">' + escapeHtml(data.summary || '') + '</p>';
    const row = document.createElement('div');
    row.className = 'fc-confirm-btns';
    const ok = document.createElement('button');
    ok.type = 'button';
    ok.className = 'fc-confirm-ok';
    ok.textContent = '确认执行';
    const no = document.createElement('button');
    no.type = 'button';
    no.className = 'fc-confirm-no';
    no.textContent = '取消';
    row.appendChild(ok);
    row.appendChild(no);
    wrap.appendChild(row);

    ok.addEventListener('click', async () => {
      ok.disabled = true;
      no.disabled = true;
      ok.textContent = '执行中…';
      try {
        const vo = await api('/api/assistant/confirm/' + encodeURIComponent(action.target || ''), { method: 'POST', timeout: 60000 });
        wrap.innerHTML = '<p class="fc-confirm-title">已执行</p><p class="fc-confirm-desc">' +
          escapeHtml(vo.reply || '') + '</p>';
      } catch (e) {
        wrap.innerHTML = '<p class="fc-confirm-title">执行失败</p><p class="fc-confirm-desc">' +
          escapeHtml(String(e && e.message || e)) + '</p>';
      }
      fcScroll();
    });
    no.addEventListener('click', () => {
      wrap.innerHTML = '<p class="fc-confirm-desc">已取消，本次没有改动任何数据。</p>';
      fcScroll();
    });
    return wrap;
  }

  // 调后端 /api/assistant/chat：规则类问题直接执行，开放问题由后端 LLM/知识库回答
  async function fcAskBackend(text) {
    // 助手接口需要登录态，未登录时先提示，避免白跑一次 401
    if (!state.token) throw new Error('请先登录');
    const data = await api('/api/assistant/chat', {
      method: 'POST',
      body: { sessionId: state.fcSessionId || undefined, message: text },
      timeout: 60000
    });
    state.fcSessionId = data.sessionId || state.fcSessionId || '';
    const action = data.action || null;
    state.fcTaskId = action && action.type === 'TASK_DETAIL'
      ? String(action.target || '').split('/').pop()
      : state.fcTaskId;
    // 后端回复是纯文本，转义后再换行渲染，避免任何 HTML 注入
    const html = escapeHtml(data.reply || '').replace(/\n/g, '<br>');
    return {
      html: html,
      acts: fcActsFor(action),
      trace: fcTrace(data.steps, data.mode, data.degraded, data.cacheHit, data.grounded, data.groundingNote)
    };
  }

  // 首次打开时用后端推荐问题刷新快捷按钮
  async function fcLoadTips() {
    if (!state.token) return;
    try {
      const list = await api('/api/assistant/suggestions', { timeout: 8000 });
      const box = $('#fcTips');
      if (!box || !Array.isArray(list) || !list.length) return;
      box.innerHTML = '';
      list.slice(0, 4).forEach((text) => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'fc-tip';
        btn.textContent = text;
        box.appendChild(btn);
      });
    } catch (e) { /* 推荐问题拉取失败不影响对话 */ }
  }

  function fcRunAct(id) {
    const def = FC_ACTS[id];
    if (!def) return;
    fcAskUser(def.label);
    try { def.run(); } catch (e) { /* 单个操作异常不打断对话 */ }
    if (def.say) {
      const text = typeof def.say === 'function' ? def.say() : def.say;
      fcThink(() => fcSay(text));
    }
    fufuChatPosition();
  }

  function fufuChatShow() {
    const panel = $('#fufuChat');
    if (!panel || fufuChatOpen) return;
    fufuChatOpen = true;
    panel.classList.add('open');
    panel.setAttribute('aria-hidden', 'false');
    const bubble = $('#fufuBubble');
    if (bubble) bubble.classList.remove('show');
    fufuChatPosition();
    if (!fufuChatGreeted) {
      fufuChatGreeted = true;
      fcSay(FC_WELCOME, ['upload', 'refresh', 'vip']);
      fcLoadTips();
    }
    setMood('happy');
    if (innerWidth > 720) setTimeout(() => { const i = $('#fcInput'); if (i) i.focus({ preventScroll: true }); }, 220);
  }

  function fufuChatHide() {
    const panel = $('#fufuChat');
    if (!panel || !fufuChatOpen) return;
    fufuChatOpen = false;
    panel.classList.remove('open');
    const fufu = $('#fufu');
    if (fufu) fufu.focus({ preventScroll: true });   // 先把焦点移出面板，再标记隐藏
    panel.setAttribute('aria-hidden', 'true');
  }

  function fufuChatToggle() {
    if (fufuChatOpen) fufuChatHide(); else fufuChatShow();
  }

  function fcSubmit() {
    const input = $('#fcInput');
    if (!input) return;
    const text = input.value.trim();
    if (!text) return;
    input.value = '';
    fcAskUser(text);
    fcAsk(text);
  }

  function bootFufuChat() {
    const panel = $('#fufuChat');
    if (!panel) return;

    $('#fcClose').addEventListener('click', fufuChatHide);
    $('#fcSend').addEventListener('click', fcSubmit);
    $('#fcInput').addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.isComposing) { e.preventDefault(); fcSubmit(); }
      if (e.key === 'Escape') fufuChatHide();
    });

    $('#fcTips').addEventListener('click', (e) => {
      const tip = e.target.closest('.fc-tip');
      if (!tip) return;
      const text = tip.textContent.trim();
      fcAskUser(text);
      fcAsk(text);
    });

    $('#fcBody').addEventListener('click', (e) => {
      const btn = e.target.closest('.fc-act');
      if (!btn) return;
      fcRunAct(btn.dataset.act);
    });

    // 点击面板与玩偶之外的区域自动收起
    document.addEventListener('pointerdown', (e) => {
      if (!fufuChatOpen) return;
      if (e.target.closest('#fufuChat') || e.target.closest('#fufu')) return;
      fufuChatHide();
    }, true);

    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && fufuChatOpen) fufuChatHide();
    });
  }

  function bootFufu() {
    const el = $('#fufu');
    if (!el) return;
    const bubble = $('#fufuBubble');

    let saved = null;
    try { saved = JSON.parse(localStorage.getItem(FUFU_POS_KEY) || 'null'); } catch (e) { /* 忽略脏数据 */ }
    placeFufu(saved && typeof saved.x === 'number' && typeof saved.y === 'number' ? saved : fufuHome());

    let bubbleTimer = 0;
    let pokeTimer = 0;

    const say = (text, mood) => {
      if (fufuChatOpen) return;   // 对话框展开时不弹气泡，避免和面板打架
      bubble.textContent = text;
      bubble.classList.add('show');
      clearTimeout(bubbleTimer);
      bubbleTimer = setTimeout(() => bubble.classList.remove('show'), 2600);
      if (mood) setMood(mood);
    };

    let drag = null;
    let moved = false;

    // 首次访问且从未拖动过时，提示一次可拖拽
    if (!saved) {
      setTimeout(() => say('点我聊两句，也可以拖到任意角落 ♪'), 1600);
    }

    el.addEventListener('pointerdown', (ev) => {
      if (ev.pointerType === 'mouse' && ev.button !== 0) return;
      const rect = el.getBoundingClientRect();
      drag = { dx: ev.clientX - rect.left, dy: ev.clientY - rect.top, sx: ev.clientX, sy: ev.clientY };
      moved = false;
      try { el.setPointerCapture(ev.pointerId); } catch (e) { /* 忽略 */ }
    });

    el.addEventListener('pointermove', (ev) => {
      if (!drag) return;
      if (!moved && Math.abs(ev.clientX - drag.sx) + Math.abs(ev.clientY - drag.sy) < 5) return;
      if (!moved) { moved = true; el.classList.add('dragging'); }
      placeFufu({ x: ev.clientX - drag.dx, y: ev.clientY - drag.dy });
      if (fufuChatOpen) fufuChatPosition();   // 拖动时对话框跟着走
    });

    const endDrag = (ev) => {
      if (!drag) return;
      drag = null;
      try { if (el.hasPointerCapture(ev.pointerId)) el.releasePointerCapture(ev.pointerId); } catch (e) { /* 忽略 */ }
      el.classList.remove('dragging');
      if (moved) { saveFufuPos(); return; }
      // 延迟 220ms 再切换，给双击留出识别窗口，避免归位时把面板点掉
      clearTimeout(pokeTimer);
      pokeTimer = setTimeout(() => { pokeTimer = 0; fufuChatToggle(); }, 220);
    };

    el.addEventListener('pointerup', endDrag);
    el.addEventListener('pointercancel', endDrag);

    // 兜底：键盘、脚本或无障碍工具可能只派发合成 click（无 pointerup），这里补一次切换
    el.addEventListener('click', () => {
      if (moved || pokeTimer) return;
      fufuChatToggle();
    });

    // 双击归位到右下角默认位置
    el.addEventListener('dblclick', () => {
      clearTimeout(pokeTimer);
      pokeTimer = 0;
      placeFufu(fufuHome());
      saveFufuPos();
      if (fufuChatOpen) fufuChatPosition();
      say('回到原位啦 ♪');
    });

    el.addEventListener('keydown', (ev) => {
      if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); fufuChatToggle(); }
    });
  }

  /** 账户页：额度 / 优惠券 Tab、兑换、领取与管理员发券 */
  function bootAccount() {
    const tabs = $('#accTabs');
    if (tabs) {
      tabs.addEventListener('click', (e) => {
        const btn = e.target.closest('.acc-tab');
        if (!btn) return;
        tabs.querySelectorAll('.acc-tab').forEach((b) => {
          const selected = b === btn;
          b.classList.toggle('active', selected);
          b.setAttribute('aria-selected', selected ? 'true' : 'false');
        });
        const key = btn.dataset.acc;
        const quota = $('#accQuota');
        const coupon = $('#accCoupon');
        if (quota) quota.hidden = key !== 'quota';
        if (coupon) coupon.hidden = key !== 'coupon';
      });
    }

    on('#btnRedeem', 'click', doRedeem);
    on('#redeemInput', 'keydown', (e) => { if (e.key === 'Enter') doRedeem(); });
    on('#btnCouponRefresh', 'click', () => loadCoupons());
    on('#couponList', 'click', (e) => {
      const btn = e.target.closest('.js-claim');
      if (btn) claimCoupon(btn.dataset.id);
    });
    on('#couponMine', 'click', (e) => {
      const btn = e.target.closest('.js-use');
      if (btn) useCoupon(btn.dataset.id);
    });
    // 「管理」按钮：切换管理员发券 details 的展开状态
    on('#couponToggle', 'click', () => {
      const drawer = $('#adminDrawer');
      if (!drawer) return;
      drawer.open = !drawer.open;
    });
    on('#adminDrawer', 'toggle', async (e) => {
      if (!e.target.open) return;
      await loadPlans();
      await loadAdminCoupons();
    });
    on('#btnCreateCoupon', 'click', createCoupon);
    on('#adminCouponList', 'click', (e) => {
      const btn = e.target.closest('.js-offline');
      if (btn) offlineCoupon(btn.dataset.id);
    });
  }

  /** 工作台：文件拖拽上传与新建任务 */
  function bootUpload() {
    const dz = $('#dropzone');
    const input = $('#fileInput');
    if (!dz || !input) return;

    dz.addEventListener('click', () => input.click());
    input.addEventListener('change', () => { uploadFile(input.files[0]); input.value = ''; });
    let dragging = false;
    dz.addEventListener('dragover', (e) => {
      e.preventDefault();               // 必须同步阻止，拖放才能生效
      if (dragging) return;             // 高频触发只做一次 class 变更
      dragging = true;
      dz.classList.add('drag');
    });
    dz.addEventListener('dragleave', (e) => {
      e.preventDefault();
      if (dz.contains(e.relatedTarget)) return;
      dragging = false;
      dz.classList.remove('drag');
    });
    dz.addEventListener('drop', (e) => {
      e.preventDefault();
      dragging = false;
      dz.classList.remove('drag');
      uploadFile(e.dataTransfer.files && e.dataTransfer.files[0]);
    });

    on('#btnCreateTask', 'click', createTask);
  }

  /** 工作台：任务队列筛选与选中 */
  function bootQueue() {
    on('#btnRefresh', 'click', () => loadQueue(false));

    const filterRow = $('#filterRow');
    if (filterRow) {
      filterRow.addEventListener('click', (e) => {
        const btn = e.target.closest('.filter');
        if (!btn) return;
        state.filter = btn.dataset.status || '';
        filterRow.querySelectorAll('.filter').forEach(b => b.classList.toggle('active', b === btn));
        loadQueue(true);
      });
    }

    const queue = $('#queue');
    if (!queue) return;
    let lastPickId = '';
    let lastPickAt = 0;
    queue.addEventListener('click', (e) => {
      const card = e.target.closest('.task');
      if (!card) return;
      const id = card.dataset.id;
      const now = Date.now();
      if (id === lastPickId && now - lastPickAt < 400) return;  // 误双击同一张卡片只取第一次
      lastPickId = id;
      lastPickAt = now;
      loadDetail(id, false);
    });
  }

  /** 工作台：报告区导出 / 重试 / 删除 / 追问 */
  function bootReport() {
    on('#btnExportMd', 'click', () => exportReport('md'));
    on('#btnExportHtml', 'click', () => exportReport('html'));
    on('#btnExportDocx', 'click', () => exportReport('docx'));
    on('#btnRetry', 'click', retryTask);
    on('#btnDelete', 'click', deleteTask);
    on('#btnChat', 'click', sendChat);
    on('#chatInput', 'keydown', (e) => {
      if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) sendChat();
    });

    const reportBody = $('#reportBody');
    if (!reportBody) return;
    reportBody.addEventListener('scroll', rafThrottle(() => {
      const max = reportBody.scrollHeight - reportBody.clientHeight;
      const ratio = max > 0 ? reportBody.scrollTop / max : 0;
      const bar = $('#scrollBar');
      if (bar) bar.style.width = Math.round(ratio * 100) + '%';
    }));
  }

  /** 接入设置页：保存配置与重新探测 RAG */
  function bootSettings() {
    on('#cfgSave', 'click', () => {
      saveSettings();
      toast('接入配置已保存');
      refreshRag();
      if (has('#queue')) {
        loadQueue(true);
        if (state.selectedId) loadDetail(state.selectedId, true);
      }
      speak('已连上 ' + state.settings.base + ' ♪', 'happy');
    });
    on('#btnRagRefresh', 'click', () => { refreshRag(); toast('正在重新探测 RAG'); });
  }

  /** 所有页面共用：导航高亮、登录按钮、点按水波纹、星尘与窗口尺寸 */
  function bootChrome() {
    document.querySelectorAll('.btn').forEach(addRipple);

    const active = PAGE === 'account' ? 'account.html' : PAGE === 'settings' ? 'settings.html' : 'index.html';
    document.querySelectorAll('.nav a').forEach((a) => {
      a.classList.toggle('active', (a.getAttribute('href') || '') === active);
    });

    on('#authToggle', 'click', () => { if (state.token) doLogout(); else goLogin(); });

    // canvas 尺寸重算按帧节流，粒子重排防抖，避免拖拽窗口时反复重建
    window.addEventListener('resize', rafThrottle(resizeCanvas));
    window.addEventListener('resize', debounce(seedParticles, 200));
    // 窗口变化后把玩偶夹回可视区域，防止被推到屏幕外
    window.addEventListener('resize', rafThrottle(() => {
      if (!has('#fufu')) return;
      placeFufu(state.fufuPos || fufuHome());
      if (fufuChatOpen) fufuChatPosition();
    }));
  }

  function bind() {
    bootChrome();
    if (has('#fufu')) bootFufu();
    if (has('#fufuChat')) bootFufuChat();
    if (has('#dropzone')) bootUpload();
    if (has('#queue')) bootQueue();
    if (has('#reportBody')) bootReport();
    if (has('#vipCard')) bootAccount();
    if (has('#cfgBase')) bootSettings();
  }

  function boot() {
    loadSettings();
    loadAuth();
    bind();
    renderEmptyReport();
    bootParticles();
    refreshRag();
    loadQueue(true);
    refreshVip();
    loadMe();
    loadCoupons();
    if (has('#speech')) {
      setTimeout(() => speak('把 PRD 丢进来吧，我帮你把需求唱成代码的结构 ♪'), 900);
    }

    if (has('#queue')) {
      state.polling = setInterval(() => {
        if (document.hidden) return;   // 后台标签页暂停轮询，减少无效请求
        const running = state.tasks.some(t => t.taskStatus === 'INIT' || t.taskStatus === 'PROCESS');
        if (running) loadQueue(true);
        setEq(running);
      }, 4000);
    }

    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) loadQueue(true);   // 回到前台立即补一次最新状态
    });
  }

  document.addEventListener('DOMContentLoaded', boot);
})();