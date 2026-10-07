"use strict";
// All remote-supplied strings (device names, share labels, file names) are
// attacker-controlled, so they are only ever inserted with textContent.

const $ = (id) => document.getElementById(id);
let incomingList = []; // pushes this device is receiving
let snippetsList = []; // text snippets this device has received

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function clear(node) { node.replaceChildren(); }

function fmtBytes(n) {
  if (!n && n !== 0) return "";
  const u = ["B", "KB", "MB", "GB", "TB"];
  let i = 0, v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(i ? 2 : 0)} ${u[i]}`;
}
function fmtSpeed(mbps) {
  if (!mbps || mbps <= 0) return "--";
  if (settings.speed_unit === "mbps") return `${(mbps * 8).toFixed(1)} Mbps`;
  return `${mbps.toFixed(1)} MB/s`;
}
function fmtETA(sec) {
  if (!sec || sec <= 0) return "--";
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = Math.floor(sec % 60);
  const p = (n) => String(n).padStart(2, "0");
  return h ? `${h}:${p(m)}:${p(s)}` : `${p(m)}:${p(s)}`;
}
function fmtCountdown(iso) {
  if (!iso) return "";
  const ms = new Date(iso).getTime() - Date.now();
  if (ms <= 0) return "expired";
  const s = Math.floor(ms / 1000), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
  if (h) return `${h}h ${m}m left`;
  if (m) return `${m}m ${s % 60}s left`;
  return `${s}s left`;
}
function fmtWhen(iso) {
  if (!iso || String(iso).startsWith("0001")) return "";
  return new Date(iso).toLocaleString();
}
function prettyId(id) {
  if (!id) return "";
  const m = id.slice(0, 16).toUpperCase().match(/.{4}/g);
  return m ? m.join(" ") : id.toUpperCase();
}

// ---------- icons ----------
const svg = (inner, size) =>
  `<svg viewBox="0 0 24 24" width="${size || 20}" height="${size || 20}" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${inner}</svg>`;

const I = {
  home: svg('<path d="M4 11.2l8-6.4 8 6.4"/><path d="M6.2 10v9.5h11.6V10"/>'),
  monitor: svg('<rect x="3" y="4.5" width="18" height="12" rx="2"/><path d="M9 20h6M12 16.5V20"/>'),
  laptop: svg('<rect x="4" y="5" width="16" height="10.5" rx="1.6"/><path d="M2.5 18.5h19"/>'),
  phone: svg('<rect x="7" y="3" width="10" height="18" rx="2.2"/><path d="M11 18.2h2"/>'),
  link: svg('<path d="M10.2 13.8a3.6 3.6 0 0 1 0-5.1l1.3-1.3a3.6 3.6 0 0 1 5.1 5.1l-1 1"/><path d="M13.8 10.2a3.6 3.6 0 0 1 0 5.1l-1.3 1.3a3.6 3.6 0 0 1-5.1-5.1l1-1"/>'),
  gear: svg('<circle cx="12" cy="12" r="3.1"/><path d="M12 2.6v2.2M12 19.2v2.2M4.4 12H2.2M21.8 12h-2.2M6.3 6.3l1.5 1.5M16.2 16.2l1.5 1.5M17.7 6.3l-1.5 1.5M7.8 16.2l-1.5 1.5"/>'),
  share: svg('<circle cx="6" cy="12" r="2.4"/><circle cx="17.5" cy="6" r="2.4"/><circle cx="17.5" cy="18" r="2.4"/><path d="M8.1 10.9l7.3-3.8M8.1 13.1l7.3 3.8"/>'),
  transfers: svg('<path d="M8 4v15M8 4L4.5 7.5M8 4l3.5 3.5M16 20V5M16 20l-3.5-3.5M16 20l3.5-3.5"/>'),
  search: svg('<circle cx="11" cy="11" r="6.5"/><path d="M16 16l4.5 4.5"/>'),
  refresh: svg('<path d="M20 12a8 8 0 1 1-2.34-5.66M20 4v4h-4"/>'),
  back: svg('<path d="M15 5l-7 7 7 7"/>'),
  fwd: svg('<path d="M9 5l7 7-7 7"/>'),
  up: svg('<path d="M12 19V6M6 12l6-6 6 6"/>'),
  grid: svg('<rect x="3.5" y="3.5" width="7" height="7" rx="1.4"/><rect x="13.5" y="3.5" width="7" height="7" rx="1.4"/><rect x="3.5" y="13.5" width="7" height="7" rx="1.4"/><rect x="13.5" y="13.5" width="7" height="7" rx="1.4"/>'),
  list: svg('<path d="M4 6h16M4 12h16M4 18h16"/>'),
  folder: svg('<path d="M3 6.6A1.6 1.6 0 0 1 4.6 5h4.1l1.6 1.9h9.1A1.6 1.6 0 0 1 21 8.5v8.9A1.6 1.6 0 0 1 19.4 19H4.6A1.6 1.6 0 0 1 3 17.4z"/>'),
  file: svg('<path d="M6 3.5h7l5 5v12H6z"/><path d="M13 3.5V9h5"/>'),
  download: svg('<path d="M12 3.5v11M8 11l4 4 4-4"/><path d="M4.5 16.5v3.5h15v-3.5"/>'),
  push: svg('<path d="M12 20.5v-11M8 13l4-4 4 4"/><path d="M4.5 7.5V4h15v3.5"/>'),
  open: svg('<path d="M14 4h6v6"/><path d="M20 4l-8.5 8.5"/><path d="M18 14v5.5H4.5V6H10"/>'),
  copy: svg('<rect x="8.5" y="8.5" width="11" height="11" rx="1.8"/><path d="M5.5 15.5h-1V4.5h11v1"/>'),
  stop: svg('<rect x="6" y="6" width="12" height="12" rx="2"/>'),
  pause: svg('<path d="M9 5v14M15 5v14"/>'),
  play: svg('<path d="M7 5l12 7-12 7z"/>'),
  x: svg('<path d="M6 6l12 12M18 6L6 18"/>'),
  mount: svg('<rect x="3" y="5" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/><path d="M7 8.5h.01M7 16.5h.01"/>'),
  plus: svg('<path d="M12 5v14M5 12h14"/>'),
  dot: svg('<circle cx="12" cy="12" r="4" fill="currentColor" stroke="none"/>'),
};

// colored quick-folder icons
const QUICK_COLOR = { desktop: "#3b82f6", documents: "#64748b", downloads: "#22c55e", pictures: "#38bdf8", music: "#ef4444", videos: "#a855f7", folder: "#f59e0b" };
function quickGlyph(kind) {
  switch (kind) {
    case "desktop": return '<rect x="8" y="9.6" width="8" height="5" rx=".8" fill="#fff"/><path d="M11 16h2" stroke="#fff" stroke-width="1"/>';
    case "documents": return '<path d="M9 10h6M9 12.2h6M9 14.4h4" stroke="#fff" stroke-width="1.1"/>';
    case "downloads": return '<path d="M12 9v5M9.8 12l2.2 2.2L14.2 12" stroke="#fff" stroke-width="1.3" fill="none"/>';
    case "pictures": return '<circle cx="10" cy="11" r="1.2" fill="#fff"/><path d="M8 15l2.6-2.6L13 15" stroke="#fff" stroke-width="1.2" fill="none"/>';
    case "music": return '<path d="M14 9.5v4.2a1.5 1.5 0 1 1-1-1.4V10l-4 .9v3.8a1.5 1.5 0 1 1-1-1.4V9.2z" fill="#fff"/>';
    case "videos": return '<path d="M10.4 10l4 2-4 2z" fill="#fff"/>';
    default: return "";
  }
}
function quickIcon(kind, size) {
  const c = QUICK_COLOR[kind] || QUICK_COLOR.folder;
  return `<svg viewBox="0 0 24 24" width="${size || 20}" height="${size || 20}">
    <path d="M3 6.6A1.6 1.6 0 0 1 4.6 5h4.1l1.6 1.9h9.1A1.6 1.6 0 0 1 21 8.5v8.9A1.6 1.6 0 0 1 19.4 19H4.6A1.6 1.6 0 0 1 3 17.4z" fill="${c}"/>
    ${quickGlyph(kind)}</svg>`;
}

// large device artwork
function deviceArt(kind) {
  const defs = `<defs><linearGradient id="lzscr" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#6aa8ff"/><stop offset="1" stop-color="#1d4ed8"/></linearGradient></defs>`;
  if (kind === "phone") {
    return `<svg viewBox="0 0 130 120" width="104" height="112">${defs}
      <rect x="49" y="8" width="32" height="104" rx="7" fill="#2a3242"/>
      <rect x="52" y="14" width="26" height="90" rx="4" fill="url(#lzscr)"/>
      <rect x="58" y="10.5" width="14" height="2" rx="1" fill="#1a2029"/></svg>`;
  }
  if (kind === "laptop") {
    return `<svg viewBox="0 0 150 120" width="150" height="120">${defs}
      <path d="M33 78h84l7 20a4 4 0 0 1-3.8 5H29.8A4 4 0 0 1 26 98z" fill="#39424f"/>
      <rect x="40" y="24" width="70" height="52" rx="4" fill="#2a3242"/>
      <rect x="44.5" y="28.5" width="61" height="43" rx="2" fill="url(#lzscr)"/></svg>`;
  }
  return `<svg viewBox="0 0 160 120" width="154" height="120">${defs}
    <rect x="18" y="20" width="100" height="66" rx="6" fill="#2a3242"/>
    <rect x="24" y="26" width="88" height="54" rx="3" fill="url(#lzscr)"/>
    <rect x="70" y="86" width="16" height="14" fill="#39424f"/>
    <rect x="50" y="100" width="56" height="6" rx="3" fill="#39424f"/>
    <rect x="122" y="52" width="26" height="44" rx="4" fill="#2a3242"/>
    <rect x="126" y="57" width="18" height="30" rx="2" fill="#3b4b66"/></svg>`;
}

function devKind(os) {
  os = (os || "").toLowerCase();
  if (os.includes("android") || os.includes("ios")) return "phone";
  if (os.includes("mac") || os.includes("darwin") || os.includes("linux")) return "laptop";
  return "desktop";
}
function devKindName(k) { return k === "phone" ? "Phone" : k === "laptop" ? "Laptop" : "Desktop"; }
function devIconFor(os, size) { return I[devKind(os)] ? svg(I[devKind(os)].replace(/<\/?svg[^>]*>/g, ""), size) : I.monitor; }
function smallDeviceIcon(os, size) {
  const k = devKind(os);
  const inner = { desktop: '<rect x="3" y="4.5" width="18" height="12" rx="2"/><path d="M9 20h6M12 16.5V20"/>', laptop: '<rect x="4" y="5" width="16" height="10.5" rx="1.6"/><path d="M2.5 18.5h19"/>', phone: '<rect x="7" y="3" width="10" height="18" rx="2.2"/>' }[k];
  return svg(inner, size || 20);
}

// ---------- state ----------
let settings = { speed_unit: "mbs", sound_on_complete: false, notifications: true, theme: "dark", default_download_folder: "" };
// Online means the device answered a recent check (discovery probes every few
// seconds). A paired device that stops answering stays listed as Offline.
const seenAt = (() => { try { return JSON.parse(localStorage.getItem("lanyard.seen") || "{}"); } catch (e) { return {}; } })();
const wasOnline = new Map();
let peersLoaded = false;
const isOnline = (fp) => peers.some((p) => p.verified && p.device_id === fp);
function lastSeenText(fp) { const t = seenAt[fp]; return t ? "last seen " + fmtWhen(new Date(t).toISOString()) : "not seen yet"; }
// Everything worth listing: devices found now, plus paired devices that are away.
function knownDevices() {
  const out = peers.filter((p) => p.verified).map((p) => ({ device_id: p.device_id, name: p.name, os: p.os, online: true }));
  for (const e of trustList) {
    const fp = e.cert_fingerprint || e.device_id;
    if (!out.some((d) => d.device_id === fp)) out.push({ device_id: fp, name: e.name, os: "", online: false });
  }
  return out;
}
let peers = [], trustList = [], sessions = [], sharesList = [], transfersList = [], approvalsList = [], mountsList = [];
const S = {
  view: "devices",
  search: "",
  mode: "grid",
  ex: {
    roots: null,
    hist: [{ kind: "home" }],
    hi: 0,
    hover: null,
  },
};
const place = () => S.ex.hist[S.ex.hi];
function navigate(p) { S.ex.hist.splice(S.ex.hi + 1); S.ex.hist.push(p); S.ex.hi = S.ex.hist.length - 1; renderExplorer(); }
function placePath(k) { return k === "folder" ? "f:" + (place().path || "") : k === "device" ? "d:" + place().device : k === "remote" ? "r:" + place().device + "/" + place().share + "/" + (place().path || "") : "home"; }

