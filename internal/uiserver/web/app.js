"use strict";
// All remote-supplied strings (device names, share labels, file names) are
// attacker-controlled, so they are only ever inserted with textContent.

const $ = (id) => document.getElementById(id);

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
}

function fmtBytes(n) {
  if (!n && n !== 0) return "";
  const u = ["B", "KB", "MB", "GB", "TB"];
  let i = 0, v = n;
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(i ? 2 : 0)} ${u[i]}`;
}

let settings = { speed_unit: "mbs", sound_on_complete: false, theme: "system", default_download_folder: "" };

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

let peers = [];
let trustList = [];
let sessions = [];
let remote = { device: "", name: "", share: "", shareLabel: "", path: "", selected: new Set() };

// --- peers ---
function pairedEntry(deviceId) {
  return trustList.find((e) => e.cert_fingerprint === deviceId) || null;
}

function activeSession(deviceId) {
  return sessions.find((s) =>
    s.peer_fp === deviceId && (s.status === "active" || s.status === "accepted")) || null;
}

function renderPeers(list) {
  peers = list;
  const box = $("peers");
  box.replaceChildren();
  $("empty").hidden = list.length > 0;
  for (const p of list) {
    const card = el("div", "card");
    card.appendChild(el("div", "name", p.name || "(unnamed)"));
    card.appendChild(el("div", "meta", `${p.os || "unknown OS"} · ${(p.addrs || [])[0] || ""}:${p.port}`));
    const paired = pairedEntry(p.device_id);
    const session = activeSession(p.device_id);
    const state = paired ? "Paired" : session ? "Connected" : "Unpaired";
    const badges = el("div", "badges");
    badges.appendChild(el("span", "badge " + (p.verified ? "ok" : "warn"), p.verified ? "Verified" : "Verifying…"));
    badges.appendChild(el("span", "badge " + (paired ? "ok" : session ? "ok" : ""), state));
    badges.appendChild(el("span", "badge", p.source));
    card.appendChild(badges);
    if (p.verified) {
      card.appendChild(el("div", "fp", (p.device_label ? p.device_label + " · " : "") + "ID " + prettyId(p.device_id)));
      const actions = el("div", "actions");
      if (paired) {
        actions.appendChild(btn("Browse", () => openRemote(p.device_id, p.name)));
        actions.appendChild(btn("Push", () => pushTo(p.device_id, p.name)));
        actions.appendChild(btn("Mount as drive", () => mountDevice(p.device_id, p.name), "ghost"));
        actions.appendChild(btn("Unpair", () => unpair(p.device_id, paired), "ghost"));
      } else if (session) {
        actions.appendChild(btn("Browse", () => openRemote(p.device_id, p.name)));
        actions.appendChild(btn("Push", () => pushTo(p.device_id, p.name)));
        actions.appendChild(btn("Disconnect", () => sessionAction(session.id, "close"), "ghost"));
      } else {
        actions.appendChild(btn("Connect", () => startPair(p.device_id, p.name, "connect")));
        actions.appendChild(btn("Pair", () => startPair(p.device_id, p.name, "pair")));
      }
      card.appendChild(actions);
    }
    box.appendChild(card);
  }
  renderSessions(sessions);
}

function btn(label, fn, cls) {
  const b = el("button", cls || null, label);
  b.addEventListener("click", fn);
  return b;
}

function prettyId(id) {
  if (!id) return "";
  return id.slice(0, 16).toUpperCase().match(/.{4}/g).join(" ");
}

// --- paired devices ---
function renderTrust(list) {
  trustList = list;
  const box = $("trust");
  box.replaceChildren();
  $("trust-empty").hidden = list.length > 0;
  for (const e of list) {
    const row = el("div", "row");
    const main = el("div", "grow");
    main.appendChild(el("div", "name", e.name || e.device_id || "device"));
    const perms = e.permissions || {};
    const bits = [];
    bits.push(perms.browse ? "can browse" : "no browse");
    bits.push(perms.push ? "can push" : "no push");
    main.appendChild(el("div", "meta", bits.join(" · ")));
    main.appendChild(el("div", "fp", "ID " + prettyId(e.cert_fingerprint)));
    row.appendChild(main);
    row.appendChild(btn("Unpair", () => unpair(e.cert_fingerprint, e), "ghost"));
    box.appendChild(row);
  }
  renderPeers(peers);
}

async function unpair(fp, entry) {
  const name = (entry && entry.name) || prettyId(fp);
  if (!confirm(`Unpair ${name}? Active connections from this device will be rejected immediately.`)) return;
  await fetch(`/api/trust/${encodeURIComponent(fp)}/unpair`, { method: "POST" });
}

async function pushTo(deviceId, name) {
  const path = prompt(`Path of a file or folder to push to ${name || "the device"}'s Inbox:`);
  if (!path) return;
  const r = await fetch("/api/push", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ device: deviceId, paths: [path] }),
  });
  if (!r.ok) alert((await r.text()).trim());
}

