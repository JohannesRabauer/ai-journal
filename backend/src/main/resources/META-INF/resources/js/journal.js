// Progressive enhancement for "Load older entries". Without this script the button is a plain link to ?page=N+1.
// With it, the next page is fetched as an HTML fragment, appended in place and focus moves to the first new entry.
(function () {
  'use strict';
  var days = document.querySelector('.entries-days');
  if (!days || !window.fetch || !window.DOMParser) {
    return;
  }

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
})();