function pairedEntry(fp) { return trustList.find((e) => e.cert_fingerprint === fp || e.device_id === fp) || null; }
function activeSession(fp) { return sessions.find((s) => s.peer_fp === fp && (s.status === "active" || s.status === "accepted")) || null; }

// ---------- nav ----------
const NAV = [
  { id: "devices", label: "View Devices", icon: "monitor" },
  { id: "paired", label: "View Paired Machines", icon: "link" },
  { id: "shares", label: "My Shares", icon: "share" },
  { id: "transfers", label: "Transfers", icon: "transfers" },
  { id: "settings", label: "Settings", icon: "gear" },
];
function renderNav() {
  const nav = $("nav");
  clear(nav);
  for (const n of NAV) {
    const item = el("div", "nav-item" + (S.view === n.id ? " active" : ""));
    item.innerHTML = I[n.icon];
    item.appendChild(el("span", null, n.label));
    if (n.id === "transfers") {
      const live = transfersList.filter((t) => t.state !== "Done").length + incomingList.length + snippetsList.length;
      if (live) item.appendChild(el("span", "nav-badge", String(live)));
    }
    if (n.id === "paired" && S.actionable) {
      item.appendChild(el("span", "nav-badge alert", String(S.actionable)));
    }
    item.addEventListener("click", () => showView(n.id));
    nav.appendChild(item);
  }
}
function showView(id) {
  S.view = id;
  for (const n of NAV) $("view-" + n.id).classList.toggle("active", n.id === id);
  renderNav();
  if (id === "devices") renderExplorer();
  if (id === "paired") renderPairedPage();
  if (id === "shares") renderSharesPage();
  if (id === "transfers") renderTransfersPage();
  if (id === "settings") openSettings();
}

// ---------- toasts ----------
function toast(msg, kind) {
  const t = el("div", "toast" + (kind ? " " + kind : ""), msg);
  $("toasts").appendChild(t);
  setTimeout(() => { t.style.opacity = "0"; t.style.transition = "opacity .3s"; setTimeout(() => t.remove(), 350); }, 4200);
}
// A notification that stays in the bottom-right corner until it is clicked,
// dismissed, or no longer relevant. Clicking it runs onClick.
const stickyToasts = new Map();
function stickyToast(key, title, detail, onClick) {
  if (stickyToasts.has(key)) return;
  const t = el("div", "toast sticky");
  t.tabIndex = 0;
  t.appendChild(el("div", "toast-title", title));
  if (detail) t.appendChild(el("div", "toast-detail", detail));
  const x = el("button", "toast-x", "\u00d7"); x.title = "Dismiss"; x.setAttribute("aria-label", "Dismiss");
  x.addEventListener("click", (e) => { e.stopPropagation(); dropSticky(key, true); });
  t.appendChild(x);
  const go = () => { dropSticky(key, true); onClick(); };
  t.addEventListener("click", go);
  t.addEventListener("keydown", (e) => { if (e.key === "Enter") go(); });
  $("toasts").appendChild(t);
  stickyToasts.set(key, { el: t, dismissed: false });
  playBeep();
}
function dropSticky(key, keep) {
  const e = stickyToasts.get(key);
  if (!e) return;
  e.el.remove();
  if (keep) stickyToasts.set(key, { el: e.el, dismissed: true }); else stickyToasts.delete(key);
}
// Remove sticky toasts whose key starts with prefix and is not in the live set.
function pruneSticky(prefix, live) {
  for (const k of [...stickyToasts.keys()]) if (k.startsWith(prefix) && !live.has(k)) dropSticky(k, false);
}

// A one-off notification from the app (download/send/receive started or
// finished, or failed). Sizes and names are formatted here.
function showNotice(n) {
  const who = n.peer || "a device";
  const count = n.files || 0;
  const files = count === 1 ? "1 file" : (count > 1 ? count + " files" : "files");
  const size = n.total ? " (" + fmtBytes(n.total) + ")" : "";
  switch (n.kind) {
    case "download": toast("Download complete: " + files + size + " from " + who + ".", "ok"); break;
    case "send": toast("Sent " + files + size + " to " + who + ".", "ok"); break;
    case "download-start": toast("Downloading " + files + size + " from " + who + "\u2026", "info"); break;
    case "send-start": toast("Sending " + files + size + " to " + who + "\u2026", "info"); break;
    case "receive-start": toast("Receiving " + files + size + " from " + who + "\u2026", "info"); break;
    case "receive": toast("Received " + files + size + " from " + who + ". Saved to your Inbox.", "ok"); break;
    case "download-failed": toast("Download failed" + (n.error ? ": " + n.error : "."), "err"); break;
    case "send-failed": toast("Send failed" + (n.error ? ": " + n.error : "."), "err"); break;
    default: return;
  }
}

// ---------- native file / folder dialog ----------
// Opens the operating system's own Explorer-style dialog (via the app) and
// returns the chosen paths, or [] if cancelled. Falls back to typing a path
// where the system has no native dialog.
async function pickPaths(kind, title, start) {
  try {
    const r = await fetch("/api/fs/pick", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ kind, title, start: start || "" }) });
    if (r.ok) return (await r.json()).paths || [];
    if (r.status !== 501) { toast((await r.text()).trim(), "err"); return []; }
  } catch (e) { /* fall through to typing */ }
  const typed = prompt(kind === "folder" ? "Folder path:" : "File or folder path:", start || "");
  return typed && typed.trim() ? [typed.trim()] : [];
}
async function pickFolder(title, start) { const a = await pickPaths("folder", title, start); return a[0] || ""; }
const savedDest = () => { try { return localStorage.getItem("lanyard.dest") || ""; } catch (e) { return ""; } };
const currentDest = () => savedDest() || settings.default_download_folder || "";

// ---------- context menu ----------
function closeMenus() { clear($("ctx-root")); }
function ctxMenu(items) {
  const m = el("div", "ctx");
  m.addEventListener("click", (e) => e.stopPropagation());
  for (const it of items) {
    if (!it) continue;
    if (it.sep) { m.appendChild(el("div", "ctx-sep")); continue; }
    const row = el("div", "ctx-item");
    if (it.icon && I[it.icon]) row.innerHTML = I[it.icon];
    row.appendChild(el("span", null, it.label));
    if (it.submenu) row.appendChild(el("span", "sub", "\u203a"));
    row.addEventListener("click", (e) => {
      e.stopPropagation();
      if (it.submenu) {
        const sub = ctxMenu(it.submenu);
        const r = row.getBoundingClientRect();
        sub.style.left = Math.min(r.right + 2, window.innerWidth - 220) + "px";
        sub.style.top = r.top + "px";
        $("ctx-root").appendChild(sub);
        return;
      }
      closeMenus();
      if (it.onClick) it.onClick();
    });
    m.appendChild(row);
  }
  return m;
}
function showMenu(x, y, items) {
  closeMenus();
  const m = ctxMenu(items);
  m.style.left = x + "px"; m.style.top = y + "px";
  $("ctx-root").appendChild(m);
  const r = m.getBoundingClientRect();
  if (r.right > window.innerWidth) m.style.left = Math.max(6, window.innerWidth - r.width - 6) + "px";
  if (r.bottom > window.innerHeight) m.style.top = Math.max(6, window.innerHeight - r.height - 6) + "px";
}
document.addEventListener("click", closeMenus);
document.addEventListener("contextmenu", (e) => { if (!e.target.closest(".file-item,.device-card,.tree-item")) closeMenus(); });
window.addEventListener("blur", closeMenus);

// ---------- explorer ----------
function renderExplorer() {
  renderExSide();
  renderToolbar();
  renderBody();
}
function renderExSide() {
  const box = $("ex-side");
  clear(box);
  const p = place();

  const home = el("div", "tree-item" + (p.kind === "home" || p.kind === "folder" ? " active" : ""));
  home.innerHTML = I.home;
  home.appendChild(el("span", "ti-label", "This PC"));
  home.addEventListener("click", () => navigate({ kind: "home" }));
  box.appendChild(home);

  if (S.ex.roots) {
    for (const q of S.ex.roots.quick) {
      const it = el("div", "tree-item" + (p.kind === "folder" && p.path === q.path ? " active" : ""));
      it.innerHTML = quickIcon(q.kind);
      it.appendChild(el("span", "ti-label", q.name));
      it.addEventListener("click", () => navigate({ kind: "folder", path: q.path }));
      it.addEventListener("contextmenu", (e) => { e.preventDefault(); showMenu(e.clientX, e.clientY, folderMenu(q.path, q.name)); });
      box.appendChild(it);
    }
  }

  box.appendChild(el("div", "tree-sep"));
  const sh = el("div", "tree-item" + (p.kind === "shared" ? " active" : ""));
  sh.innerHTML = I.share;
  sh.appendChild(el("span", "ti-label", "Shared with me"));
  sh.addEventListener("click", () => navigate({ kind: "shared" }));
  box.appendChild(sh);
  box.appendChild(el("div", "tree-label", "Devices"));
  const list = knownDevices();
  if (!list.length) box.appendChild(el("div", "tree-label", "No devices nearby"));
  for (const peer of list) {
    const isDev = (p.kind === "device" || p.kind === "remote") && p.device === peer.device_id;
    const it = el("div", "tree-item" + (isDev ? " active" : ""));
    it.innerHTML = smallDeviceIcon(peer.os);
    it.appendChild(el("span", "ti-label", peer.name || "(unnamed)"));
    const dot = el("span", "dot sm" + (peer.online ? "" : " off")); dot.title = peer.online ? "Online" : "Offline";
    it.appendChild(dot);
    it.addEventListener("click", () => navigate({ kind: "device", device: peer.device_id, name: peer.name }));
    it.addEventListener("contextmenu", (e) => { e.preventDefault(); showMenu(e.clientX, e.clientY, peerMenu(peer)); });
    box.appendChild(it);
  }
}
function renderToolbar() {
  const p = place();
  $("nav-back").disabled = S.ex.hi <= 0;
  $("nav-fwd").disabled = S.ex.hi >= S.ex.hist.length - 1;
  $("nav-up").disabled = !canGoUp(p);
  const vm = $("view-mode");
  vm.innerHTML = S.mode === "grid" ? I.list : I.grid;
  renderCrumbs();
}
function canGoUp(p) { return p.kind === "folder" || p.kind === "remote" || p.kind === "device" || p.kind === "shared" ? true : false; }
function renderCrumbs() {
  const c = $("crumbs");
  clear(c);
  const p = place();
  const crumb = (label, iconHtml, fn) => {
    const b = el("div", "crumb");
    if (iconHtml) b.innerHTML = iconHtml;
    b.appendChild(el("span", null, label));
    if (fn) b.addEventListener("click", fn);
    return b;
  };
  if (p.kind === "home") {
    c.appendChild(crumb("This PC", smallDeviceIcon("windows", 16), null));
  } else if (p.kind === "folder") {
    c.appendChild(crumb("This PC", smallDeviceIcon("windows", 16), () => navigate({ kind: "home" })));
    c.appendChild(el("span", "crumb-sep", "/"));
    const parts = p.path.split(/[\\/]/).filter(Boolean);
    let acc = "";
    parts.forEach((part, i) => {
      acc = acc ? acc + "\\" + part : part;
      const target = acc;
      const isLast = i === parts.length - 1;
      if (i > 0) c.appendChild(el("span", "crumb-sep", "/"));
      c.appendChild(crumb(part, i === 0 ? I.folder : null, isLast ? null : () => navigate({ kind: "folder", path: target })));
    });
  } else if (p.kind === "shared") {
    c.appendChild(crumb("This PC", smallDeviceIcon("windows", 16), () => navigate({ kind: "home" })));
    c.appendChild(el("span", "crumb-sep", "/"));
    c.appendChild(crumb("Shared with me", I.share, null));
  } else if (p.kind === "device") {
    c.appendChild(crumb("This PC", smallDeviceIcon("windows", 16), () => navigate({ kind: "home" })));
    c.appendChild(el("span", "crumb-sep", "/"));
    c.appendChild(crumb(p.name || "Device", smallDeviceIcon(peerOS(p.device), 16), null));
  } else if (p.kind === "remote") {
    c.appendChild(crumb("This PC", smallDeviceIcon("windows", 16), () => navigate({ kind: "home" })));
    c.appendChild(el("span", "crumb-sep", "/"));
    c.appendChild(crumb(p.name || "Device", smallDeviceIcon(peerOS(p.device), 16), () => navigate({ kind: "device", device: p.device, name: p.name })));
    c.appendChild(el("span", "crumb-sep", "/"));
    const parts = (p.path || "").split("/").filter(Boolean);
    c.appendChild(crumb(p.shareLabel || "share", I.folder, parts.length ? () => navigate({ ...p, path: "" }) : null));
    let acc = [];
    parts.forEach((part, i) => {
      acc.push(part);
      const target = acc.join("/");
      const isLast = i === parts.length - 1;
      c.appendChild(el("span", "crumb-sep", "/"));
      c.appendChild(crumb(part, null, isLast ? null : () => navigate({ ...p, path: target })));
    });
  }
}
function peerOS(fp) { const x = peers.find((p) => p.device_id === fp) || trustList.find((e) => e.cert_fingerprint === fp); return x ? (x.os || "") : "windows"; }