// --- sessions & requests ---
function renderSessions(list) {
  sessions = list;
  const box = $("requests");
  const active = list.filter((s) => s.status === "pending" || s.status === "accepted" || s.status === "active");
  $("requests-section").hidden = active.length === 0;
  box.replaceChildren();
  for (const s of active) {
    const row = el("div", "row");
    const main = el("div", "grow");
    const who = s.peer_name || prettyId(s.peer_fp) || s.peer_device || "device";
    main.appendChild(el("div", "name", `${s.mode === "pair" ? "Pairing" : "Connect"} · ${who}`));
    const statusText = s.status === "pending"
      ? (s.incoming ? "wants to connect — review the code" : "waiting for the other device to accept")
      : s.status === "accepted" ? (s.incoming ? "accepted — waiting for them to confirm the code" : "they accepted — confirm the code")
        : "connected";
    main.appendChild(el("div", "meta", statusText));
    row.appendChild(main);
    const actions = el("div", "actions");
    actions.appendChild(btn("Review", () => openSession(s.id)));
    if (s.incoming && s.status === "pending") actions.appendChild(btn("Accept", () => acceptSession(s.id, s.mode), "ghost"));
    if (!s.incoming && (s.status === "accepted" || s.status === "active")) actions.appendChild(btn("Confirm code", () => confirmSession(s.id), "ghost"));
    row.appendChild(actions);
    box.appendChild(row);
  }
}

// --- pairing / connect modal ---
let pairView = null;          // current session view or a setup form
let pairPerms = { browse: true, push: false };
let pairKeep = false;
let pairTimer = null;

function startPair(deviceId, name, mode) {
  pairPerms = { browse: true, push: false };
  pairKeep = false;
  if (mode === "pair") {
    pairView = { setup: true, mode, peer_fp: deviceId, peer_name: name };
    $("pair").hidden = false;
    renderPair();
    return;
  }
  sendPairRequest(deviceId, name, mode);
}

async function sendPairRequest(deviceId, name, mode) {
  const body = {
    device: deviceId, mode,
    permissions: pairPerms, keep_connected: pairKeep,
  };
  const r = await fetch("/api/sessions/request", {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  });
  if (!r.ok) { pairView = { setup: true, mode, peer_fp: deviceId, peer_name: name, error: (await r.text()).trim() }; renderPair(); return; }
  pairView = await r.json();
  $("pair").hidden = false;
  startPairTimer();
  renderPair();
}

function openSession(id) {
  pairView = sessions.find((s) => s.id === id) || null;
  if (!pairView) return;
  pairKeep = !!pairView.keep_connected;
  $("pair").hidden = false;
  startPairTimer();
  renderPair();
}

function startPairTimer() {
  if (pairTimer) clearInterval(pairTimer);
  pairTimer = setInterval(async () => {
    if (!pairView || pairView.setup) return;
    if (!pairView.incoming && pairView.status !== "active") {
      try {
        const r = await fetch(`/api/sessions/${pairView.id}/refresh`, { method: "POST" });
        if (r.ok) { pairView = await r.json(); renderPair(); return; }
      } catch (e) { /* fall through to the local list */ }
    }
    const fresh = sessions.find((s) => s.id === pairView.id);
    if (fresh) { pairView = fresh; renderPair(); }
  }, 1500);
}

function closePair() {
  if (pairTimer) { clearInterval(pairTimer); pairTimer = null; }
  pairView = null;
  $("pair").hidden = true;
}

function sasText(sas) {
  return sas && sas.length === 6 ? `${sas.slice(0, 3)} ${sas.slice(3)}` : (sas || "");
}

function renderPair() {
  const body = $("pair-body");
  body.replaceChildren();
  if (!pairView) return;
  const v = pairView;
  const who = v.peer_name || prettyId(v.peer_fp) || "device";
  $("pair-title").textContent = (v.mode === "pair" ? "Pair with " : "Connect to ") + who;

  if (v.setup) {
    body.appendChild(el("p", "muted", "Choose what " + who + " may do on this device. You can change it later by unpairing."));
    body.appendChild(permToggle("browse", "Let them browse and pull my shares", pairPerms.browse));
    body.appendChild(permToggle("push", "Let them push files to my Inbox", pairPerms.push));
    if (v.error) body.appendChild(el("div", "msg err", v.error));
    const start = btn("Start pairing", () => sendPairRequest(v.peer_fp, who, v.mode));
    body.appendChild(el("div", "actions", "")).appendChild(start);
    return;
  }

  const sasBox = el("div", "sasbox");
  sasBox.appendChild(el("div", "muted", "Does the other device show this code?"));
  sasBox.appendChild(el("div", "sas", sasText(v.sas)));
  body.appendChild(sasBox);

  const status = el("p", "muted", "");
  body.appendChild(status);

  const actions = el("div", "actions");

  if (v.incoming && v.status === "pending") {
    status.textContent = "A device wants to " + (v.mode === "pair" ? "pair" : "connect") + ". Confirm the code matches before accepting.";
    if (v.mode === "pair") {
      body.appendChild(permToggle("browse", "Let them browse and pull my shares", pairPerms.browse));
      body.appendChild(permToggle("push", "Let them push files to my Inbox", pairPerms.push));
    }
    actions.appendChild(btn("Accept", () => acceptSession(v.id, v.mode)));
    actions.appendChild(btn("Reject", () => sessionAction(v.id, "reject"), "ghost"));
  } else if (!v.incoming && (v.status === "pending" || v.status === "accepted")) {
    status.textContent = v.status === "pending"
      ? "Waiting for the other device to accept…"
      : "They accepted. Check the code, then confirm.";
    actions.appendChild(btn("The codes match — connect", () => confirmSession(v.id)));
    actions.appendChild(btn("Cancel", () => sessionAction(v.id, "close"), "ghost"));
  } else if (v.status === "active") {
    status.textContent = "Connected. " + (v.incoming ? "You can offer shares for them to download." : "You can browse what they offered.");
    if (v.incoming && v.mode === "connect") body.appendChild(offersUI(v));
    actions.appendChild(btn("Close session", () => sessionAction(v.id, "close"), "ghost"));
  } else {
    status.textContent = "Session " + v.status + (v.error ? ": " + v.error : "") + ".";
    actions.appendChild(btn("Close", closePair, "ghost"));
  }
  body.appendChild(actions);
}

