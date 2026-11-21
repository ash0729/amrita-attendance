(function () {
  let last = '';
  let lastPage = '';
  let timer;

  function report() {
    if (location.pathname !== '/client/class-attendance') return;
    const rows = [];
    document.querySelectorAll('tr').forEach(tr => {
      const cells = tr.children;
      if (cells.length >= 9) {
        rows.push(Array.from(cells, cell => (cell.innerText || '').trim()));
      }
    });
    if (!rows.length) return;

    const select = document.querySelector('select');
    const terms = select ? Array.from(select.options, option => option.text.trim()) : [];
    const ids = select ? Array.from(select.options, option => option.value) : [];
    const selected = select ? select.selectedIndex : -1;
    const data = {
      term: terms[selected] || '',
      terms,
      ids,
      sel: selected,
      rows
    };
    const json = JSON.stringify(data);
    if (json === last) return;
    last = json;
    browser.runtime.sendNativeMessage('browser', { type: 'attendance', dataJson: json }).catch(() => {});
  }

  // ---- Any other page: tables and label/value pairs, read-only, for the Campus tab. ----
  const MAX_TABLES = 20, MAX_ROWS = 400, MAX_PAIRS = 200, MAX_JSON = 190000, MAX_CELL = 200;

  function clean(text) {
    return (text || '').replace(/\s+/g, ' ').trim().slice(0, MAX_CELL);
  }

  function headingFor(el) {
    for (let node = el; node && node !== document.body; node = node.parentElement) {
      for (let sib = node.previousElementSibling; sib; sib = sib.previousElementSibling) {
        if (/^H[1-6]$/.test(sib.tagName)) return clean(sib.innerText);
        const inner = sib.querySelector && sib.querySelector('h1,h2,h3,h4,h5,h6');
        if (inner) return clean(inner.innerText);
      }
    }
    return '';
  }

  function readTable(table) {
    const caption = table.querySelector('caption');
    const trs = Array.from(table.querySelectorAll('tr')).filter(tr => tr.closest('table') === table);
    let headers = [];
    let start = 0;
    const head = table.tHead && table.tHead.rows[0];
    if (head) {
      headers = Array.from(head.children, c => clean(c.innerText));
      start = trs.indexOf(head) + 1;
    } else if (trs.length && Array.from(trs[0].children).every(c => c.tagName === 'TH')) {
      headers = Array.from(trs[0].children, c => clean(c.innerText));
      start = 1;
    }
    const rows = [];
    for (let i = start; i < trs.length && rows.length < MAX_ROWS; i++) {
      if (trs[i] === head) continue;
      const cells = Array.from(trs[i].children, c => clean(c.innerText));
      if (cells.some(Boolean)) rows.push(cells);
    }
    return { caption: caption ? clean(caption.innerText) : headingFor(table), headers, rows };
  }

  function readPairs() {
    const pairs = [];
    const seen = new Set();
    const add = (label, value) => {
      label = clean(label).replace(/:\s*$/, '');
      value = clean(value);
      if (!label || !value || label.length > 60 || pairs.length >= MAX_PAIRS) return;
      const key = label + '\u0000' + value;
      if (seen.has(key)) return;
      seen.add(key);
      pairs.push([label, value]);
    };
    document.querySelectorAll('dl').forEach(dl => {
      let label = '';
      Array.from(dl.children).forEach(el => {
        if (el.tagName === 'DT') label = el.innerText;
        else if (el.tagName === 'DD') add(label, el.innerText);
      });
    });
    document.querySelectorAll('tr').forEach(tr => {
      if (tr.children.length === 2) add(tr.children[0].innerText, tr.children[1].innerText);
    });
    document.querySelectorAll('.card, .card-body, .panel, .box, li, p').forEach(el => {
      const text = el.innerText || '';
      if (text.length > 2000) return;
      text.split('\n').forEach(line => {
        const m = line.match(/^\s*([^:]{2,40}):\s*(\S.*)$/);
        if (m) add(m[1], m[2]);
      });
    });
    return pairs;
  }

  function reportPage() {
    if (location.protocol !== 'https:' || location.hostname !== 'students.amrita.edu') return;
    const heading = document.querySelector('h1,h2,h3,h4');
    const title = [document.title, heading ? heading.innerText : '']
      .map(clean).filter(Boolean).join(' · ');
    const tables = [];
    document.querySelectorAll('table').forEach(table => {
      if (tables.length >= MAX_TABLES) return;
      const t = readTable(table);
      if (t.rows.length) tables.push(t);
    });
    const pairs = readPairs();
    if (!tables.length && !pairs.length) return;
    const data = { path: location.pathname, title, tables, pairs };
    let json = JSON.stringify(data);
    while (json.length > MAX_JSON && tables.length) {
      const big = tables.reduce((a, b) => (b.rows.length > a.rows.length ? b : a));
      if (big.rows.length > 1) big.rows.length = Math.floor(big.rows.length / 2);
      else tables.splice(tables.indexOf(big), 1);
      json = JSON.stringify(data);
    }
    if (json.length > MAX_JSON) { data.pairs.length = 0; json = JSON.stringify(data); }
    if (json === lastPage) return;
    lastPage = json;
    browser.runtime.sendNativeMessage('browser', { type: 'page', dataJson: json }).catch(() => {});
  }

  function run() {
    report();
    try { reportPage(); } catch (e) { /* never break the attendance bridge */ }
  }

  function schedule() {
    clearTimeout(timer);
    timer = setTimeout(run, 250);
  }

  run();
  new MutationObserver(schedule).observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true
  });
  document.addEventListener('change', schedule, true);
})();