function renderBody() {
  const body = $("ex-body");
  clear(body);
  const p = place();
  if (p.kind === "home") return renderHome(body);
  if (p.kind === "folder") return renderFolder(body, p);
  if (p.kind === "device") return renderDevice(body, p);
  if (p.kind === "shared") return renderShared(body);
  if (p.kind === "remote") return renderRemote(body, p);
}

// -- home: device cards --
function renderHome(body) {
  const all = knownDevices();
  const list = all.filter((p) => !S.search || (p.name || "").toLowerCase().includes(S.search) || (p.os || "").toLowerCase().includes(S.search));
  if (!list.length) {
    const wrap = el("div", "empty");
    wrap.appendChild(el("div", null, all.length ? "No devices match your search." : "Looking for other devices running LANyard on this network\u2026"));
    const row = el("div", "form-row"); row.style.justifyContent = "center"; row.style.marginTop = "14px";
    const inp = el("input"); inp.placeholder = "Add by address (192.168.1.20:47800)"; inp.style.minWidth = "260px";
    const b = el("button", "btn", "Add");
    b.addEventListener("click", () => addPeer(inp.value.trim()));
    inp.addEventListener("keydown", (e) => { if (e.key === "Enter") addPeer(inp.value.trim()); });
    row.appendChild(inp); row.appendChild(b);
    wrap.appendChild(row);
    body.appendChild(wrap);
    return;
  }
  const grid = el("div", "cards");
  for (const p of list) {
    const k = devKind(p.os);
    const card = el("div", "device-card" + (p.online ? "" : " offline"));
    card.tabIndex = 0;
    const art = el("div", "dc-art"); art.innerHTML = deviceArt(k); card.appendChild(art);
    card.appendChild(el("div", "dc-name", devKindName(k)));
    card.appendChild(el("div", "dc-sub", p.name || "(unnamed)"));
    const st = el("div", "status" + (p.online ? "" : " off"));
    st.appendChild(el("span", "dot" + (p.online ? "" : " off")));
    st.appendChild(el("span", null, p.online ? "Online" : "Offline"));
    card.appendChild(st);
    if (!p.online) card.appendChild(el("div", "dc-sub", lastSeenText(p.device_id)));
    card.addEventListener("click", () => navigate({ kind: "device", device: p.device_id, name: p.name }));
    card.addEventListener("contextmenu", (e) => { e.preventDefault(); showMenu(e.clientX, e.clientY, peerMenu(p)); });
    grid.appendChild(card);
  }
  body.appendChild(grid);
}

// -- local folder --
function renderFolder(body, p) {
  const grid = el("div", S.mode === "grid" ? "file-grid" : "file-list");
  grid.appendChild(el("div", "empty", "Loading\u2026"));
  body.appendChild(grid);
  fetch(`/api/fs/list?path=${encodeURIComponent(p.path)}`)
    .then((r) => r.ok ? r.json() : r.text().then((t) => Promise.reject(t.trim())))
    .then((data) => {
      if (place().kind !== "folder" || place().path !== p.path) return; // stale
      clear(grid);
      const entries = data.entries || [];
      if (!entries.length) { grid.appendChild(el("div", "empty", "This folder is empty.")); return; }
      for (const e of entries) {
        const item = el("div", "file-item");
        item.innerHTML = e.is_dir ? quickIcon("folder") : I.file;
        item.appendChild(el("span", "fi-name", e.name));
        if (!e.is_dir) item.appendChild(el("span", "fi-size", fmtBytes(e.size)));
        if (e.is_dir) item.addEventListener("dblclick", () => navigate({ kind: "folder", path: e.path }));
        item.addEventListener("contextmenu", (ev) => {
          ev.preventDefault();
          const items = [];
          if (e.is_dir) items.push({ label: "Open", icon: "folder", onClick: () => navigate({ kind: "folder", path: e.path }) });
          items.push({ label: "Share with\u2026", icon: "share", submenu: shareSubmenu(e.path) });
          items.push({ label: "Share with everyone paired", icon: "share", onClick: () => sharePath(e.path) });
          items.push({ sep: true });
          items.push({ label: "Copy path", icon: "copy", onClick: () => copyText(e.path) });
          showMenu(ev.clientX, ev.clientY, items);
        });
        grid.appendChild(item);
      }
    })
    .catch((err) => { grid.replaceChildren(el("div", "empty", String(err))); });
}

// -- device detail --
function renderDevice(body, p) {
  const peer = peers.find((x) => x.device_id === p.device) || { name: p.name, os: "", device_id: p.device };
  const paired = pairedEntry(p.device);
  const session = activeSession(p.device);
  const online = isOnline(p.device);
  const head = el("div", "row device-head");
  const main = el("div", "grow");
  main.appendChild(el("div", "name", peer.name || "(unnamed)"));
  const bits = [online ? "Online" : "Offline \u2014 " + lastSeenText(p.device), devKindName(devKind(peer.os)), paired ? "Paired" : session ? "Connected" : "Not paired"];
  if (peer.os) bits.push(peer.os);
  main.appendChild(el("div", "meta", bits.join(" \u00b7 ")));
  main.appendChild(el("div", "meta", "ID " + prettyId(p.device)));
  head.appendChild(main);
  const actions = el("div", "actions");
  if (paired) {
    actions.appendChild(btn("Push files\u2026", () => pushTo(p.device, peer.name)));
    actions.appendChild(btn("Push folder\u2026", () => pushTo(p.device, peer.name, true), "ghost"));
    actions.appendChild(btn("Mount as drive", () => mountDevice(p.device, peer.name), "ghost"));
    actions.appendChild(btn("Unpair", () => unpair(p.device, paired), "ghost"));
  } else if (session) {
    actions.appendChild(btn("Push files\u2026", () => pushTo(p.device, peer.name)));
    actions.appendChild(btn("Push folder\u2026", () => pushTo(p.device, peer.name, true), "ghost"));
    actions.appendChild(btn("Disconnect", () => sessionAction(session.id, "close"), "ghost"));
  } else {
    actions.appendChild(btn("Connect", () => startPair(p.device, peer.name, "connect")));
    actions.appendChild(btn("Pair", () => startPair(p.device, peer.name, "pair")));
  }
  if (!online) for (const b of actions.querySelectorAll("button")) if (b.textContent !== "Unpair" && b.textContent !== "Disconnect") { b.disabled = true; b.title = "This device is offline"; }
  head.appendChild(actions);
  body.appendChild(head);

  if (paired || session) {
    body.appendChild(el("div", "section-title", "Send text"));
    const box = el("div", "form-row");
    const ta = document.createElement("textarea");
    ta.rows = 3; ta.maxLength = 65536;
    ta.placeholder = "Type a short message or paste a link\u2026";
    ta.style.flex = "1 1 240px"; ta.style.minWidth = "0";
    const send = btn("Send text", async () => {
      const text = ta.value;
      if (!text.trim()) { toast("Type something to send.", "err"); return; }
      const r = await fetch("/api/snippet", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ device: p.device, text }) });
      if (!r.ok) { toast((await r.text()).trim(), "err"); return; }
      ta.value = "";
      toast("Text sent to " + (peer.name || "the device") + ".", "ok");
    });
    if (!online) send.disabled = true;
    box.appendChild(ta); box.appendChild(send);
    body.appendChild(box);
  }

  body.appendChild(el("div", "section-title", "Shared with you"));
  const listBox = el("div", "stack");
  listBox.appendChild(el("div", "empty", "Loading shares\u2026"));
  body.appendChild(listBox);

  if (!online) { clear(listBox); listBox.appendChild(el("div", "empty", "This device is offline. Its shares will appear here when it is back.")); return; }
  fetch(`/api/remote/shares?device=${encodeURIComponent(p.device)}`)
    .then((r) => r.ok ? r.json() : r.text().then((t) => Promise.reject(t.trim())))
    .then((list) => {
      if (place().kind !== "device" || place().device !== p.device) return;
      clear(listBox);
      if (!list.length) { listBox.appendChild(el("div", "empty", paired || session ? "This device is not sharing anything with you right now." : "Pair with or connect to this device to see what it shares.")); return; }
      for (const s of list) {
        const row = el("div", "row");
        const m = el("div", "grow");
        m.appendChild(el("div", "name", s.label));
        const mb = [s.kind];
        if (s.size) mb.push(fmtBytes(s.size));
        const life = s.lifetime === "one_time" ? "one-time" : s.lifetime === "timed" && s.expires_at ? fmtCountdown(s.expires_at) : "";
        if (life) mb.push(life);
        m.appendChild(el("div", "meta", mb.join(" \u00b7 ")));
        row.appendChild(m);
        const acts = el("div", "actions");
        if (s.kind === "folder") acts.appendChild(btn("Open", () => navigate({ kind: "remote", device: p.device, name: peer.name, share: s.share_id, shareLabel: s.label, path: "" })));
        acts.appendChild(btn("Download", () => downloadDialog(p.device, peer.name, s.share_id, s.label, [""]), "ghost"));
        acts.appendChild(btn("Download to\u2026", () => downloadDialog(p.device, peer.name, s.share_id, s.label, [""], true), "ghost"));
        row.appendChild(acts);
        listBox.appendChild(row);
      }
    })
    .catch((err) => { clear(listBox); listBox.appendChild(el("div", "empty", String(err))); });
}

// -- everything other devices share with this one --
function renderShared(body) {
  body.appendChild(el("div", "section-title", "Shared with me"));
  const box = el("div", "stack");
  body.appendChild(box);
  const list = peers.filter((x) => x.verified && (pairedEntry(x.device_id) || activeSession(x.device_id)));
  if (!list.length) {
    box.appendChild(el("div", "empty", "Pair with or connect to a device to see what it shares with you. Open a device from the list on the left to start."));
    return;
  }
  for (const peer of list) {
    const sec = el("div", "stack");
    sec.appendChild(el("div", "meta", peer.name || "(unnamed)"));
    const rows = el("div", "stack"); rows.appendChild(el("div", "empty", "Loading\u2026"));
    sec.appendChild(rows); box.appendChild(sec);
    fetch(`/api/remote/shares?device=${encodeURIComponent(peer.device_id)}`)
      .then((r) => r.ok ? r.json() : r.text().then((t) => Promise.reject(t.trim())))
      .then((shs) => {
        if (place().kind !== "shared") return;
        clear(rows);
        if (!shs.length) { rows.appendChild(el("div", "empty", "Nothing shared with you right now.")); return; }
        for (const sh of shs) {
          const row = el("div", "row"); const m = el("div", "grow");
          m.appendChild(el("div", "name", sh.label));
          m.appendChild(el("div", "meta", [sh.kind, sh.size ? fmtBytes(sh.size) : ""].filter(Boolean).join(" \u00b7 ")));
          row.appendChild(m);
          const acts = el("div", "actions");
          if (sh.kind === "folder") acts.appendChild(btn("Open", () => navigate({ kind: "remote", device: peer.device_id, name: peer.name, share: sh.share_id, shareLabel: sh.label, path: "" })));
          acts.appendChild(btn("Download", () => downloadDialog(peer.device_id, peer.name, sh.share_id, sh.label, [""]), "ghost"));
          row.appendChild(acts); rows.appendChild(row);
        }
      })
      .catch((err) => { clear(rows); rows.appendChild(el("div", "msg err", "Could not list this device's shares: " + err)); });
  }
}