function permToggle(key, label, checked) {
  const wrap = el("label", "toggle");
  const cb = document.createElement("input");
  cb.type = "checkbox";
  cb.checked = !!checked;
  cb.addEventListener("change", () => { pairPerms[key] = cb.checked; });
  wrap.appendChild(cb);
  wrap.appendChild(el("span", null, label));
  return wrap;
}

async function acceptSession(id, mode) {
  const r = await fetch(`/api/sessions/${id}/accept`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ permissions: pairPerms, keep_connected: pairKeep }),
  });
  if (!r.ok) { alert((await r.text()).trim()); return; }
  pairView = await r.json();
  renderPair();
}

async function confirmSession(id) {
  const r = await fetch(`/api/sessions/${id}/confirm`, { method: "POST" });
  if (!r.ok) { alert((await r.text()).trim()); return; }
  pairView = await r.json();
  renderPair();
}

async function sessionAction(id, action) {
  await fetch(`/api/sessions/${id}/${action}`, { method: "POST" });
  if (action === "close" || action === "reject") closePair();
}

function offersUI(v) {
  const box = el("div", "offers");
  box.appendChild(el("div", "muted", "Offer shares into this session:"));
  const chosen = new Set(v.offers || []);
  const list = window.__shares || [];
  if (!list.length) box.appendChild(el("div", "muted", "You have no shares yet."));
  for (const s of list) {
    const wrap = el("label", "toggle");
    const cb = document.createElement("input");
    cb.type = "checkbox";
    cb.checked = chosen.has(s.share_id);
    cb.addEventListener("change", () => { cb.checked ? chosen.add(s.share_id) : chosen.delete(s.share_id); });
    wrap.appendChild(cb);
    wrap.appendChild(el("span", null, s.label || s.path));
    box.appendChild(wrap);
  }
  box.appendChild(btn("Offer selected", () => offerShares(v.id, [...chosen])));
  return box;
}

async function offerShares(id, ids) {
  const r = await fetch(`/api/sessions/${id}/offers`, {
    method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ share_ids: ids }),
  });
  if (!r.ok) { alert((await r.text()).trim()); return; }
  pairView = await r.json();
  renderPair();
}

// --- local shares ---
const END_TEXT = {
  expired: "Expired",
  completed: "Downloaded (one-time)",
  stopped: "Stopped",
};

function shareLifetimeText(s) {
  const lt = s.lifetime || {};
  switch (lt.type) {
    case "timed": {
      const c = fmtCountdown(lt.expires_at);
      return c === "expired" ? "Expiring…" : "Ends in " + c.replace(" left", "");
    }
    case "one_time": return "One-time, ends after one completed download";
    case "persistent": return "Always";
    default: return "Until stopped";
  }
}

function renderShares(list) {
  window.__shares = list;
  const box = $("shares");
  box.replaceChildren();
  $("shares-empty").hidden = list.length > 0;
  for (const s of list) {
    const finishing = s.state === "finishing";
    const row = el("div", "row" + (finishing ? " finishing" : ""));
    const main = el("div", "grow");
    main.appendChild(el("div", "name", s.label || s.path));
    const bits = [s.kind || ""];
    if (finishing) {
      bits.push(END_TEXT[s.end_reason] || "Ended");
      const n = s.active_transfers || 0;
      bits.push(`finishing ${n} active transfer${n === 1 ? "" : "s"}`);
      const c = fmtCountdown(s.drain_until);
      if (c !== "expired") bits.push("force-stops in " + c.replace(" left", ""));
    } else {
      bits.push(shareLifetimeText(s));
      if (s.active_transfers > 0) bits.push(`${s.active_transfers} transferring`);
    }
    main.appendChild(el("div", "meta", bits.filter(Boolean).join(" · ")));
    row.appendChild(main);
    if (finishing) row.appendChild(el("span", "badge warn", "Finishing"));
    const stop = el("button", finishing ? "danger" : "ghost", finishing ? "Stop now" : "Stop");
    stop.addEventListener("click", () => {
      if (finishing && (s.active_transfers || 0) > 0 &&
          !confirm("Stop now? The active transfers will be cut off immediately.")) return;
      shareAction(`/api/shares/${encodeURIComponent(s.share_id)}/stop`);
    });
    row.appendChild(stop);
    box.appendChild(row);
  }
}

// --- transfers ---
const openJobs = new Set(); // job IDs whose file list is expanded (survives re-render)
const MAX_FILE_ROWS = 200;

function fmtWhen(iso) {
  if (!iso || iso.startsWith("0001")) return "";
  return new Date(iso).toLocaleString();
}

