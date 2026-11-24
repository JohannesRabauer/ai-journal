// <diary-audio-player src="..." labels='{"play":"..","pause":"..","seek":"..","position":"{0} of {1}","error":"..","group":".."}'>
// Card with a round play/pause button, a seek slider (native range input, keyboard operable) and a time display.
// The audio itself is a plain <audio> element without its own controls; the button and slider drive it.
// Renders into the light DOM so the page stylesheet (entries.css) styles it.

const SPRITE = '/icons/diary-icons.svg';

export function formatTime(seconds) {
  if (!Number.isFinite(seconds) || seconds < 0) {
    return '0:00';
  }
  const total = Math.floor(seconds);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = String(total % 60).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`;
}

function icon(symbol) {
  return `<svg class="diary-icon" aria-hidden="true" focusable="false"><use href="${SPRITE}#${symbol}"></use></svg>`;
}

export class DiaryAudioPlayer extends HTMLElement {
  connectedCallback() {
    if (this._built) {
      return;
    }
    this._built = true;
    let labels = {};
    try {
      labels = JSON.parse(this.getAttribute('labels') || '{}');
    } catch (e) {
      labels = {};
    }
    this.setAttribute('role', 'group');
    if (labels.group) {
      this.setAttribute('aria-label', labels.group);
    }
    this.innerHTML = `
      <button type="button" class="entries-player__play" aria-label="">
        <span class="entries-player__icon-play">${icon('play')}</span>
        <span class="entries-player__icon-pause">${icon('pause')}</span>
      </button>
      <input type="range" class="entries-player__seek" min="0" max="0" step="1" value="0" disabled>
      <span class="entries-player__time">0:00 / 0:00</span>
      <span class="entries-player__error" role="status"></span>`;
    const audio = document.createElement('audio');
    audio.preload = 'metadata';
    audio.src = this.getAttribute('src') || '';
    this._audio = audio;
    const button = this.querySelector('button');
    const seek = this.querySelector('input');
    const time = this.querySelector('.entries-player__time');
    const error = this.querySelector('.entries-player__error');
    seek.setAttribute('aria-label', labels.seek || '');

    const render = () => {
      const duration = Number.isFinite(audio.duration) ? audio.duration : 0;
      const current = audio.currentTime || 0;
      seek.max = String(Math.floor(duration));
      seek.value = String(Math.floor(current));
      seek.disabled = duration <= 0;
      seek.style.setProperty('--entries-progress', duration > 0 ? `${(current / duration) * 100}%` : '0%');
      const now = formatTime(current);
      const all = formatTime(duration);
      time.textContent = `${now} / ${all}`;
      const text = (labels.position || '{0} / {1}').replace('{0}', now).replace('{1}', all);
      seek.setAttribute('aria-valuetext', text);
      const playing = !audio.paused && !audio.ended;
      this.dataset.state = playing ? 'playing' : 'paused';
      button.setAttribute('aria-label', (playing ? labels.pause : labels.play) || '');
    };

    button.addEventListener('click', () => {
      error.textContent = '';
      if (audio.paused) {
        audio.play().catch(() => {
          error.textContent = labels.error || '';
        });
      } else {
        audio.pause();
      }
    });
    seek.addEventListener('input', () => {
      audio.currentTime = Number(seek.value);
      render();
    });
    for (const type of ['loadedmetadata', 'durationchange', 'timeupdate', 'play', 'pause', 'ended']) {
      audio.addEventListener(type, render);
    }
    audio.addEventListener('error', () => {
      error.textContent = labels.error || '';
    });
    render();
  }

  disconnectedCallback() {
    if (this._audio) {
      this._audio.pause();
    }
  }
}

if (!customElements.get('diary-audio-player')) {
  customElements.define('diary-audio-player', DiaryAudioPlayer);
}
