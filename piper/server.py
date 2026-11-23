"""Minimal Piper TTS HTTP service for AI Diary.

POST /synthesize  {"text": "...", "voice": "de_DE-thorsten-medium", "length_scale": 1.0}  ->  audio/wav
GET  /voices      ->  {"voices": ["de_DE-thorsten-medium", ...], "default": "..."}
GET  /health      ->  {"status": "ok"}

Privacy: request text is never logged and never kept after the response (unlike piper.http_server, which
exposes the last utterance on GET /info). Voices are loaded lazily from PIPER_VOICES_DIR.
"""
import io
import logging
import os
import threading
import wave
from pathlib import Path

from flask import Flask, jsonify, request
from piper import PiperVoice, SynthesisConfig

VOICES_DIR = Path(os.environ.get("PIPER_VOICES_DIR", "/voices"))
DEFAULT_VOICE = os.environ.get("PIPER_DEFAULT_VOICE", "en_US-lessac-medium")
MAX_TEXT_CHARS = int(os.environ.get("PIPER_MAX_TEXT_CHARS", "5000"))

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("piper-service")

app = Flask(__name__)
_voices = {}
_lock = threading.Lock()


def available_voices():
    return sorted(p.stem for p in VOICES_DIR.glob("*.onnx"))


def load_voice(name):
    with _lock:
        voice = _voices.get(name)
        if voice is None:
            path = VOICES_DIR / (name + ".onnx")
            if not path.is_file():
                return None
            voice = PiperVoice.load(path)
            _voices[name] = voice
        return voice


def error(status, code, message):
    return jsonify({"error": code, "message": message}), status


@app.get("/health")
def health():
    return jsonify({"status": "ok", "voices": len(available_voices())})


@app.get("/voices")
def voices():
    return jsonify({"voices": available_voices(), "default": DEFAULT_VOICE})


@app.post("/synthesize")
def synthesize():
    data = request.get_json(silent=True)
    if not isinstance(data, dict):
        return error(400, "invalid_json", "Body must be a JSON object")
    text = str(data.get("text", "")).strip()
    if not text:
        return error(400, "empty_text", "text is required")
    if len(text) > MAX_TEXT_CHARS:
        return error(400, "text_too_long", "text exceeds %d characters" % MAX_TEXT_CHARS)
    name = data.get("voice") or DEFAULT_VOICE
    voice = load_voice(str(name))
    if voice is None:
        return error(400, "unknown_voice", "voice is not installed; see GET /voices")
    try:
        length_scale = float(data.get("length_scale", voice.config.length_scale))
    except (TypeError, ValueError):
        return error(400, "invalid_length_scale", "length_scale must be a number")
    cfg = SynthesisConfig(length_scale=max(0.5, min(2.0, length_scale)))
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wav:
        first = True
        with _lock:  # one synthesis at a time keeps memory predictable
            for chunk in voice.synthesize(text, cfg):
                if first:
                    wav.setframerate(chunk.sample_rate)
                    wav.setsampwidth(chunk.sample_width)
                    wav.setnchannels(chunk.sample_channels)
                    first = False
                wav.writeframes(chunk.audio_int16_bytes)
    log.info("synthesized voice=%s chars=%d bytes=%d", name, len(text), buf.tell())
    return app.response_class(buf.getvalue(), mimetype="audio/wav")


if __name__ == "__main__":
    from waitress import serve

    serve(app, host=os.environ.get("PIPER_HOST", "0.0.0.0"), port=int(os.environ.get("PIPER_PORT", "5000")), threads=2)