function renderTransfers(list) {
  const doneIds = new Set(list.filter((t) => t.state === "Done").map((t) => t.id));
  if (settings.sound_on_complete && window.__lastDone && [...doneIds].some((id) => !window.__lastDone.has(id))) playBeep();
  window.__lastDone = doneIds;
  const box = $("transfers");
  box.replaceChildren();
  $("transfers-empty").hidden = list.length > 0;
  $("clear-finished").hidden = !list.some((t) => t.state === "Done");
  for (const t of list) {
    const row = el("div", "row col");
    const top = el("div", "row");
    const files = t.files || [];
    const cur = files[t.current_index] || null;
    const name = t.state === "Done" ? (t.share_label || t.share_id) : (cur ? cur.local : (t.share_label || t.share_id));
    top.appendChild(el("div", "grow name", `${t.direction === "download" ? "↓" : "↑"} ${name}`));
    top.appendChild(el("span", "badge " + stateClass(t.state), t.state));
    row.appendChild(top);

    const pct = t.total ? Math.min(100, (t.done / t.total) * 100) : (t.state === "Done" ? 100 : 0);
    const bar = el("div", "bar");
    const fill = el("div", "fill" + (t.state === "Waiting for peer" || t.state === "Failed" ? " dim" : ""));
    fill.style.width = pct.toFixed(1) + "%";
    bar.appendChild(fill);
    row.appendChild(bar);

    const n = t.files_total || files.length;
    const meta = el("div", "meta");
    const live = t.state === "Transferring" || t.state === "Verifying";
    meta.textContent = `${pct.toFixed(0)}%` +
      (live ? ` · ${fmtSpeed(t.speed_mbps)} · ETA ${fmtETA(t.eta_seconds)}` : "") +
      ` · ${fmtBytes(t.done)} / ${fmtBytes(t.total)}` +
      ` · ${t.files_done} of ${n} file${n === 1 ? "" : "s"}` +
      (t.state === "Done" && t.finished_at ? ` · finished ${fmtWhen(t.finished_at)}` : "");
    row.appendChild(meta);

    if (t.note && t.state !== "Done") row.appendChild(el("div", "note", t.note));
    if (t.error) row.appendChild(el("div", "msg err", t.error));
    if (t.peer_name) row.appendChild(el("div", "meta", (t.direction === "download" ? "from " : "to ") + t.peer_name + " · TLS 1.3 mutual auth · SHA-256 verified"));

    if (n > 1 || (n === 1 && files[0].state !== "done")) {
      const det = document.createElement("details");
      det.open = openJobs.has(t.id);
      det.addEventListener("toggle", () => { if (det.open) openJobs.add(t.id); else openJobs.delete(t.id); });
      det.appendChild(el("summary", null, `Files (${t.files_done}/${n})`));
      const lst = el("div", "filelist");
      files.slice(0, MAX_FILE_ROWS).forEach((f) => {
        const fr = el("div", "frow");
        fr.appendChild(el("span", "fname", f.local));
        const fp = f.size ? Math.min(100, (f.done / f.size) * 100) : (f.state === "done" ? 100 : 0);
        fr.appendChild(el("span", "fstate " + f.state, f.state === "done" ? "done" : f.state === "pending" ? "queued" : `${f.state} ${fp.toFixed(0)}%`));
        lst.appendChild(fr);
      });
      if (n > Math.min(files.length, MAX_FILE_ROWS)) lst.appendChild(el("div", "meta", `…and ${n - Math.min(files.length, MAX_FILE_ROWS)} more not shown`));
      det.appendChild(lst);
      row.appendChild(det);
    }

    const actions = el("div", "actions");
    if (t.state === "Paused" || t.state === "Failed" || t.state === "Waiting for peer") {
      actions.appendChild(actionBtn("Resume", `/api/transfers/${t.id}/resume`));
    } else if (t.state !== "Done") {
      actions.appendChild(actionBtn("Pause", `/api/transfers/${t.id}/pause`));
    }
    const cancel = el("button", "ghost", t.state === "Done" ? "Remove" : "Cancel");
    cancel.addEventListener("click", async () => {
      let del = false;
      if (t.state !== "Done") {
        if (!confirm("Cancel this transfer?")) return;
        del = confirm("Also delete the partial files?\n\nOK = delete them. Cancel = keep them so a later download can pick up where this one stopped.");
      }
      await fetch(`/api/transfers/${t.id}/cancel${del ? "?delete=1" : ""}`, { method: "POST" });
    });
    actions.appendChild(cancel);
    row.appendChild(actions);
    box.appendChild(row);
  }
}

function stateClass(state) {
  switch (state) {
    case "Done": return "ok";
    case "Failed": case "Waiting for peer": return "warn";
    default: return "";
  }
}

function actionBtn(label, url) {
  const b = el("button", null, label);
  b.addEventListener("click", () => fetch(url, { method: "POST" }));
  return b;
}

async function shareAction(url) {
  await fetch(url, { method: "POST" });
}

// --- remote browser ---
function openRemote(deviceId, name) {
  remote = { device: deviceId, name: name || "device", share: "", shareLabel: "", path: "", selected: new Set() };
  $("remote-title").textContent = "Browse " + (name || "device");
  $("remote").hidden = false;
  $("remote-tree-wrap").hidden = true;
  loadRemoteShares();
}

function closeRemote() {
  $("remote").hidden = true;
}