// -- remote share browser --
function renderRemote(body, p) {
  const bar = el("div", "form-row");
  bar.appendChild(el("span", "muted", "Save to:"));
  const destLabel = el("span", "dest-path", currentDest() || "not chosen yet");
  destLabel.title = "Where downloads are saved";
  bar.appendChild(destLabel);
  bar.appendChild(btn("Change\u2026", async () => {
    const d = await pickFolder("Choose where to save downloads", currentDest());
    if (d) { try { localStorage.setItem("lanyard.dest", d); } catch (e) { } destLabel.textContent = d; }
  }, "ghost"));
  const dlHere = btn("Download this folder", () => downloadRemote(p, [p.path || ""]));
  bar.appendChild(dlHere);
  body.appendChild(bar);

  const list = el("div", S.mode === "grid" ? "file-grid" : "file-list");
  list.appendChild(el("div", "empty", "Loading\u2026"));
  body.appendChild(list);

  const q = `device=${encodeURIComponent(p.device)}&share=${encodeURIComponent(p.share)}&path=${encodeURIComponent(p.path || "")}`;
  fetch(`/api/remote/tree?${q}`)
    .then((r) => r.ok ? r.json() : r.text().then((t) => Promise.reject(t.trim())))
    .then((entries) => {
      const cur = place();
      if (cur.kind !== "remote" || cur.device !== p.device || cur.share !== p.share || (cur.path || "") !== (p.path || "")) return;
      clear(list);
      if (!entries.length) { list.appendChild(el("div", "empty", "Empty folder.")); return; }
      for (const e of entries) {
        const item = el("div", "file-item");
        item.innerHTML = e.is_dir ? quickIcon("folder") : I.file;
        item.appendChild(el("span", "fi-name", e.name));
        if (!e.is_dir) item.appendChild(el("span", "fi-size", fmtBytes(e.size)));
        if (e.is_dir) item.addEventListener("dblclick", () => navigate({ ...p, path: e.path }));
        item.addEventListener("contextmenu", (ev) => {
          ev.preventDefault();
          const items = [];
          if (e.is_dir) items.push({ label: "Open", icon: "folder", onClick: () => navigate({ ...p, path: e.path }) });
          items.push({ label: "Download", icon: "download", onClick: () => downloadRemote(p, [e.path]) });
          items.push({ label: "Download to\u2026", icon: "download", onClick: () => downloadRemote(p, [e.path], true) });
          showMenu(ev.clientX, ev.clientY, items);
        });
        list.appendChild(item);
      }
    })
    .catch((err) => { list.replaceChildren(el("div", "empty", String(err))); });
}

// Starts a download. It goes to the saved (or default) folder; the first time,
// or with askWhere, the system folder dialog opens so nothing has to be typed.
async function downloadRemote(p, paths, askWhere) {
  let dest = currentDest();
  if (askWhere === true || !dest) {
    dest = await pickFolder("Choose where to save the download", dest);
    if (!dest) return;
  }
  try { localStorage.setItem("lanyard.dest", dest); } catch (e) { }
  const r = await fetch("/api/transfers", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ device: p.device, share_id: p.share, share_label: p.shareLabel, peer_name: p.name, paths, dest }),
  });
  if (r.ok) { toast("Downloading to " + dest, "ok"); showView("transfers"); } else toast((await r.text()).trim(), "err");
}
function downloadDialog(device, name, share, label, paths, askWhere) {
  return downloadRemote({ device, name, share, shareLabel: label }, paths, askWhere);
}

