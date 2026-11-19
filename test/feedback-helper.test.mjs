// End-to-end test of browser/amrita-feedback.user.js against test/mock-portal.py, driving headless Chromium
// over raw CDP (Node's built-in WebSocket/fetch; no dependencies).
// Usage: node test/feedback-helper.test.mjs [port]   (the mock must already be running on that port)
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PORT = process.argv[2] || '8765';
const BASE = `http://localhost:${PORT}`;
const SCREENSHOT = process.env.AFH_SCREENSHOT ||
  '/tmp/claude-1000/-home-ash--claude-spares-spare-dJlzio/bd52cf72-57fd-4b0a-be7e-c72161459a54/scratchpad/helper-panel.png';
const USERSCRIPT = fs.readFileSync(path.join(ROOT, 'browser/amrita-feedback.user.js'), 'utf8');
const BOOKMARKLET = decodeURIComponent(fs.readFileSync(path.join(ROOT, 'browser/bookmarklet.txt'), 'utf8').replace(/^javascript:/, ''));
if (!fs.readFileSync(path.join(ROOT, 'browser/bookmarklet.txt'), 'utf8').startsWith('javascript:')) throw new Error('bookmarklet.txt is not a javascript: URL');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------- CDP plumbing ----------------
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'afh-chrome-'));
const chrome = spawn('/usr/bin/chromium', ['--headless=new', '--no-sandbox', '--disable-gpu', '--no-first-run',
  '--no-default-browser-check', '--disable-extensions', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'],
  { stdio: 'ignore' });
let cleaned = false;
function cleanup() {
  if (cleaned) return; cleaned = true;
  try { chrome.kill('SIGKILL'); } catch {}
  try { fs.rmSync(profile, { recursive: true, force: true }); } catch {}
}
process.on('exit', cleanup);

async function connect() {
  const f = path.join(profile, 'DevToolsActivePort');
  for (let i = 0; i < 100 && !fs.existsSync(f); i++) await sleep(100);
  const [port, wsPath] = fs.readFileSync(f, 'utf8').trim().split('\n');
  const ws = new WebSocket(`ws://127.0.0.1:${port}${wsPath}`);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws failed')); });
  let id = 0; const pending = new Map(); const listeners = [];
  ws.onmessage = (m) => {
    const msg = JSON.parse(m.data);
    if (msg.id) { const p = pending.get(msg.id); pending.delete(msg.id); msg.error ? p.rej(new Error(msg.error.message)) : p.res(msg.result); }
    else listeners.forEach((l) => l(msg));
  };
  const send = (method, params = {}, sessionId) => new Promise((res, rej) => {
    const i = ++id; pending.set(i, { res, rej }); ws.send(JSON.stringify({ id: i, method, params, sessionId }));
  });
  return { send, on: (l) => listeners.push(l) };
}

const cdp = await connect();
const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' });
const { sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true });
const S = (m, p) => cdp.send(m, p, sessionId);
await S('Page.enable'); await S('Runtime.enable');

let injectUserscript = false;
const dialogs = [];
let loadWaiters = [];
cdp.on(async (msg) => {
  if (msg.sessionId !== sessionId) return;
  if (msg.method === 'Page.javascriptDialogOpening') {
    dialogs.push(msg.params.message);
    S('Page.handleJavaScriptDialog', { accept: true }).catch(() => {});
  }
  if (msg.method === 'Page.loadEventFired') {
    // Userscript semantics: run the script on every top-level page load.
    if (injectUserscript) await S('Runtime.evaluate', { expression: USERSCRIPT }).catch(() => {});
    loadWaiters.forEach((w) => w()); loadWaiters = [];
  }
});