async function loadRemoteShares() {
  const box = $("remote-shares");
  box.replaceChildren();
  box.appendChild(el("div", "muted", "Loading shares…"));
  const r = await fetch(`/api/remote/shares?device=${encodeURIComponent(remote.device)}`);
  if (!r.ok) { box.replaceChildren(el("div", "msg err", (await r.text()).trim())); return; }
  const list = await r.json();
  box.replaceChildren();
  if (!list.length) { box.appendChild(el("div", "muted", "This device is not sharing anything.")); return; }
  for (const s of list) {
    const row = el("div", "row");
    const main = el("div", "grow");
    main.appendChild(el("div", "name", s.label));
    const life = s.lifetime === "one_time" ? " · one-time" :
      s.lifetime === "timed" && s.expires_at ? " · " + fmtCountdown(s.expires_at) : "";
    main.appendChild(el("div", "meta", `${s.kind}${s.size ? " · " + fmtBytes(s.size) : ""}${life}`));
    row.appendChild(main);
    const open = el("button", null, s.kind === "folder" ? "Open" : "Select");
    open.addEventListener("click", () => {
      remote.share = s.share_id; remote.shareLabel = s.label;
      remote.path = ""; remote.selected = new Set();
      if (s.kind === "folder") loadTree();
      else { $("remote-tree-wrap").hidden = false; renderTree([]); }
    });
    row.appendChild(open);
    box.appendChild(row);
  }
}

async function loadTree() {
  $("remote-tree-wrap").hidden = false;
  const tree = $("remote-tree");
  tree.replaceChildren(el("div", "muted", "Loading…"));
  const q = `device=${encodeURIComponent(remote.device)}&share=${encodeURIComponent(remote.share)}&path=${encodeURIComponent(remote.path)}`;
  const r = await fetch(`/api/remote/tree?${q}`);
  if (!r.ok) { tree.replaceChildren(el("div", "msg err", (await r.text()).trim())); return; }
  renderTree(await r.json());
}

function renderTree(entries) {
  const tree = $("remote-tree");
  tree.replaceChildren();
  const crumbs = $("remote-crumbs");
  crumbs.replaceChildren();
  const parts = remote.path ? remote.path.split("/") : [];
  const root = el("a", "crumb", remote.shareLabel || "share");
  root.href = "#";
  root.addEventListener("click", (e) => { e.preventDefault(); remote.path = ""; loadTree(); });
  crumbs.appendChild(root);
  let acc = [];
  for (const part of parts) {
    acc.push(part);
    crumbs.appendChild(el("span", "crumb-sep", " / "));
    const a = el("a", "crumb", part);
    a.href = "#";
    const target = acc.join("/");
    a.addEventListener("click", (e) => { e.preventDefault(); remote.path = target; loadTree(); });
    crumbs.appendChild(a);
  }

  const dl = el("div", "row");
  const dlFolder = el("button", null, "Download this folder");
  dlFolder.addEventListener("click", () => downloadRemote([remote.path]));
  dl.appendChild(dlFolder);
  const dlSel = el("button", null, "Download selected");
  dlSel.addEventListener("click", () => {
    const paths = [...remote.selected];
    if (!paths.length) { alert("Nothing selected."); return; }
    downloadRemote(paths);
  });
  dl.appendChild(dlSel);
  tree.appendChild(dl);

  for (const e of entries) {
    const row = el("div", "tree-row");
    const cb = document.createElement("input");
    cb.type = "checkbox";
    cb.checked = remote.selected.has(e.path);
    cb.addEventListener("change", () => {
      if (cb.checked) remote.selected.add(e.path); else remote.selected.delete(e.path);
    });
    row.appendChild(cb);
    if (e.is_dir) {
      const a = el("a", "tree-name dir", "📁 " + e.name);
      a.href = "#";
      a.addEventListener("click", (ev) => { ev.preventDefault(); remote.path = e.path; remote.selected = new Set(); loadTree(); });
      row.appendChild(a);
    } else {
      row.appendChild(el("span", "tree-name", "📄 " + e.name));
      row.appendChild(el("span", "meta", fmtBytes(e.size)));
    }
    tree.appendChild(row);
  }
  if (!entries.length) tree.appendChild(el("div", "muted", "Empty folder."));
}

async function downloadRemote(paths) {
  const dest = $("dest").value.trim();
  if (!dest) { alert("Enter a destination folder."); return; }
  localStorage.setItem("lanyard.dest", dest);
  const msg = $("remote-msg");
  msg.hidden = false; msg.className = "msg"; msg.textContent = "Starting…";
  const r = await fetch("/api/transfers", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      device: remote.device, share_id: remote.share, share_label: remote.shareLabel,
      peer_name: remote.name, paths, dest,
    }),
  });
  if (r.ok) { msg.hidden = true; closeRemote(); }
  else { msg.className = "msg err"; msg.textContent = (await r.text()).trim(); }
}

// --- events ---
function connectEvents() {
  const es = new EventSource("/api/events");
  es.addEventListener("peers", (ev) => renderPeers(JSON.parse(ev.data)));
  es.addEventListener("shares", (ev) => renderShares(JSON.parse(ev.data)));
  es.addEventListener("transfers", (ev) => renderTransfers(JSON.parse(ev.data)));
  es.addEventListener("trust", (ev) => renderTrust(JSON.parse(ev.data)));
  es.addEventListener("approvals", (ev) => renderApprovals(JSON.parse(ev.data)));
  es.addEventListener("mounts", (ev) => renderMounts(JSON.parse(ev.data)));
  es.addEventListener("sessions", (ev) => {
    const list = JSON.parse(ev.data);
    if (pairView && !pairView.setup) {
      const fresh = list.find((s) => s.id === pairView.id);
      if (fresh) { pairView = fresh; renderPair(); }
    }
    renderSessions(list);
  });
  es.onerror = () => { /* EventSource reconnects on its own */ };
}