// ---------- sharing ----------
function shareTargets() {
  const out = [];
  for (const s of sessions) {
    if (s.status === "active" || s.status === "accepted") {
      if (!out.find((x) => x.id === s.peer_fp)) out.push({ id: s.peer_fp, name: s.peer_name || prettyId(s.peer_fp), kind: "connected" });
    }
  }
  for (const e of trustList) {
    if (!out.find((x) => x.id === (e.cert_fingerprint || e.device_id))) {
      out.push({ id: e.cert_fingerprint || e.device_id, name: e.name || prettyId(e.cert_fingerprint), kind: "paired" });
    }
  }
  return out;
}
function shareSubmenu(path) {
  const t = shareTargets();
  if (!t.length) return [{ label: "No connected devices", onClick: () => toast("Pair or connect with a device first.", "err") }];
  return t.map((x) => ({
    label: x.name + (x.kind === "connected" ? " (connected)" : ""),
    icon: devKind(peerOS(x.id)) === "phone" ? "phone" : "monitor",
    onClick: () => sharePathWith(path, x),
  }));
}
async function sharePathWith(path, target) {
  const body = { path, label: "", lifetime: "until_stopped", seconds: 0, visibility: "specific", allowed_devices: [target.id] };
  const r = await fetch("/api/shares", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  if (r.status === 409) {
    const j = await r.json();
    if (!confirm(j.warning)) return;
    body.confirm = true;
    const r2 = await fetch("/api/shares", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    if (r2.ok) toast(`Shared with ${target.name}.`, "ok"); else toast((await r2.text()).trim(), "err");
    return;
  }
  if (r.ok) toast(`Shared with ${target.name}.`, "ok");
  else toast((await r.text()).trim(), "err");
}
async function sharePath(path) {
  const body = { path, label: "", lifetime: "until_stopped", seconds: 0, confirm: false };
  let r = await fetch("/api/shares", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  if (r.status === 409) {
    const j = await r.json(); if (!confirm(j.warning)) return;
    body.confirm = true; r = await fetch("/api/shares", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  }
  if (r.ok) toast("Shared with paired devices.", "ok"); else toast((await r.text()).trim(), "err");
}
function folderMenu(path, name) {
  return [
    { label: "Open", icon: "folder", onClick: () => navigate({ kind: "folder", path }) },
    { label: "Share with\u2026", icon: "share", submenu: shareSubmenu(path) },
    { label: "Share with everyone paired", icon: "share", onClick: () => sharePath(path) },
    { sep: true },
    { label: "Copy path", icon: "copy", onClick: () => copyText(path) },
  ];
}
function peerMenu(p) {
  const paired = pairedEntry(p.device_id);
  const session = activeSession(p.device_id);
  const items = [{ label: "Open", icon: "monitor", onClick: () => navigate({ kind: "device", device: p.device_id, name: p.name }) }];
  if (paired || session) {
    if (isOnline(p.device_id)) items.push({ label: "Push files\u2026", icon: "push", onClick: () => pushTo(p.device_id, p.name) });
    if (paired) items.push({ label: "Unpair", icon: "x", onClick: () => unpair(p.device_id, paired) });
    else items.push({ label: "Disconnect", icon: "x", onClick: () => sessionAction(session.id, "close") });
  } else {
    items.push({ label: "Connect", icon: "link", onClick: () => startPair(p.device_id, p.name, "connect") });
    items.push({ label: "Pair", icon: "link", onClick: () => startPair(p.device_id, p.name, "pair") });
  }
  return items;
}
function copyText(t) {
  navigator.clipboard && navigator.clipboard.writeText(t).then(() => toast("Copied.", "ok")).catch(() => toast(t));
}

// ---------- paired page ----------
function renderPairedPage() {
  const box = $("paired-body");
  clear(box);
  // A finished pairing lives in "Paired devices" below; only requests still in
  // progress and open Connect sessions are listed here.
  const active = sessions.filter((s) => s.status === "pending" || s.status === "accepted" || (s.status === "active" && s.mode !== "pair"));
  if (active.length) {
    box.appendChild(el("div", "section-title", "Requests & sessions"));
    const stack = el("div", "stack");
    for (const s of active) {
      const row = el("div", "row");
      const m = el("div", "grow");
      const who = s.peer_name || prettyId(s.peer_fp) || s.peer_device || "device";
      m.appendChild(el("div", "name", (s.mode === "pair" ? "Pairing" : "Connect") + " \u00b7 " + who));
      const st = s.status === "pending" ? (s.incoming ? "wants to connect \u2014 review the code" : "waiting for the other device to accept")
        : s.status === "accepted" ? (s.incoming ? "accepted \u2014 waiting for them to confirm the code" : "they accepted \u2014 confirm the code") : "connected";
      m.appendChild(el("div", "meta", st));
      row.appendChild(m);
      const acts = el("div", "actions");
      if (s.status === "active") {
        acts.appendChild(btn("Disconnect", () => sessionAction(s.id, "close"), "ghost"));
      } else {
        acts.appendChild(btn("Review", () => openSession(s.id)));
        if (s.incoming && s.status === "pending") acts.appendChild(btn("Accept", () => acceptSession(s.id), "ghost"));
        if (!s.incoming && s.status === "accepted") acts.appendChild(btn("Confirm code", () => confirmSession(s.id), "ghost"));
      }
      row.appendChild(acts);
      stack.appendChild(row);
    }
  if (history.length) {
    const hf = S.historyFilter || "all";
    box.appendChild(el("div", "section-title", "History"));
    const filters = el("div", "form-row");
    for (const [key, label] of [["all", "All"], ["sent", "Sent"], ["received", "Received"], ["failed", "Failed"]]) {
      const b = el("button", "btn ghost" + (hf === key ? " active" : ""), label);
      b.addEventListener("click", () => { S.historyFilter = key; renderTransfersPage(); });
      filters.appendChild(b);
    }
    box.appendChild(filters);
    const list = history.filter((t) => {
      if (hf === "sent") return t.direction === "push";
      if (hf === "received") return t.direction === "download" || t.direction === "receive";
      if (hf === "failed") return t.state === "Failed";
      return true;
    });
    const hstack = el("div", "stack");
    if (!list.length) hstack.appendChild(el("div", "empty", "Nothing here."));
    for (const t of list) {
      const row = el("div", "row col");
      const top = el("div", "row"); top.style.border = "0"; top.style.padding = "0"; top.style.background = "transparent";
      const dir = t.direction === "push" ? "\u2191 Sent" : "\u2193 Received";
      const name = t.share_label || (t.files && t.files[0] && t.files[0].local) || t.share_id || "transfer";
      top.appendChild(el("div", "grow name", dir + " \u00b7 " + name));
      top.appendChild(el("span", "badge " + stateClass(t.state), t.state));
      row.appendChild(top);
      const n = t.files_total || (t.files || []).length;
      const meta = el("div", "meta");
      meta.textContent = `${fmtBytes(t.total)} \u00b7 ${n} file${n === 1 ? "" : "s"}` +
        (t.peer_name ? ` \u00b7 ${t.direction === "push" ? "to " : "from "}${t.peer_name}` : "") +
        (t.finished_at ? ` \u00b7 ${fmtWhen(t.finished_at)}` : "");
      row.appendChild(meta);
      if (t.error) row.appendChild(el("div", "msg err", t.error));
      const acts = el("div", "actions");
      if (t.direction === "receive") {
        acts.appendChild(btn("Open folder", () => fetch("/api/inbox/open", { method: "POST" }), "ghost"));
      } else {
        acts.appendChild(btn("Resend", async () => {
          const r = await fetch(`/api/transfers/${encodeURIComponent(t.id)}/retry`, { method: "POST" });
          if (!r.ok) { toast((await r.text()).trim(), "err"); return; }
          toast("Resent.", "ok");
        }));
      }
      acts.appendChild(btn("Remove", async () => {
        await fetch(`/api/transfers/${encodeURIComponent(t.id)}/cancel`, { method: "POST" });
      }, "ghost"));
      row.appendChild(acts);
      hstack.appendChild(row);
    }
    box.appendChild(hstack);
  }
  box.appendChild(stack);
}
  box.appendChild(el("div", "section-title", "Paired devices"));
  const stack = el("div", "stack");
  if (!trustList.length) stack.appendChild(el("div", "empty", "No paired devices yet. Pair with a device from View Devices."));
  for (const e of trustList) {
    const p = e.permissions || {};
    const row = el("div", "row");
    const m = el("div", "grow");
    m.appendChild(el("div", "name", e.name || e.device_id || "device"));
    const bits = [p.browse ? "can browse" : "no browse", p.push ? "can push" : "no push"];
    if (p.ask_over) bits.push(`asks over ${Math.round(p.ask_over / 1048576)} MB`);
    if (p.push_max_bytes) bits.push(`max ${Math.round(p.push_max_bytes / 1048576)} MB`);
    m.appendChild(el("div", "meta", bits.join(" \u00b7 ")));
    m.appendChild(el("div", "meta", "ID " + prettyId(e.cert_fingerprint || e.device_id)));
    const fpE = e.cert_fingerprint || e.device_id, onE = isOnline(fpE);
    const stE = el("div", "status" + (onE ? "" : " off")); stE.appendChild(el("span", "dot" + (onE ? "" : " off")));
    stE.appendChild(el("span", null, onE ? "Online" : "Offline \u2014 " + lastSeenText(fpE)));
    m.appendChild(stE);
    row.appendChild(m);
    const acts = el("div", "actions");
    const pushBtn = btn("Push files\u2026", () => pushTo(fpE, e.name), "ghost");
    if (!onE) { pushBtn.disabled = true; pushBtn.title = "This device is offline"; }
    acts.appendChild(pushBtn);
    acts.appendChild(btn("Unpair", () => unpair(e.cert_fingerprint || e.device_id, e), "ghost"));
    row.appendChild(acts);
    stack.appendChild(row);
  }
  box.appendChild(stack);
}

// ---------- shares page ----------
function renderSharesPage() {
  const box = $("shares-body");
  clear(box);
  const stack = el("div", "stack");
  if (!sharesList.length) stack.appendChild(el("div", "empty", "No shares yet. Share a file or folder from the explorer."));
  for (const s of sharesList) {
    const finishing = s.state === "finishing";
    const row = el("div", "row");
    const m = el("div", "grow");
    m.appendChild(el("div", "name", s.label || s.path));
    const bits = [s.kind || ""];
    if (s.visibility === "specific") bits.push("specific device");
    if (finishing) {
      bits.push("Ended");
      if (s.active_transfers) bits.push(`finishing ${s.active_transfers}`);
    } else {
      bits.push(shareLifetimeText(s));
      if (s.active_transfers > 0) bits.push(`${s.active_transfers} transferring`);
    }
    m.appendChild(el("div", "meta", bits.filter(Boolean).join(" \u00b7 ")));
    m.appendChild(el("div", "meta", s.path));
    row.appendChild(m);
    if (finishing) row.appendChild(el("span", "badge warn", "Finishing"));
    const stop = el("button", finishing ? "btn danger" : "btn ghost", finishing ? "Stop now" : "Stop");
    stop.addEventListener("click", () => {
      if (finishing && (s.active_transfers || 0) > 0 && !confirm("Stop now? Active transfers will be cut off immediately.")) return;
      fetch(`/api/shares/${encodeURIComponent(s.share_id)}/stop`, { method: "POST" });
    });
    row.appendChild(stop);
    stack.appendChild(row);
  }
  box.appendChild(stack);
}
function shareLifetimeText(s) {
  const lt = s.lifetime || {};
  switch (lt.type) {
    case "timed": { const c = fmtCountdown(lt.expires_at); return c === "expired" ? "Expiring\u2026" : "Ends in " + c.replace(" left", ""); }
    case "one_time": return "One-time, ends after one download";
    case "persistent": return "Always";
    default: return "Until stopped";
  }
}

// ---------- transfers page ----------
function renderTransfersPage() {
  const box = $("transfers-body");
  clear(box);
  const history = transfersList.filter((t) => t.state === "Done" || t.state === "Failed");
  const activeTransfers = transfersList.filter((t) => t.state !== "Done" && t.state !== "Failed");
  $("clear-finished").hidden = !history.length;
  const stack = el("div", "stack");
  for (const sp of snippetsList) {
    const row = el("div", "row col");
    const top = el("div", "row"); top.style.border = "0"; top.style.padding = "0"; top.style.background = "transparent";
    top.appendChild(el("div", "grow name", "\u2709 Text from " + snippetPeer(sp)));
    top.appendChild(el("span", "badge", "Text"));
    row.appendChild(top);
    const text = el("div", "meta"); text.textContent = sp.text; text.style.whiteSpace = "pre-wrap"; text.style.wordBreak = "break-word";
    row.appendChild(text);
    const acts = el("div", "actions");
    acts.appendChild(btn("Copy", () => copyText(sp.text), "ghost"));
    acts.appendChild(btn("Dismiss", async () => {
      const r = await fetch(`/api/snippets/${encodeURIComponent(sp.id)}/dismiss`, { method: "POST" });
      if (!r.ok) toast((await r.text()).trim(), "err");
    }, "ghost"));
    row.appendChild(acts);
    stack.appendChild(row);
  }
  if (!transfersList.length && !incomingList.length && !snippetsList.length) stack.appendChild(el("div", "empty", "No transfers."));  for (const inc of incomingList) {
    const row = el("div", "row col");
    const top = el("div", "row"); top.style.border = "0"; top.style.padding = "0"; top.style.background = "transparent";
    top.appendChild(el("div", "grow name", "\u2193 Receiving from " + (inc.peer_name || prettyId(inc.peer_fp) || "a device")));
    top.appendChild(el("span", "badge", "Receiving"));
    row.appendChild(top);
    const pct = inc.total ? Math.min(100, (inc.done / inc.total) * 100) : 0;
    const bar = el("div", "bar"); const fill = el("div", "fill"); fill.style.width = pct.toFixed(1) + "%"; bar.appendChild(fill); row.appendChild(bar);
    row.appendChild(el("div", "meta", `${pct.toFixed(0)}% \u00b7 ${fmtBytes(inc.done)} / ${fmtBytes(inc.total)} \u00b7 ${inc.files_done} of ${inc.files_total} file${inc.files_total === 1 ? "" : "s"} \u00b7 saved to your Inbox`));
    if (inc.current) row.appendChild(el("div", "meta", inc.current));
    const acts = el("div", "actions");
    acts.appendChild(btn("Cancel", async () => {
      if (!confirm("Stop receiving these files? Files already received stay in your Inbox.")) return;
      const r = await fetch(`/api/incoming/${encodeURIComponent(inc.id)}/cancel`, { method: "POST" });
      if (!r.ok) toast((await r.text()).trim(), "err"); else toast("Transfer cancelled.", "info");
    }, "ghost"));
    row.appendChild(acts);
    stack.appendChild(row);
  }
  for (const t of activeTransfers) {
    const row = el("div", "row col");
    const files = t.files || [];
    const cur = files[t.current_index] || null;
    const name = t.state === "Done" ? (t.share_label || t.share_id) : (cur ? cur.local : (t.share_label || t.share_id));
    const top = el("div", "row");
    top.style.border = "0"; top.style.padding = "0"; top.style.background = "transparent";
    top.appendChild(el("div", "grow name", `${t.direction === "download" ? "\u2193" : "\u2191"} ${name}`));
    top.appendChild(el("span", "badge " + stateClass(t.state), t.state));
    row.appendChild(top);

    const pct = t.total ? Math.min(100, (t.done / t.total) * 100) : (t.state === "Done" ? 100 : 0);
    const bar = el("div", "bar");
    const fill = el("div", "fill" + (t.state === "Waiting for peer" || t.state === "Failed" ? " dim" : ""));
    fill.style.width = pct.toFixed(1) + "%";
    bar.appendChild(fill); row.appendChild(bar);

    const n = t.files_total || files.length;
    const live = t.state === "Transferring" || t.state === "Verifying";
    const meta = el("div", "meta");
    meta.textContent = `${pct.toFixed(0)}%` +
      (live ? ` \u00b7 ${fmtSpeed(t.speed_mbps)} \u00b7 ETA ${fmtETA(t.eta_seconds)}` : "") +
      ` \u00b7 ${fmtBytes(t.done)} / ${fmtBytes(t.total)}` +
      ` \u00b7 ${t.files_done} of ${n} file${n === 1 ? "" : "s"}` +
      (t.state === "Done" && t.finished_at ? ` \u00b7 finished ${fmtWhen(t.finished_at)}` : "");
    row.appendChild(meta);
    if (t.note && t.state !== "Done") row.appendChild(el("div", "meta", t.note));
    if (t.error) row.appendChild(el("div", "msg err", t.error));
    if (t.peer_name) row.appendChild(el("div", "meta", (t.direction === "download" ? "from " : "to ") + t.peer_name));

    const acts = el("div", "actions");
    if (t.state === "Paused" || t.state === "Failed" || t.state === "Waiting for peer") {
      acts.appendChild(actionBtn("Resume", `/api/transfers/${t.id}/resume`));
    } else if (t.state !== "Done") {
      acts.appendChild(actionBtn("Pause", `/api/transfers/${t.id}/pause`));
    }
    const cancel = el("button", "btn ghost", t.state === "Done" ? "Remove" : "Cancel");
    cancel.addEventListener("click", async () => {
      let del = false;
      if (t.state !== "Done") {
        if (!confirm("Cancel this transfer?")) return;
        del = confirm("Also delete the partial files?");
      }
      await fetch(`/api/transfers/${t.id}/cancel${del ? "?delete=1" : ""}`, { method: "POST" });
    });
    acts.appendChild(cancel);
    row.appendChild(acts);
    stack.appendChild(row);
  }
  box.appendChild(stack);
}
function stateClass(state) {
  switch (state) { case "Done": return "ok"; case "Failed": case "Waiting for peer": return "warn"; default: return ""; }
}
function actionBtn(label, url) { const b = el("button", "btn ghost", label); b.addEventListener("click", () => fetch(url, { method: "POST" })); return b; }
function btn(label, fn, cls) { const b = el("button", "btn" + (cls ? " " + cls : ""), label); b.addEventListener("click", (e) => { e.stopPropagation(); fn(); }); return b; }

// ---------- settings ----------
async function openSettings() {
  const box = $("settings-body");
  clear(box); box.appendChild(el("div", "empty", "Loading\u2026"));
  const r = await fetch("/api/settings");
  if (!r.ok) { clear(box); box.appendChild(el("div", "empty", (await r.text()).trim())); return; }
  const s = await r.json();
  applySettings(s);
  renderSettings(s);
}
function renderSettings(s) {
  const box = $("settings-body");
  clear(box);
  const name = textInput(s.device_name, "set-name");
  const label = textInput(s.device_id_label, "set-label"); label.placeholder = s.generated_label || "";
  const theme = selectEl([["dark", "Dark"], ["light", "Light"]], s.theme === "light" ? "light" : "dark", "set-theme");
  const speed = selectEl([["mbs", "MB/s"], ["mbps", "Mbps"]], s.speed_unit || "mbs", "set-speed");
  const sound = checkInput(s.sound_on_complete, "set-sound");
  const notif = checkInput(s.notifications, "set-notif");
  const startup = checkInput(s.start_on_login, "set-startup");
  const dl = textInput(s.default_download_folder, "set-dl");
  const inbox = textInput(s.inbox_folder, "set-inbox"); inbox.placeholder = "default: ~/LANyard";
  const inboxWrap = withBrowse(inbox, "Choose the Inbox folder");
  inboxWrap.appendChild(btn("Open folder", () => fetch("/api/inbox/open", { method: "POST" }), "ghost"));
  const tray = checkInput(s.minimize_to_tray, "set-tray");
  const bw = numberInput(s.bandwidth_limit_mbps || 0, "set-bw");
  const port = numberInput(s.peer_port || 47800, "set-port");

  box.appendChild(settingsField("Device name", name));
  box.appendChild(settingsField("Device ID (label)", label));
  box.appendChild(el("p", "muted", "The Device ID is a label; identity stays bound to the certificate fingerprint (" + (s.fingerprint || "").slice(0, 16) + "\u2026). Changing it does not affect pairings."));
  box.appendChild(settingsField("Theme", theme));
  box.appendChild(settingsField("Speed unit", speed));
  box.appendChild(settingsField("Sound when a transfer finishes", sound));
  box.appendChild(settingsField("Show desktop notifications for pairing requests and finished transfers", notif));
  box.appendChild(settingsField("Start LANyard when I sign in", startup));
  if (s.tray_supported) box.appendChild(settingsField("Minimize to system tray (closing or minimizing hides the window; use the tray icon to reopen or quit)", tray));
  box.appendChild(settingsField("Default download folder", withBrowse(dl, "Choose the default download folder")));
  box.appendChild(settingsField("Inbox folder (pushes)", inboxWrap));
  box.appendChild(settingsField("Bandwidth limit (MB/s, 0 = unlimited)", bw));
  box.appendChild(settingsField("Peer port (restart to apply)", port));

  // Updates. The whole block is hidden until a release channel is configured
  // (the compiled default has none), so no update control is ever offered that
  // could make the app contact the internet by itself.
  if (s.update_url && s.update_url.trim()) {
    const updURL = textInput(s.update_url, "set-update-url");
    updURL.style.minWidth = "360px";
    const auto = checkInput(s.auto_update, "set-auto-update");
    const updResult = el("div", "msg", ""); updResult.hidden = true; updResult.id = "set-upd-result";
    box.appendChild(el("div", "section-title", "Updates"));
    box.appendChild(settingsField("Running version", el("div", "muted", s.version || "")));
    box.appendChild(settingsField("Update manifest URL (https)", updURL));
    box.appendChild(settingsField("Download new versions automatically", auto));
    const updActions = el("div", "actions");
    updActions.appendChild(btn("Check for updates", checkForUpdates, "ghost"));
    updActions.appendChild(btn("Download update", downloadUpdate, "ghost"));
    box.appendChild(updActions);
    box.appendChild(updResult);
  }

  const msg = el("div", "msg err", ""); msg.hidden = true; msg.id = "set-msg"; box.appendChild(msg);
  const acts = el("div", "actions");
  acts.appendChild(btn("Save", saveSettings));
  acts.appendChild(btn("Troubleshoot", () => openDiagnostics(), "ghost"));
  acts.appendChild(btn("Cancel all shares", cancelAllShares, "ghost"));
  box.appendChild(acts);

  if (s.paired && s.paired.length) {
    box.appendChild(el("div", "section-title", "Paired devices"));
    for (const e of s.paired) {
      const p = e.permissions || {};
      const row = el("div", "row");
      const m = el("div", "grow");
      m.appendChild(el("div", "name", e.name || e.device_id || "device"));
      const bits = [p.browse ? "can browse" : "no browse", p.push ? "can push" : "no push"];
      if (p.ask_over) bits.push(`asks over ${Math.round(p.ask_over / 1048576)} MB`);
      if (p.push_max_bytes) bits.push(`max ${Math.round(p.push_max_bytes / 1048576)} MB`);
      m.appendChild(el("div", "meta", bits.join(" \u00b7 ")));
      row.appendChild(m);
      const acts2 = el("div", "actions");
      const browse = checkRow("Let them browse and pull my shares", !!p.browse);
      const push = checkRow("Let them push files to my Inbox", !!p.push);
      const ask = numberRow("Ask for files larger than (MB, 0 = never)", Math.round((p.ask_over || 0) / 1048576));
      const max = numberRow("Maximum push size (MB, 0 = no limit)", Math.round((p.push_max_bytes || 0) / 1048576));
      const editor = el("div", "row col"); editor.hidden = true;
      editor.appendChild(browse); editor.appendChild(push); editor.appendChild(ask); editor.appendChild(max);
      editor.appendChild(btn("Save permissions", async () => {
        const b = {
          browse: browse.querySelector("input").checked,
          push: push.querySelector("input").checked,
          ask_over: (parseInt(ask.querySelector("input").value || "0", 10) || 0) * 1048576,
          push_max_bytes: (parseInt(max.querySelector("input").value || "0", 10) || 0) * 1048576,
        };
        const rr = await fetch(`/api/trust/${encodeURIComponent(e.cert_fingerprint)}/permissions`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(b) });
        if (!rr.ok) { toast((await rr.text()).trim(), "err"); return; }
        openSettings();
      }));
      acts2.appendChild(btn("Edit", () => { editor.hidden = !editor.hidden; }, "ghost"));
      acts2.appendChild(btn("Unpair", () => unpair(e.cert_fingerprint, e), "ghost"));
      row.appendChild(acts2);
      box.appendChild(row);
      box.appendChild(editor);
    }
  }
}
function withBrowse(input, title) {
  const w = el("div", "browse-wrap");
  w.appendChild(input);
  w.appendChild(btn("Browse\u2026", async () => { const d = await pickFolder(title, input.value); if (d) input.value = d; }, "ghost"));
  return w;
}
function settingsField(label, input) { const row = el("div", "set-row"); row.appendChild(el("label", "set-label", label)); row.appendChild(input); return row; }
function selectEl(options, value, id) {
  const sel = document.createElement("select"); sel.id = id;
  for (const [v, t] of options) { const o = document.createElement("option"); o.value = v; o.textContent = t; if (v === value) o.selected = true; sel.appendChild(o); }
  return sel;
}
function textInput(value, id) { const i = el("input"); i.value = value || ""; i.id = id; return i; }
function numberInput(value, id) { const i = el("input"); i.type = "number"; i.min = "0"; i.value = value; i.id = id; return i; }
function checkInput(checked, id) { const i = el("input"); i.type = "checkbox"; i.checked = !!checked; i.id = id; return i; }
function checkRow(label, checked) { const w = el("label", "toggle"); w.appendChild(checkInput(checked)); w.appendChild(el("span", null, label)); return w; }
function numberRow(label, value) { const w = el("label", "toggle"); w.appendChild(el("span", null, label)); const i = el("input"); i.type = "number"; i.min = "0"; i.value = value; w.appendChild(i); return w; }

async function checkForUpdates() {
  const res = $("set-upd-result");
  if (!res) return;
  res.hidden = false; res.className = "msg"; res.textContent = "Checking\u2026";
  const r = await fetch("/api/update/check", { method: "POST" });
  if (!r.ok) { res.className = "msg err"; res.textContent = (await r.text()).trim(); return; }
  const j = await r.json();
  if (!j.configured) { res.className = "msg"; res.textContent = "Automatic updates are not enabled yet (no release channel is set)."; return; }
  if (j.available) { res.className = "msg"; res.textContent = "Update available: " + j.current + " \u2192 " + j.latest + (j.notes ? " \u2014 " + j.notes : ""); }
  else { res.className = "msg"; res.textContent = "Up to date (" + j.current + ")."; }
}
async function downloadUpdate() {
  const res = $("set-upd-result");
  if (!res) return;
  res.hidden = false; res.className = "msg"; res.textContent = "Downloading and verifying\u2026";
  const r = await fetch("/api/update/download", { method: "POST" });
  if (!r.ok) { res.className = "msg err"; res.textContent = (await r.text()).trim(); return; }
  const j = await r.json();
  res.className = "msg";
  res.textContent = j.staged ? ("Downloaded and verified " + j.version + ". It will be applied on the next start.") : "Already up to date.";
}

async function saveSettings() {
  const msg = $("set-msg");
  const body = {
    device_name: $("set-name").value.trim(),
    device_id_label: $("set-label").value.trim(),
    theme: $("set-theme").value,
    speed_unit: $("set-speed").value,
    sound_on_complete: $("set-sound").checked,
    notifications: $("set-notif").checked,
    start_on_login: $("set-startup").checked,
    ...($("set-tray") ? { minimize_to_tray: $("set-tray").checked } : {}),
    default_download_folder: $("set-dl").value.trim(),
    inbox_folder: $("set-inbox").value.trim(),
    bandwidth_limit_mbps: parseInt($("set-bw").value || "0", 10) || 0,
    peer_port: parseInt($("set-port").value || "47800", 10) || 47800,
    ...($("set-update-url") ? { update_url: $("set-update-url").value.trim() } : {}),
    ...($("set-auto-update") ? { auto_update: $("set-auto-update").checked } : {}),
  };
  const opts = { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) };
  let r = await fetch("/api/settings", opts);
  if (r.status === 409) { const j = await r.json(); if (!confirm(j.warning)) return; body.confirm_device_id = true; r = await fetch("/api/settings", opts); }
  if (!r.ok) { msg.hidden = false; msg.className = "msg err"; msg.textContent = (await r.text()).trim(); return; }
  const s = await r.json();
  applySettings(s); renderSettings(s); loadSelf();
  msg.hidden = false; msg.className = "msg"; msg.textContent = "Saved.";
}
async function cancelAllShares() {
  if (!confirm("Cancel ALL shares and stop every in-progress transfer?")) return;
  const r = await fetch("/api/cancel-all", { method: "POST" });
  if (!r.ok) { toast((await r.text()).trim(), "err"); return; }
  const j = await r.json();
  toast(`Stopped ${j.shares_stopped} share(s), cancelled ${j.transfers_cancelled} transfer(s).`, "ok");
}

// ---------- pairing / sessions ----------
let pairView = null, pairPerms = { browse: true, push: false }, pairKeep = false, qrTimer = null;
const sessionMemo = {};        // id -> last status seen (drives transition toasts)
const autoOpened = new Set();  // incoming ids we auto-surfaced once
const dismissed = new Set();   // ids the user closed/rejected (do not auto-reopen)

function whoOf(s) { return s.peer_name || prettyId(s.peer_fp) || s.peer_device || "device"; }

function startPair(deviceId, name, mode) {
  pairPerms = { browse: true, push: false }; pairKeep = false;
  if (mode === "pair") { pairView = { setup: true, mode, peer_fp: deviceId, peer_name: name }; $("pair").hidden = false; renderPair(); return; }
  sendPairRequest(deviceId, name, mode);
}
function openQRPanel() { pairView = { qr: true }; $("pair").hidden = false; renderPair(); }
async function sendPairRequest(deviceId, name, mode) {
  const r = await fetch("/api/sessions/request", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ device: deviceId, mode, permissions: pairPerms, keep_connected: pairKeep }) });
  if (!r.ok) { pairView = { setup: true, mode, peer_fp: deviceId, peer_name: name, error: (await r.text()).trim() }; $("pair").hidden = false; renderPair(); return; }
  pairView = await r.json();
  $("pair").hidden = false; renderPair();
  toast((mode === "pair" ? "Pairing" : "Connect") + " request sent to " + (name || "the device") + ".", "info");
  pollSessions();
}
function openSession(id) {
  const s = sessions.find((x) => x.id === id);
  if (!s) return;
  pairView = s; pairKeep = !!s.keep_connected;
  dismissed.delete(id); autoOpened.add(id);
  $("pair").hidden = false; renderPair();
}
function closePair() { pairView = null; $("pair").hidden = true; }
function sasText(sas) { return sas && sas.length === 6 ? `${sas.slice(0, 3)} ${sas.slice(3)}` : (sas || ""); }

