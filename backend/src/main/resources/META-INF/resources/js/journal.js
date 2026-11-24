// Progressive enhancement for "Load older entries". Without this script the button is a plain link to ?page=N+1.
// With it, the next page is fetched as an HTML fragment, appended in place and focus moves to the first new entry.
// The search field and the filter links work without JavaScript as a plain GET form; with it the list updates
// live (debounced), the address bar follows via replaceState and the result count is announced politely.
import { buildUrl, createLiveSearch, normalizeFilter, normalizeQuery } from './journal-search.js';
import { notify } from './notify.js';

(function () {
  'use strict';
  if (!document.querySelector('.entries-results') || !window.fetch || !window.DOMParser) {
    return;
  }

  function currentDays() {
    return document.querySelector('.entries-days');
  }

  initSearch();

  document.addEventListener('click', function (event) {
    var link = event.target.closest ? event.target.closest('a.entries-load-older') : null;
    if (!link || event.defaultPrevented || event.button !== 0
        || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
      return;
    }
    event.preventDefault();
    loadOlder(link);
  });

  function loadOlder(link) {
    if (link.getAttribute('aria-busy') === 'true') {
      return;
    }
    link.setAttribute('aria-busy', 'true');
    var url = new URL(link.href, window.location.href);
    url.searchParams.set('fragment', '1');
    fetch(url, { headers: { Accept: 'text/html' }, credentials: 'same-origin' })
      .then(function (response) {
        if (!response.ok) {
          throw new Error('HTTP ' + response.status);
        }
        return response.text();
      })
      .then(function (html) {
        append(new DOMParser().parseFromString(html, 'text/html'), link);
      })
      .catch(function () {
        // Fall back to the normal page load of the same link.
        window.location.href = link.href;
      });
  }

  function append(doc, link) {
    var days = currentDays();
    var firstNew = null;
    Array.prototype.slice.call(doc.querySelectorAll('.entries-days > .entries-day')).forEach(function (section) {
      var cards = Array.prototype.slice.call(section.querySelectorAll('.entries-card')).filter(function (card) {
        // Entries may have shifted between pages since the last load: never show one twice.
        return !days.querySelector('.entries-card[data-id="' + card.getAttribute('data-id') + '"]');
      });
      if (cards.length === 0) {
        return;
      }
      var last = days.lastElementChild;
      if (last && last.getAttribute('data-day') === section.getAttribute('data-day')) {
        // The same day continues on the next page: no second heading, add to the existing list.
        var list = last.querySelector('.entries-list');
        cards.forEach(function (card) { list.appendChild(card); });
      } else {
        var newList = section.querySelector('.entries-list');
        Array.prototype.slice.call(newList.children).forEach(function (card) {
          if (cards.indexOf(card) < 0) {
            newList.removeChild(card);
          }
        });
        days.appendChild(section);
      }
      if (!firstNew) {
        firstNew = cards[0].querySelector('a');
      }
    });

    var progress = document.querySelector('.entries-progress');
    var newProgress = doc.querySelector('.entries-progress');
    if (progress && newProgress) {
      progress.textContent = newProgress.textContent;
    }
    var more = document.querySelector('.entries-more');
    var newMore = doc.querySelector('.entries-more');
    if (more && newMore) {
      more.textContent = '';
      Array.prototype.slice.call(newMore.childNodes).forEach(function (node) { more.appendChild(node); });
    }
    // The pressed link may be gone (last page): keyboard focus goes to the first new entry.
    if (firstNew) {
      firstNew.focus();
    } else if (!document.contains(link) && progress) {
      progress.setAttribute('tabindex', '-1');
      progress.focus();
    }
  }

  function initSearch() {
    var form = document.querySelector('.entries-toolbar');
    var input = form && form.querySelector('input[name="q"]');
    var hidden = form && form.querySelector('input[name="filter"]');
    if (!form || !input || !hidden) {
      return;
    }
    var links = Array.prototype.slice.call(form.querySelectorAll('a[data-filter]'));

    function state() {
      return { q: input.value, filter: hidden.value };
    }

    var search = createLiveSearch({
      initial: state(),
      getState: state,
      load: function (s) {
        var results = document.querySelector('.entries-results');
        if (results) {
          results.setAttribute('aria-busy', 'true');
        }
        return fetch(buildUrl({ q: s.q, filter: s.filter, fragment: true }),
          { headers: { Accept: 'text/html' }, credentials: 'same-origin' })
          .then(function (response) {
            if (!response.ok) {
              throw new Error('HTTP ' + response.status);
            }
            return response.text();
          })
          .then(function (html) { return new DOMParser().parseFromString(html, 'text/html'); });
      },
      apply: function (doc, s) {
        var fresh = doc.querySelector('.entries-results');
        var old = document.querySelector('.entries-results');
        if (!fresh || !old) {
          window.location.href = buildUrl({ q: s.q, filter: s.filter });
          return;
        }
        old.replaceWith(document.importNode(fresh, true));
        try {
          window.history.replaceState(null, '', buildUrl({ q: s.q, filter: s.filter }));
        } catch (e) {
          // History may be unavailable; the list is correct regardless.
        }
        syncLinks(s);
        // One polite announcement per settled search, taken from the server-rendered count / no-match text.
        var summary = document.querySelector('.entries-count, .entries-nomatch-text');
        if (summary) {
          notify(summary.textContent.trim());
        }
      },
      onError: function () {
        var results = document.querySelector('.entries-results');
        if (results) {
          results.removeAttribute('aria-busy');
        }
        notify(form.getAttribute('data-error-text') || '', { kind: 'error' });
      },
    });

    function syncLinks(s) {
      links.forEach(function (a) {
        var f = a.getAttribute('data-filter');
        a.setAttribute('href', buildUrl({ q: s.q, filter: f }));
        if (f === s.filter) {
          a.setAttribute('aria-current', 'true');
        } else {
          a.removeAttribute('aria-current');
        }
      });
    }

    input.addEventListener('input', function () { search.schedule(); });
    form.addEventListener('submit', function (event) {
      event.preventDefault();
      search.now();
    });
    links.forEach(function (a) {
      a.addEventListener('click', function (event) {
        if (event.defaultPrevented || event.button !== 0
            || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
          return;
        }
        event.preventDefault();
        hidden.value = normalizeFilter(a.getAttribute('data-filter'));
        syncLinks({ q: normalizeQuery(input.value), filter: hidden.value });
        search.now();
      });
    });
    // "Clear search" lives inside the replaced results: delegate.
    document.addEventListener('click', function (event) {
      var clear = event.target.closest ? event.target.closest('a.entries-clear') : null;
      if (!clear || event.defaultPrevented || event.button !== 0
          || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
        return;
      }
      event.preventDefault();
      input.value = '';
      hidden.value = 'all';
      syncLinks({ q: '', filter: 'all' });
      search.now();
      input.focus();
    });
  }
})();
