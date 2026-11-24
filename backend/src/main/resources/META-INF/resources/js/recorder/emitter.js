/** Minimal event emitter (no DOM dependency, usable in node tests). */
export class Emitter {
  #handlers = new Map();

  on(name, handler) {
    const list = this.#handlers.get(name) ?? [];
    list.push(handler);
    this.#handlers.set(name, list);
    return () => this.off(name, handler);
  }

  off(name, handler) {
    const list = this.#handlers.get(name);
    if (list) {
      this.#handlers.set(name, list.filter((h) => h !== handler));
    }
  }

  emit(name, detail) {
    for (const handler of this.#handlers.get(name) ?? []) {
      try {
        handler(detail);
      } catch {
        // a faulty listener must never break recording
      }
    }
  }
}