function renderPair() {
  const body = $("pair-body"); clear(body);
  if (!pairView) return;
  const v = pairView;
  if (v.qr) { $("pair-title").textContent = "Pair with a QR code"; renderQRPanel(body); return; }
  const who = whoOf(v);
  $("pair-title").textContent = (v.mode === "pair" ? "Pair with " : "Connect to ") + who;
  if (v.setup) {
    body.appendChild(el("p", "muted", "Choose what " + who + " may do on this device."));
    body.appendChild(permToggle("browse", "Let them browse and pull my shares", pairPerms.browse));
    body.appendChild(permToggle("push", "Let them push files to my Inbox", pairPerms.push));
    if (v.error) body.appendChild(el("div", "msg err", v.error));
    body.appendChild(el("div", "actions", "")).appendChild(btn("Send request", () => sendPairRequest(v.peer_fp, who, v.mode)));
    return;
  }
  if (v.via_qr) {
    const box = el("div", "sasbox");
    box.appendChild(el("div", "muted", "Paired via QR code \u2014 the fingerprint was pinned when the code was scanned, so there is no code to compare."));
    if (v.peer_fp) box.appendChild(el("div", "muted", "Device fingerprint: " + v.peer_fp.slice(0, 16) + "\u2026"));
    body.appendChild(box);
  } else {
    const sasBox = el("div", "sasbox");
    sasBox.appendChild(el("div", "muted", "Both devices must show the same code:"));
    sasBox.appendChild(el("div", "sas", sasText(v.sas)));
    body.appendChild(sasBox);
  }
  const status = el("p", "muted", ""); body.appendChild(status);
  const actions = el("div", "actions");
  const action = v.mode === "pair" ? "pair" : "connect";

  if (v.incoming && v.status === "pending") {
    status.textContent = v.via_qr
      ? who + " wants to pair via QR code. Their fingerprint was pinned when they scanned your code."
      : who + " wants to " + action + ". Confirm the code matches before accepting.";
    if (v.mode === "pair") { body.appendChild(permToggle("browse", "Let them browse and pull my shares", pairPerms.browse)); body.appendChild(permToggle("push", "Let them push files to my Inbox", pairPerms.push)); }
    actions.appendChild(btn("Accept", () => acceptSession(v.id)));
    actions.appendChild(btn("Reject", () => sessionAction(v.id, "reject"), "ghost"));
  } else if (v.incoming && v.status === "accepted") {
    status.textContent = v.via_qr ? "Accepted. Waiting for " + who + " to finish\u2026" : "Accepted. Waiting for " + who + " to confirm the code\u2026";
    actions.appendChild(btn("Cancel", () => sessionAction(v.id, "close"), "ghost"));
  } else if (!v.incoming && v.status === "pending") {
    status.textContent = "Waiting for " + who + " to accept\u2026";
    actions.appendChild(btn("Cancel", () => sessionAction(v.id, "close"), "ghost"));
  } else if (!v.incoming && v.status === "accepted" && v.via_qr) {
    status.textContent = who + " accepted. Finishing the pairing\u2026";
    actions.appendChild(btn("Cancel", () => sessionAction(v.id, "close"), "ghost"));
  } else if (!v.incoming && v.status === "accepted") {
    status.textContent = who + " accepted. Check the code, then confirm.";
    actions.appendChild(btn("The codes match \u2014 " + action, () => confirmSession(v.id)));
    actions.appendChild(btn("Cancel", () => sessionAction(v.id, "close"), "ghost"));
  } else if (v.status === "active") {
    status.textContent = v.mode === "pair" ? "Paired. The device is in View Paired Machines." : "Connected.";
    if (v.incoming && v.mode === "connect") body.appendChild(offersUI(v));
    actions.appendChild(btn("Close session", () => sessionAction(v.id, "close"), "ghost"));
  } else {
    const reason = v.status === "rejected" ? "declined" : v.status === "expired" ? "not answered in time" : "ended";
    status.textContent = "The request was " + reason + (v.error ? ": " + v.error : "") + ".";
    actions.appendChild(btn("Close", closePair, "ghost"));
  }
  body.appendChild(actions);
}
async function renderQRPanel(body) {
  body.appendChild(el("p", "muted", "Show this code to the other device, or paste their pairing link below. The code pins this device's fingerprint."));
  const holder = el("div", "qrbox"); holder.id = "qr-holder";
  holder.appendChild(el("div", "muted", "Loading\u2026"));
  body.appendChild(holder);

  const row = el("div", "form-row");
  const inp = el("input"); inp.id = "pair-link-in"; inp.placeholder = "Paste a pairing link (lanyard://pair?\u2026)"; inp.style.flex = "1"; inp.style.minWidth = "260px";
  const b = el("button", "btn", "Pair");
  b.addEventListener("click", () => pairWithLink(inp.value));
  inp.addEventListener("keydown", (e) => { if (e.key === "Enter") pairWithLink(inp.value); });
  row.appendChild(inp); row.appendChild(b);
  body.appendChild(row);

  // The code is a one-time invite that lives for 2 minutes. Show a live
  // countdown and swap in a fresh code the moment the current one expires or
  // has been used, so a dead code is never left on screen.
  let current = null;   // nonce currently shown
  let expiresAt = 0;    // ms since epoch
  let info = null;      // countdown line
  let loading = false;

  if (qrTimer) { clearInterval(qrTimer); qrTimer = null; }

  const draw = (p) => {
    current = p.nonce;
    expiresAt = Date.parse(p.expires_at) || (Date.now() + 120000);
    clear(holder);
    const box = el("div", "qrbox");
    box.appendChild(renderQR(p.uri));
    const link = el("div", "muted", p.uri); link.style.wordBreak = "break-all"; link.style.fontSize = "11px";
    box.appendChild(link);
    info = el("div", "muted");
    box.appendChild(info);
    holder.appendChild(box);
  };

  const load = async () => {
    if (loading) return;
    loading = true;
    try {
      const q = current ? "?nonce=" + encodeURIComponent(current) : "";
      const r = await fetch("/api/pair/payload" + q);
      if (!pairView || !pairView.qr) return;
      if (!r.ok) { clear(holder); holder.appendChild(el("div", "msg err", (await r.text()).trim())); return; }
      const p = await r.json();
      if (p.nonce !== current) draw(p); // redraw only when the invite actually changed
    } catch (e) {
      clear(holder); holder.appendChild(el("div", "msg err", String(e)));
    } finally {
      loading = false;
    }
  };

  const tick = () => {
    if (!pairView || !pairView.qr) { clearInterval(qrTimer); qrTimer = null; return; }
    const left = expiresAt - Date.now();
    if (info) {
      if (left <= 0) {
        info.textContent = "Refreshing code\u2026";
      } else {
        const s = Math.floor(left / 1000);
        info.textContent = "Expires in " + Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0") + " \u00b7 works once";
      }
    }
    load(); // also notices a used invite and mints a fresh code
  };

  await load();
  qrTimer = setInterval(tick, 1000);
}

