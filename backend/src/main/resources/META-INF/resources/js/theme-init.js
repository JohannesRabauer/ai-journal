// Applies the colour scheme as early as possible: user override (localStorage "diary.theme": system|light|dark),
// otherwise prefers-color-scheme. Lumo reads the "theme" attribute on <html>. No network, no tracking.
(function () {
  var KEY = 'diary.theme';
  var root = document.documentElement;
  var mq = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

  function stored() {
    try {
      var v = window.localStorage.getItem(KEY);
      return v === 'light' || v === 'dark' ? v : 'system';
    } catch (e) {
      return 'system';
    }
  }

  function apply() {
    var mode = stored();
    var dark = mode === 'dark' || (mode === 'system' && mq && mq.matches);
    if (dark) {
      root.setAttribute('theme', 'dark');
    } else {
      root.removeAttribute('theme');
    }
    root.style.colorScheme = dark ? 'dark' : 'light';
  }

  window.diaryTheme = {
    get: stored,
    set: function (mode) {
      try {
        if (mode === 'light' || mode === 'dark') {
          window.localStorage.setItem(KEY, mode);
        } else {
          window.localStorage.removeItem(KEY);
        }
      } catch (e) { /* storage unavailable: override only lasts for this page */ }
      apply();
    }
  };
  if (mq && mq.addEventListener) {
    mq.addEventListener('change', apply);
  }
  apply();

  // The sky behind the page follows the local time of day (css/styles.css, html[data-daypart]). Decoration only.
  function daypart() {
    var h = new Date().getHours();
    root.setAttribute('data-daypart', h < 5 ? 'night' : h < 11 ? 'morning' : h < 17 ? 'day' : h < 22 ? 'evening' : 'night');
  }
  daypart();
  window.setInterval(daypart, 10 * 60 * 1000);
})();
