// Remember page enhancement. The server renders the page (due count, the first card's prompt, suggestions, cards,
// lessons); this module runs the review (one card at a time, attempt first, then answer and grade with next-interval
// hints), turns the "Also here" tiles into toggles, and saves suggestions / deletes cards in place through /api/memory.
// Without it: suggestions and cards work as form posts (see MemoryPage), reviewing needs JavaScript. Texts come from
// data-* attributes of #memory-root and from the <template>s; this module has none of its own.
import { get, post, del, ApiError } from './api.js';
import { confirmDialog } from './confirm.js';
import {
  ReviewSession, PHASE, GRADES, gradeForDigit, formatInterval, attemptText, editProblem, errorInfo, extractionTarget,
} from './memory-logic.js';

export function initMemory(doc = globalThis.document, deps = {}) {
  const root = doc.getElementById('memory-root');
  if (!root) {
    return null;
  }
  const win = deps.window ?? globalThis.window;
  const api = { get: deps.get ?? get, post: deps.post ?? post, del: deps.del ?? del };
  const confirm = deps.confirmDialog ?? confirmDialog;
  const fetchPage = deps.fetchPage ?? ((url) => globalThis.fetch(url, { headers: { Accept: 'text/html' }, credentials: 'same-origin' }));
  const attr = (name) => root.getAttribute(`data-${name}`) ?? '';
  const ref = (scope, name) => scope.querySelector(`[data-ref="${name}"]`);

  const review = doc.getElementById('memory-review');
  const eyebrow = doc.getElementById('memory-eyebrow');
  const status = doc.getElementById('memory-status');
  const errorBox = doc.getElementById('memory-error');

  const cards = [...doc.querySelectorAll('#memory-queue > li')].map((li) => ({
    id: Number(li.getAttribute('data-id')),
    type: li.getAttribute('data-type'),
    typeLabel: li.getAttribute('data-type-label'),
    prompt: li.textContent,
  }));
  const session = new ReviewSession(cards);
  let view = null; // {reveal, entryId} while the answer is shown
  let busy = false;

  // ---- messages
  const say = (text) => { status.textContent = text; };
  const clearError = () => { errorBox.textContent = ''; };
  function showError(error, retry) {
    const info = errorInfo(error instanceof ApiError ? error : { code: 'NETWORK', status: 0 });
    errorBox.textContent = '';
    const p = doc.createElement('p');
    p.textContent = attr(`err-${info.code.toLowerCase()}`) || attr('err-unreachable');
    errorBox.appendChild(p);
    if (info.retryable && retry) {
      const again = doc.createElement('button');
      again.type = 'button';
      again.className = 'diary-button diary-button-quiet';
      again.textContent = attr('retry');
      again.addEventListener('click', () => { clearError(); retry(); });
      errorBox.appendChild(again);
    }
    if (info.refresh) {
      // the data changed elsewhere: show it fresh with the calm message
      win.location.assign(`/memory?error=${info.code}`);
    }
  }
  const setBusy = (value) => {
    busy = value;
    review.querySelectorAll('button, textarea').forEach((el) => {
      if (el.tagName === 'TEXTAREA') {
        el.readOnly = value;
      } else {
        el.disabled = value;
      }
    });
  };
  async function guarded(task, retry) {
    if (busy) {
      return;
    }
    clearError();
    setBusy(true);
    try {
      await task();
    } catch (e) {
      showError(e, retry);
    } finally {
      setBusy(false);
    }
  }

  // ---- review
  function updateEyebrow() {
    const n = session.remaining();
    eyebrow.textContent = n === 0 ? attr('due-none') : n === 1 ? attr('due-one') : attr('due-many').replace('{0}', String(n));
  }

  function renderReview(focus) {
    const phase = session.phase();
    review.textContent = '';
    if (phase === PHASE.DONE) {
      const fragment = doc.getElementById('memory-tpl-done').content.cloneNode(true);
      const done = ref(fragment, 'done');
      review.appendChild(fragment);
      if (focus) {
        done.focus();
      }
      return;
    }
    const card = session.current();
    const fragment = doc.getElementById(phase === PHASE.PROMPT ? 'memory-tpl-prompt' : 'memory-tpl-reveal').content.cloneNode(true);
    ref(fragment, 'position').textContent = attr('position').replace('{0}', String(session.position())).replace('{1}', String(session.total));
    ref(fragment, 'type').textContent = card.typeLabel;
    ref(fragment, 'prompt').textContent = card.prompt;
    if (phase === PHASE.PROMPT) {
      const attempt = ref(fragment, 'attempt');
      ref(fragment, 'show').addEventListener('click', () => submitAttempt(attemptText(attempt.value)));
      ref(fragment, 'inHead').addEventListener('click', () => submitAttempt(null));
      attempt.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
          event.preventDefault();
          submitAttempt(attemptText(attempt.value));
        }
      });
      review.appendChild(fragment);
      if (focus) {
        attempt.focus();
      }
      return;
    }
    fillReveal(fragment, view);
    const region = ref(fragment, 'region');
    fragment.querySelectorAll('button[data-grade]').forEach((b) => {
      const grade = GRADES.find((g) => g.key === b.getAttribute('data-grade'));
      b.addEventListener('click', () => gradeCard(grade));
    });
    review.appendChild(fragment);
    if (focus) {
      region.focus();
    }
  }

  function fillReveal(fragment, shown) {
    const reveal = shown.reveal;
    const labels = {};
    for (const name of ['label-entry', 'label-conversation', 'link-entry', 'link-conversation']) {
      const span = ref(fragment, name);
      labels[name] = span.textContent;
      span.remove();
    }
    if (reveal.attemptText && reveal.attemptText.trim() !== '') {
      ref(fragment, 'yourAnswer').hidden = false;
      ref(fragment, 'yourAnswerText').textContent = reveal.attemptText;
    } else {
      ref(fragment, 'inHeadNote').hidden = false;
    }
    ref(fragment, 'answer').textContent = reveal.answer;
    if (reveal.sourceQuote && reveal.sourceQuote.trim() !== '') {
      const conversation = reveal.sourceType === 'CONVERSATION';
      ref(fragment, 'source').hidden = false;
      ref(fragment, 'sourceLabel').textContent = labels[conversation ? 'label-conversation' : 'label-entry'];
      ref(fragment, 'sourceQuote').textContent = `“${reveal.sourceQuote}”`;
      if (shown.entryId) {
        const link = ref(fragment, 'sourceLink');
        link.href = conversation ? `/journal/${shown.entryId}/reflect` : `/journal/${shown.entryId}`;
        link.textContent = labels[conversation ? 'link-conversation' : 'link-entry'];
        link.hidden = false;
      }
    }
    for (const g of GRADES) {
      const hint = ref(fragment, `hint-${g.key}`);
      const days = reveal.nextIntervals ? reveal.nextIntervals[g.key] : undefined;
      if (Number.isInteger(days)) {
        hint.textContent = formatInterval(days, attr('interval-one'), attr('interval-other'));
      } else {
        hint.remove();
      }
    }
  }

  async function resolveEntryId(reveal) {
    if (!reveal.sourceType || reveal.sourceId === null || reveal.sourceId === undefined) {
      return null;
    }
    if (reveal.sourceType === 'ENTRY') {
      return reveal.sourceId;
    }
    try {
      const conversation = await api.get(`/api/conversations/${reveal.sourceId}`);
      return conversation && conversation.entryId ? conversation.entryId : null;
    } catch (e) {
      return null;
    }
  }

  function submitAttempt(text) {
    const card = session.current();
    return guarded(async () => {
      const reveal = await api.post(`/api/memory/reviews/${card.id}/attempt`, { attemptText: text });
      const entryId = await resolveEntryId(reveal);
      session.revealed(reveal);
      view = { reveal, entryId };
      renderReview(true);
    }, () => submitAttempt(text));
  }

  function gradeCard(grade) {
    const card = session.current();
    return guarded(async () => {
      await api.post(`/api/memory/reviews/${card.id}/grade`, { quality: grade.quality });
      session.graded();
      view = null;
      updateEyebrow();
      renderReview(true);
      if (session.phase() === PHASE.DONE) {
        await refreshPanels(currentPanelUrl()); // cards and lessons changed
      }
    }, () => gradeCard(grade));
  }

  doc.addEventListener('keydown', (event) => {
    if (session.phase() !== PHASE.REVEALED || busy || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) {
      return;
    }
    const target = event.target;
    if (target && target.tagName && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) {
      return;
    }
    // WCAG 2.1.4: a single-character shortcut is only active while focus is inside the revealed card (focus is
    // moved there on reveal), so it cannot fire by accident for speech-input or other users of the page.
    if (!target || !target.closest || !target.closest('.memory-reveal')) {
      return;
    }
    const grade = gradeForDigit(event.key);
    if (grade) {
      event.preventDefault();
      gradeCard(grade);
    }
  });

  // ---- "Also here" panels: tiles toggle, everything else stays server-rendered and is swapped in place
  const panels = () => doc.getElementById('memory-panels');
  const openPanel = () => panels().getAttribute('data-open') ?? '';
  const currentPanelUrl = () => (openPanel() ? `/memory?panel=${openPanel()}` : '/memory');

  function showPanel(panel) {
    panels().setAttribute('data-open', panel);
    panels().querySelectorAll('.memory-section').forEach((s) => {
      s.hidden = s.id !== `memory-panel-${panel}`;
    });
    panels().querySelectorAll('a.memory-tile').forEach((tile) => {
      const open = tile.getAttribute('data-panel') === panel;
      tile.setAttribute('aria-expanded', String(open));
      tile.querySelector('.memory-tile__state').textContent = attr(open ? 'hide' : 'show');
    });
  }

  function enhanceTiles() {
    const open = openPanel();
    panels().querySelectorAll('a.memory-tile').forEach((tile) => {
      tile.setAttribute('role', 'button');
      tile.setAttribute('aria-expanded', String(tile.getAttribute('data-panel') === open));
    });
  }

  function toggle(panel) {
    const closing = openPanel() === panel;
    showPanel(closing ? '' : panel);
    if (!closing) {
      const heading = doc.getElementById(`memory-${panel}-title`);
      if (heading) {
        heading.focus();
      }
    }
  }

  async function refreshPanels(url) {
    try {
      const response = await fetchPage(url);
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const html = await response.text();
      const next = new win.DOMParser().parseFromString(html, 'text/html').getElementById('memory-panels');
      if (!next) {
        throw new Error('no panels');
      }
      panels().innerHTML = next.innerHTML;
      panels().setAttribute('data-open', next.getAttribute('data-open') ?? '');
      enhanceTiles();
    } catch (e) {
      win.location.assign(url); // the plain page shows the same state
    }
  }

  root.addEventListener('click', (event) => {
    const target = event.target;
    const closest = (selector) => (target && target.closest ? target.closest(selector) : null);
    const tile = closest('a.memory-tile');
    if (tile) {
      event.preventDefault();
      toggle(tile.getAttribute('data-panel'));
      return;
    }
    const edit = closest('a.memory-edit-link');
    if (edit) {
      event.preventDefault();
      clearError();
      refreshPanels(edit.getAttribute('href').split('#')[0]).then(() => {
        const field = doc.getElementById('memory-edit-prompt');
        if (field) {
          field.focus();
        }
      });
      return;
    }
    const cancel = closest('a.memory-cancel-link');
    if (cancel) {
      event.preventDefault();
      refreshPanels(cancel.getAttribute('href')).then(() => {
        const heading = doc.getElementById('memory-candidates-title');
        if (heading) {
          heading.focus();
        }
      });
      return;
    }
    const summary = closest('details.memory-confirm > summary');
    if (summary) {
      event.preventDefault();
      summary.parentElement.querySelector('form').requestSubmit();
    }
  });
  root.addEventListener('keydown', (event) => {
    if (event.key === ' ' && event.target && event.target.matches && event.target.matches('a.memory-tile')) {
      event.preventDefault();
      event.target.click();
    }
  });

  root.addEventListener('submit', (event) => {
    const form = event.target;
    const action = form.getAttribute ? form.getAttribute('data-memory-action') : null;
    if (!action) {
      return;
    }
    event.preventDefault();
    if (action === 'approve') {
      approve(form);
    } else if (action === 'discard') {
      decide(form, { decision: 'DISCARD' }, 'discarded');
    } else if (action === 'delete-item') {
      deleteItem(form);
    }
  });

  function decisionId(form) {
    return form.getAttribute('action').split('/')[3];
  }

  function approve(form) {
    const body = { decision: 'APPROVE' };
    const prompt = form.elements.prompt;
    if (prompt) {
      const answer = form.elements.answer;
      const problem = editProblem(prompt.value, answer.value);
      const slot = ref(form, 'problem');
      if (problem) {
        slot.textContent = attr(`edit-${problem.toLowerCase()}`);
        (problem.startsWith('prompt') ? prompt : answer).focus();
        return;
      }
      slot.textContent = '';
      body.prompt = prompt.value.trim();
      body.answer = answer.value.trim();
      body.type = form.elements.type.value;
    }
    decide(form, body, 'approved');
  }

  async function decide(form, body, messageName) {
    if (busy) {
      return;
    }
    clearError();
    busy = true;
    try {
      await api.post(`/api/memory/candidates/${decisionId(form)}/decision`, body);
      say(attr(messageName === 'approved' ? 'approved' : 'discarded'));
      await refreshPanels('/memory?panel=candidates');
      const heading = doc.getElementById('memory-candidates-title');
      if (heading) {
        heading.focus();
      }
    } catch (e) {
      showError(e, () => decide(form, body, messageName));
    } finally {
      busy = false;
    }
  }

  async function deleteItem(form) {
    const ok = await confirm({
      title: form.getAttribute('data-confirm-title'),
      message: form.getAttribute('data-confirm-text') ?? '',
      confirmLabel: form.getAttribute('data-confirm-label'),
      cancelLabel: attr('cancel'),
      destructive: true,
    });
    if (!ok || busy) {
      return;
    }
    const id = Number(form.getAttribute('data-item-id'));
    clearError();
    busy = true;
    try {
      await api.del(`/api/memory/items/${id}`);
      session.removeItem(id);
      view = session.phase() === PHASE.REVEALED ? view : null;
      updateEyebrow();
      renderReview(false);
      say(attr('item-deleted'));
      await refreshPanels('/memory?panel=items');
      const heading = doc.getElementById('memory-items-title');
      if (heading) {
        heading.focus();
      }
    } catch (e) {
      showError(e, () => deleteItem(form));
    } finally {
      busy = false;
    }
  }

  // ---- extraction: only after the user chose "Extract learnings" (the address carries source and id)
  async function extract(target) {
    say(attr('extracting'));
    win.history.replaceState(null, '', '/memory'); // a reload must not ask the language model again
    try {
      const created = await api.post('/api/memory/extractions', target);
      const n = Array.isArray(created) ? created.length : 0;
      win.location.replace(n === 0 ? '/memory?panel=candidates&msg=extractNone' : `/memory?panel=candidates&msg=extracted&n=${n}`);
    } catch (e) {
      say('');
      showError(e, () => extract(target));
    }
  }

  // ---- start
  enhanceTiles();
  if (session.phase() !== PHASE.DONE) {
    renderReview(false); // replaces the static first card with the interactive one
  }
  const target = extractionTarget(attr('extract-source'), attr('extract-id'));
  if (target) {
    extract(target);
  }
  return { session };
}

if (globalThis.document && globalThis.document.getElementById) {
  initMemory();
}