// --- forms ---
$("add-form").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const msg = $("add-msg");
  const addr = $("add-addr").value.trim();
  if (!addr) return;
  msg.hidden = false; msg.className = "msg"; msg.textContent = "Connecting…";
  const r = await fetch("/api/peers/add", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ address: addr }),
  });
  if (r.ok) { msg.hidden = true; $("add-addr").value = ""; }
  else { msg.className = "msg err"; msg.textContent = (await r.text()).trim(); }
});

$("share-lifetime").addEventListener("change", (ev) => {
  const custom = ev.target.value === "custom";
  $("share-custom").hidden = !custom;
  $("share-custom-unit").hidden = !custom;
});

let pendingShare = null;
async function submitShare(confirmFlag) {
  const lifetime = $("share-lifetime").value;
  const body = {
    path: $("share-path").value.trim(),
    label: $("share-label").value.trim(),
    lifetime: lifetime === "custom" ? "timed" : lifetime,
    seconds: lifetime === "custom"
      ? parseInt($("share-custom").value || "0", 10) * parseInt($("share-custom-unit").value, 10)
      : parseInt(lifetime, 10) || 0,
    confirm: !!confirmFlag,
  };
  const msg = $("share-msg");
  msg.hidden = false; msg.className = "msg"; msg.textContent = "Sharing…";
  const r = await fetch("/api/shares", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (r.ok) {
    msg.hidden = true;
    $("share-path").value = ""; $("share-label").value = "";
    pendingShare = null;
  } else if (r.status === 409) {
    const j = await r.json();
    pendingShare = body;
    if (confirm(j.warning)) submitShare(true);
    else msg.hidden = true;
  } else {
    msg.className = "msg err"; msg.textContent = (await r.text()).trim();
  }
}

$("share-form").addEventListener("submit", (ev) => { ev.preventDefault(); submitShare(false); });
$("remote-close").addEventListener("click", closeRemote);
$("remote").addEventListener("click", (ev) => { if (ev.target === $("remote")) closeRemote(); });
$("pair-close").addEventListener("click", closePair);
$("pair").addEventListener("click", (ev) => { if (ev.target === $("pair")) closePair(); });
$("settings-open").addEventListener("click", openSettings);
$("settings-close").addEventListener("click", () => { $("settings").hidden = true; });
$("settings").addEventListener("click", (ev) => { if (ev.target === $("settings")) $("settings").hidden = true; });

// --- settings (M6, §11) ---
function applyTheme(theme) {
  settings.theme = theme || "system";
  try { localStorage.setItem("lanyard.theme", settings.theme); } catch (e) { /* ignore */ }
  if (settings.theme === "system") document.documentElement.removeAttribute("data-theme");
  else document.documentElement.dataset.theme = settings.theme;
}

function applySettings(s) {
  settings = Object.assign(settings, s);
  applyTheme(settings.theme);
}

async function loadSettings() {
  const r = await fetch("/api/settings");
  if (r.ok) applySettings(await r.json());
}

function settingsField(label, input) {
  const row = el("div", "set-row");
  row.appendChild(el("label", "set-label", label));
  row.appendChild(input);
  return row;
}

function selectEl(options, value, id) {
  const sel = document.createElement("select");
  sel.id = id;
  for (const [v, t] of options) {
    const o = document.createElement("option");
    o.value = v; o.textContent = t;
    if (v === value) o.selected = true;
    sel.appendChild(o);
  }
  return sel;
}

function textInput(value, id) {
  const i = document.createElement("input");
  i.value = value || ""; i.id = id;
  return i;
}

function renderSettings(s) {
  const box = $("settings-body");
  box.replaceChildren();
  const name = textInput(s.device_name, "set-name");
  const label = textInput(s.device_id_label, "set-label");
  label.placeholder = s.generated_label || "";
  const theme = selectEl([["system", "System"], ["light", "Light"], ["dark", "Dark"]], s.theme || "system", "set-theme");
  const speed = selectEl([["mbs", "MB/s"], ["mbps", "Mbps"]], s.speed_unit || "mbs", "set-speed");
  const sound = document.createElement("input");
  sound.type = "checkbox"; sound.checked = !!s.sound_on_complete; sound.id = "set-sound";
  const startup = document.createElement("input");
  startup.type = "checkbox"; startup.checked = !!s.start_on_login; startup.id = "set-startup";
  const dl = textInput(s.default_download_folder, "set-dl");
  const inbox = textInput(s.inbox_folder, "set-inbox");
  inbox.placeholder = "default: <data dir>/Inbox";
  const bw = document.createElement("input");
  bw.type = "number"; bw.min = "0"; bw.value = s.bandwidth_limit_mbps || 0; bw.id = "set-bw";
  const port = document.createElement("input");
  port.type = "number"; port.value = s.peer_port || 47800; port.id = "set-port";

  box.appendChild(settingsField("Device name", name));
  box.appendChild(settingsField("Device ID (label)", label));
  box.appendChild(el("p", "muted", "The Device ID is a label; identity stays bound to the certificate fingerprint (" + (s.fingerprint || "").slice(0, 16) + "…). Changing it does not affect pairings."));
  box.appendChild(settingsField("Theme", theme));
  box.appendChild(settingsField("Speed unit", speed));
  box.appendChild(settingsField("Sound when a transfer finishes", sound));
  box.appendChild(settingsField("Start LANyard when I sign in", startup));
  box.appendChild(settingsField("Default download folder", dl));
  box.appendChild(settingsField("Inbox folder (pushes)", inbox));
  box.appendChild(settingsField("Bandwidth limit (MB/s, 0 = unlimited)", bw));
  box.appendChild(settingsField("Peer port (restart to apply)", port));
  const msg = el("div", "msg", "");
  msg.hidden = true; msg.id = "set-msg";
  box.appendChild(msg);
  const actions = el("div", "actions");
  actions.appendChild(btn("Save", saveSettings));
  const cancelAll = el("button", "ghost", "Cancel all shares");
  cancelAll.addEventListener("click", cancelAllShares);
  actions.appendChild(cancelAll);
  box.appendChild(actions);

  if (s.paired && s.paired.length) {
    box.appendChild(el("h3", null, "Paired devices"));
    for (const e of s.paired) {
      const p = e.permissions || {};
      const row = el("div", "row");
      const main = el("div", "grow");
      main.appendChild(el("div", "name", e.name || e.device_id || "device"));
      main.appendChild(el("div", "meta", permissionText(p)));
      row.appendChild(main);
      const editor = el("div", "row col");
      editor.hidden = true;
      const actions = el("div", "actions");
      actions.appendChild(btn("Edit", () => { editor.hidden = !editor.hidden; }, "ghost"));
      actions.appendChild(btn("Unpair", () => unpair(e.cert_fingerprint, e), "ghost"));
      row.appendChild(actions);
      box.appendChild(row);

      const browse = checkRow("Let them browse and pull my shares", !!p.browse);
      const push = checkRow("Let them push files to my Inbox", !!p.push);
      const ask = numberRow("Ask for files larger than (MB, 0 = never)", Math.round((p.ask_over || 0) / 1048576));
      const max = numberRow("Maximum push size (MB, 0 = no limit)", Math.round((p.push_max_bytes || 0) / 1048576));
      editor.appendChild(browse);
      editor.appendChild(push);
      editor.appendChild(ask);
      editor.appendChild(max);
      editor.appendChild(btn("Save permissions", async () => {
        const body = {
          browse: browse.querySelector("input").checked,
          push: push.querySelector("input").checked,
          ask_over: (parseInt(ask.querySelector("input").value || "0", 10) || 0) * 1048576,
          push_max_bytes: (parseInt(max.querySelector("input").value || "0", 10) || 0) * 1048576,
        };
        const r = await fetch(`/api/trust/${encodeURIComponent(e.cert_fingerprint)}/permissions`, {
          method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
        });
        if (!r.ok) { alert((await r.text()).trim()); return; }
        openSettings();
      }));
      box.appendChild(editor);
    }
  }
}

function permissionText(p) {
  const bits = [p.browse ? "can browse" : "no browse", p.push ? "can push" : "no push"];
  if (p.ask_over) bits.push(`asks over ${Math.round(p.ask_over / 1048576)} MB`);
  if (p.push_max_bytes) bits.push(`max ${Math.round(p.push_max_bytes / 1048576)} MB`);
  return bits.join(" · ");
}

function checkRow(label, checked) {
  const wrap = el("label", "toggle");
  const cb = document.createElement("input");
  cb.type = "checkbox"; cb.checked = checked;
  wrap.appendChild(cb);
  wrap.appendChild(el("span", null, label));
  return wrap;
}

function numberRow(label, value) {
  const wrap = el("label", "toggle");
  wrap.appendChild(el("span", null, label));
  const inp = document.createElement("input");
  inp.type = "number"; inp.min = "0"; inp.value = value;
  wrap.appendChild(inp);
  return wrap;
}

async function saveSettings() {
  const msg = $("set-msg");
  const body = {
    device_name: $("set-name").value.trim(),
    device_id_label: $("set-label").value.trim(),
    theme: $("set-theme").value,
    speed_unit: $("set-speed").value,
    sound_on_complete: $("set-sound").checked,
    start_on_login: $("set-startup").checked,
    default_download_folder: $("set-dl").value.trim(),
    inbox_folder: $("set-inbox").value.trim(),
    bandwidth_limit_mbps: parseInt($("set-bw").value || "0", 10) || 0,
    peer_port: parseInt($("set-port").value || "47800", 10) || 47800,
  };
  const opts = { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) };
  let r = await fetch("/api/settings", opts);
  if (r.status === 409) {
    const j = await r.json();
    if (!confirm(j.warning)) return;
    body.confirm_device_id = true;
    r = await fetch("/api/settings", opts);
  }
  if (!r.ok) { msg.hidden = false; msg.className = "msg err"; msg.textContent = (await r.text()).trim(); return; }
  const s = await r.json();
  applySettings(s);
  renderSettings(s);
  msg.hidden = false; msg.className = "msg"; msg.textContent = "Saved.";
  loadSelf();
}