// renderQR draws a pairing link as a QR code onto a canvas, entirely offline.
function renderQR(text) {
  const canvas = document.createElement("canvas");
  if (typeof qrcodegen === "undefined") return el("div", "msg err", "QR generator unavailable.");
  const qr = qrcodegen.QrCode.encodeText(text, qrcodegen.QrCode.Ecc.MEDIUM);
  const border = 4, scale = 6, dim = (qr.size + border * 2) * scale;
  canvas.width = dim; canvas.height = dim; canvas.className = "qrcanvas";
  const ctx = canvas.getContext("2d");
  ctx.fillStyle = "#fff"; ctx.fillRect(0, 0, dim, dim);
  ctx.fillStyle = "#000";
  for (let y = 0; y < qr.size; y++) for (let x = 0; x < qr.size; x++) {
    if (qr.getModule(x, y)) ctx.fillRect((x + border) * scale, (y + border) * scale, scale, scale);
  }
  return canvas;
}

// parsePairLink is a light client-side read; the server validates the fields.
function parsePairLink(uri) {
  const m = /^lanyard:\/\/pair\?(.*)$/i.exec((uri || "").trim());
  if (!m) return null;
  const q = new URLSearchParams(m[1]);
  return {
    fp: q.get("fp") || "",
    name: q.get("name") || "",
    addrs: (q.get("addr") || "").split(",").map((s) => s.trim()).filter(Boolean),
    n: q.get("n") || "",
  };
}

// pairWithLink pairs with a device from a pasted (or scanned) link: add the
// peer pinned to the link's fingerprint, then start a pairing session carrying
// the one-time invite nonce.
async function pairWithLink(uri) {
  const p = parsePairLink(uri);
  if (!p || !p.fp || !p.n || !p.addrs.length) { toast("That is not a valid pairing link.", "err"); return; }
  pairPerms = { browse: true, push: false }; pairKeep = false;
  let ok = false, lastErr = "";
  for (const a of p.addrs) {
    const r = await fetch("/api/peers/add", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ address: a, fingerprint: p.fp }) });
    if (r.ok) { ok = true; break; }
    lastErr = (await r.text()).trim();
  }
  if (!ok) { toast("Could not reach the device: " + lastErr, "err"); return; }
  const name = p.name || p.fp.slice(0, 8);
  const r = await fetch("/api/sessions/request", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ device: p.fp, mode: "pair", permissions: pairPerms, keep_connected: false, invite: p.n }) });
  if (!r.ok) { toast((await r.text()).trim(), "err"); return; }
  pairView = await r.json();
  renderPair();
  toast("Pairing request sent to " + name + ".", "info");
  pollSessions();
}

function permToggle(key, label, checked) {
  const w = el("label", "toggle");
  const cb = document.createElement("input"); cb.type = "checkbox"; cb.checked = !!checked;
  cb.addEventListener("change", () => { pairPerms[key] = cb.checked; });
  w.appendChild(cb); w.appendChild(el("span", null, label)); return w;
}
function offersUI(v) {
  const box = el("div", "offers");
  box.appendChild(el("div", "muted", "Offer shares into this session:"));
  const chosen = new Set(v.offers || []);
  if (!sharesList.length) box.appendChild(el("div", "muted", "You have no shares yet."));
  for (const s of sharesList) {
    const w = el("label", "toggle");
    const cb = document.createElement("input"); cb.type = "checkbox"; cb.checked = chosen.has(s.share_id);
    cb.addEventListener("change", () => { cb.checked ? chosen.add(s.share_id) : chosen.delete(s.share_id); });
    w.appendChild(cb); w.appendChild(el("span", null, s.label || s.path));
    box.appendChild(w);
  }
  box.appendChild(btn("Offer selected", () => offerShares(v.id, [...chosen])));
  return box;
}
async function offerShares(id, ids) {
  const r = await fetch(`/api/sessions/${id}/offers`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ share_ids: ids }) });
  if (!r.ok) { toast((await r.text()).trim(), "err"); return; }
  pairView = await r.json(); renderPair();
  toast("Offered " + ids.length + " share(s) to the session.", "ok");
}
async function acceptSession(id) {
  const r = await fetch(`/api/sessions/${id}/accept`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ permissions: pairPerms, keep_connected: pairKeep }) });
  if (!r.ok) { toast((await r.text()).trim(), "err"); return; }
  pairView = await r.json(); renderPair();
  toast("Accepted. Waiting for the other device to confirm the code.", "info");
  pollSessions();
}
async function confirmSession(id) {
  const r = await fetch(`/api/sessions/${id}/confirm`, { method: "POST" });
  if (!r.ok) { toast((await r.text()).trim(), "err"); pollSessions(); return; }
  pairView = await r.json(); renderPair();
  pollSessions();
}
async function sessionAction(id, action) {
  await fetch(`/api/sessions/${id}/${action}`, { method: "POST" });
  if (action === "close" || action === "reject") { dismissed.add(id); closePair(); }
  toast(action === "reject" ? "Request rejected." : "Request cancelled.", "info");
  pollSessions();
}

// Sessions can change on the other device (accept, confirm, reject, close).
// The responder does not push to us, so poll our outgoing handshakes, then
// refetch our local list and react to any transition with a notification.
let pollBusy = false;
async function pollSessions() {
  if (pollBusy || document.hidden) return;
  pollBusy = true;
  try {
    const outs = sessions.filter((s) => !s.incoming && (s.status === "pending" || s.status === "accepted"));
    for (const s of outs) { try { await fetch(`/api/sessions/${s.id}/refresh`, { method: "POST" }); } catch (e) { /* offline */ } }
    const list = await (await fetch("/api/sessions")).json();
    handleSessions(list);
  } catch (e) { /* transient */ } finally { pollBusy = false; }
}
setInterval(pollSessions, 1500);

function onSessionTransition(s, before) {
  const who = whoOf(s);
  if (!s.incoming && s.status === "accepted" && s.via_qr) {
    confirmSession(s.id); // QR pairing: the fingerprint is already pinned, so no code to compare
    return;
  }
  if (!s.incoming && s.status === "accepted" && before === "pending") {
    toast(who + " accepted \u2014 confirm the code to finish.", "ok");
  }
  if (s.status === "active" && before !== "active") {
    toast(s.mode === "pair" ? "Paired with " + who + "." : "Connected to " + who + ".", "ok");
    if (pairView && pairView.id === s.id) closePair();
  }
  if (!s.incoming && (s.status === "rejected" || s.status === "closed" || s.status === "expired") && before !== s.status) {
    const t = s.status === "rejected" ? "declined the request" : s.status === "expired" ? "did not respond" : "ended the session";
    toast(who + " " + t + ".", s.status === "rejected" ? "err" : "info");
    if (pairView && pairView.id === s.id) closePair();
  }
}
function handleSessions(list) {
  sessions = list;
  for (const s of list) {
    const before = sessionMemo[s.id];
    if (before !== s.status) { onSessionTransition(s, before); sessionMemo[s.id] = s.status; }
  }
  for (const id of Object.keys(sessionMemo)) if (!list.some((s) => s.id === id)) delete sessionMemo[id];
  if (pairView && !pairView.setup && !pairView.qr) { // setup and QR panels have no session yet
    const fresh = list.find((s) => s.id === pairView.id);
    if (fresh) { pairView = fresh; renderPair(); } else { closePair(); }
  }
  S.actionable = list.filter((s) => (s.incoming && s.status === "pending") || (!s.incoming && s.status === "accepted")).length;
  document.title = (S.actionable ? `(${S.actionable}) ` : "") + "LANyard File Transfer";
  renderNav();
  if (S.view === "paired") renderPairedPage();
  if (S.view === "devices") renderExSide();
  // A new incoming request is a small notification (bottom right); clicking it
  // opens the accept screen. It never takes over the window by itself.
  const live = new Set();
  for (const s of list) {
    if (!(s.incoming && s.status === "pending")) continue;
    const key = "sess:" + s.id; live.add(key);
    if (dismissed.has(s.id)) continue;
    stickyToast(key, whoOf(s) + " wants to " + (s.mode === "pair" ? "pair" : "connect"), "Click to review and accept or reject", () => openSession(s.id));
  }
  pruneSticky("sess:", live);
}

// ---------- misc actions ----------
async function addPeer(addr) {
  if (!addr) return;
  const r = await fetch("/api/peers/add", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ address: addr }) });
  if (r.ok) toast("Added.", "ok"); else toast((await r.text()).trim(), "err");
}
async function unpair(fp, entry) {
  const name = (entry && entry.name) || prettyId(fp);
  if (!confirm(`Unpair ${name}? Active connections from this device will be rejected immediately.`)) return;
  await fetch(`/api/trust/${encodeURIComponent(fp)}/unpair`, { method: "POST" });
}
async function pushTo(deviceId, name, folder) {
  const paths = await pickPaths(folder ? "folder" : "files", `Choose ${folder ? "a folder" : "files"} to send to ${name || "the device"}`, "");
  if (!paths.length) return;
  const r = await fetch("/api/push", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ device: deviceId, paths }) });
  if (!r.ok) { toast((await r.text()).trim(), "err"); return; }
  toast("Sending to " + (name || "the device") + "\u2026", "ok"); showView("transfers");
}
async function mountDevice(deviceId, name) {
  let drive = "";
  const self = await (await fetch("/api/self")).json();
  if (self.os === "windows") {
    const ans = prompt(`Drive letter for ${name || "this device"} (e.g. Z:).\nLeave empty to only get the address.`, "Z:");
    if (ans === null) return; drive = ans;
  }
  const r = await fetch("/api/mounts", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ device: deviceId, drive: drive.trim() }) });
  if (!r.ok) { toast((await r.text()).trim(), "err"); return; }
  const m = await r.json();
  toast(m.mounted ? `${m.name} mounted read-only as ${m.drive}.` : `${m.name} served at ${m.url}`, "ok");
}
function playBeep() {
  try {
    const Ctx = window.AudioContext || window.webkitAudioContext; if (!Ctx) return;
    const ctx = new Ctx(); const o = ctx.createOscillator(), g = ctx.createGain();
    o.frequency.value = 880; g.gain.value = 0.08; o.connect(g); g.connect(ctx.destination);
    o.start(); o.stop(ctx.currentTime + 0.18); setTimeout(() => ctx.close(), 500);
  } catch (e) { }
}

