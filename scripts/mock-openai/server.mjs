#!/usr/bin/env node
// Mock of the OpenAI endpoints the lite-cloud providers call. Dependency-free (Node 18+), for smoke tests only.
//   POST /v1/chat/completions      canned, schema-valid answers for the app's JSON schemas (see answerFor)
//   POST /v1/embeddings            deterministic 64-dim hashed bag-of-words vectors
//   POST /v1/audio/transcriptions  fixed German text
//   POST /v1/audio/speech          tiny valid WAV (response_format wav) or MP3 (anything else)
// Requires an "Authorization: Bearer <non-empty>" header. Listens on 127.0.0.1 only. Logs method and path, never bodies.
// Usage: node server.mjs [--port N]     (default: a free port). Prints "MOCK_OPENAI_LISTENING http://127.0.0.1:PORT/v1".
import http from 'node:http';

const TRANSCRIPT_TEXT = 'Das ist ein Test der Spracherkennung.';
const DIM = 64;

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) });
  res.end(body);
}

// ---------------------------------------------------------------- embeddings
function embed(text) {
  const v = new Array(DIM).fill(0);
  for (const tok of String(text).toLowerCase().split(/[^\p{L}\p{N}]+/u)) {
    if (!tok) continue;
    let h = 2166136261;
    for (let i = 0; i < tok.length; i++) { h ^= tok.charCodeAt(i); h = Math.imul(h, 16777619) >>> 0; }
    v[h % DIM] += 1;
  }
  const norm = Math.sqrt(v.reduce((s, x) => s + x * x, 0)) || 1;
  return v.map(x => x / norm);
}

// ---------------------------------------------------------------- audio
function wav() {
  const rate = 8000, samples = 800; // 0.1 s of silence, 16-bit mono PCM
  const b = Buffer.alloc(44 + samples * 2);
  b.write('RIFF', 0); b.writeUInt32LE(36 + samples * 2, 4); b.write('WAVE', 8); b.write('fmt ', 12);
  b.writeUInt32LE(16, 16); b.writeUInt16LE(1, 20); b.writeUInt16LE(1, 22); b.writeUInt32LE(rate, 24);
  b.writeUInt32LE(rate * 2, 28); b.writeUInt16LE(2, 32); b.writeUInt16LE(16, 34); b.write('data', 36);
  b.writeUInt32LE(samples * 2, 40);
  return b;
}
function mp3() { // 4 silent MPEG-1 Layer III frames, 128 kbit/s, 44.1 kHz, 417 bytes each
  const frame = Buffer.alloc(417); frame[0] = 0xff; frame[1] = 0xfb; frame[2] = 0x90; frame[3] = 0x00;
  return Buffer.concat([frame, frame, frame, frame]);
}

// ---------------------------------------------------------------- chat completions
function between(text, open, close) {
  const i = text.indexOf(open);
  if (i < 0) return '';
  const j = text.indexOf(close, i + open.length);
  return text.slice(i + open.length, j < 0 ? undefined : j).trim();
}
function words(text) { return text.replace(/\s+/g, ' ').trim().split(' ').filter(Boolean); }
/** A verbatim passage: the first n words (or all of them). Whitespace is collapsed, which the app tolerates. */
function head(text, n) { return words(text).slice(0, n).join(' '); }
function tail(text, n) { return words(text).slice(-n).join(' '); }
function unescapeLt(s) { return s.replace(/&lt;/g, '<').replace(/&gt;/g, '>'); }

function schemaOf(body) {
  const rf = body.response_format;
  if (!rf) return null;
  if (rf.type === 'json_schema' && rf.json_schema) return { name: rf.json_schema.name, schema: rf.json_schema.schema };
  if (rf.type === 'json_object') {
    // The gateway prepends "... matches this JSON schema: {...}" as a system message in this mode.
    for (const m of body.messages || []) {
      const i = typeof m.content === 'string' ? m.content.indexOf('JSON schema: ') : -1;
      if (i >= 0) { try { return { name: '', schema: JSON.parse(m.content.slice(i + 13)) }; } catch { /* fall through */ } }
    }
    return { name: '', schema: {} };
  }
  return null;
}

function kindOf(s) {
  const p = (s.schema && s.schema.properties) || {};
  if (p.recurringThemes) return 'weekly';
  if (p.candidates) return 'memory';
  if (p.steps && p.sourceExcerpt) return 'gym_exercise';
  if (p.kind && p.text) return 'coach_turn';
  if (p.questions) return (p.questions.minItems || 0) >= 2 ? 'coach_opening' : 'gym_feedback';
  return s.name || 'unknown';
}

