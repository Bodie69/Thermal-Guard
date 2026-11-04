/*
 * Thermal Guard - zone details panel.
 * Tap a zone card to open an in-depth view of that zone.
 * Listens for the 'thermalguard:live' event dispatched by kiosk.js after every
 * successful /api/live poll, and keeps a short rolling history per zone
 * (in memory only, so it restarts whenever the kiosk page reloads).
 */
(() => {
  'use strict';

  const ZONES = {
    entrance: { label: 'Entrance', zone: 'Zone 1', icon: '<path d="M5 21V4l12-2v19M17 21h3V5h-3M10 12h.01"/>' },
    exit: { label: 'Exit', zone: 'Zone 2', icon: '<path d="M10 4H4v16h6M13 8l4 4-4 4M8 12h9"/>' },
    front: { label: 'Front', zone: 'Zone 3', icon: '<circle cx="8" cy="8" r="3"/><circle cx="16" cy="8" r="3"/><path d="M2 20c0-4 2.6-6 6-6s6 2 6 6M10 20c0-4 2.6-6 6-6s6 2 6 6"/>' }
  };
  const HISTORY_MS = 10 * 60 * 1000;   // chart window
  const TREND_MS = 60 * 1000;          // trend slope window
  const AUTO_CLOSE_MS = 120 * 1000;    // return to overview when left open

  const history = { entrance: [], exit: [], front: [] };
  const session = {};                  // min/max since kiosk start
  let latest = null;                   // last /api/live payload
  let openId = null;
  let idleTimer = null;
  let ageTimer = null;
  const el = {};

  /* ---------- helpers ---------- */
  // Number(null) and Number('') are 0, so treat empty values as missing first.
  const num = (v) => {
    if (v === null || v === undefined || v === '' || typeof v === 'boolean') return null;
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  };
  const fx = (v, d = 1) => (num(v) === null ? '--' : num(v).toFixed(d));
  const tempOf = (z) => num(z?.temperature ?? z?.temperature_c ?? z?.temp_c ?? z?.temp);
  const humOf = (z) => num(z?.humidity ?? z?.humidity_percent ?? z?.rh);

  function statusOf(z) {
    const raw = String(z?.status ?? '').toLowerCase();
    if (raw.includes('critical')) return 'critical';
    if (raw.includes('warning')) return 'warning';
    if (raw.includes('normal')) return 'normal';
    if (/offline|missing|retry|fault/.test(raw) || z?.online === false) return 'offline';
    return tempOf(z) !== null && humOf(z) !== null ? 'normal' : 'offline';
  }

  // Magnus formula
  function dewPoint(t, rh) {
    if (t === null || rh === null || rh <= 0) return null;
    const a = 17.62, b = 243.12;
    const g = Math.log(rh / 100) + (a * t) / (b + t);
    return (b * g) / (a - g);
  }

  // NOAA heat index (Rothfusz regression with adjustments)
  function heatIndex(t, rh) {
    if (t === null || rh === null) return null;
    const F = t * 9 / 5 + 32;
    let hi = 0.5 * (F + 61 + (F - 68) * 1.2 + rh * 0.094);
    if ((hi + F) / 2 >= 80) {
      hi = -42.379 + 2.04901523 * F + 10.14333127 * rh - 0.22475541 * F * rh
        - 0.00683783 * F * F - 0.05481717 * rh * rh + 0.00122874 * F * F * rh
        + 0.00085282 * F * rh * rh - 0.00000199 * F * F * rh * rh;
      if (rh < 13 && F >= 80 && F <= 112) hi -= ((13 - rh) / 4) * Math.sqrt((17 - Math.abs(F - 95)) / 17);
      else if (rh > 85 && F >= 80 && F <= 87) hi += ((rh - 85) / 10) * ((87 - F) / 5);
    }
    return (hi - 32) * 5 / 9;
  }

  // PAGASA heat index classification
  function heatLevel(hi) {
    if (hi === null) return { text: '--', cls: '' };
    if (hi < 27) return { text: 'Not hazardous', cls: 'ok' };
    if (hi < 33) return { text: 'Caution', cls: 'caution' };
    if (hi < 42) return { text: 'Extreme caution', cls: 'warn' };
    if (hi < 52) return { text: 'Danger', cls: 'danger' };
    return { text: 'Extreme danger', cls: 'danger' };
  }

  // least-squares slope in degrees per minute over the trend window
  function trendOf(id) {
    const now = Date.now();
    const pts = history[id].filter((p) => now - p.t <= TREND_MS);
    if (pts.length < 5) return null;
    const n = pts.length, t0 = pts[0].t;
    let sx = 0, sy = 0, sxx = 0, sxy = 0;
    for (const p of pts) { const x = (p.t - t0) / 60000; sx += x; sy += p.temp; sxx += x * x; sxy += x * p.temp; }
    const den = n * sxx - sx * sx;
    return den === 0 ? null : (n * sxy - sx * sy) / den;
  }

  function ago(ms) {
    if (ms === null || !Number.isFinite(ms)) return '--';
    const s = Math.max(0, Math.round(ms / 1000));
    if (s < 60) return s + ' s ago';
    const m = Math.floor(s / 60);
    if (m < 60) return m + ' min ' + (s % 60) + ' s ago';
    const h = Math.floor(m / 60);
    return h + ' h ' + (m % 60) + ' min ago';
  }

  const svg = (inner) => `<svg viewBox="0 0 24 24" aria-hidden="true">${inner}</svg>`;

  /* ---------- data intake ---------- */
  function ingest(payload) {
    latest = payload;
    const zones = payload?.zones ?? {};
    const now = Date.now();
    for (const id of Object.keys(ZONES)) {
      const z = zones[id] ?? (id === 'front' ? zones.front_of_comlab_1 : null) ?? {};
      const t = tempOf(z), h = humOf(z);
      const st = statusOf(z);
      const list = history[id];
      const stamp = num(z.sample_epoch_ms) ?? now;
      if (st !== 'offline' && t !== null && h !== null && (!list.length || list[list.length - 1].stamp !== stamp)) {
        list.push({ t: now, stamp, temp: t, hum: h });
        const s = session[id] ?? (session[id] = { tMin: t, tMax: t, hMin: h, hMax: h, since: now });
        s.tMin = Math.min(s.tMin, t); s.tMax = Math.max(s.tMax, t);
        s.hMin = Math.min(s.hMin, h); s.hMax = Math.max(s.hMax, h);
      }
      while (list.length && now - list[0].t > HISTORY_MS) list.shift();
    }
    if (openId) render();
  }

  /* ---------- panel markup ---------- */
  function build() {
    const overlay = document.createElement('div');
    overlay.className = 'zd-overlay';
    overlay.id = 'zdOverlay';

    const panel = document.createElement('section');
    panel.className = 'zd-panel';
    panel.id = 'zdPanel';
    panel.setAttribute('role', 'dialog');
    panel.setAttribute('aria-modal', 'true');
    panel.setAttribute('aria-hidden', 'true');
    panel.setAttribute('aria-labelledby', 'zdTitle');
    panel.innerHTML = `
      <header class="zd-head">
        <div class="zd-icon" data-zd="icon"></div>
        <div class="zd-title"><h2 id="zdTitle" data-zd="title">--</h2><p data-zd="subtitle">--</p></div>
        <span class="zd-badge" data-zd="badge"><i></i><b>--</b></span>
        <button class="zd-close" type="button" aria-label="Close details">${svg('<path d="m6 6 12 12M18 6 6 18"/>')}</button>
      </header>

      <div class="zd-body">
        <div class="zd-main">
          <div class="zd-readings">
            <div class="zd-reading temp">
              <small>Temperature</small>
              <div class="zd-value"><strong data-zd="temp">--.-</strong><span>°C</span><em class="zd-trend" data-zd="trend"></em></div>
            </div>
            <div class="zd-reading hum">
              <small>Humidity</small>
              <div class="zd-value"><strong data-zd="hum">--.-</strong><span>% RH</span></div>
            </div>
          </div>

          <div class="zd-chart">
            <div class="zd-chart-head"><small>Temperature · last <b data-zd="span">10 min</b></small><small data-zd="range"></small></div>
            <div class="zd-chart-box">
              <svg data-zd="chart" viewBox="0 0 600 150" preserveAspectRatio="none" aria-hidden="true"></svg>
              <div class="zd-chart-empty" data-zd="chartEmpty">Collecting readings…</div>
              <span class="zd-axis top" data-zd="yMax"></span>
              <span class="zd-axis bottom" data-zd="yMin"></span>
            </div>
          </div>

          <div class="zd-threshold">
            <div class="zd-bar"><div class="zd-bar-fill" data-zd="barFill"></div>
              <i class="zd-mark warn" data-zd="markWarn"></i><i class="zd-mark crit" data-zd="markCrit"></i>
              <i class="zd-pointer" data-zd="pointer"></i></div>
            <div class="zd-bar-legend"><span data-zd="margin">--</span><span><b class="w">▮</b> Warning <b data-zd="warnVal">--</b> &nbsp; <b class="c">▮</b> Critical <b data-zd="critVal">--</b></span></div>
          </div>
        </div>

        <aside class="zd-side">
          <div class="zd-tiles">
            <div class="zd-tile"><small>Feels like</small><strong data-zd="hi">--</strong><em data-zd="hiLevel">--</em></div>
            <div class="zd-tile"><small>Dew point</small><strong data-zd="dew">--</strong><em data-zd="dewNote">--</em></div>
            <div class="zd-tile"><small>Temp min / max</small><strong data-zd="tRange">--</strong><em data-zd="since">since kiosk start</em></div>
            <div class="zd-tile"><small>Humidity min / max</small><strong data-zd="hRange">--</strong><em>% RH</em></div>
          </div>

          <dl class="zd-health">
            <div><dt>Sensor</dt><dd data-zd="source">--</dd></div>
            <div><dt>Signal quality</dt><dd data-zd="quality">--</dd></div>
            <div><dt>Last reading</dt><dd data-zd="age">--</dd></div>
            <div><dt>Errors (now / total)</dt><dd data-zd="errors">--</dd></div>
            <div><dt>Reconnects</dt><dd data-zd="reconnects">--</dd></div>
            <div class="wide"><dt>Last error</dt><dd data-zd="lastError">None</dd></div>
          </dl>
        </aside>
      </div>`;

    document.body.append(overlay, panel);
    el.overlay = overlay;
    el.panel = panel;
    panel.querySelectorAll('[data-zd]').forEach((n) => { el[n.dataset.zd] = n; });

    overlay.addEventListener('click', close);
    panel.querySelector('.zd-close').addEventListener('click', close);
    panel.addEventListener('pointerdown', bumpIdle);
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && openId) close(); });
  }

  /* ---------- open / close ---------- */
  function open(id) {
    if (!ZONES[id]) return;
    openId = id;
    const meta = ZONES[id];
    el.icon.innerHTML = svg(meta.icon);
    el.icon.className = 'zd-icon ' + id;
    el.title.textContent = meta.label;
    render();
    el.overlay.classList.add('show');
    el.panel.classList.add('show');
    el.panel.setAttribute('aria-hidden', 'false');
    bumpIdle();
    clearInterval(ageTimer);
    ageTimer = setInterval(renderAge, 1000);
  }

  function close() {
    openId = null;
    el.overlay.classList.remove('show');
    el.panel.classList.remove('show');
    el.panel.setAttribute('aria-hidden', 'true');
    clearTimeout(idleTimer);
    clearInterval(ageTimer);
  }

  function bumpIdle() {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(close, AUTO_CLOSE_MS);
  }

  /* ---------- rendering ---------- */
  function currentZone() {
    const zones = latest?.zones ?? {};
    return zones[openId] ?? (openId === 'front' ? zones.front_of_comlab_1 : null) ?? {};
  }

  function render() {
    if (!openId) return;
    const z = currentZone();
    const meta = ZONES[openId];
    const sensorSt = statusOf(z);
    const offline = sensorSt === 'offline';
    // Alarm held by the backend while the sensor is offline (siren stays on).
    const alarm = String(z?.alarm_state ?? '').toLowerCase();
    const sensorLost = offline && (alarm === 'critical' || alarm === 'warning');
    const st = sensorLost ? alarm : sensorSt;
    const t = offline ? null : tempOf(z);
    const h = offline ? null : humOf(z);
    const warn = num(latest?.warning_temp_c ?? latest?.thresholds?.warning);
    const crit = num(latest?.critical_temp_c ?? latest?.thresholds?.critical);

    el.panel.dataset.status = st;
    el.subtitle.textContent = `${meta.zone} · ${
      sensorLost ? `Sensor offline · ${st.toUpperCase()} alarm held until it reads normal`
      : offline ? 'No live data' : 'Live · updates every 3 s'}`;
    el.badge.querySelector('b').textContent = st.toUpperCase() + (sensorLost ? ' · NO SENSOR' : '');

    el.temp.textContent = fx(t, 2);
    el.hum.textContent = fx(h, 2);

    const slope = offline ? null : trendOf(openId);
    if (slope === null) { el.trend.textContent = ''; el.trend.className = 'zd-trend'; }
    else {
      const dir = slope > 0.05 ? 'up' : slope < -0.05 ? 'down' : 'flat';
      el.trend.className = 'zd-trend ' + dir;
      el.trend.textContent = (dir === 'up' ? '▲ ' : dir === 'down' ? '▼ ' : '► ') +
        (dir === 'flat' ? 'Steady' : `${slope > 0 ? '+' : ''}${slope.toFixed(2)}°/min`);
    }

    // derived comfort values
    const hi = heatIndex(t, h);
    const lvl = heatLevel(hi);
    el.hi.textContent = hi === null ? '--' : `${hi.toFixed(1)} °C`;
    el.hiLevel.textContent = lvl.text;
    el.hiLevel.className = 'lvl ' + lvl.cls;
    const dp = dewPoint(t, h);
    el.dew.textContent = dp === null ? '--' : `${dp.toFixed(1)} °C`;
    el.dewNote.textContent = dp === null ? '--' : dp >= 24 ? 'Very humid' : dp >= 20 ? 'Humid' : dp >= 16 ? 'Comfortable' : 'Dry';

    const s = session[openId];
    el.tRange.textContent = s ? `${s.tMin.toFixed(1)} / ${s.tMax.toFixed(1)} °C` : '--';
    el.hRange.textContent = s ? `${s.hMin.toFixed(1)} / ${s.hMax.toFixed(1)}` : '--';
    el.since.textContent = s ? `since ${new Date(s.since).toLocaleTimeString('en-PH', { timeZone: 'Asia/Manila', hour: '2-digit', minute: '2-digit' })}` : 'since kiosk start';

    // sensor health
    const src = String(z.source ?? '');
    el.source.textContent = src.includes('sht31') ? `SHT31${src.includes('tca') ? ' via TCA9548A' : ''}` : (src || '--');
    el.quality.textContent = z.quality ? String(z.quality) : '--';
    el.quality.className = /good/i.test(z.quality ?? '') ? 'good' : z.quality ? 'bad' : '';
    el.errors.textContent = `${num(z.consecutive_errors) ?? 0} / ${num(z.total_errors) ?? 0}`;
    el.errors.className = (num(z.consecutive_errors) ?? 0) > 0 ? 'bad' : '';
    el.reconnects.textContent = String(num(z.reconnects) ?? 0);
    const errAt = num(z.last_error_at);
    el.lastError.textContent = z.error
      ? String(z.error) + (errAt ? ` (${ago(Date.now() - errAt * 1000)})` : '')
      : errAt ? `Recovered · ${ago(Date.now() - errAt * 1000)}` : 'None';
    el.lastError.className = z.error ? 'bad' : '';
    renderAge();

    renderThreshold(t, warn, crit);
    renderChart(warn, crit);
  }

  function renderAge() {
    if (!openId) return;
    const z = currentZone();
    const seen = num(z.last_seen);
    el.age.textContent = seen ? ago(Date.now() - seen * 1000) : '--';
    el.age.className = seen && Date.now() - seen * 1000 > 15000 ? 'bad' : '';
  }

  function renderThreshold(t, warn, crit) {
    el.warnVal.textContent = warn === null ? '--' : `${warn.toFixed(1)}°`;
    el.critVal.textContent = crit === null ? '--' : `${crit.toFixed(1)}°`;
    const lo = 20;
    const hi = Math.max((crit ?? 38) + 4, (t ?? 0) + 1);
    const pos = (v) => `${Math.min(100, Math.max(0, ((v - lo) / (hi - lo)) * 100))}%`;
    el.markWarn.style.left = warn === null ? '-99px' : pos(warn);
    el.markCrit.style.left = crit === null ? '-99px' : pos(crit);
    if (t === null) {
      el.pointer.style.display = 'none';
      el.barFill.style.width = '0';
      el.margin.textContent = 'No live reading';
      return;
    }
    el.pointer.style.display = '';
    el.pointer.style.left = pos(t);
    el.barFill.style.width = pos(t);
    if (crit !== null && t >= crit) el.margin.textContent = `${(t - crit).toFixed(1)} °C above critical`;
    else if (warn !== null && t >= warn) el.margin.textContent = crit !== null ? `${(crit - t).toFixed(1)} °C below critical` : `${(t - warn).toFixed(1)} °C above warning`;
    else if (warn !== null) el.margin.textContent = `${(warn - t).toFixed(1)} °C below warning`;
    else el.margin.textContent = '';
  }

  function renderChart(warn, crit) {
    const pts = history[openId];
    const W = 600, H = 150, PAD = 10;
    if (pts.length < 2) {
      el.chart.innerHTML = '';
      el.chartEmpty.style.display = '';
      el.yMax.textContent = el.yMin.textContent = el.range.textContent = '';
      el.span.textContent = '10 min';
      return;
    }
    el.chartEmpty.style.display = 'none';
    const now = Date.now();
    const t0 = Math.max(pts[0].t, now - HISTORY_MS);
    const spanMs = Math.max(now - t0, 1000);
    const temps = pts.map((p) => p.temp);
    let yMin = Math.min(...temps), yMax = Math.max(...temps);
    // pull threshold lines into view when the reading is close to them
    if (warn !== null && warn - yMax < 3) yMax = Math.max(yMax, warn);
    if (crit !== null && crit - yMax < 3) yMax = Math.max(yMax, crit);
    if (yMax - yMin < 1) { const mid = (yMax + yMin) / 2; yMin = mid - 0.5; yMax = mid + 0.5; }
    yMin -= (yMax - yMin) * 0.12; yMax += (yMax - yMin) * 0.12;
    const x = (t) => ((t - t0) / spanMs) * W;
    const y = (v) => PAD + (1 - (v - yMin) / (yMax - yMin)) * (H - PAD * 2);

    const line = pts.map((p, i) => `${i ? 'L' : 'M'}${x(p.t).toFixed(1)},${y(p.temp).toFixed(1)}`).join('');
    const area = `${line}L${x(pts[pts.length - 1].t).toFixed(1)},${H}L${x(pts[0].t).toFixed(1)},${H}Z`;
    const hLine = (v, cls) => (v !== null && v >= yMin && v <= yMax)
      ? `<line class="${cls}" x1="0" x2="${W}" y1="${y(v).toFixed(1)}" y2="${y(v).toFixed(1)}" vector-effect="non-scaling-stroke"/>` : '';
    const grid = [0.25, 0.5, 0.75].map((f) => `<line class="grid" x1="0" x2="${W}" y1="${(PAD + f * (H - PAD * 2)).toFixed(1)}" y2="${(PAD + f * (H - PAD * 2)).toFixed(1)}" vector-effect="non-scaling-stroke"/>`).join('');
    const last = pts[pts.length - 1];

    el.chart.innerHTML = `
      <defs><linearGradient id="zdArea" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stop-color="currentColor" stop-opacity=".32"/><stop offset="1" stop-color="currentColor" stop-opacity="0"/>
      </linearGradient></defs>
      ${grid}${hLine(warn, 'warn')}${hLine(crit, 'crit')}
      <path class="area" d="${area}" fill="url(#zdArea)"/>
      <path class="line" d="${line}" vector-effect="non-scaling-stroke"/>
      <circle class="dot" cx="${x(last.t).toFixed(1)}" cy="${y(last.temp).toFixed(1)}" r="4" vector-effect="non-scaling-stroke"/>`;

    el.yMax.textContent = `${yMax.toFixed(1)}°`;
    el.yMin.textContent = `${yMin.toFixed(1)}°`;
    const mins = Math.max(1, Math.round(spanMs / 60000));
    el.span.textContent = spanMs >= HISTORY_MS - 5000 ? '10 min' : `${mins} min`;
    el.range.textContent = `min ${Math.min(...temps).toFixed(1)}° · max ${Math.max(...temps).toFixed(1)}°`;
  }

  /* ---------- wire up ---------- */
  function init() {
    build();
    for (const id of Object.keys(ZONES)) {
      const card = document.getElementById('zone-' + id);
      if (!card) continue;
      card.classList.add('zd-tappable');
      card.setAttribute('role', 'button');
      card.setAttribute('tabindex', '0');
      card.setAttribute('aria-label', `${ZONES[id].label} details`);
      if (!card.querySelector('.zd-hint')) {
        const hint = document.createElement('span');
        hint.className = 'zd-hint';
        hint.setAttribute('aria-hidden', 'true');
        hint.textContent = 'Details ›';
        card.appendChild(hint);
      }
      card.addEventListener('click', () => open(id));
      card.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(id); } });
    }
    window.addEventListener('thermalguard:live', (e) => ingest(e.detail));
    window.ThermalGuardZoneDetails = { open, close };
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
