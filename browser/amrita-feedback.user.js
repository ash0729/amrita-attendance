// ==UserScript==
// @name         Amrita Feedback Helper
// @namespace    com.ash.attendance
// @version      1.0
// @description  Picks the same answer for every course-feedback question and presses Save & Next. You sign in and type the OTP yourself.
// @match        https://students.amrita.edu/client/*
// @grant        none
// @noframes
// @run-at       document-idle
// ==/UserScript==

// Two runtime modes from this one file:
//  - userscript: runs on every page load of the top window and works on `document` directly.
//  - frame (bookmarklet): build-bookmarklet.py puts `window.__afhFrame=1;` in front of the code. The page
//    is then re-opened in a full-viewport same-origin iframe and the step logic runs against that iframe.
// No network requests, no GM_* APIs, no login/password fields are ever touched.
(function () {
  'use strict';
  var W = window;
  if (W.__afh) { W.__afh.toggle(); return; }
  var FRAME = !!W.__afhFrame;
  var KEY_S = 'afh-settings', KEY_R = 'afh-run', KEY_U = 'afh-ui';
  var LABELS = ['Strongly Agree', 'Agree', 'Neutral', 'Disagree', 'Strongly Disagree'];
  var LISTS = ['/client/course-feedback-list', '/client/class-feedback'];
  var OTP_TRIES_MAX = 160, OTP_POLL_MS = 1500, WATCHDOG_MS = 10000;

  // ---- storage (always in try/catch: private mode can throw) ----
  function load(store, key) {
    try { var v = JSON.parse(W[store].getItem(key)); return v && typeof v === 'object' ? v : {}; } catch (e) { return {}; }
  }
  function save(store, key, obj) { try { W[store].setItem(key, JSON.stringify(obj)); } catch (e) {} }
  var settings = (function () {
    var s = load('localStorage', KEY_S), r = parseInt(s.rating, 10);
    return { rating: r >= 1 && r <= 5 ? r : 2, auto: s.auto === true };
  })();
  function getRun() { return load('sessionStorage', KEY_R); }
  function setRun(r) { save('sessionStorage', KEY_R, r); }

  // ---- state ----
  var frame = null, frameLoaded = false, blocked = false, clicked = false, otpTries = 0;
  var stepTimer = 0, watchTimer = 0, tickTimer = 0, blockTimer = 0, statusMsg = '', gone = false, savedOverflow = '';
  var ui = load('localStorage', KEY_U);

  // ---- target page (the document the step logic works on) ----
  function target() {
    if (!FRAME) return { win: W, doc: document };
    try {
      var d = frame && frame.contentDocument;
      if (d && frame.contentWindow.location.href) return { win: frame.contentWindow, doc: d };
    } catch (e) {}
    return null;
  }
  function pageInfo(t) {
    var p = t.win.location.pathname.replace(/\/+$/, ''), q = !!t.win.location.search;
    if (/\/client\/course-feedback-list$/.test(p)) return { type: 'list', kind: 0 };
    if (/\/client\/class-feedback$/.test(p)) return { type: 'list', kind: 1 };
    if (q && /\/client\/course-feedback$/.test(p)) return { type: 'question', kind: 0 };
    if (q && /\/client\/feedback$/.test(p)) return { type: 'question', kind: 1 };
    return { type: 'other', kind: 0 };
  }
  function find(list, fn) { return [].find.call(list, fn); }
  function visible(x) {
    if (!x.getClientRects().length) return false;
    try { return x.ownerDocument.defaultView.getComputedStyle(x).visibility !== 'hidden'; } catch (e) { return true; }
  }
  function txt(e) { return ((e && (e.innerText || e.textContent)) || '').trim(); }

  // Port of the app's LIST_JS: course list rows have >= 5 cells; the TLP list has a course row followed by
  // 3-cell faculty rows (name, status, action).
  function readList(t) {
    var rows = [], course = '';
    [].forEach.call(t.doc.querySelectorAll('tr'), function (tr) {
      var c = tr.children, n = c.length, row = null, act;
      if (n >= 5) {
        var st = txt(c[3]);
        act = tr.querySelector('a[href],button');
        if (!act || !/pending|complete|submit|done/i.test(st)) return;
        row = { course: txt(c[1]), status: st, act: txt(act) };
      } else if (n === 3) {
        var t0 = txt(c[0]);
        if (c[0].rowSpan > 1 || /^\d+$/.test(t0)) { course = txt(c[2]); return; }
        act = tr.querySelector('a[href]');
        row = { course: course, status: txt(c[1]), act: act ? txt(act) : txt(c[2]) };
      }
      if (!row) return;
      var a = tr.querySelector('a[href]');
      row.href = a && a.href && a.origin === t.win.location.origin ? a.href : '';
      row.pend = /pending/i.test(row.status) || /submit/i.test(row.act);
      row.closed = /closed/i.test(row.act + ' ' + row.status);
      if (/complete|done/i.test(row.status)) row.pend = false;
      rows.push(row);
    });
    return rows;
  }

  // ---- UI (Shadow DOM so the portal's CSS cannot touch it) ----
  var CSS = ':host{all:initial}*{box-sizing:border-box;letter-spacing:normal;text-transform:none}[hidden]{display:none!important}'
    + '.card,.pill{--bg:#fff;--fg:#1c1b1f;--mut:#555b66;--bd:#c9ced6;--ac:#1a56db;--acf:#fff;--chip:#eef1f6;--off:#8a909b;'
    + 'position:fixed;z-index:2147483647;font:16px/1.35 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;color:var(--fg)}'
    + '@media(prefers-color-scheme:dark){.card,.pill{--bg:#16181d;--fg:#ecedf0;--mut:#a4a9b3;--bd:#383d47;--ac:#7aa7ff;--acf:#0b1220;--chip:#252a33;--off:#6b717c}}'
    + '.card{left:0;right:0;bottom:0;margin:0 auto;max-width:560px;background:var(--bg);border:1px solid var(--bd);border-bottom:0;'
    + 'border-radius:16px 16px 0 0;box-shadow:0 -4px 24px rgba(0,0,0,.28);padding:8px 12px calc(12px + env(safe-area-inset-bottom,0px))}'
    + '.pill{right:12px;bottom:calc(12px + env(safe-area-inset-bottom,0px));min-height:48px;padding:0 20px;border-radius:24px;'
    + 'background:var(--ac);color:var(--acf);border:0;font-weight:600;box-shadow:0 2px 12px rgba(0,0,0,.35);cursor:pointer}'
    + '.top{display:flex;align-items:center;gap:4px;min-height:48px}.top strong{flex:1}'
    + 'button{font:inherit;cursor:pointer}'
    + '.btn{min-height:48px;min-width:48px;padding:0 14px;border-radius:12px;border:1px solid var(--bd);background:transparent;color:var(--fg)}'
    + '.status{min-height:2.7em;color:var(--fg);font-size:15px;padding:2px 2px 8px;overflow-wrap:anywhere}'
    + '.chips{display:flex;gap:6px}.chip{flex:1;min-width:48px;height:48px;border-radius:12px;border:1px solid var(--bd);background:var(--chip);color:var(--fg);font-size:18px;font-weight:600}'
    + '.chip[aria-pressed=true]{background:var(--ac);color:var(--acf);border-color:var(--ac)}'
    + '.hint{min-height:1.5em;padding:6px 2px;font-size:14px;color:var(--mut)}'
    + '.sw{position:relative;display:flex;align-items:center;gap:12px;min-height:48px;cursor:pointer}'
    + '.sw input{position:absolute;opacity:0;width:48px;height:48px;left:0;margin:0;cursor:pointer}'
    + '.track{flex:none;width:44px;height:26px;border-radius:13px;background:var(--off);position:relative;transition:background .15s}'
    + '.knob{position:absolute;top:3px;left:3px;width:20px;height:20px;border-radius:50%;background:#fff;transition:transform .15s}'
    + '.sw input:checked+.track{background:var(--ac)}.sw input:checked+.track .knob{transform:translateX(18px)}'
    + '.sw input:focus-visible+.track{outline:2px solid var(--ac);outline-offset:2px}'
    + '.main{display:block;width:100%;min-height:48px;margin-top:6px;border-radius:12px;border:0;background:var(--ac);color:var(--acf);font-weight:600;font-size:17px}'
    + '.main:disabled{opacity:.5;cursor:default}.main.stop{background:#c62828;color:#fff}'
    + 'button:focus-visible{outline:2px solid var(--ac);outline-offset:2px}';
  var HTML = '<div class="card" role="region" aria-label="Amrita feedback helper">'
    + '<div class="top"><strong>Feedback helper</strong><button class="btn min" type="button">Hide</button>'
    + '<button class="btn close" type="button" hidden>Close</button></div>'
    + '<div class="status" role="status" aria-live="polite"></div>'
    + '<div class="chips" role="group" aria-label="Answer for every question"></div><div class="hint"></div>'
    + '<label class="sw"><input class="auto" type="checkbox" role="switch"><span class="track"><span class="knob"></span></span>'
    + '<span>Finish &amp; submit by itself</span></label>'
    + '<button class="main" type="button"></button></div>'
    + '<button class="pill" type="button" hidden>Feedback helper</button>';

  var host = document.createElement('div');
  host.id = 'afh-host';
  var root = host.attachShadow({ mode: 'open' });
  root.innerHTML = '<style>' + CSS + '</style>' + HTML;
  var $ = function (s) { return root.querySelector(s); };
  var card = $('.card'), pill = $('.pill'), statusEl = $('.status'), hintEl = $('.hint'), mainEl = $('.main'), autoEl = $('.auto');
  var chipEls = [];
  for (var ci = 1; ci <= 5; ci++) {
    var cb = document.createElement('button');
    cb.type = 'button'; cb.className = 'chip'; cb.textContent = ci; cb.setAttribute('data-n', ci);
    $('.chips').appendChild(cb);
    chipEls.push(cb);
  }

  function say(msg) {
    statusMsg = msg;
    var r = getRun(); r.msg = msg; setRun(r);
    render();
  }

  function render() {
    if (gone) return;
    var t = blocked ? null : target(), info = t ? pageInfo(t) : { type: 'other', kind: 0 }, run = getRun();
    var kind = info.type === 'other' ? (run.kind || 0) : info.kind;
    chipEls.forEach(function (c, i) {
      c.setAttribute('aria-pressed', String(i + 1 === settings.rating));
      c.setAttribute('aria-label', kind ? 'Choice number ' + (i + 1) : (i + 1) + ' ' + LABELS[i]);
    });
    hintEl.textContent = kind ? 'Choice number ' + settings.rating + ' (1 = first option)'
                              : settings.rating + ' ' + LABELS[settings.rating - 1];
    autoEl.checked = settings.auto;
    $('.close').hidden = !FRAME;
    var label = '', text = statusMsg, dis = false;
    mainEl.classList.remove('stop');
    if (blocked) {
      text = 'This browser blocked frame mode. Use Firefox with Violentmonkey instead.'; mainEl.hidden = true;
    } else {
      mainEl.hidden = false;
      if (info.type === 'list') {
        var n = readList(t).filter(function (r) { return r.pend && !r.closed; }).length;
        text = n + ' pending' + (statusMsg ? '. ' + statusMsg : '');
        label = n ? 'Open next pending' : 'Nothing pending'; dis = !n;
      } else if (info.type === 'question') {
        if (run.running) { label = 'Stop'; mainEl.classList.add('stop'); }
        else label = 'Fill every question with ' + settings.rating;
        if (!text) text = run.running ? 'Working…' : settings.auto ? 'Auto is on: verify the OTP and the rest runs by itself' : 'Pick a number, then fill every question';
      } else {
        label = 'Open course feedback';
        if (!text) text = frameLoaded || !FRAME ? 'Open Course Feedback or Class Feedback in the portal' : 'Loading…';
      }
    }
    statusEl.textContent = text;
    mainEl.textContent = label; mainEl.disabled = dis;
    var min = !!ui.min;
    card.hidden = min; pill.hidden = !min;
    layout();
  }

  // Some browsers (Brave on Android) report a layout viewport taller than what is on screen, so bottom:0
  // lands behind their bottom toolbar. Measure the visible part and lift the panel above the hidden strip.
  var cardH = 0;
  function layout() {
    var vv = W.visualViewport;
    var visible = vv ? vv.offsetTop + vv.height : document.documentElement.clientHeight;
    var hidden = Math.max(0, Math.round(W.innerHeight - visible));
    // With the keyboard up (typing the OTP) there is no room for the panel: step aside until it closes.
    if (card.offsetHeight) cardH = card.offsetHeight;
    var squeezed = !card.hidden && visible < cardH + 240;
    card.style.visibility = squeezed ? 'hidden' : '';
    // A table wider than the phone stretches the layout viewport sideways too: size against what is visible.
    var vw = vv ? vv.width : document.documentElement.clientWidth, vl = vv ? vv.offsetLeft : 0;
    var w = Math.min(560, Math.floor(vw));
    card.style.cssText += ';right:auto;margin:0;width:' + w + 'px;left:' + Math.round(vl + (vw - w) / 2) + 'px;bottom:' + hidden + 'px';
    pill.style.cssText += ';right:auto;left:auto';
    pill.style.left = Math.round(vl + vw - 12 - (pill.offsetWidth || 180)) + 'px';
    pill.style.bottom = 'calc(' + (hidden + 12) + 'px + env(safe-area-inset-bottom,0px))';
    if (frame) frame.style.width = Math.floor(vw) + 'px';
    // An iframe's height:auto stays at its 150px default even with top and bottom set, so size it in px.
    if (frame) frame.style.height = Math.max(0, Math.round(visible) - (card.hidden || squeezed ? 0 : cardH)) + 'px';
  }

  // ---- run control ----
  function schedule(fn, ms) { clearTimeout(stepTimer); stepTimer = setTimeout(fn, ms); }
  function arm(msg) {
    clearTimeout(watchTimer);
    watchTimer = setTimeout(function () { clicked = false; stop(msg || 'No page change within 10 seconds; stopped'); }, WATCHDOG_MS);
  }
  // soft = nothing was answered yet, so a later page load may still start an auto run
  function stop(msg, soft) {
    clearTimeout(stepTimer); clearTimeout(watchTimer);
    var r = getRun();
    setRun({ halted: !soft, kind: r.kind || 0, count: r.count || 0, msg: msg });
    say(msg);
  }
  function startRun(kind) {
    setRun({ running: true, lastQid: null, finishing: false, halted: false, kind: kind, count: 0, msg: 'Starting…' });
    otpTries = 0; clicked = false; statusMsg = 'Starting…';
    render();
    schedule(step, 0);
  }
  function waitOtp(msg) {
    if (++otpTries > OTP_TRIES_MAX) return stop('Gave up waiting for the OTP');
    say(msg);
    schedule(step, OTP_POLL_MS);
  }

  // One step of the run: the same rules as the app's autoStep().
  function step() {
    var run = getRun();
    if (!run.running || clicked || gone) return;
    var t = target();
    if (!t) return showBlocked();
    var d = t.doc, w = t.win, r = settings.rating;
    var otp = find(d.querySelectorAll('input,textarea'), function (x) {
      return x.type !== 'hidden' && x.type !== 'radio' && x.type !== 'checkbox' && visible(x)
        && /otp/i.test((x.name || '') + ' ' + (x.id || '') + ' ' + (x.placeholder || ''));
    });
    if (otp && !(otp.value || '').trim()) return waitOtp('Enter the OTP — it carries on after');
    var f = d.querySelector('button[name=finish_feedback]') || find(d.querySelectorAll('form button,form input[type=submit]'), function (x) {
      return x.type !== 'button' && /^\s*(finish|submit)\s*$/i.test(x.innerText || x.value || '');
    });
    if (f) {
      if (!settings.auto) return stop('All answered — press Finish');
      try { w.confirm = function () { return true; }; } catch (e) {}
      run.finishing = true; run.msg = 'Submitting…'; setRun(run);
      clicked = true; arm('Finish did not go through; stopped');
      say('Submitting…');
      f.click();
      return;
    }
    var rs = d.querySelectorAll('input[name=option_id]'), q = d.querySelector('input[name=question_id]');
    var b = d.querySelector('button[name=submit_ans]') || find(d.querySelectorAll('form button'), function (x) {
      return x.type !== 'button' && /save|next/i.test(x.innerText || '');
    });
    if (!rs.length || !q || !b) {
      if (otp) return waitOtp('Press Verify — it carries on after');
      var send = find(d.querySelectorAll('button,input[type=submit],a'), function (x) {
        return visible(x) && /otp/i.test(x.innerText || x.value || '');
      });
      if (send && !run.count) return stop('Tap ' + (send.innerText || send.value || 'Send OTP').trim().replace(/\s+/g, ' ') + ', then type the code from your email', true);
      return stop('Nothing to fill on this page', !run.count);
    }
    if (rs.length < r) return stop('This question has only ' + rs.length + ' options; pick a smaller number');
    var all = d.querySelectorAll('.step-item'), act = d.querySelector('.step-item.active');
    var i = act ? [].indexOf.call(all, act) + 1 : (run.count || 0) + 1, n = all.length;
    if (q.value === run.lastQid) return stop("Save didn't advance; stopped at question " + i);
    run.lastQid = q.value; run.count = (run.count || 0) + 1; run.msg = 'Saved ' + i + (n ? ' of ' + n : '');
    setRun(run);
    otpTries = 0; clicked = true; arm();
    say(run.msg);
    rs[r - 1].click();
    b.click();
  }

  // Runs after every page load of the operated document.
  function onLoad() {
    clearTimeout(watchTimer); clearTimeout(stepTimer); clearTimeout(blockTimer);
    clicked = false;
    var t = target();
    if (!t) return showBlocked();
    blocked = false;
    var info = pageInfo(t), run = getRun();
    if (info.type === 'list') {
      setRun({ kind: info.kind }); statusMsg = '';
      return render();
    }
    if (run.finishing) {
      var k = run.kind || 0;
      setRun({ kind: k, msg: 'Submitted. Back to the list…' });
      say('Submitted. Back to the list…');
      schedule(function () {
        var t2 = target();
        if (t2) t2.win.location.href = t2.win.location.origin + LISTS[k];
      }, 800);
      return;
    }
    if (info.type === 'question') {
      if (run.running) { statusMsg = run.msg || ''; render(); schedule(step, 500); return; }
      statusMsg = '';
      if (settings.auto && !run.halted) return startRun(info.kind);
      return render();
    }
    if (run.running) return stop('This page has nothing to fill; stopped');
    statusMsg = run.msg || '';
    render();
  }

  function showBlocked() {
    blocked = true; clicked = false;
    clearTimeout(stepTimer); clearTimeout(watchTimer);
    render();
  }

  // ---- wiring ----
  function openNext(t) {
    var row = find(readList(t), function (r) { return r.pend && !r.closed && r.href; });
    if (!row) return say('No pending row has a link to open');
    arm('The page did not open within 10 seconds; stopped');
    say('Opening ' + (row.course.split('\n')[0] || 'next course') + '…');
    t.win.location.href = row.href; // same tab/frame; the links say target=_blank but we never open a tab
  }
  mainEl.addEventListener('click', function () {
    var t = target();
    if (!t) return showBlocked();
    var info = pageInfo(t), run = getRun();
    if (info.type === 'list') openNext(t);
    else if (info.type === 'question') { if (run.running) stop('Stopped'); else startRun(info.kind); }
    else t.win.location.href = t.win.location.origin + LISTS[0];
  });
  chipEls.forEach(function (c) {
    c.addEventListener('click', function () {
      settings.rating = +c.getAttribute('data-n');
      save('localStorage', KEY_S, settings);
      render();
    });
  });
  autoEl.addEventListener('change', function () {
    settings.auto = autoEl.checked;
    save('localStorage', KEY_S, settings);
    var t = target(), run = getRun();
    if (settings.auto && t && pageInfo(t).type === 'question' && !run.running) startRun(pageInfo(t).kind);
    else render();
  });
  function setMin(v) { ui.min = v; save('localStorage', KEY_U, ui); render(); }
  $('.min').addEventListener('click', function () { setMin(true); });
  pill.addEventListener('click', function () { setMin(false); });
  $('.close').addEventListener('click', destroy);

  function destroy() {
    gone = true;
    clearTimeout(stepTimer); clearTimeout(watchTimer); clearTimeout(blockTimer); clearInterval(tickTimer);
    host.remove();
    if (frame) { frame.remove(); document.documentElement.style.overflow = savedOverflow; }
    try { W.sessionStorage.removeItem(KEY_R); } catch (e) {}
    delete W.__afh;
  }
  W.__afh = {
    toggle: function () { if (FRAME) destroy(); else setMin(!ui.min); }
  };

  document.documentElement.appendChild(host);
  if (typeof W.ResizeObserver === 'function') new W.ResizeObserver(layout).observe(card);
  W.addEventListener('resize', layout);
  if (W.visualViewport) W.visualViewport.addEventListener('resize', layout);
  tickTimer = setInterval(render, 1000);

  if (FRAME) {
    // The portal page is re-opened inside a same-origin iframe; the panel stays on this outer page.
    savedOverflow = document.documentElement.style.overflow;
    document.documentElement.style.overflow = 'hidden';
    frame = document.createElement('iframe');
    frame.id = 'afh-frame';
    frame.setAttribute('style', 'position:fixed;top:0;left:0;width:100%;height:100%;border:0;margin:0;'
      + 'padding:0;background:#fff;z-index:2147483646');
    frame.addEventListener('load', function () { frameLoaded = true; onLoad(); });
    frame.src = W.location.href;
    document.documentElement.appendChild(frame);
    blockTimer = setTimeout(function () { if (!frameLoaded) showBlocked(); }, 20000);
    render();
  } else {
    statusMsg = getRun().msg || '';
    onLoad();
  }
})();