function answerFor(body) {
  const s = schemaOf(body);
  if (!s) return 'Das ist eine Antwort des Mock-Servers.';
  const lastUser = [...(body.messages || [])].reverse().find(m => m.role === 'user');
  const user = lastUser && typeof lastUser.content === 'string' ? lastUser.content : '';
  switch (kindOf(s)) {
    case 'coach_opening':
      return { questions: ['Was hat dich heute am meisten beschäftigt?', 'Wie hast du dich dabei gefühlt?'] };
    case 'coach_turn': {
      const allowed = (s.schema.properties.kind.enum) || ['FOLLOW_UP_QUESTION'];
      const kind = allowed[0];
      const text = kind === 'CLOSING' ? 'Danke, dass du dir die Zeit genommen hast. Bis zum nächsten Mal.'
        : kind === 'EXPLANATION' ? 'Eine Erklärung ist eine kurze Beschreibung. Was möchtest du dazu sagen?'
        : 'Was würdest du als Nächstes gern ausprobieren?';
      return { kind, text };
    }
    case 'memory': {
      const src = unescapeLt(between(user, '<source>', '</source>'));
      const quote = head(src, 6);
      if (!quote) return { candidates: [] };
      return { candidates: [{ type: 'FACT', prompt: 'Was war der Anfang des Textes?', answer: 'Der Text beginnt mit: ' + quote, sourceQuote: quote }] };
    }
    case 'gym_exercise': {
      const entry = unescapeLt(between(user, '<journal_entry>', '</journal_entry>'));
      return {
        title: 'Die Annahme prüfen',
        prompt: 'Welche Annahme steckt hinter deiner Aussage, und woran könntest du sie überprüfen?',
        steps: ['Lies die markierte Stelle noch einmal.', 'Schreibe eine Annahme auf, die darin steckt.', 'Notiere, wie du sie testen könntest.'],
        sourceExcerpt: head(entry, 8),
        estimatedMinutes: 5,
      };
    }
    case 'gym_feedback':
      return { questions: ['Welcher Teil deiner Antwort ist dir am wichtigsten?'] };
    case 'weekly': {
      const entries = [...user.matchAll(/<entry id="(\d+)" date="[^"]*">\n([\s\S]*?)\n<\/entry>/g)];
      if (entries.length === 0) throw new Error('no entries in weekly prompt');
      const id = Number(entries[0][1]);
      const text = unescapeLt(entries[0][2]);
      return {
        recurringThemes: [
          { title: 'Alltag', description: 'Mehrere Einträge beschreiben alltägliche Abläufe.', entryIds: [id] },
          { title: 'Gedanken', description: 'Immer wieder geht es um eigene Gedanken zu einem Ereignis.', entryIds: [id] },
        ],
        inYourOwnWords: [{ text: head(text, 5), entryId: id }, { text: tail(text, 5), entryId: id }],
        openThreads: ['Eine Sache ist noch offen geblieben.'],
        noticedPatterns: ['Die Einträge beginnen meist mit einer kurzen Schilderung.'],
        questionForNextWeek: 'Was möchtest du in der nächsten Woche genauer betrachten?',
      };
    }
    default:
      return {};
  }
}

// ---------------------------------------------------------------- server
const server = http.createServer(async (req, res) => {
  const path = (req.url || '').split('?')[0];
  console.log(`${req.method} ${path}`);
  try {
    const auth = req.headers['authorization'] || '';
    if (!/^Bearer \S+/.test(auth)) return sendJson(res, 401, { error: { message: 'missing bearer token', type: 'invalid_request_error' } });
    if (req.method !== 'POST') return sendJson(res, 405, { error: { message: 'method not allowed' } });
    const raw = await readBody(req);

    if (path === '/v1/chat/completions') {
      const body = JSON.parse(raw.toString('utf8'));
      const answer = answerFor(body);
      const content = typeof answer === 'string' ? answer : JSON.stringify(answer);
      return sendJson(res, 200, {
        id: 'chatcmpl-mock', object: 'chat.completion', created: 0, model: body.model || 'mock',
        choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
      });
    }
    if (path === '/v1/embeddings') {
      const body = JSON.parse(raw.toString('utf8'));
      const inputs = Array.isArray(body.input) ? body.input : [body.input];
      return sendJson(res, 200, {
        object: 'list', model: body.model || 'mock',
        data: inputs.map((t, index) => ({ object: 'embedding', index, embedding: embed(t) })),
        usage: { prompt_tokens: 1, total_tokens: 1 },
      });
    }
    if (path === '/v1/audio/transcriptions') {
      const verbose = raw.includes('verbose_json');
      return sendJson(res, 200, verbose ? { text: TRANSCRIPT_TEXT, language: 'german', duration: 1.0 } : { text: TRANSCRIPT_TEXT });
    }
    if (path === '/v1/audio/speech') {
      const body = JSON.parse(raw.toString('utf8'));
      const isWav = body.response_format === 'wav';
      const audio = isWav ? wav() : mp3();
      res.writeHead(200, { 'Content-Type': isWav ? 'audio/wav' : 'audio/mpeg', 'Content-Length': audio.length });
      return res.end(audio);
    }
    return sendJson(res, 404, { error: { message: 'unknown path' } });
  } catch (e) {
    console.log(`error ${path}: ${e.message}`); // message only, never request content
    return sendJson(res, 500, { error: { message: 'mock failure' } });
  }
});

const portArg = process.argv.indexOf('--port');
const port = portArg > 0 ? Number(process.argv[portArg + 1]) : 0;
server.listen(port, '127.0.0.1', () => {
  console.log(`MOCK_OPENAI_LISTENING http://127.0.0.1:${server.address().port}/v1`);
});