async function cancelAllShares() {
  if (!confirm("Cancel ALL shares and stop every in-progress transfer? This cannot be undone.")) return;
  const r = await fetch("/api/cancel-all", { method: "POST" });
  if (!r.ok) { alert((await r.text()).trim()); return; }
  const j = await r.json();
  alert(`Stopped ${j.shares_stopped} share(s) and cancelled ${j.transfers_cancelled} transfer(s).`);
}

async function openSettings() {
  $("settings").hidden = false;
  const r = await fetch("/api/settings");
  if (!r.ok) { $("settings-body").replaceChildren(el("div", "msg err", (await r.text()).trim())); return; }
  const s = await r.json();
  applySettings(s);
  renderSettings(s);
}

function playBeep() {
  try {
    const Ctx = window.AudioContext || window.webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    const o = ctx.createOscillator(), g = ctx.createGain();
    o.frequency.value = 880; g.gain.value = 0.08;
    o.connect(g); g.connect(ctx.destination);
    o.start(); o.stop(ctx.currentTime + 0.18);
    setTimeout(() => ctx.close(), 500);
  } catch (e) { /* audio is best-effort */ }
}

async function loadSelf() {
  const r = await fetch("/api/self");
  if (!r.ok) throw new Error("not authorized — open LANyard from its launch link");
  const s = await r.json();
  $("self").textContent =
    `This device: ${s.name} · ${s.os} · port ${s.peer_port} · ID ${s.device_id_pretty.slice(0, 19)}…`;
}