async function ev(expression) {
  const r = await S('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error('eval failed: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
  return r.result.value;
}
async function nav(url) {
  const loaded = new Promise((r) => loadWaiters.push(r));
  await S('Page.navigate', { url });
  await loaded;
  await sleep(200); // let the injected script run
}
async function waitFor(desc, fn, ms = 30000) {
  const end = Date.now() + ms; let last;
  while (Date.now() < end) {
    try { const v = await fn(); if (v) return v; last = v; } catch (e) { last = e.message; }
    await sleep(100);
  }
  throw new Error(`timeout waiting for ${desc} (last: ${JSON.stringify(last)})`);
}
const serverState = async () => (await fetch(`${BASE}/__state`)).json();
const reset = () => fetch(`${BASE}/__reset`, { method: 'POST' });
// Pages of ours: the one test tab (any URL) plus any other tab that is on the mock; anything else is not ours.
async function pageCount() {
  return (await cdp.send('Target.getTargets')).targetInfos.filter((t) => t.type === 'page' && (t.targetId === targetId || t.url.startsWith(BASE))).length;
}

// Panel / page accessors, as page-side expressions.
const R = `document.getElementById('afh-host').shadowRoot`;
const status = () => ev(`${R}.querySelector('.status').textContent`);
const mainText = () => ev(`${R}.querySelector('.main').textContent`);
const clickMain = () => ev(`${R}.querySelector('.main').click()`);
const clickChip = (n) => ev(`${R}.querySelector('.chip[data-n="${n}"]').click()`);
const setSettings = (o) => ev(`localStorage.setItem('afh-settings', ${JSON.stringify(JSON.stringify(o))})`);
const url = () => ev('location.href');
const statusHas = (re) => async () => re.test(await status());

async function freshStart(settings, { inject }) {
  injectUserscript = false;
  await reset();
  await nav(`${BASE}/client/course-feedback-list`);
  await ev('localStorage.clear(); sessionStorage.clear()');
  if (settings) await setSettings(settings);
  injectUserscript = inject;
  dialogs.length = 0;
}
function assert(c, m) { if (!c) throw new Error('assertion failed: ' + m); }

// ---------------- tests ----------------
const tests = [];
const test = (name, fn) => tests.push({ name, fn });

test('1 userscript, manual: list -> OTP -> fill with 3 -> stops at "press Finish"', async () => {
  await freshStart(null, { inject: true });
  await nav(`${BASE}/client/course-feedback-list`);
  await waitFor('3 pending', statusHas(/^3 pending/));
  assert((await mainText()) === 'Open next pending', 'main button on list: ' + await mainText());
  await clickChip(3);
  assert(JSON.parse(await ev(`localStorage.getItem('afh-settings')`)).rating === 3, 'rating persisted');
  await clickMain();
  await waitFor('course 1 url', async () => /course-feedback\?id=1$/.test(await url()));
  assert((await pageCount()) === 1, 'no extra tab was opened');
  await waitFor('panel on course page', async () => /^Fill every question with 3$/.test(await mainText()));
  await ev(`document.querySelector('button[name=send_otp]').click()`);
  await waitFor('OTP input', () => ev(`!!document.querySelector('input[name=otp]')`));
  assert((await mainText()) === 'Fill every question with 3', 'nothing runs by itself in manual mode');
  await clickMain();
  await waitFor('OTP wait status', statusHas(/Enter the OTP/));
  assert((await mainText()) === 'Stop', 'main button shows Stop while running');
  await clickMain();
  await waitFor('stopped', statusHas(/^Stopped/));
  await ev(`document.querySelector('input[name=otp]').value='123456'; document.querySelector('button[name=verify_otp]').click()`);
  await waitFor('first question', () => ev(`!!document.querySelector('input[name=option_id]')`));
  await waitFor('panel back', async () => /^Fill every question with 3$/.test(await mainText()));
  await clickMain();
  await waitFor('press Finish status', statusHas(/All answered — press Finish/), 40000);
  const st = (await serverState())['1'];
  assert(st.answers.length === 5 && st.answers.every((a) => a.option === 3), 'five answers of 3: ' + JSON.stringify(st.answers));
  assert(!st.completed, 'not submitted in manual mode');
  assert(await ev(`!!document.querySelector('button[name=finish_feedback]')`), 'Finish button still there for the user');
  assert(dialogs.length === 0, 'no dialog opened');
});

test('2 userscript, auto: course 2 rating 4, end OTP, auto Finish, back to list', async () => {
  await freshStart({ rating: 4, auto: true }, { inject: true });
  await nav(`${BASE}/client/course-feedback?id=2`);
  await waitFor('waiting for end OTP', async () => /Enter the OTP/.test(await status()) && await ev(`!!document.querySelector('button[name=finish_feedback]')`), 40000);
  let st = (await serverState())['2'];
  assert(st.answers.length === 3 && st.answers.every((a) => a.option === 4), 'three answers of 4: ' + JSON.stringify(st.answers));
  assert(!st.completed, 'not finished before OTP');
  await sleep(2000);
  assert(!(await serverState())['2'].completed, 'still waiting while OTP empty');
  await ev(`document.querySelector('input[name=otp]').value='123456'`);
  await waitFor('back on list', async () => /course-feedback-list$/.test(await url()) && /pending/.test(await status()), 40000);
  st = (await serverState())['2'];
  assert(st.completed, 'course 2 completed on server');
  assert(await status() === '2 pending', 'list says 2 pending: ' + await status());
  assert(await ev(`[...document.querySelectorAll('tr')].some(r => /CS102/.test(r.innerText) && /Completed/i.test(r.innerText))`), 'course 2 row shows Completed');
  assert(dialogs.length === 0, 'confirm was auto-accepted, no real dialog: ' + JSON.stringify(dialogs));
});

test('3 loop guard: course 3 stops with "didn\'t advance"', async () => {
  await freshStart({ rating: 2, auto: false }, { inject: true });
  await nav(`${BASE}/client/course-feedback?id=3`);
  await waitFor('panel', async () => /^Fill every question with 2$/.test(await mainText()));
  await clickMain();
  await waitFor('loop guard', statusHas(/Save didn't advance; stopped at question 1/));
  assert((await serverState())['3'].answers.length === 1, 'only one save attempted: ' + JSON.stringify((await serverState())['3'].answers) + await status());
  await sleep(1500);
  assert(/didn't advance/.test(await status()), 'status stays stopped');
});

test('4 frame mode (bookmarklet): course 1 auto, rating 2, toggle on second run', async () => {
  await freshStart({ rating: 2, auto: true }, { inject: false });
  await nav(`${BASE}/client/course-feedback-list`);
  await ev(BOOKMARKLET);
  const counts = () => ev(`[document.querySelectorAll('#afh-host').length, document.querySelectorAll('iframe').length]`);
  await waitFor('panel + frame', async () => (await counts()).join() === '1,1');
  await waitFor('list inside frame', statusHas(/^3 pending/));
  const F = `document.querySelector('#afh-frame').contentDocument`;
  await clickMain();
  await waitFor('course 1 in frame', () => ev(`${F}.location.search === '?id=1'`));
  assert((await url()) === `${BASE}/client/course-feedback-list`, 'outer URL untouched');
  assert((await pageCount()) === 1, 'no extra tab: ' + JSON.stringify((await cdp.send('Target.getTargets')).targetInfos.map((t) => [t.type, t.url])));
  await ev(`${F}.querySelector('button[name=send_otp]').click()`);
  await waitFor('OTP input in frame', () => ev(`!!${F}.querySelector('input[name=otp]')`));
  await waitFor('auto waits for OTP', statusHas(/Enter the OTP/));
  await ev(`${F}.querySelector('input[name=otp]').value='123456'; ${F}.querySelector('button[name=verify_otp]').click()`);
  await waitFor('back on list in frame', async () => /^3 pending|^2 pending/.test(await status()) && await ev(`${F}.location.pathname.endsWith('course-feedback-list')`), 45000);
  const st = (await serverState())['1'];
  assert(st.answers.length === 5 && st.answers.every((a) => a.option === 2), 'five answers of 2: ' + JSON.stringify(st.answers));
  assert(st.completed, 'course 1 completed');
  assert((await status()) === '2 pending', 'status: ' + await status());
  assert(dialogs.length === 0, 'no real dialogs');
  assert((await counts()).join() === '1,1', 'still exactly one panel and one frame');
  await ev(BOOKMARKLET); // second run toggles it off
  assert((await counts()).join() === '0,0', 'second run removed panel and frame: ' + await counts());
  await ev(BOOKMARKLET);
  await waitFor('toggled back on, once', async () => (await counts()).join() === '1,1');
  await ev(BOOKMARKLET);
});

test('5 frame mode blocked: shows the fallback message', async () => {
  // A page whose framing is refused: the portal's own origin is mocked, so simulate with an XFO-less cross-origin frame
  // by pointing the frame at 127.0.0.1 (a different origin from localhost).
  await freshStart({ rating: 2, auto: false }, { inject: false });
  await nav(`${BASE}/client/course-feedback-list`);
  await ev(`document.documentElement.setAttribute('data-x','1')`);
  await ev(BOOKMARKLET);
  await waitFor('panel + frame', async () => (await ev(`document.querySelectorAll('iframe').length`)) === 1);
  await ev(`document.querySelector('#afh-frame').src = 'http://127.0.0.1:${PORT}/client/course-feedback-list'`);
  await waitFor('blocked message', statusHas(/This browser blocked frame mode\. Use Firefox with Violentmonkey instead\./));
  await ev(BOOKMARKLET);
});

test('6 userscript, TLP list: class feedback, rating 2 of 4 options, auto', async () => {
  await freshStart({ rating: 2, auto: true }, { inject: true });
  await nav(`${BASE}/client/class-feedback`);
  await waitFor('1 pending', statusHas(/^1 pending/));
  assert(/Choice number 2/.test(await ev(`${R}.querySelector('.hint').textContent`)), 'TLP hint wording');
  await clickMain();
  await waitFor('back on class list', async () => /class-feedback$/.test(await url()) && (await serverState())['T1'].completed, 40000);
  const st = (await serverState())['T1'];
  assert(st.answers.length === 3 && st.answers.every((a) => a.option === 2), 'TLP answers: ' + JSON.stringify(st.answers));
  await waitFor('0 pending', statusHas(/^0 pending/));
});

test('7 360x780 dark screenshot and touch-target sizes', async () => {
  await freshStart({ rating: 3, auto: false }, { inject: true });
  await S('Emulation.setDeviceMetricsOverride', { width: 360, height: 780, deviceScaleFactor: 2, mobile: true });
  await S('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: 'dark' }] });
  await nav(`${BASE}/client/course-feedback?id=3`);
  await waitFor('panel', async () => /^Fill every question with 3$/.test(await mainText()));
  const m = await ev(`(() => { const r = ${R}, q = (s) => r.querySelector(s).getBoundingClientRect();
    const card = q('.card'); const chips = [...r.querySelectorAll('.chip')].map((c) => c.getBoundingClientRect());
    return { card: [card.left, card.right, card.bottom], chipMinW: Math.min(...chips.map((c) => c.width)), chipMinH: Math.min(...chips.map((c) => c.height)),
      main: q('.main').height, sw: q('.sw').height, scrollW: document.scrollingElement.scrollWidth, vw: innerWidth,
      bg: getComputedStyle(r.querySelector('.card')).backgroundColor }; })()`);
  assert(m.card[0] >= 0 && m.card[1] <= 360, 'card inside the viewport ' + JSON.stringify(m));
  assert(m.chipMinW >= 48 && m.chipMinH >= 48 && m.main >= 48 && m.sw >= 48, '48px targets ' + JSON.stringify(m));
  assert(m.scrollW <= m.vw, 'no horizontal page scroll ' + JSON.stringify(m));
  assert(/^rgb\(22, 24, 29\)/.test(m.bg), 'dark scheme applied: ' + m.bg);
  fs.mkdirSync(path.dirname(SCREENSHOT), { recursive: true });
  const shot = await S('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(SCREENSHOT, Buffer.from(shot.data, 'base64'));
  console.log('   screenshot:', SCREENSHOT);
  await S('Emulation.setDeviceMetricsOverride', { width: 0, height: 0, deviceScaleFactor: 0, mobile: false });
});

let failed = 0;
for (const t of tests) {
  try { await t.fn(); console.log('PASS', t.name); }
  catch (e) { failed++; console.log('FAIL', t.name, '\n   ', e.message); }
}
console.log(failed ? `${failed} of ${tests.length} FAILED` : `all ${tests.length} passed`);
cleanup();
process.exit(failed ? 1 : 0);
