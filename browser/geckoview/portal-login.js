// my.amrita.edu landing page: the portal session ends when the app restarts, but the Microsoft sign-in is remembered,
// so pressing LOGIN signs straight back in. Press it once for the user (never more than once every 30 s, so a
// failed sign-in can't loop). Nothing is typed; if Microsoft asks for a password the user enters it.
(function () {
  const KEY = 'aa-autologin';
  function tryLogin() {
    try {
      const last = Number(sessionStorage.getItem(KEY) || 0);
      if (Date.now() - last < 30000) return true;
      const el = Array.from(document.querySelectorAll('a, button, input[type=submit], [role=button], div, span'))
        .find(e => (e.innerText || e.value || '').trim().toUpperCase() === 'LOGIN' && e.offsetParent !== null);
      if (!el) return false;
      const target = el.closest('a, button, [role=button], input') || el.querySelector('a, button') || el;
      sessionStorage.setItem(KEY, String(Date.now()));
      target.click();
      return true;
    } catch (e) { return true; }
  }
  let tries = 0;
  (function poll() { if (!tryLogin() && ++tries < 20) setTimeout(poll, 250); })();
})();
