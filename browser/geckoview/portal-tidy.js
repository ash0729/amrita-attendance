// Makes students.amrita.edu comfortable on a phone and lets the user hide parts of it, like Brave's "block element".
// Runs in the app browser only. Hidden parts are kept in extension storage and applied on every portal page.
(function () {
  if (window.__amritaTidy) return;
  window.__amritaTidy = true;

  const KEY = 'hidden';
  let hidden = [];          // CSS selectors the user hid
  let undo = [];            // selectors hidden in this picking session, newest last
  let picking = false;
  let port = null;

  // ---- styles ------------------------------------------------------------------------------------

  const PHONE_CSS = `
/* Never wider than the phone. */
html, body { max-width: 100vw !important; overflow-x: hidden !important; -webkit-text-size-adjust: 100% !important; }
img { max-width: 100% !important; height: auto; }

/* The app browser has its own address bar and back button: drop the breadcrumb, footer and scroll-to-top. */
#kt_content > nav, nav:has(> ol.breadcrumb), #kt_footer, #kt_scrolltop, #kt_demo_panel { display: none !important; }

/* Smaller header bars. */
#kt_header_mobile { height: 48px !important; min-height: 48px !important; }
body.header-mobile-fixed { padding-top: 48px !important; }
/* The empty top band only matters when the user menu (Logout, Bus Pass) is opened from the header icon. */
body:not(.topbar-mobile-on) #kt_header { display: none !important; }
#kt_header .header-top { height: auto !important; min-height: 0 !important; padding: 2px 0 !important; }
body.header-fixed #kt_wrapper { padding-top: 0 !important; }

/* Page title row: compact. */
#kt_subheader { padding: 4px 0 !important; margin: 0 !important; min-height: 0 !important; }
#kt_subheader h5 { margin: 0 !important; font-size: 17px !important; }

/* Tighter page edges and cards. */
#kt_content { padding-top: 4px !important; padding-bottom: 12px !important; }
#kt_content .container, #kt_content .container-fluid { padding-left: 8px !important; padding-right: 8px !important; max-width: 100% !important; }
#kt_content .row { margin-left: -4px !important; margin-right: -4px !important; }
#kt_content .row > [class*="col"] { padding-left: 4px !important; padding-right: 4px !important; }
.card.card-custom { margin-bottom: 10px !important; border-radius: 14px !important; box-shadow: 0 1px 6px rgba(0,0,0,.06) !important; }
.card.card-custom > .card-body { padding: 12px !important; }
.card.card-custom > .card-header { padding: 0 12px !important; min-height: 48px !important; }
.card-stretch, .card-stretch > .card-body, .card-stretch .flex-grow-1, .card-stretch .table-responsive { height: auto !important; min-height: 0 !important; max-height: none !important; }
.gutter-b, .mb-10 { margin-bottom: 10px !important; }
.row.mt-4, .mt-4 { margin-top: 6px !important; }

/* Tab strips (Attendance Report / Absent Report …) scroll sideways instead of wrapping. */
.card-header-tabs-line .card-toolbar { width: 100%; overflow-x: auto; }
.nav-tabs-line { flex-wrap: nowrap !important; }
.nav-tabs-line .nav-link { white-space: nowrap; padding: 12px 10px !important; }

/* Tables fit the width; long cells wrap. */
.table-responsive { overflow-x: auto !important; -webkit-overflow-scrolling: touch; }
.table { font-size: 13px !important; margin-bottom: 0 !important; }
.table th, .table td { padding: 7px 5px !important; white-space: normal !important; word-break: normal !important; overflow-wrap: normal !important; vertical-align: middle !important; }
.table th { font-size: 11.5px !important; line-height: 1.25 !important; }
/* Cards inside cards: no second frame or padding. */
.card .card { box-shadow: none !important; border: 0 !important; margin-bottom: 0 !important; }
.card .card > .card-body, .card .card > .card-header { padding-left: 0 !important; padding-right: 0 !important; }
.tab-content > .tab-pane > .row > [class*="col"] { padding-left: 0 !important; padding-right: 0 !important; }
/* Attendance: "Class Name" repeats the course code and "Faculty" is long; the app's own screen has both. */
body:has(.nav-link.active[href="class-attendance"]) #home_view table:not(table table) > * > tr > :is(:nth-child(1), :nth-child(2), :nth-child(4)),
body:has(.nav-link.active[href="absent-report"]) #home_view table:not(table table) > * > tr > :is(:nth-child(1), :nth-child(2), :nth-child(4)) { display: none !important; }
body:has(.nav-link.active[href="class-attendance"]) #home_view table td { padding: 7px 4px !important; }

/* Forms: full-width controls that are easy to hit. */
.form-control, select, .custom-select { min-height: 44px !important; font-size: 16px !important; }
.btn { min-height: 40px; }
.dataTables_wrapper .row { margin: 0 !important; }
.dataTables_length, .dataTables_filter { text-align: left !important; margin-bottom: 6px; }
.dataTables_filter input { width: 100% !important; margin-left: 0 !important; }

/* Tables wider than the phone: each row becomes a small card of "label  value" lines (set up by stackWide()). */
table.aa-stack, table.aa-stack > thead, table.aa-stack > tbody { display: block !important; width: 100% !important; overflow: visible !important; }
table.aa-stack tr.aa-head { display: none !important; }
table.aa-stack > * > tr { display: block !important; margin: 0 0 8px !important; padding: 6px 10px !important;
  border: 1px solid rgba(0,0,0,.08) !important; border-radius: 12px !important; background: rgba(255,255,255,.9) !important; }
table.aa-stack > * > tr > :is(td, th) { display: flex !important; justify-content: space-between; gap: 12px; border: 0 !important;
  padding: 4px 0 !important; text-align: right !important; font-size: 13.5px !important; }
table.aa-stack > * > tr > :is(td, th)::before { content: attr(data-aa-label); flex: none; max-width: 45%; text-align: left;
  font-weight: 600; color: #6b6f80; }
table.aa-stack > * > tr > :is(td, th):empty { display: none !important; }

table.aa-wrap, table.aa-wrap > tbody { display: block !important; width: 100% !important; overflow: visible !important; }
table.aa-wrap tr { display: flex !important; flex-wrap: wrap !important; }
table.aa-wrap tr > td, table.aa-wrap tr > th { flex: 1 0 50% !important; max-width: 50% !important; box-sizing: border-box !important; }
table.aa-wrap tr > :nth-child(odd) { color: #6b6f80 !important; font-weight: 600 !important; }

/* Big photos (profile) stay a sensible size. */
#kt_content img:not(.max-h-30px) { max-height: 180px !important; width: auto !important; object-fit: contain; }
.card.p-12, .card.p-10, .card.p-8 { padding: 12px !important; }
#kt_content .row.mt-5 { margin-top: 0 !important; }

/* Home: the section buttons as a three-column grid so they all fit on one screen. */
#kt_content .row:has(> [class*="col"] > a.serv-btn) { display: grid !important; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin: 0 !important; }
#kt_content .row > [class*="col"]:has(> a.serv-btn) { padding: 0 !important; max-width: none !important; width: auto !important; flex: none !important; margin: 0 !important; }
a.serv-btn { display: flex !important; flex-direction: column-reverse !important; align-items: center !important; justify-content: center !important;
  gap: 0; height: 56px !important; min-height: 0 !important; margin: 0 !important; padding: 8px 4px !important; border-radius: 14px !important;
  font-size: 11px !important; line-height: 1.2 !important; letter-spacing: 0 !important; text-align: center !important; white-space: normal !important; word-break: break-word; }
a.serv-btn .btn-icon { width: auto !important; height: auto !important; min-width: 0 !important; padding: 0 !important; margin: 0 !important; background: none !important; position: static !important; }
a.serv-btn .btn-icon { display: none !important; }
/* Home: the welcome quote card. */
body:has(a.serv-btn) .card.bgi-no-repeat { display: none !important; }
`;
  const PICK_CSS = `
#aa-pick-bar{position:fixed;left:12px;right:12px;bottom:14px;z-index:2147483647;display:flex;gap:8px;align-items:center;
  padding:10px 12px;border-radius:999px;background:rgba(28,28,34,.88);color:#fff;font:600 14px/1.2 system-ui,sans-serif;
  box-shadow:0 6px 24px rgba(0,0,0,.35);-webkit-backdrop-filter:blur(14px);backdrop-filter:blur(14px)}
#aa-pick-bar span{flex:1;padding-left:6px}
#aa-pick-bar button{all:unset;padding:9px 14px;border-radius:999px;background:rgba(255,255,255,.14);color:#fff;font-weight:600}
#aa-pick-bar button.aa-done{background:#ffa048;color:#1c1c22}
.aa-pick-hover{outline:3px solid #ffa048 !important;outline-offset:-3px !important;background-color:rgba(255,160,72,.18) !important}
`;

  function styleTag(id) {
    let s = document.getElementById(id);
    if (!s) {
      s = document.createElement('style');
      s.id = id;
      (document.head || document.documentElement).appendChild(s);
    }
    return s;
  }

  function applyHidden() {
    const valid = hidden.filter(sel => { try { document.querySelector(sel); return true; } catch (e) { return false; } });
    styleTag('aa-hidden').textContent = valid.length
      ? valid.join(',\n') + '{display:none !important}' : '';
  }

  function applyPhone() { styleTag('aa-phone').textContent = PHONE_CSS; }

  // ---- selector for a tapped element -------------------------------------------------------------

  function cssEscape(s) { return window.CSS && CSS.escape ? CSS.escape(s) : s.replace(/[^\w-]/g, '\\$&'); }

  function selectorFor(el) {
    const parts = [];
    while (el && el.nodeType === 1 && el !== document.body && el !== document.documentElement) {
      if (el.id && !/\d{3,}/.test(el.id)) { parts.unshift('#' + cssEscape(el.id)); break; }
      let part = el.tagName.toLowerCase();
      const classes = Array.from(el.classList).filter(c => !c.startsWith('aa-') && !/\d{3,}|active|show|open|hover|focus/.test(c));
      if (classes.length) part += '.' + classes.slice(0, 3).map(cssEscape).join('.');
      const parent = el.parentElement;
      if (parent) {
        const same = Array.from(parent.children).filter(c => c.matches(part));
        if (same.length > 1) part += ':nth-child(' + (Array.from(parent.children).indexOf(el) + 1) + ')';
      }
      parts.unshift(part);
      if (parts.length >= 5) break;
      el = parent;
    }
    return parts.join(' > ');
  }

  // ---- picking -----------------------------------------------------------------------------------

  let hover = null;
  function mark(el) {
    if (hover) hover.classList.remove('aa-pick-hover');
    hover = el;
    if (hover) hover.classList.add('aa-pick-hover');
  }

  function bar() {
    let b = document.getElementById('aa-pick-bar');
    if (b) return b;
    b = document.createElement('div');
    b.id = 'aa-pick-bar';
    b.innerHTML = '<span>Tap anything to hide it</span><button class="aa-undo">Undo</button><button class="aa-done">Done</button>';
    b.querySelector('.aa-undo').addEventListener('click', e => { e.stopPropagation(); undoLast(); });
    b.querySelector('.aa-done').addEventListener('click', e => { e.stopPropagation(); stopPicking(); });
    document.body.appendChild(b);
    return b;
  }

  function onPick(e) {
    if (!picking) return;
    if (e.target.closest && e.target.closest('#aa-pick-bar')) return;
    // Keep the page's own handlers out of it, but only cancel the click itself: cancelling the touch would stop
    // the click from ever being generated.
    e.stopPropagation();
    e.stopImmediatePropagation();
    if (e.type !== 'click') { if (e.type === 'touchstart' || e.type === 'pointerdown') mark(e.target); return; }
    e.preventDefault();
    const el = e.target;
    if (!el || el === document.body || el === document.documentElement) return;
    const sel = selectorFor(el);
    if (!sel || hidden.includes(sel)) return;
    hidden.push(sel);
    undo.push(sel);
    mark(null);
    applyHidden();
    save();
  }

  function undoLast() {
    const sel = undo.pop();
    if (!sel) return;
    hidden = hidden.filter(s => s !== sel);
    applyHidden();
    save();
  }

  function startPicking() {
    if (picking) return;
    picking = true;
    undo = [];
    styleTag('aa-pick').textContent = PICK_CSS;
    bar().style.display = 'flex';
    for (const t of ['click', 'pointerdown', 'touchstart', 'mousedown', 'pointerup', 'mouseup', 'touchend'])
      document.addEventListener(t, onPick, { capture: true, passive: false });
  }

  function stopPicking() {
    if (!picking) return;
    picking = false;
    mark(null);
    for (const t of ['click', 'pointerdown', 'touchstart', 'mousedown', 'pointerup', 'mouseup', 'touchend'])
      document.removeEventListener(t, onPick, { capture: true });
    const b = document.getElementById('aa-pick-bar');
    if (b) b.remove();
    styleTag('aa-pick').textContent = '';
    send({ type: 'tidy', picking: false, count: hidden.length });
  }

  // ---- storage and the app -----------------------------------------------------------------------

  function save() {
    browser.storage.local.set({ [KEY]: hidden }).catch(() => {});
    send({ type: 'tidy', picking, count: hidden.length });
  }

  function send(msg) { try { if (port) port.postMessage(msg); } catch (e) { } }

  function connect() {
    try {
      port = browser.runtime.connectNative('browser');
      port.onMessage.addListener(m => {
        if (!m || !m.cmd) return;
        if (m.cmd === 'pick') startPicking();
        else if (m.cmd === 'stop') stopPicking();
        else if (m.cmd === 'reset') { hidden = []; undo = []; applyHidden(); save(); }
        else if (m.cmd === 'phone') {
          phoneOn = !!m.on;
          if (phoneOn) { applyPhone(); stackWide(); } else { styleTag('aa-phone').textContent = ''; unstack(); }
        }
        else if (m.cmd === 'attendance') openAttendance();
      });
      port.onDisconnect.addListener(() => { port = null; });
      send({ type: 'tidy', picking, count: hidden.length });
    } catch (e) { port = null; }
  }

  function openAttendance() {
    let attempts = 0;
    const findLink = () => Array.from(document.querySelectorAll('a[href]')).find(a => {
      try {
        return new URL(a.href, location.href).origin === location.origin
          && new URL(a.href, location.href).pathname.replace(/\/$/, '') === '/client/class-attendance';
      } catch (e) { return false; }
    });
    const wait = setInterval(() => {
      const link = findLink();
      if (link) {
        clearInterval(wait);
        link.click();
      } else if (++attempts >= 40) clearInterval(wait);
    }, 250);
  }

  // ---- wide tables -------------------------------------------------------------------------------

  let phoneOn = true;
  function stackWide() {
    if (!phoneOn) return;
    document.querySelectorAll('table').forEach(t => {
      if (t.classList.contains('aa-stack') || t.parentElement.closest('table') || t.querySelector('table')
          || /^\/client\/(class-attendance|absent-report)/.test(location.pathname)) return;
      if (t.classList.contains('aa-wrap')) return;
      const box = t.parentElement;
      const right = t.getBoundingClientRect().right;
      const wide = right > document.documentElement.clientWidth + 2 || t.scrollWidth > t.clientWidth + 2
        || (box && box.scrollWidth > box.clientWidth + 2);
      if (!wide || !t.rows.length) return;
      // Some portal tables keep every row, data included, inside <thead>; only the first row is the header.
      let head = t.tHead && t.tHead.rows[0];
      if (!head && t.rows.length && Array.from(t.rows[0].cells).every(c => c.tagName === 'TH')) head = t.rows[0];
      if (head) head.classList.add('aa-head');
      if (!head) { t.classList.add('aa-wrap'); return; } // label/value grids: two cells per line
      if (head.cells.length < 3) return;
      const labels = Array.from(head.cells, c => (c.innerText || '').replace(/\s+/g, ' ').trim());
      Array.from(t.rows).forEach(r => {
        if (r === head) return;
        Array.from(r.cells).forEach((c, i) => { if (labels[i] && !c.hasAttribute('colspan')) c.setAttribute('data-aa-label', labels[i]); });
      });
      t.classList.add('aa-stack');
    });
  }
  function unstack() { document.querySelectorAll('table.aa-stack, table.aa-wrap').forEach(t => t.classList.remove('aa-stack', 'aa-wrap')); }
  let stackTimer = 0;
  function scheduleStack() { clearTimeout(stackTimer); stackTimer = setTimeout(stackWide, 300); }
  function watchTables() {
    stackWide();
    new MutationObserver(m => { if (m.some(x => !(x.target.closest && x.target.closest('table.aa-stack')))) scheduleStack(); })
      .observe(document.body, { childList: true, subtree: true });
    window.addEventListener('resize', scheduleStack);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', watchTables, { once: true });
  else watchTables();

  applyPhone();
  browser.storage.local.get(KEY).then(r => {
    hidden = Array.isArray(r && r[KEY]) ? r[KEY] : [];
    applyHidden();
    send({ type: 'tidy', picking, count: hidden.length });
  }).catch(() => {});
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', connect, { once: true });
  else connect();
})();
