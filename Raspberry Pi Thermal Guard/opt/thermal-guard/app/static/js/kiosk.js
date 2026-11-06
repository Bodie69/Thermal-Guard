(() => {
  'use strict';

  const $ = (s) => document.querySelector(s);
  const zoneIds = ['entrance', 'exit', 'front'];
  let liveTimer = null;
  let fitTimer = null;

  const numberOrNull = (v) => {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  };

  const format2 = (v) => {
    const n = numberOrNull(v);
    return n === null ? '--.--' : n.toFixed(2);
  };

  function updateClock() {
    const now = new Date();

    const dateEl = $('#currentDate');
    const timeEl = $('#currentTime');

    if (dateEl) {
      dateEl.textContent = now.toLocaleDateString('en-PH', {
        timeZone: 'Asia/Manila',
        weekday: 'short',
        month: 'short',
        day: 'numeric',
        year: 'numeric'
      });
    }

    if (timeEl) {
      timeEl.textContent = now.toLocaleTimeString('en-PH', {
        timeZone: 'Asia/Manila',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hour12: true
      });
    }
  }

  function getZones(payload) {
    return (
      payload?.zones ??
      payload?.sensors ??
      payload?.live?.zones ??
      payload?.data?.zones ??
      {}
    );
  }

  function getZone(zones, id) {
    if (zones?.[id]) return zones[id];

    if (id === 'front') {
      return (
        zones?.front_of_comlab_1 ??
        zones?.front_of_comlab1 ??
        zones?.front_comlab1 ??
        zones?.front ??
        {}
      );
    }

    return {};
  }

  function normalizeStatus(zone) {
    const raw = String(zone?.status ?? zone?.state ?? '').trim().toLowerCase();

    if (raw.includes('critical')) return 'critical';
    if (raw.includes('warning')) return 'warning';
    if (raw.includes('normal')) return 'normal';
    if (
      raw.includes('offline') ||
      raw.includes('missing') ||
      raw.includes('retry') ||
      raw.includes('fault')
    ) {
      return 'offline';
    }

    if (zone?.online === false) return 'offline';
    if (zone?.online === true) return 'normal';

    const temp = numberOrNull(
      zone?.temperature ??
      zone?.temperature_c ??
      zone?.temp_c ??
      zone?.temp
    );

    const hum = numberOrNull(
      zone?.humidity ??
      zone?.humidity_percent ??
      zone?.rh
    );

    return temp !== null && hum !== null ? 'normal' : 'offline';
  }

  function setZone(id, zone) {
    const card = $('#zone-' + id);
    const badge = document.querySelector(`[data-zone-status="${id}"]`);
    const tempEl = document.querySelector(`[data-zone-temp="${id}"]`);
    const humEl = document.querySelector(`[data-zone-hum="${id}"]`);

    const sensorStatus = normalizeStatus(zone);
    // The backend holds a zone's alarm while its sensor is offline (the siren
    // stays on). Show that alarm level instead of a plain OFFLINE.
    const alarm = String(zone?.alarm_state ?? '').trim().toLowerCase();
    const sensorLost = sensorStatus === 'offline' && (alarm === 'critical' || alarm === 'warning');
    const status = sensorLost ? alarm : sensorStatus;

    if (card) {
      card.classList.remove('normal', 'warning', 'critical', 'offline');
      card.classList.add(status);
      card.classList.toggle('sensor-lost', sensorLost);
    }

    if (badge) {
      badge.innerHTML = '<i></i>' + status.toUpperCase() + (sensorLost ? ' · NO SENSOR' : '');
      badge.title = sensorLost ? 'Sensor offline - alarm held until it reads normal again' : '';
    }

    const temperature =
      zone?.temperature ??
      zone?.temperature_c ??
      zone?.temp_c ??
      zone?.temp;

    const humidity =
      zone?.humidity ??
      zone?.humidity_percent ??
      zone?.rh;

    if (tempEl) {
      tempEl.textContent = sensorStatus === 'offline' ? '--.--' : format2(temperature);
    }

    if (humEl) {
      humEl.textContent = sensorStatus === 'offline' ? '--.--' : format2(humidity);
    }

    return status;
  }

  function setFirebase(payload) {
    // firebase_online = the Pi really reached Firebase recently. firebase_ready
    // only means "SDK initialised" and stayed true with the Wi-Fi off, so it is
    // used only for an older backend that does not send the new fields.
    const online = Boolean(
      payload?.firebase_online ??
      payload?.firebase_connected ??
      payload?.firebase_ready ??
      false
    );

    const state = $('#firebaseState');
    const dot = $('#firebaseDot');

    if (state) {
      state.textContent = online ? 'Online' : 'Offline';
      state.title = online ? '' : 'No internet: alerts are kept on the Raspberry Pi and sent when it reconnects. SMS still works.';
    }
    if (dot) dot.className = 'dot ' + (online ? 'green' : 'red');
  }

  function setSensorCount(payload, statuses) {
    let online = numberOrNull(payload?.online_sensors);
    const total = numberOrNull(payload?.total_sensors) ?? 3;

    if (online === null) {
      online = statuses.filter((s) => s !== 'offline').length;
    }

    const count = $('#sensorCount');
    const dot = $('#sensorDot');

    if (count) count.textContent = `${online} / ${total}`;

    if (dot) {
      dot.className =
        'dot ' +
        (online <= 0 ? 'red' : online >= total ? 'green' : 'amber');
    }
  }

  function setThresholds(payload) {
    const warning = numberOrNull(
      payload?.warning_temp_c ??
      payload?.settings?.warning_temp_c ??
      payload?.thresholds?.warning_temp_c ??
      payload?.thresholds?.warning
    );

    const critical = numberOrNull(
      payload?.critical_temp_c ??
      payload?.settings?.critical_temp_c ??
      payload?.thresholds?.critical_temp_c ??
      payload?.thresholds?.critical
    );

    const warningEl = $('#warningValue');
    const criticalEl = $('#criticalValue');

    if (warningEl) {
      warningEl.textContent =
        warning === null ? '--.- °C' : `${warning.toFixed(1)} °C`;
    }

    if (criticalEl) {
      criticalEl.textContent =
        critical === null ? '--.- °C' : `${critical.toFixed(1)} °C`;
    }
  }

  let sirenRevertMinutes = 30;

  function setSiren(payload) {
    const relay = payload?.relay ?? payload?.siren ?? {};

    const mode = String(
      relay?.mode ??
      payload?.relay_mode ??
      payload?.siren_mode ??
      'auto'
    ).toUpperCase();

    const raw =
      relay?.state ??
      relay?.on ??
      relay?.active ??
      payload?.relay_state ??
      payload?.siren_state ??
      false;

    const on =
      raw === true ||
      raw === 1 ||
      String(raw).toLowerCase() === 'on' ||
      String(raw).toLowerCase() === 'true';

    const state = $('#sirenState');
    const card = $('#sirenCard');

    // Manual OFF is temporary: show when it returns to AUTO.
    const revertMin = Number(relay?.off_revert_minutes);
    if (Number.isFinite(revertMin)) sirenRevertMinutes = revertMin;
    const left = Number(relay?.off_remaining_seconds);
    const countdown = mode === 'OFF' && Number.isFinite(left) && relay?.off_remaining_seconds !== null
      ? ` • AUTO in ${Math.max(1, Math.ceil(left / 60))}m` : '';

    if (state) state.textContent = countdown ? `OFF${countdown}` : `${mode} • ${on ? 'ON' : 'OFF'}`;
    if (card) card.classList.toggle('active', on);
  }

  /* ---------- toast (used by siren control and other add-ons) ---------- */
  let toastTimer = null;
  function toast(message, isError = false) {
    const el = $('#toast');
    if (!el) return;
    el.textContent = message;
    el.classList.toggle('error', Boolean(isError));
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), isError ? 5000 : 2600);
  }
  window.ThermalGuardToast = toast;

  /* ---------- network status (real, from the backend) ---------- */
  function setNetwork(payload) {
    const state = $('#networkState');
    const dot = $('#networkDot');
    let text = 'Unknown';
    let color = 'red';
    if (payload) {
      const ip = payload?.controller?.local_ip;
      const internet = Boolean(payload?.firebase_connected ?? payload?.firebase_ready);
      if (ip && internet) { text = 'Online'; color = 'green'; }
      else if (ip) { text = 'No internet'; color = 'amber'; }
      else { text = 'Offline'; color = 'red'; }
    }
    if (state) state.textContent = text;
    if (dot) dot.className = 'dot ' + color;
  }

  /* ---------- stale data ---------- */
  // Readings stay fully visible when stale; body.data-stale is only a hook.
  const STALE_AFTER_MS = 15000;
  let lastLiveOkAt = 0;        // browser time of last successful poll
  let lastDataAt = 0;          // Pi time of the newest reading

  function checkStale() {
    const now = Date.now();
    const newest = Math.max(lastDataAt, 0);
    const age = newest ? now - newest : (lastLiveOkAt ? now - lastLiveOkAt : Infinity);
    const stale = !lastLiveOkAt || now - lastLiveOkAt > STALE_AFTER_MS || age > STALE_AFTER_MS;
    document.body.classList.toggle('data-stale', stale);
  }

  /* ---------- warning chips (siren, power, SMS) ---------- */
  const chipState = { siren: null, power: null, sms: null };

  function setSirenChip(payload) {
    const r = payload?.relay;
    if (!r) chipState.siren = null;
    else if (r.available === false || r.error) {
      chipState.siren = { level: 'red', text: '🔔 Siren fault', detail: r.error ? `Siren relay error: ${r.error}` : 'Siren relay is not responding (GPIO unavailable).' };
    } else chipState.siren = null;
    const card = $('#sirenCard');
    if (card) card.classList.toggle('fault', Boolean(chipState.siren));
    renderChips();
  }

  function renderChips() {
    const wrap = $('#healthChips');
    if (!wrap) return;
    const chips = [chipState.siren, chipState.power, chipState.sms].filter(Boolean);
    wrap.innerHTML = '';
    for (const c of chips) {
      const span = document.createElement('span');
      span.className = 'health-chip ' + c.level;
      span.textContent = c.text;
      span.title = c.detail;
      wrap.appendChild(span);
    }
    wrap.closest('.brand-copy')?.classList.toggle('has-chips', chips.length > 0);
  }

  function setPowerChip(payload) {
    const p = payload?.system?.power;
    if (!p || !p.available) chipState.power = null;
    else if (p.undervoltage_now) chipState.power = { level: 'red', text: '⚡ Power too weak', detail: 'Under-voltage right now. Use a 5.1 V / 2.5 A supply.' };
    else if (p.undervoltage_since_boot) chipState.power = { level: 'amber', text: '⚡ Power dipped', detail: 'Under-voltage happened since the last boot. Check the power supply.' };
    else chipState.power = null;
    renderChips();
  }

  // Every backend request gets a timeout. Without one, requests hung during a
  // backend restart pile up (Chromium allows 6 per host), every later poll
  // queues behind them, the page goes silent and the watchdog reloads it.
  function fetchWithTimeout(url, options = {}, ms = 6000) {
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), ms);
    return fetch(url, { ...options, signal: ctrl.signal }).finally(() => clearTimeout(t));
  }

  let smsBusy = false;
  let liveBusy = false;

  async function refreshSms() {
    if (smsBusy) return;
    smsBusy = true;
    try {
      const response = await fetchWithTimeout('/api/sms?ts=' + Date.now(), { cache: 'no-store' });
      if (!response.ok) throw new Error('HTTP ' + response.status);
      const d = await response.json();
      const gsm = d?.gsm ?? {};
      const configured = d?.recipients?.configured;
      if (gsm.enabled === false) chipState.sms = null;
      else if (!gsm.ready) chipState.sms = { level: 'red', text: '✉ SMS offline', detail: gsm.error || 'SIM800L modem is not responding.' };
      else if (!gsm.registered) chipState.sms = { level: 'amber', text: '✉ SMS no signal', detail: 'Modem is not registered on the mobile network.' };
      else if (configured === false) chipState.sms = { level: 'amber', text: '✉ No SMS numbers', detail: 'Add recipients in the app (Settings → SMS).' };
      else chipState.sms = null;
    } catch (_) {
      // Leave the last known SMS state; the stale banner covers a dead backend.
    } finally {
      smsBusy = false;
    }
    renderChips();
  }

  /* ---------- never let an overlay hide the dashboard ---------- */
  const SETTINGS_IDLE_MS = 120000;
  const RANK = { offline: 0, normal: 0, warning: 1, critical: 2 };
  const ZONE_LABEL = { entrance: 'Entrance', exit: 'Exit', front: 'Front' };
  let lastInteraction = Date.now();
  let prevStatuses = null;

  ['pointerdown', 'keydown', 'input'].forEach((type) =>
    document.addEventListener(type, () => { lastInteraction = Date.now(); }, true));

  function settingsOpen() {
    return Boolean(document.getElementById('tgWifiRefScreen')?.classList.contains('open'));
  }

  function closeOverlays() {
    let closed = false;
    if (settingsOpen()) { window.closeWifiSettings?.(); closed = true; }
    if (document.getElementById('tgSysConfirm')?.classList.contains('open')) {
      window.ThermalGuardSystemPower?.close?.(); closed = true;
    }
    if (document.getElementById('zdPanel')?.classList.contains('show')) {
      window.ThermalGuardZoneDetails?.close?.(); closed = true;
    }
    $('#sirenMenu')?.classList.remove('open');
    return closed;
  }

  // Close everything the moment a zone gets hotter, so the alarm is visible.
  function handleEscalation(statuses) {
    const now = {};
    zoneIds.forEach((id, i) => { now[id] = statuses[i]; });
    if (prevStatuses) {
      const escalated = zoneIds.filter((id) =>
        RANK[now[id]] > 0 && RANK[now[id]] > (RANK[prevStatuses[id]] ?? 0));
      if (escalated.length && closeOverlays()) {
        const id = escalated.sort((a, b) => RANK[now[b]] - RANK[now[a]])[0];
        toast(`${ZONE_LABEL[id] || id} is ${now[id].toUpperCase()} — showing the dashboard`, now[id] === 'critical');
      }
    }
    prevStatuses = now;
  }

  // Settings screen left open with nobody touching it: go back to the dashboard.
  setInterval(() => {
    if (settingsOpen() && Date.now() - lastInteraction > SETTINGS_IDLE_MS) {
      window.closeWifiSettings?.();
      toast('Settings closed after 2 minutes without use');
    }
  }, 5000);

  async function refreshLive() {
    if (liveBusy) return;
    liveBusy = true;
    try {
      const response = await fetchWithTimeout('/api/live?ts=' + Date.now(), {
        cache: 'no-store',
        headers: { 'X-TG-Kiosk': '1' }   // lets the kiosk watchdog see the page is alive
      });

      if (!response.ok) {
        throw new Error('Live API HTTP ' + response.status);
      }

      const payload = await response.json();
      const zones = getZones(payload);
      lastLiveOkAt = Date.now();
      lastDataAt = numberOrNull(payload?.sample_epoch_ms) ?? Date.parse(payload?.last_update ?? '') ?? 0;
      if (!Number.isFinite(lastDataAt)) lastDataAt = 0;

      const statuses = zoneIds.map((id) =>
        setZone(id, getZone(zones, id))
      );

      setFirebase(payload);
      setNetwork(payload);
      setSensorCount(payload, statuses);
      setThresholds(payload);
      setSiren(payload);
      setSirenChip(payload);
      setPowerChip(payload);
      handleEscalation(statuses);

      // Share every live payload with add-ons (zone details panel).
      window.dispatchEvent(new CustomEvent('thermalguard:live', {detail: payload}));
    } catch (error) {
      console.error('[KIOSK] Live refresh failed:', error);

      const firebaseState = $('#firebaseState');
      const firebaseDot = $('#firebaseDot');

      if (firebaseState) firebaseState.textContent = 'Unknown';
      if (firebaseDot) firebaseDot.className = 'dot red';
      setNetwork(null);
    } finally {
      liveBusy = false;
      checkStale();
    }
  }

  /* ---------- siren ON/OFF confirmation ---------- */
  const SIREN_COPY = {
    on: {
      title: 'Turn the siren ON?',
      body: 'The siren will sound continuously until someone sets it back to AUTO or OFF, even if temperatures are normal.',
      button: 'Turn siren ON'
    },
    off: {
      title: 'Turn the siren OFF?',
      body: 'The siren will stay silent even if a zone reaches CRITICAL temperature. Use AUTO to restore automatic alarms.',
      button: 'Turn siren OFF'
    }
  };

  function confirmSiren(mode) {
    const copy = SIREN_COPY[mode];
    let wrap = $('#sirenConfirm');
    if (!wrap) {
      wrap = document.createElement('div');
      wrap.id = 'sirenConfirm';
      wrap.className = 'siren-confirm';
      wrap.setAttribute('role', 'dialog');
      wrap.setAttribute('aria-modal', 'true');
      wrap.setAttribute('aria-labelledby', 'sirenConfirmTitle');
      wrap.innerHTML = `
        <div class="siren-confirm-box">
          <h2 id="sirenConfirmTitle"></h2>
          <p id="sirenConfirmBody"></p>
          <div class="siren-confirm-actions">
            <button type="button" class="sc-cancel">Cancel</button>
            <button type="button" class="sc-ok"></button>
          </div>
        </div>`;
      document.body.appendChild(wrap);
    }
    $('#sirenConfirmTitle').textContent = copy.title;
    $('#sirenConfirmBody').textContent = mode === 'off' && sirenRevertMinutes > 0
      ? `The siren will stay silent for ${Math.round(sirenRevertMinutes)} minutes, then return to AUTO. ` +
        'It returns to AUTO at once if any zone newly reaches CRITICAL temperature.'
      : copy.body;
    const ok = wrap.querySelector('.sc-ok');
    const cancel = wrap.querySelector('.sc-cancel');
    ok.textContent = copy.button;
    ok.className = 'sc-ok ' + mode;
    wrap.classList.add('open');

    return new Promise((resolve) => {
      let timer = null;
      const done = (answer) => {
        clearTimeout(timer);
        wrap.classList.remove('open');
        ok.onclick = cancel.onclick = wrap.onclick = null;
        resolve(answer);
      };
      ok.onclick = () => done(true);
      cancel.onclick = () => done(false);
      wrap.onclick = (e) => { if (e.target === wrap) done(false); };
      timer = setTimeout(() => done(false), 30000);   // unattended dialog closes itself
      setTimeout(() => cancel.focus(), 30);
    });
  }

  function positionSirenMenu() {
    const siren = $('#sirenCard');
    const menu = $('#sirenMenu');

    if (!siren || !menu) return;

    const rect = siren.getBoundingClientRect();

    menu.style.left = `${Math.max(
      6,
      Math.min(window.innerWidth - menu.offsetWidth - 6, rect.left)
    )}px`;

    menu.style.top = `${Math.max(
      6,
      rect.top - menu.offsetHeight - 6
    )}px`;
  }

  function initSirenMenu() {
    const siren = $('#sirenCard');
    const menu = $('#sirenMenu');

    if (!siren || !menu) return;

    siren.addEventListener('click', (event) => {
      event.stopPropagation();

      menu.classList.toggle('open');

      if (menu.classList.contains('open')) {
        requestAnimationFrame(positionSirenMenu);
      }
    });

    menu.addEventListener('click', async (event) => {
      const button = event.target.closest('button[data-mode]');
      if (!button) return;

      menu.classList.remove('open');
      const mode = button.dataset.mode;

      // ON and OFF override automatic control, so ask first. AUTO is the safe default.
      if ((mode === 'on' || mode === 'off') && !(await confirmSiren(mode))) return;

      try {
        const response = await fetch('/api/siren/mode', {
          method: 'POST',
          headers: {'Content-Type': 'application/json'},
          body: JSON.stringify({mode})
        });

        if (!response.ok) {
          let message = 'Unable to change siren mode';
          try {
            const data = await response.json();
            message = data?.detail ?? message;
          } catch (_) {}
          throw new Error(message);
        }

        await refreshLive();
        toast(`Siren set to ${mode.toUpperCase()}`);
      } catch (error) {
        console.error('[KIOSK] Siren mode failed:', error);
        toast(error.message || 'Unable to change siren mode', true);
      }
    });

    document.addEventListener('click', (event) => {
      if (
        !menu.contains(event.target) &&
        !siren.contains(event.target)
      ) {
        menu.classList.remove('open');
      }
    });

    window.addEventListener('resize', () => {
      if (menu.classList.contains('open')) {
        requestAnimationFrame(positionSirenMenu);
      }
    });
  }

  /*
   * Responsive CSS is the primary fit system.
   * This is only a final safety fit if content still exceeds the viewport.
   * The Wi-Fi drawer is fixed-position and is intentionally excluded.
   */
  // Phones in portrait get a scrolling, stacked layout (css/mobile.css),
  // so they are never shrunk to fit. Keep in sync with mobile.css.
  const PHONE_LAYOUT = window.matchMedia('(max-width: 600px) and (orientation: portrait)');

  function fitScreen() {
    const shell = $('#appShell');
    if (!shell) return;

    shell.style.transform = '';
    shell.style.transformOrigin = 'top left';

    if (PHONE_LAYOUT.matches) {
      shell.style.width = '';
      shell.style.height = '';
      document.documentElement.dataset.kioskFit = 'phone';
      return;
    }

    shell.style.width = '100vw';
    shell.style.height = '100vh';

    requestAnimationFrame(() => {
      const viewportWidth =
        window.innerWidth ||
        document.documentElement.clientWidth;

      const viewportHeight =
        window.innerHeight ||
        document.documentElement.clientHeight;

      const rect = shell.getBoundingClientRect();

      const contentWidth = Math.max(
        shell.scrollWidth,
        rect.width,
        viewportWidth
      );

      const contentHeight = Math.max(
        shell.scrollHeight,
        rect.height,
        viewportHeight
      );

      let scale = Math.min(
        1,
        viewportWidth / contentWidth,
        viewportHeight / contentHeight
      );

      if (!Number.isFinite(scale) || scale <= 0) {
        scale = 1;
      }

      if (scale < 0.995) {
        scale = Math.max(0.55, scale * 0.99);

        shell.style.transform = `scale(${scale})`;
        shell.style.width = `${100 / scale}vw`;
        shell.style.height = `${100 / scale}vh`;
      }

      document.documentElement.dataset.kioskFit =
        scale.toFixed(3);
    });
  }

  function scheduleFit() {
    clearTimeout(fitTimer);
    fitTimer = setTimeout(fitScreen, 80);
  }

  window.ThermalGuardAutoFit = fitScreen;

  updateClock();
  setInterval(updateClock, 1000);

  refreshLive();

  liveTimer = setInterval(refreshLive, 3000);

  refreshSms();
  setInterval(refreshSms, 30000);
  // Keep the "last update … ago" text moving even while requests hang.
  setInterval(checkStale, 1000);

  initSirenMenu();

  window.addEventListener('load', () => {
    fitScreen();
    setTimeout(fitScreen, 250);
    setTimeout(fitScreen, 900);
  });

  window.addEventListener('resize', scheduleFit);
  window.addEventListener('orientationchange', () => setTimeout(fitScreen, 250));
  document.addEventListener('fullscreenchange', scheduleFit);

  if (document.fonts?.ready) {
    document.fonts.ready.then(fitScreen).catch(() => {});
  }

  window.addEventListener('beforeunload', () => {
    if (liveTimer) clearInterval(liveTimer);
    if (fitTimer) clearTimeout(fitTimer);
  });
})();
