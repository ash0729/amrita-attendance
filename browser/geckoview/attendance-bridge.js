(function () {
  let last = '';
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

  function schedule() {
    clearTimeout(timer);
    timer = setTimeout(report, 250);
  }

  report();
  new MutationObserver(schedule).observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true
  });
  document.addEventListener('change', schedule, true);
})();