// ---------- approvals ----------
let approvalsOpen = false;
function renderApprovals(list) {
  approvalsList = list;
  const box = $("approvals"), modal = $("approvals-modal");
  document.title = (list.length ? `(${list.length}) ` : "") + "LANyard File Transfer";
  const live = new Set();
  for (const a of list) {
    const key = "appr:" + a.id; live.add(key);
    const files = a.count === 1 ? "1 file" : `${a.count} files`;
    stickyToast(key, `${a.peer_name || "A device"} wants to send you ${files}`, `${fmtBytes(a.total)} \u00b7 click to review`, () => { approvalsOpen = true; renderApprovals(approvalsList); });
  }
  pruneSticky("appr:", live);
  if (!list.length) approvalsOpen = false;
  box.hidden = !approvalsOpen;
  clear(modal);
  if (!approvalsOpen) return;
  const head = el("div", "modal-head"); head.appendChild(el("h2", null, "Incoming files"));
  head.appendChild(btn("Decide later", () => { approvalsOpen = false; renderApprovals(approvalsList); }, "ghost"));
  modal.appendChild(head);
  for (const a of list) {
    const card = el("div", "row col");
    const files = a.count === 1 ? "1 file" : `${a.count} files`;
    card.appendChild(el("div", "name", `${a.peer_name || "A device"} wants to send you ${files} (${fmtBytes(a.total)})`));
    card.appendChild(el("div", "meta", a.reason === "connect" ? "Connected device for one transfer. Files go to your Inbox." : "Larger than your automatic limit. Files go to your Inbox."));
    const lst = el("div", "stack");
    (a.files || []).slice(0, 10).forEach((f) => lst.appendChild(el("div", "meta", f.path + "  \u00b7  " + fmtBytes(f.size))));
    card.appendChild(lst);
    const acts = el("div", "actions");
    acts.appendChild(btn("Accept", () => decideApproval(a.id, "accept")));
    acts.appendChild(btn("Reject", () => decideApproval(a.id, "reject"), "ghost"));
    card.appendChild(acts);
    modal.appendChild(card);
  }
}
async function decideApproval(id, action) { const r = await fetch(`/api/approvals/${encodeURIComponent(id)}/${action}`, { method: "POST" }); if (!r.ok) toast((await r.text()).trim(), "err"); }

// ---------- events ----------
function renderPeers(list) {
  peers = list;
  const now = Date.now();
  for (const p of list) if (p.verified && p.device_id) seenAt[p.device_id] = now;
  try { localStorage.setItem("lanyard.seen", JSON.stringify(seenAt)); } catch (e) { }
  checkPresence();
  if (S.view === "devices") renderExplorer();
  if (S.view === "paired") renderPairedPage();
}
function checkPresence() {
  for (const e of trustList) {
    const fp = e.cert_fingerprint || e.device_id;
    const on = isOnline(fp), before = wasOnline.get(fp);
    if (peersLoaded && before !== undefined && before !== on) toast((e.name || "A paired device") + (on ? " is online." : " went offline."), on ? "ok" : "info");
    wasOnline.set(fp, on);
  }
  peersLoaded = true;
}
function renderTrust(list) { trustList = list; checkPresence(); if (S.view === "paired") renderPairedPage(); if (S.view === "devices") renderExSide(); }
function renderShares(list) { sharesList = list; if (S.view === "shares") renderSharesPage(); }
function renderTransfers(list) {
  const doneIds = new Set(list.filter((t) => t.state === "Done").map((t) => t.id));
  if (settings.sound_on_complete && window.__lastDone && [...doneIds].some((id) => !window.__lastDone.has(id))) playBeep();
  window.__lastDone = doneIds;
  transfersList = list;
  renderNav();
  if (S.view === "transfers") renderTransfersPage();
}
function renderSessions(list) { handleSessions(list); }
function renderIncoming(list) {
  const had = incomingList.length;
  incomingList = list;
  if (S.view === "transfers") renderTransfersPage();
  if (list.length !== had) renderNav();
}
function renderSnippets(list) {
  const had = snippetsList.length;
  snippetsList = list;
  if (S.view === "transfers") renderTransfersPage();
  if (list.length !== had) renderNav();
}
function snippetPeer(sp) { return sp.peer_name || prettyId(sp.peer_fp) || "a device"; }
function connectEvents() {
  const es = new EventSource("/api/events");
  es.addEventListener("peers", (ev) => renderPeers(JSON.parse(ev.data)));
  es.addEventListener("shares", (ev) => renderShares(JSON.parse(ev.data)));
  es.addEventListener("transfers", (ev) => renderTransfers(JSON.parse(ev.data)));
  es.addEventListener("trust", (ev) => renderTrust(JSON.parse(ev.data)));
  es.addEventListener("approvals", (ev) => renderApprovals(JSON.parse(ev.data)));
  es.addEventListener("sessions", (ev) => renderSessions(JSON.parse(ev.data)));
  es.addEventListener("incoming", (ev) => renderIncoming(JSON.parse(ev.data)));
  es.addEventListener("snippets", (ev) => renderSnippets(JSON.parse(ev.data)));
  es.addEventListener("notice", (ev) => showNotice(JSON.parse(ev.data)));
  es.onerror = () => { };
}

// ---------- diagnostics ----------
let diagReport = "";
async function openDiagnostics(device) {
  $("diag").hidden = false;
  const body = $("diag-body");
  clear(body); body.appendChild(el("div", "empty", "Running checks\u2026"));
  const q = device ? "?device=" + encodeURIComponent(device) : "";
  let j;
  try {
    const r = await fetch("/api/diagnostics" + q);
    if (!r.ok) { clear(body); body.appendChild(el("div", "msg err", (await r.text()).trim())); return; }
    j = await r.json();
  } catch (e) { clear(body); body.appendChild(el("div", "msg err", String(e))); return; }
  diagReport = j.report || "";
  renderDiag(j.checks || [], device || "");
}
function renderDiag(checks, device) {
  const body = $("diag-body"); clear(body);
  const targets = shareTargets();
  if (targets.length) {
    const row = el("div", "form-row");
    row.appendChild(el("span", "muted", "Check a device:"));
    const sel = document.createElement("select"); sel.id = "diag-device";
    const none = document.createElement("option"); none.value = ""; none.textContent = "Any (skip)"; sel.appendChild(none);
    for (const t of targets) {
      const o = document.createElement("option"); o.value = t.id; o.textContent = t.name;
      if (t.id === device) o.selected = true;
      sel.appendChild(o);
    }
    sel.addEventListener("change", () => openDiagnostics(sel.value));
    row.appendChild(sel);
    body.appendChild(row);
  }
  const icons = { ok: "\u2713", warn: "!", fail: "\u2715", skip: "\u2013" };
  const stack = el("div", "stack");
  for (const c of checks) {
    const row = el("div", "row col");
    const top = el("div", "row"); top.style.border = "0"; top.style.padding = "0"; top.style.background = "transparent";
    top.appendChild(el("span", "badge " + diagClass(c.status), icons[c.status] || "?"));
    top.appendChild(el("div", "grow name", c.title || c.id));
    row.appendChild(top);
    if (c.detail) row.appendChild(el("div", "meta", c.detail));
    if (c.fix) row.appendChild(el("div", "muted", "Fix: " + c.fix));
    stack.appendChild(row);
  }
  body.appendChild(stack);
}
function diagClass(status) { return status === "ok" ? "ok" : status === "warn" ? "warn" : status === "fail" ? "err" : ""; }
function closeDiag() { $("diag").hidden = true; }

// ---------- settings helpers ----------
function applyTheme(theme) {
  settings.theme = theme || "dark";
  try { localStorage.setItem("lanyard.theme", settings.theme); } catch (e) { }
  document.documentElement.dataset.theme = settings.theme === "light" ? "light" : "dark";
}
function applySettings(s) { settings = Object.assign(settings, s); applyTheme(settings.theme); }

// ---------- self ----------
async function loadSelf() {
  const r = await fetch("/api/self");
  if (!r.ok) throw new Error("not authorized \u2014 open LANyard from its launch link");
  const s = await r.json();
  $("self-name").textContent = s.name || "This device";
  $("self-name").title = `This device \u00b7 ${s.os} \u00b7 port ${s.peer_port} \u00b7 ID ${s.device_id_pretty}`;
}
async function loadRoots() {
  try { const r = await fetch("/api/fs/roots"); if (r.ok) { S.ex.roots = await r.json(); renderExSide(); } } catch (e) { }
}

// ---------- window chrome ----------
function initWindowChrome() {
  const hasBridge = typeof window.lanWin === "function";
  $("win-btns").hidden = !hasBridge;
  if (!hasBridge) return;
  for (const b of document.querySelectorAll("[data-win]")) {
    b.addEventListener("click", () => window.lanWin(b.dataset.win));
  }
  const drag = $("tb-drag");
  drag.addEventListener("mousedown", (e) => { if (e.button === 0 && typeof window.lanDrag === "function") window.lanDrag(); });
  drag.addEventListener("dblclick", () => window.lanWin("max"));
}

// ---------- init ----------
$("user-menu").addEventListener("click", () => showView("settings"));
$("device-search").addEventListener("input", (e) => { S.search = e.target.value.trim().toLowerCase(); if (S.view === "devices" && place().kind === "home") renderBody(); });
$("refresh-btn").addEventListener("click", () => fetch("/api/peers").then((r) => r.json()).then(renderPeers).catch(() => { }));
$("pair-qr-btn").addEventListener("click", openQRPanel);
$("refresh-paired").addEventListener("click", () => { renderPairedPage(); });
$("nav-back").addEventListener("click", () => { if (S.ex.hi > 0) { S.ex.hi--; renderExplorer(); } });
$("nav-fwd").addEventListener("click", () => { if (S.ex.hi < S.ex.hist.length - 1) { S.ex.hi++; renderExplorer(); } });
$("nav-up").addEventListener("click", () => {
  const p = place();
  if (p.kind === "folder") { const parts = p.path.split(/[\\/]/).filter(Boolean); if (parts.length <= 1) navigate({ kind: "home" }); else navigate({ kind: "folder", path: parts.slice(0, -1).join("\\") }); }
  else if (p.kind === "remote") { if (p.path) navigate({ ...p, path: p.path.split("/").slice(0, -1).join("/") }); else navigate({ kind: "device", device: p.device, name: p.name }); }
  else if (p.kind === "device") navigate({ kind: "home" });
});
$("view-mode").addEventListener("click", () => { S.mode = S.mode === "grid" ? "list" : "grid"; renderExplorer(); });
$("share-add").addEventListener("click", () => submitShare(false));
$("share-path").addEventListener("keydown", (e) => { if (e.key === "Enter") submitShare(false); });
$("stop-all").addEventListener("click", async () => { if (!confirm("Stop every share now?")) return; await fetch("/api/shares/stop-all", { method: "POST" }); });
$("clear-finished").addEventListener("click", () => fetch("/api/transfers/clear-history", { method: "POST" }));
$("pair-close").addEventListener("click", closePair);
$("pair").addEventListener("click", (e) => { if (e.target === $("pair")) closePair(); });
$("diag-close").addEventListener("click", closeDiag);
$("diag").addEventListener("click", (e) => { if (e.target === $("diag")) closeDiag(); });
$("diag-copy").addEventListener("click", () => copyText(diagReport || ""));

async function submitShare(confirmFlag) {
  const lifetime = $("share-lifetime").value;
  const body = { path: $("share-path").value.trim(), label: $("share-label").value.trim(), lifetime, seconds: parseInt(lifetime, 10) || 0, confirm: !!confirmFlag };
  const msg = $("share-msg");
  if (!body.path) { msg.hidden = false; msg.textContent = "Enter a path to share."; return; }
  msg.hidden = true;
  const r = await fetch("/api/shares", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  if (r.ok) { $("share-path").value = ""; $("share-label").value = ""; toast("Shared.", "ok"); return; }
  if (r.status === 409) { const j = await r.json(); if (confirm(j.warning)) submitShare(true); return; }
  msg.hidden = false; msg.textContent = (await r.text()).trim();
}

setInterval(() => { if (S.view === "shares" && !document.hidden) renderSharesPage(); }, 1000);

applyTheme("dark");
renderNav();
initWindowChrome();
showView("devices");
loadRoots();
loadSelf().then(connectEvents).catch((e) => { $("self-name").textContent = e.message; });