$("dest").value = (localStorage.getItem("lanyard.dest") || localStorage.getItem("ezshare.dest") || "");
setInterval(() => { if (!document.hidden) renderShares(window.__shares || []); }, 1000);

loadSettings().then(() => {
  if (!$("dest").value && settings.default_download_folder) $("dest").value = settings.default_download_folder;
}).catch(() => {});
loadSelf().then(connectEvents).catch((e) => { $("self").textContent = e.message; });

$("clear-finished").addEventListener("click", () => fetch("/api/transfers/clear-finished", { method: "POST" }));

// First run on Windows: open the connection help once so the firewall prompt
// is not a mystery. After that it stays collapsed.
(async function firstRunHelp() {
  try {
    const self = await (await fetch("/api/self")).json();
    $("fw-windows").hidden = self.os !== "windows";
    if (self.os === "windows" && !localStorage.getItem("lanyard.fwhelp")) {
      $("net-help").open = true;
      localStorage.setItem("lanyard.fwhelp", "1");
    }
  } catch (e) { /* the help is optional */ }
})();

// --- incoming transfers waiting for a decision ---
// Every string here comes from the other device: textContent only.
function renderApprovals(list) {
  const box = $("approvals");
  const modal = $("approvals-modal");
  box.hidden = list.length === 0;
  document.title = (list.length ? `(${list.length}) ` : "") + "LANyard File Transfer";
  modal.replaceChildren();
  for (const a of list) {
    const card = el("div", "row col");
    const files = a.count === 1 ? "1 file" : `${a.count} files`;
    card.appendChild(el("div", "name", `${a.peer_name || "A device"} wants to send you ${files} (${fmtBytes(a.total)})`));
    card.appendChild(el("div", "meta", a.reason === "connect"
      ? "You are connected to this device for one transfer. The files go to your Inbox."
      : "This is larger than the limit you set for automatic transfers. The files go to your Inbox."));
    const lst = el("div", "filelist");
    (a.files || []).slice(0, 10).forEach((f) => {
      const fr = el("div", "frow");
      fr.appendChild(el("span", "fname", f.path));
      fr.appendChild(el("span", "fstate", fmtBytes(f.size)));
      lst.appendChild(fr);
    });
    if (a.count > 10) lst.appendChild(el("div", "meta", `…and ${a.count - 10} more`));
    card.appendChild(lst);
    const actions = el("div", "actions");
    actions.appendChild(btn("Accept", () => decideApproval(a.id, "accept")));
    actions.appendChild(btn("Reject", () => decideApproval(a.id, "reject"), "ghost"));
    card.appendChild(actions);
    modal.appendChild(card);
  }
}

async function decideApproval(id, action) {
  const r = await fetch(`/api/approvals/${encodeURIComponent(id)}/${action}`, { method: "POST" });
  if (!r.ok) alert((await r.text()).trim());
}

// --- mount a paired device as a drive (read-only) ---
async function mountDevice(deviceId, name) {
  let drive = "";
  const self = await (await fetch("/api/self")).json();
  if (self.os === "windows") {
    const ans = prompt(`Drive letter for ${name || "this device"} (for example Z:).\nLeave empty to only get the address and mount it yourself.`, "Z:");
    if (ans === null) return; // cancelled
    drive = ans;
  }
  const r = await fetch("/api/mounts", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ device: deviceId, drive: drive.trim() }),
  });
  if (!r.ok) { alert((await r.text()).trim()); return; }
  const m = await r.json();
  if (m.mounted) {
    alert(`${m.name} is mounted read-only as ${m.drive}.`);
  } else {
    alert(`${m.name} is served read-only at:\n${m.url}\n\n` +
      (m.os_error ? `Automatic mounting did not work: ${m.os_error}\n\n` : "") +
      `To mount it yourself, run:\n${m.hint}`);
  }
}

function renderMounts(list) {
  $("mounts-section").hidden = list.length === 0;
  const box = $("mounts");
  box.replaceChildren();
  for (const m of list) {
    const row = el("div", "row");
    const main = el("div", "grow");
    main.appendChild(el("div", "name", m.name || "Device"));
    main.appendChild(el("div", "meta", (m.mounted ? `Drive ${m.drive} · ` : "") + "read-only · " + m.url));
    if (m.os_error) main.appendChild(el("div", "note", m.os_error));
    row.appendChild(main);
    row.appendChild(btn("Unmount", async () => {
      await fetch(`/api/mounts/${encodeURIComponent(m.id)}/remove`, { method: "POST" });
    }, "ghost"));
    box.appendChild(row);
  }
}
