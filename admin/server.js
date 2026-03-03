const express = require('express');
const { exec, execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

const app = express();
app.use(express.json());
app.use(express.static(__dirname));

const CONFIG_PATH    = path.join(__dirname, '../app/src/main/assets/nullclaw.json');
const CONFIG_EXAMPLE_PATH = path.join(__dirname, '../app/src/main/assets/nullclaw.json.example');
const PKG            = 'com.thinkoff.clawwatch';
const PREFS_PATH     = `/data/data/${PKG}/shared_prefs/clawwatch_prefs.xml`;
const STATE_FILE     = path.join(__dirname, '.admin-state.json');

function loadState() {
  try { return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')); } catch { return {}; }
}
function saveState(s) { fs.writeFileSync(STATE_FILE, JSON.stringify(s, null, 2)); }

let watchTarget = loadState().watchTarget || null; // e.g. "192.168.50.101:35977"

function ensureConfigPath() {
  if (fs.existsSync(CONFIG_PATH)) return CONFIG_PATH;
  if (fs.existsSync(CONFIG_EXAMPLE_PATH)) {
    fs.copyFileSync(CONFIG_EXAMPLE_PATH, CONFIG_PATH);
    return CONFIG_PATH;
  }
  throw new Error('Missing nullclaw.json and nullclaw.json.example');
}

function adb(args, opts = {}) {
  const prefix = watchTarget ? ['-s', watchTarget] : [];
  return execFileSync('adb', [...prefix, ...args], {
    timeout: opts.timeout ?? 10000,
    encoding: 'utf8'
  });
}

// ── Watch ADB connection ──────────────────────────────

app.get('/api/watch', (req, res) => {
  try {
    const out = execFileSync('adb', ['devices'], { timeout: 5000, encoding: 'utf8' });
    const devices = out
      .split('\n')
      .filter(l => l.includes('\t'))
      .map(l => {
        const [serial, status] = l.split('\t');
        return { serial, status };
      });
    const onlineDevices = devices.filter(d => d.status === 'device');
    const connected = watchTarget
      ? onlineDevices.some(d => d.serial === watchTarget)
      : onlineDevices.length > 0;
    const device = connected
      ? (watchTarget || onlineDevices[0]?.serial || null)
      : null;
    res.json({ ok: true, connected, device, target: watchTarget });
  } catch {
    res.json({ ok: true, connected: false, device: null, target: watchTarget });
  }
});

// Connect to watch by IP:port
app.post('/api/watch/connect', (req, res) => {
  const { target } = req.body; // e.g. "192.168.50.101:35977"
  if (!target || !/^\d+\.\d+\.\d+\.\d+:\d+$/.test(target)) {
    return res.json({ ok: false, error: 'Invalid format — use IP:PORT e.g. 192.168.50.101:35977' });
  }
  try {
    const out = execFileSync('adb', ['connect', target], { timeout: 8000, encoding: 'utf8' }).trim();
    watchTarget = target;
    saveState({ watchTarget });
    const connected = out.includes('connected') || out.includes('already connected');
    res.json({ ok: true, connected, message: out });
  } catch (e) {
    res.json({ ok: false, error: e.message });
  }
});

// ── nullclaw.json config (asset file on Mac) ──────────

app.get('/api/config', (req, res) => {
  try {
    const config = JSON.parse(fs.readFileSync(ensureConfigPath(), 'utf8'));
    res.json({ ok: true, config });
  } catch (e) {
    res.json({ ok: false, error: e.message });
  }
});

app.post('/api/config', (req, res) => {
  try {
    const configPath = ensureConfigPath();
    const current = JSON.parse(fs.readFileSync(configPath, 'utf8'));
    const updated = { ...current, ...req.body };
    fs.writeFileSync(configPath, JSON.stringify(updated, null, 2));
    res.json({ ok: true });
  } catch (e) {
    res.json({ ok: false, error: e.message });
  }
});

// ── Push full SharedPreferences to watch ─────────────
// Writes ALL settings at once: api_key, model, system_prompt,
// max_tokens, rag_mode, brave_api_key

function escapeXml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

function decodeXml(value) {
  return String(value)
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&');
}

function buildPrefsXml(settings) {
  const entries = Object.entries(settings)
    .map(([k, v]) => {
      if (typeof v === 'number') {
        return `    <int name="${escapeXml(k)}" value="${v}" />`;
      }
      return `    <string name="${escapeXml(k)}">${escapeXml(v)}</string>`;
    })
    .join('\n');
  return `<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n${entries}\n</map>\n`;
}

function parsePrefsXml(xml) {
  const prefs = {};
  for (const m of xml.matchAll(/<string name="([^"]+)">([\s\S]*?)<\/string>/g)) {
    prefs[decodeXml(m[1])] = decodeXml(m[2]);
  }
  for (const m of xml.matchAll(/<int name="([^"]+)" value="([^"]+)"/g)) {
    const n = parseInt(m[2], 10);
    prefs[decodeXml(m[1])] = Number.isNaN(n) ? 0 : n;
  }
  return prefs;
}

function readPrefsFromWatch() {
  try {
    const xml = adb(['shell', 'run-as', PKG, 'cat', PREFS_PATH], { timeout: 8000 });
    return parsePrefsXml(xml);
  } catch {
    return {};
  }
}

function pushPrefsToWatch(settings) {
  const tmpFile = path.join(os.tmpdir(), 'clawwatch_prefs.xml');
  fs.writeFileSync(tmpFile, buildPrefsXml(settings), { mode: 0o600 });
  adb(['push', tmpFile, '/data/local/tmp/clawwatch_prefs.xml']);
  adb(['shell', 'run-as', PKG, 'mkdir', '-p', `/data/data/${PKG}/shared_prefs`]);
  adb(['shell', 'run-as', PKG, 'cp', '/data/local/tmp/clawwatch_prefs.xml', PREFS_PATH]);
  adb(['shell', 'rm', '/data/local/tmp/clawwatch_prefs.xml']);
  fs.unlinkSync(tmpFile);
}

// Get current prefs from watch
app.get('/api/prefs', (req, res) => {
  try {
    const prefs = readPrefsFromWatch();
    res.json({ ok: true, prefs });
  } catch (e) {
    res.json({ ok: false, prefs: {}, error: e.message });
  }
});

// Push all settings to watch
app.post('/api/push/settings', (req, res) => {
  const {
    anthropic_api_key,
    tavily_api_key,
    brave_api_key,
    model,
    system_prompt,
    max_tokens,
    rag_mode
  } = req.body;

  // Validate API key
  if (anthropic_api_key && !/^[a-zA-Z0-9\-_]+$/.test(anthropic_api_key)) {
    return res.json({ ok: false, error: 'Invalid API key format' });
  }
  if (tavily_api_key && !/^[a-zA-Z0-9\-_]+$/.test(tavily_api_key)) {
    return res.json({ ok: false, error: 'Invalid Tavily key format' });
  }
  if (brave_api_key && !/^[a-zA-Z0-9\-_]+$/.test(brave_api_key)) {
    return res.json({ ok: false, error: 'Invalid Brave key format' });
  }

  try {
    const current = readPrefsFromWatch();
    const updates = {};

    if (anthropic_api_key) updates.anthropic_api_key = anthropic_api_key;
    if (tavily_api_key) updates.tavily_api_key = tavily_api_key;
    if (brave_api_key) updates.brave_api_key = brave_api_key;
    if (model) updates.model = model;
    if (system_prompt) updates.system_prompt = system_prompt;
    if (max_tokens !== undefined) {
      const parsedMaxTokens = parseInt(max_tokens, 10);
      if (Number.isNaN(parsedMaxTokens)) {
        return res.json({ ok: false, error: 'Invalid max_tokens value' });
      }
      updates.max_tokens = parsedMaxTokens;
    }
    if (rag_mode) updates.rag_mode = rag_mode;

    const settings = { ...current, ...updates };

    pushPrefsToWatch(settings);
    res.json({ ok: true, message: 'Settings pushed — restart ClawWatch on the watch' });
  } catch (e) {
    res.json({ ok: false, error: e.message });
  }
});

// Legacy: push only API key
app.post('/api/push/key', (req, res) => {
  const { key } = req.body;
  if (!key || key.length < 20) return res.json({ ok: false, error: 'Key too short' });
  if (!/^[a-zA-Z0-9\-_]+$/.test(key)) return res.json({ ok: false, error: 'Invalid key format' });
  try {
    const current = readPrefsFromWatch();
    pushPrefsToWatch({ ...current, anthropic_api_key: key });
    res.json({ ok: true, message: 'API key pushed — restart ClawWatch on the watch' });
  } catch (e) {
    res.json({ ok: false, error: e.message });
  }
});

// ── Push config file to watch ─────────────────────────

app.post('/api/push/config', (req, res) => {
  try {
    const config = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
    const tmpFile = path.join(os.tmpdir(), 'nullclaw_tmp.json');
    fs.writeFileSync(tmpFile, JSON.stringify(config, null, 2));
    adb(['push', tmpFile, '/data/local/tmp/nullclaw_tmp.json']);
    adb(['shell', 'run-as', PKG, 'mkdir', '-p', `/data/data/${PKG}/files`]);
    adb(['shell', 'run-as', PKG, 'cp', '/data/local/tmp/nullclaw_tmp.json', `/data/data/${PKG}/files/nullclaw.json`]);
    adb(['shell', 'rm', '/data/local/tmp/nullclaw_tmp.json']);
    fs.unlinkSync(tmpFile);
    res.json({ ok: true, message: 'Config pushed — restart ClawWatch on the watch' });
  } catch (e) {
    res.json({ ok: false, error: e.message });
  }
});

// ── Rebuild + install APK ─────────────────────────────

app.post('/api/deploy', (req, res) => {
  res.json({ ok: true, message: 'Build started — check terminal for progress (~30s)' });
  const targetArg = watchTarget && /^\d+\.\d+\.\d+\.\d+:\d+$/.test(watchTarget)
    ? `-s ${watchTarget} `
    : '';
  exec(
    `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug && adb ${targetArg}install -r app/build/outputs/apk/debug/app-debug.apk`,
    { cwd: path.join(__dirname, '..') },
    (err, stdout, stderr) => {
      console.log(err ? `Deploy FAILED:\n${stderr}` : 'Deploy OK ✓');
    }
  );
});

const PORT = 4747;
const HOST = '127.0.0.1'; // localhost only — not exposed to network
app.listen(PORT, HOST, () => {
  console.log(`ClawWatch Admin → http://localhost:${PORT}`);
});
