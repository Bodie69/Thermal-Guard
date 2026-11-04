(() => {
  "use strict";

  const API = {
    status: "/api/wifi/status",
    on: "/api/wifi/on",
    off: "/api/wifi/off",
    scan: "/api/wifi/scan",
    connect: "/api/wifi/connect"
  };

  const COLORS = {
    bg: "#071625",
    panel: "#0C283F",
    panel2: "#102F49",
    border: "#204D6B",
    blue: "#2393D3",
    blue2: "#42B8F5",
    text: "#D7DDE4",
    muted: "#708095",
    amber: "#B25C38",
    green: "#34C47C",
    danger: "#E85B61"
  };

  let state = null;
  let selected = null;
  let manualMode = false;

  function esc(v) {
    return String(v ?? "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#039;");
  }

  async function api(url, options = {}) {
    const r = await fetch(url + (url.includes("?") ? "&" : "?") + "_=" + Date.now(), {
      cache: "no-store",
      headers: {
        "Accept": "application/json",
        ...(options.body ? {"Content-Type": "application/json"} : {})
      },
      ...options
    });
    const t = await r.text();
    let d = {};
    try { d = t ? JSON.parse(t) : {}; } catch { d = {message:t}; }
    if (!r.ok) throw new Error(d.detail || d.message || `HTTP ${r.status}`);
    return d;
  }

  function radioOn(d) {
    return !!(d && (d.wifi_on === true || d.radio_on === true || d.enabled === true));
  }

  function bars(signal) {
    const s = Number(signal || 0);
    const level = s >= 75 ? 4 : s >= 50 ? 3 : s >= 25 ? 2 : s > 0 ? 1 : 0;
    return `
      <span class="tg-wifi-bars" aria-label="${s}% signal">
        <i class="${level >= 1 ? 'on':''}"></i>
        <i class="${level >= 2 ? 'on':''}"></i>
        <i class="${level >= 3 ? 'on':''}"></i>
        <i class="${level >= 4 ? 'on':''}"></i>
      </span>`;
  }

  function lockIcon(secure) {
    return secure ? `<span class="tg-lock">▣</span>` : `<span class="tg-open">○</span>`;
  }

  function createUI() {
    if (document.getElementById("tgWifiRefScreen")) return;

    const style = document.createElement("style");
    style.id = "tgWifiRefStyle";
    style.textContent = `
      :root {
        --tg-bg:${COLORS.bg};
        --tg-panel:${COLORS.panel};
        --tg-panel2:${COLORS.panel2};
        --tg-border:${COLORS.border};
        --tg-blue:${COLORS.blue};
        --tg-blue2:${COLORS.blue2};
        --tg-text:${COLORS.text};
        --tg-muted:${COLORS.muted};
        --tg-amber:${COLORS.amber};
        --tg-green:${COLORS.green};
        --tg-danger:${COLORS.danger};
      }

      #tgWifiRefScreen {
        position:fixed;
        inset:0;
        z-index:999999;
        display:none;
        background:
          radial-gradient(circle at 80% 0%, rgba(35,147,211,.10), transparent 38%),
          linear-gradient(180deg,#071625 0%,#06121f 100%);
        color:var(--tg-text);
        font-family:Inter,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;
        overflow:hidden;
      }

      #tgWifiRefScreen.open { display:flex; flex-direction:column; }

      .tg-wifi-topbar {
        height:72px;
        flex:0 0 72px;
        display:flex;
        align-items:center;
        justify-content:space-between;
        padding:0 24px;
        border-bottom:1px solid rgba(112,128,149,.18);
        background:rgba(7,22,37,.96);
        box-sizing:border-box;
      }

      .tg-wifi-top-left { display:flex; align-items:center; gap:14px; min-width:0; }

      .tg-wifi-back {
        width:42px; height:42px;
        border:1px solid rgba(112,128,149,.28);
        border-radius:13px;
        background:#0C283F;
        color:var(--tg-text);
        font-size:24px;
        cursor:pointer;
        display:grid;
        place-items:center;
      }

      .tg-wifi-heading { min-width:0; }
      .tg-wifi-title { font-size:24px; font-weight:800; letter-spacing:.1px; line-height:1.05; }
      .tg-wifi-subtitle { color:var(--tg-muted); font-size:12px; margin-top:5px; }

      .tg-wifi-top-status {
        display:flex;
        align-items:center;
        gap:9px;
        padding:10px 14px;
        border-radius:999px;
        background:#0C283F;
        border:1px solid rgba(35,147,211,.28);
        font-size:12px;
        font-weight:800;
        color:#A8CBE2;
      }

      .tg-dot { width:8px; height:8px; border-radius:50%; background:var(--tg-muted); }
      .tg-dot.on { background:var(--tg-green); box-shadow:0 0 12px rgba(52,196,124,.7); }

      .tg-wifi-body {
        flex:1 1 auto;
        min-height:0;
        display:grid;
        grid-template-columns:290px minmax(0,1fr);
        gap:16px;
        padding:16px 20px 18px;
        box-sizing:border-box;
      }

      .tg-wifi-card {
        background:linear-gradient(180deg,rgba(12,40,63,.96),rgba(9,31,49,.96));
        border:1px solid rgba(32,77,107,.85);
        border-radius:20px;
        box-shadow:0 18px 45px rgba(0,0,0,.22);
      }

      .tg-wifi-control {
        padding:18px;
        display:flex;
        flex-direction:column;
        min-height:0;
      }

      .tg-section-kicker {
        color:#7AAAC7;
        font-size:11px;
        letter-spacing:.14em;
        text-transform:uppercase;
        font-weight:800;
      }

      .tg-control-title {
        margin-top:6px;
        font-size:21px;
        font-weight:800;
      }

      .tg-power-row {
        margin-top:17px;
        display:flex;
        align-items:center;
        justify-content:space-between;
        gap:12px;
        padding:15px 16px;
        background:#0A2033;
        border:1px solid rgba(32,77,107,.75);
        border-radius:16px;
      }

      .tg-power-label { font-size:15px; font-weight:800; }
      .tg-power-note { margin-top:3px; color:var(--tg-muted); font-size:11px; }

      .tg-switch {
        position:relative;
        width:58px; height:32px;
        flex:0 0 auto;
        border:none;
        padding:0;
        border-radius:999px;
        background:#23384A;
        cursor:pointer;
        box-shadow:inset 0 0 0 1px rgba(255,255,255,.04);
      }

      .tg-switch::after {
        content:"";
        position:absolute;
        width:24px; height:24px;
        top:4px; left:4px;
        border-radius:50%;
        background:#AAB5C1;
        transition:.18s ease;
      }

      .tg-switch.on {
        background:linear-gradient(90deg,var(--tg-blue),var(--tg-blue2));
        box-shadow:0 0 18px rgba(35,147,211,.3);
      }

      .tg-switch.on::after {
        transform:translateX(26px);
        background:white;
      }

      .tg-current-card {
        margin-top:13px;
        padding:15px;
        border-radius:16px;
        background:#081A2A;
        border:1px solid rgba(32,77,107,.65);
      }

      .tg-current-label { color:var(--tg-muted); font-size:11px; text-transform:uppercase; letter-spacing:.1em; font-weight:800; }
      .tg-current-ssid { margin-top:7px; font-size:17px; font-weight:800; overflow-wrap:anywhere; }
      .tg-current-meta { margin-top:5px; color:#80B3D0; font-size:11px; }

      .tg-wifi-actions {
        margin-top:auto;
        display:grid;
        grid-template-columns:1fr;
        gap:9px;
        padding-top:14px;
      }

      .tg-btn {
        min-height:44px;
        border-radius:13px;
        border:1px solid transparent;
        font-weight:800;
        cursor:pointer;
        padding:0 14px;
        font-size:13px;
      }

      .tg-btn.primary {
        background:linear-gradient(180deg,#2497D8,#187FB8);
        color:white;
        box-shadow:0 8px 20px rgba(35,147,211,.18);
      }

      .tg-btn.secondary {
        background:#0A2033;
        color:#A9C7D9;
        border-color:#204D6B;
      }

      .tg-networks {
        display:flex;
        flex-direction:column;
        min-height:0;
        overflow:hidden;
      }

      .tg-net-head {
        display:flex;
        align-items:center;
        justify-content:space-between;
        gap:12px;
        padding:17px 18px 13px;
        border-bottom:1px solid rgba(112,128,149,.13);
      }

      .tg-net-title { font-size:18px; font-weight:800; }
      .tg-net-count { color:var(--tg-muted); font-size:11px; margin-top:4px; }

      .tg-refresh {
        width:42px; height:42px;
        border-radius:13px;
        border:1px solid rgba(35,147,211,.28);
        background:#0B2336;
        color:#80C8F1;
        font-size:20px;
        cursor:pointer;
      }

      .tg-net-list {
        flex:1 1 auto;
        min-height:0;
        overflow:auto;
        padding:9px 11px 13px;
        scrollbar-width:thin;
      }

      .tg-network {
        width:100%;
        border:none;
        color:var(--tg-text);
        display:grid;
        grid-template-columns:46px minmax(0,1fr) auto;
        align-items:center;
        gap:12px;
        padding:11px 12px;
        margin:7px 0;
        border-radius:15px;
        background:#0A2134;
        border:1px solid rgba(32,77,107,.62);
        cursor:pointer;
        text-align:left;
        box-sizing:border-box;
      }

      .tg-network:hover,.tg-network:focus {
        border-color:rgba(35,147,211,.8);
        background:#0C2A42;
        outline:none;
      }

      .tg-network.connected {
        border-color:rgba(52,196,124,.55);
        background:linear-gradient(90deg,rgba(52,196,124,.11),#0A2134 48%);
      }

      .tg-wifi-icon {
        width:40px; height:40px;
        display:grid;
        place-items:center;
        border-radius:12px;
        background:#102F49;
        color:#7ACAF4;
        font-size:21px;
      }

      .tg-network.connected .tg-wifi-icon {
        background:rgba(52,196,124,.14);
        color:#75E5A8;
      }

      .tg-net-name {
        font-size:15px;
        font-weight:800;
        overflow:hidden;
        text-overflow:ellipsis;
        white-space:nowrap;
      }

      .tg-net-meta {
        margin-top:4px;
        display:flex;
        align-items:center;
        gap:7px;
        color:var(--tg-muted);
        font-size:11px;
      }

      .tg-connected-badge {
        color:#79DBA5;
        font-weight:800;
      }

      .tg-net-right {
        display:flex;
        align-items:center;
        gap:10px;
        color:#91A2B4;
        font-size:11px;
      }

      .tg-wifi-bars {
        height:20px;
        display:flex;
        align-items:flex-end;
        gap:2px;
      }

      .tg-wifi-bars i {
        display:block;
        width:3px;
        background:#31485A;
        border-radius:2px;
      }
      .tg-wifi-bars i:nth-child(1){height:5px}
      .tg-wifi-bars i:nth-child(2){height:9px}
      .tg-wifi-bars i:nth-child(3){height:13px}
      .tg-wifi-bars i:nth-child(4){height:17px}
      .tg-wifi-bars i.on{background:var(--tg-blue2)}

      .tg-lock { color:#B47B63; font-size:13px; }
      .tg-open { color:#64788A; font-size:14px; }

      .tg-empty {
        padding:42px 12px;
        text-align:center;
        color:var(--tg-muted);
      }

      .tg-message {
        min-height:18px;
        padding:0 18px 10px;
        color:#86C8ED;
        font-size:11px;
      }
      .tg-message.error { color:#FF969C; }

      .tg-wifi-dialog-wrap {
        position:fixed;
        inset:0;
        z-index:1000001;
        display:none;
        align-items:center;
        justify-content:center;
        background:rgba(2,9,16,.76);
        backdrop-filter:blur(8px);
        padding:18px;
      }
      .tg-wifi-dialog-wrap.open{display:flex}

      .tg-wifi-dialog {
        width:min(440px,94vw);
        border-radius:20px;
        background:#0B2236;
        border:1px solid #204D6B;
        box-shadow:0 30px 80px rgba(0,0,0,.52);
        padding:20px;
      }

      .tg-dialog-title { font-size:20px; font-weight:800; }
      .tg-dialog-sub { margin-top:5px; color:var(--tg-muted); font-size:12px; }
      .tg-field-label { display:block; margin:15px 0 7px; color:#9AB3C4; font-size:11px; font-weight:800; letter-spacing:.06em; text-transform:uppercase; }

      .tg-input {
        width:100%;
        height:46px;
        box-sizing:border-box;
        border-radius:12px;
        border:1px solid #204D6B;
        background:#071827;
        color:white;
        padding:0 13px;
        font-size:14px;
        outline:none;
      }
      .tg-input:focus{border-color:var(--tg-blue); box-shadow:0 0 0 3px rgba(35,147,211,.15)}

      .tg-show-pass {
        display:flex; align-items:center; gap:8px;
        margin-top:10px; color:#91A2B4; font-size:12px;
      }

      .tg-dialog-actions {
        margin-top:18px;
        display:grid;
        grid-template-columns:1fr 1.3fr;
        gap:9px;
      }

      @media (max-width:700px) {
        .tg-wifi-body{grid-template-columns:1fr; overflow:auto}
        .tg-wifi-control{min-height:290px}
        #tgWifiRefScreen{overflow:auto}
      }

      @media (max-height:520px) and (min-width:701px) {
        .tg-wifi-topbar{height:62px;flex-basis:62px;padding:0 18px}
        .tg-wifi-title{font-size:21px}
        .tg-wifi-body{padding:10px 14px 12px;gap:12px;grid-template-columns:260px minmax(0,1fr)}
        .tg-wifi-control{padding:14px}
        .tg-power-row{margin-top:11px;padding:11px 12px}
        .tg-current-card{padding:11px}
        .tg-net-head{padding:12px 14px 9px}
        .tg-network{padding:8px 10px;margin:5px 0}
        .tg-wifi-icon{width:34px;height:34px}
        .tg-net-list{padding:6px 9px 9px}
      }
    `;
    document.head.appendChild(style);

    const screen = document.createElement("section");
    screen.id = "tgWifiRefScreen";
    screen.innerHTML = `
      <div class="tg-wifi-topbar">
        <div class="tg-wifi-top-left">
          <button id="tgWifiBack" class="tg-wifi-back" type="button">‹</button>
          <div class="tg-wifi-heading">
            <div class="tg-wifi-title">Wi‑Fi Settings</div>
            <div class="tg-wifi-subtitle">Connect Thermal Guard to a wireless network</div>
          </div>
        </div>
        <div class="tg-wifi-top-status">
          <span id="tgWifiDot" class="tg-dot"></span>
          <span id="tgWifiTopText">Checking Wi‑Fi</span>
        </div>
      </div>

      <div class="tg-wifi-body">
        <aside class="tg-wifi-card tg-wifi-control">
          <div class="tg-section-kicker">Wireless</div>
          <div class="tg-control-title">Network Control</div>

          <div class="tg-power-row">
            <div>
              <div class="tg-power-label">Wi‑Fi</div>
              <div id="tgPowerNote" class="tg-power-note">Checking radio…</div>
            </div>
            <button id="tgWifiPowerSwitch" class="tg-switch" type="button" aria-label="Toggle Wi-Fi"></button>
          </div>

          <div class="tg-current-card">
            <div class="tg-current-label">Current connection</div>
            <div id="tgCurrentSsid" class="tg-current-ssid">Not connected</div>
            <div id="tgCurrentMeta" class="tg-current-meta">No active network</div>
          </div>

          <div class="tg-wifi-actions">
            <button id="tgAddNetwork" class="tg-btn primary" type="button">＋ Add Network</button>
            <button id="tgScanNetworks" class="tg-btn secondary" type="button">↻ Scan Networks</button>
          </div>
        </aside>

        <main class="tg-wifi-card tg-networks">
          <div class="tg-net-head">
            <div>
              <div class="tg-net-title">Available Networks</div>
              <div id="tgNetworkCount" class="tg-net-count">Scanning nearby Wi‑Fi…</div>
            </div>
            <button id="tgRefreshNetworks" class="tg-refresh" type="button" title="Refresh">↻</button>
          </div>

          <div id="tgNetworkList" class="tg-net-list">
            <div class="tg-empty">Scanning nearby networks…</div>
          </div>
          <div id="tgWifiMessage" class="tg-message"></div>
        </main>
      </div>

      <div id="tgWifiDialogWrap" class="tg-wifi-dialog-wrap">
        <div class="tg-wifi-dialog">
          <div id="tgDialogTitle" class="tg-dialog-title">Connect to Wi‑Fi</div>
          <div id="tgDialogSub" class="tg-dialog-sub">Enter network credentials.</div>

          <label id="tgSsidLabel" class="tg-field-label" for="tgWifiSsidInput">Network name (SSID)</label>
          <input id="tgWifiSsidInput" class="tg-input" type="text" autocomplete="off">

          <label id="tgPassLabel" class="tg-field-label" for="tgWifiPasswordInput">Password</label>
          <input id="tgWifiPasswordInput" class="tg-input" type="password" autocomplete="new-password">

          <label class="tg-show-pass">
            <input id="tgShowWifiPass" type="checkbox">
            Show password
          </label>

          <div class="tg-dialog-actions">
            <button id="tgCancelWifi" class="tg-btn secondary" type="button">Cancel</button>
            <button id="tgConnectWifi" class="tg-btn primary" type="button">Connect</button>
          </div>
        </div>
      </div>
    `;
    document.body.appendChild(screen);

    document.getElementById("tgWifiBack").onclick = closeWifiSettings;
    document.getElementById("tgWifiPowerSwitch").onclick = togglePower;
    document.getElementById("tgScanNetworks").onclick = scan;
    document.getElementById("tgRefreshNetworks").onclick = scan;
    document.getElementById("tgAddNetwork").onclick = () => openDialog(null, true);
    document.getElementById("tgCancelWifi").onclick = closeDialog;
    document.getElementById("tgConnectWifi").onclick = connectSelected;
    document.getElementById("tgShowWifiPass").onchange = e => {
      document.getElementById("tgWifiPasswordInput").type = e.target.checked ? "text" : "password";
    };
  }

  function msg(text="", error=false) {
    const el = document.getElementById("tgWifiMessage");
    if (!el) return;
    el.textContent = text;
    el.classList.toggle("error", error);
  }

  function updateStatus(d) {
    state = d || {};
    const on = radioOn(state);
    const connected = !!state.connected;
    const ssid = state.ssid || "";

    const sw = document.getElementById("tgWifiPowerSwitch");
    sw.classList.toggle("on", on);
    sw.setAttribute("aria-pressed", on ? "true":"false");

    document.getElementById("tgPowerNote").textContent = on ? "Wireless radio is enabled" : "Wireless radio is disabled";
    document.getElementById("tgCurrentSsid").textContent = connected && ssid ? ssid : "Not connected";
    document.getElementById("tgCurrentMeta").textContent = connected
      ? `${state.interface || "wlan0"} • Connected`
      : on ? "Wi‑Fi ON • Choose a network" : "Wi‑Fi OFF";

    document.getElementById("tgWifiDot").classList.toggle("on", connected);
    document.getElementById("tgWifiTopText").textContent = connected && ssid ? `Connected • ${ssid}` : on ? "Wi‑Fi On" : "Wi‑Fi Off";
  }

  async function refreshStatus() {
    const d = await api(API.status);
    updateStatus(d);
    return d;
  }

  function renderNetworks(networks) {
    const list = document.getElementById("tgNetworkList");
    const count = document.getElementById("tgNetworkCount");
    networks = Array.isArray(networks) ? networks : [];

    count.textContent = networks.length === 1 ? "1 network found" : `${networks.length} networks found`;

    if (!networks.length) {
      list.innerHTML = `<div class="tg-empty">No Wi‑Fi networks found.<br><small>Tap Scan Networks to try again.</small></div>`;
      return;
    }

    list.innerHTML = "";
    networks.forEach(n => {
      const secure = !!(n.security && n.security !== "OPEN" && n.security !== "--");
      const row = document.createElement("button");
      row.type = "button";
      row.className = "tg-network" + (n.connected ? " connected":"");
      row.innerHTML = `
        <span class="tg-wifi-icon">⌁</span>
        <span>
          <div class="tg-net-name">${esc(n.ssid)}</div>
          <div class="tg-net-meta">
            ${lockIcon(secure)}
            <span>${secure ? esc(n.security) : "Open network"}</span>
            ${n.connected ? `<span class="tg-connected-badge">• CONNECTED</span>` : ""}
          </div>
        </span>
        <span class="tg-net-right">
          <span>${esc(n.signal ?? 0)}%</span>
          ${bars(n.signal)}
        </span>`;
      row.onclick = () => {
        if (n.connected) {
          msg(`Already connected to ${n.ssid}.`);
          return;
        }
        openDialog(n, false);
      };
      list.appendChild(row);
    });
  }

  async function scan() {
    msg("Scanning nearby networks…");
    try {
      let s = await refreshStatus();
      if (!radioOn(s)) {
        await api(API.on, {method:"POST"});
        await new Promise(r => setTimeout(r, 800));
        s = await refreshStatus();
      }
      const d = await api(API.scan);
      renderNetworks(d.networks || []);
      msg("");
    } catch (e) {
      renderNetworks([]);
      msg("Wi‑Fi scan failed: " + e.message, true);
    }
  }

  async function togglePower() {
    try {
      const s = state || await refreshStatus();
      const target = !radioOn(s);
      msg(target ? "Turning Wi‑Fi on…" : "Turning Wi‑Fi off…");
      await api(target ? API.on : API.off, {method:"POST"});
      await new Promise(r => setTimeout(r, 650));
      const fresh = await refreshStatus();
      if (target && radioOn(fresh)) {
        await scan();
      } else {
        renderNetworks([]);
        msg("");
      }
    } catch (e) {
      msg("Unable to change Wi‑Fi: " + e.message, true);
    }
  }

  function openDialog(network=null, manual=false) {
    selected = network;
    manualMode = manual;
    const wrap = document.getElementById("tgWifiDialogWrap");
    const ssid = document.getElementById("tgWifiSsidInput");
    const pass = document.getElementById("tgWifiPasswordInput");

    const secure = manual || !!(network && network.security && network.security !== "OPEN" && network.security !== "--");

    document.getElementById("tgDialogTitle").textContent = manual ? "Add Network" : `Connect to ${network?.ssid || "Wi‑Fi"}`;
    document.getElementById("tgDialogSub").textContent = manual
      ? "Enter the Wi‑Fi name and password."
      : secure ? "Enter the network password to connect." : "This network does not require a password.";

    ssid.value = network?.ssid || "";
    ssid.readOnly = !manual;
    document.getElementById("tgSsidLabel").style.display = manual ? "block":"none";
    ssid.style.display = manual ? "block":"none";

    pass.value = "";
    document.getElementById("tgPassLabel").style.display = secure ? "block":"none";
    pass.style.display = secure ? "block":"none";
    document.querySelector(".tg-show-pass").style.display = secure ? "flex":"none";

    wrap.classList.add("open");
    setTimeout(() => (manual ? ssid : secure ? pass : document.getElementById("tgConnectWifi")).focus(), 50);
  }

  function closeDialog() {
    document.getElementById("tgWifiDialogWrap").classList.remove("open");
    selected = null;
    manualMode = false;
  }

  async function connectSelected() {
    const ssid = (manualMode ? document.getElementById("tgWifiSsidInput").value : selected?.ssid || "").trim();
    const password = document.getElementById("tgWifiPasswordInput").value;
    if (!ssid) {
      msg("Enter the Wi‑Fi network name.", true);
      return;
    }

    const btn = document.getElementById("tgConnectWifi");
    const old = btn.textContent;
    btn.disabled = true;
    btn.textContent = "Connecting…";

    try {
      msg(`Connecting to ${ssid}…`);
      await api(API.connect, {
        method:"POST",
        body:JSON.stringify({ssid, password})
      });
      closeDialog();
      await new Promise(r => setTimeout(r, 900));
      await refreshStatus();
      await scan();
      msg(`Connected to ${ssid}.`);
    } catch (e) {
      msg("Connection failed: " + e.message, true);
    } finally {
      btn.disabled = false;
      btn.textContent = old;
    }
  }

  async function openWifiSettings() {
    createUI();
    document.getElementById("tgWifiRefScreen").classList.add("open");
    document.body.style.overflow = "hidden";
    msg("");
    try {
      const s = await refreshStatus();
      if (radioOn(s)) await scan();
      else renderNetworks([]);
    } catch (e) {
      msg("Unable to read Wi‑Fi status: " + e.message, true);
    }
  }

  function closeWifiSettings() {
    const s = document.getElementById("tgWifiRefScreen");
    if (s) s.classList.remove("open");
    document.body.style.overflow = "";
    closeDialog();
  }

  function hookSettings() {
    const selectors = [
      "#settingsButton","#settingsBtn","#settingsIcon","#settings",
      ".settings-button",".settings-btn",".settings-icon",
      "[data-settings]","[data-action='settings']",
      "button[aria-label='Settings']","button[title='Settings']"
    ];

    selectors.forEach(sel => {
      document.querySelectorAll(sel).forEach(el => {
        if (el.dataset.tgWifiRefBound === "1") return;
        el.dataset.tgWifiRefBound = "1";
        el.addEventListener("click", e => {
          e.preventDefault();
          e.stopImmediatePropagation();
          openWifiSettings();
        }, true);
      });
    });
  }

  window.openWifiSettings = openWifiSettings;
  window.closeWifiSettings = closeWifiSettings;
  window.ThermalGuardWifiSettings = {open:openWifiSettings, close:closeWifiSettings, scan};

  function init() {
    createUI();
    hookSettings();
    setTimeout(hookSettings, 1000);
    setTimeout(hookSettings, 3000);
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();