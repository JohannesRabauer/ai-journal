// Minimal DOM stand-ins for the node tests (no jsdom): just what the browser modules touch.

export class FakeElement {
  constructor(tag = 'div') {
    this.tagName = tag.toUpperCase();
    this.children = [];
    this.attributes = new Map();
    this.listeners = new Map();
    this.className = '';
    this.id = '';
    this.hidden = false;
    this.textContent = '';
    this.value = '';
    this.isConnected = true;
    this.parent = null;
    this.focused = false;
  }

  setAttribute(k, v) {
    this.attributes.set(k, String(v));
  }

  getAttribute(k) {
    return this.attributes.has(k) ? this.attributes.get(k) : null;
  }

  removeAttribute(k) {
    this.attributes.delete(k);
  }

  appendChild(c) {
    c.parent = this;
    c.isConnected = true;
    this.children.push(c);
    return c;
  }

  remove() {
    if (this.parent) {
      this.parent.children = this.parent.children.filter((c) => c !== this);
    }
    this.parent = null;
    this.isConnected = false;
  }

  addEventListener(type, fn) {
    if (!this.listeners.has(type)) {
      this.listeners.set(type, []);
    }
    this.listeners.get(type).push(fn);
  }

  removeEventListener(type, fn) {
    this.listeners.set(type, (this.listeners.get(type) ?? []).filter((f) => f !== fn));
  }

  /** Click events bubble like in a browser; target stays the element that was clicked. */
  dispatch(type, event = {}) {
    const e = { target: this, ...event };
    for (let el = this; el; el = type === 'click' ? el.parent : null) {
      for (const fn of el.listeners.get(type) ?? []) {
        fn(e);
      }
    }
  }

  click() {
    this.dispatch('click');
  }

  focus() {
    this.focused = true;
    if (this.ownerDocument) {
      this.ownerDocument.activeElement = this;
    }
  }

  querySelector(sel) {
    const all = (el) => el.children.flatMap((c) => [c, ...all(c)]);
    const m = /^\.([\w-]+)$/.exec(sel) || null;
    const a = /^\[data-action="(\w+)"\]$/.exec(sel) || null;
    const t = /^(\w+)$/.exec(sel) || null;
    return all(this).find((e) => (m && e.className.split(' ').includes(m[1]))
      || (a && e.getAttribute('data-action') === a[1]) || (t && e.tagName === t[1].toUpperCase())) ?? null;
  }
}

export class FakeDialog extends FakeElement {
  constructor() {
    super('dialog');
    this.returnValue = '';
    this.open = false;
  }

  showModal() {
    this.open = true;
  }

  close(value = '') {
    this.returnValue = value;
    this.open = false;
    this.dispatch('close');
  }

  /** What the browser does for the Escape key. */
  escape() {
    this.close('');
  }
}

export class FakeDocument {
  constructor() {
    this.body = new FakeElement('body');
    this.activeElement = this.body;
    this.cookie = '';
    this.visibilityState = 'visible';
    this.listeners = new Map();
  }

  createElement(tag) {
    const el = tag === 'dialog' ? new FakeDialog() : new FakeElement(tag);
    el.ownerDocument = this;
    return el;
  }

  addEventListener(type, fn) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), fn]);
  }

  removeEventListener(type, fn) {
    this.listeners.set(type, (this.listeners.get(type) ?? []).filter((f) => f !== fn));
  }

  dispatch(type) {
    (this.listeners.get(type) ?? []).forEach((fn) => fn({}));
  }
}

/** Manual timers: tick(ms) runs what is due. */
export function fakeTimers() {
  let now = 0;
  let nextId = 1;
  const pending = new Map();
  return {
    setTimeout(fn, ms) {
      const id = nextId++;
      pending.set(id, { at: now + ms, fn });
      return id;
    },
    clearTimeout(id) {
      pending.delete(id);
    },
    async tick(ms) {
      const end = now + ms;
      for (;;) {
        const due = [...pending.entries()].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
        if (!due) {
          break;
        }
        pending.delete(due[0]);
        now = due[1].at;
        await due[1].fn();
        // let promise continuations run
        await new Promise((r) => setImmediate(r));
      }
      now = end;
    },
    get pending() {
      return pending.size;
    },
  };
}
