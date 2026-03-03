const express = require('express');
const { execSync, exec } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

const app = express();
app.use(express.json());
app.use(express.static(__dirname));

const CONFIG_PATH = path.join(__dirname, '../app/src/main/assets/nullclaw.json');
const PKG         = 'com.thinkoff.clawwatch';
const PREFS_PATH  = `/data/data/${PKG}/shared_prefs/clawwatch_prefs.xml`;

// ── Watch ADB status ──────────────────────────────────

app.get('/api/watch', (req, res) => {
  try {
    const out = execSync('adb devices', { timeout: 5000 }).toString();
    const lines = out.split('\n').filter(l => l.includes('\t'));
    const connected = lines.some(l => l.includes('device') && !l.includes('offline'));
    const device = lines.find(l => l.includes('device') && !l.includes('offline'))
                       ?.split('\t')[0] || null;
    res.json({ ok: true, connected, device });
  } catch {
    res.json({ ok: true, connected: false, device: null });
  }
});

// ── nullclaw.json config (asset file on Mac) ──────────

app.get('/api/config', (req, res) => {
  try {
    const config = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
    res.json({ ok: true, config });
  } catch (e) {
    res.json({ ok: false, error: e.message });
  }
});

app.post('/api/config', (req, res) => {
  try {
    const current = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
    const updated = { ...current, ...req.body };
    fs.writeFileSync(CONFIG_PATH, JSON.stringify(updated, null, 2));
    res.json({ ok: true });
  } catch (e) {
    res.json({ ok: false, error: e.message });
  }
});

// ── Push full SharedPreferences to watch ─────────────
// Writes ALL settings at once: api_key, model, system_prompt,
// max_tokens, rag_mode, brave_api_key

function buildPrefsXml(settings) {
  const entries = Object.entries(settings)
    .map(([k, v]) => {
      if (typeof v === 'number') return `    <int name="${k}" value="${v}" />`;
      return `    <string name="${k}">${String(v)}</string>`;
    })
    .join('\n');
  return `<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n${entries}\n</map>\n`;
}

function pushPrefsToWatch(settings) {
  const tmpFile = path.join(os.tmpdir(), 'clawwatch_prefs.xml');
  fs.writeFileSync(tmpFile, buildPrefsXml(settings), { mode: 0o600 });
  execSync(`adb push ${tmpFile} /data/local/tmp/clawwatch_prefs.xml`);
  execSync(`adb shell run-as ${PKG} sh -c 'mkdir -p /data/data/${PKG}/shared_prefs'`);
  execSync(`adb shell run-as ${PKG} sh -c 'cp /data/local/tmp/clawwatch_prefs.xml ${PREFS_PATH}'`);
  execSync(`adb shell rm /data/local/tmp/clawwatch_prefs.xml`);
  fs.unlinkSync(tmpFile);
}

// Get current prefs from watch
app.get('/api/prefs', (req, res) => {
  try {
    const xml = execSync(
      `adb shell run-as ${PKG} cat ${PREFS_PATH} 2>/dev/null || echo ''`
    ).toString();
    const prefs = {};
    // Parse strings
    for (const m of xml.matchAll(/<string name="([^"]+)">([^<]*)<\/string>/g)) {
      prefs[m[1]] = m[2];
    }
    // Parse ints
    for (const m of xml.matchAll(/<int name="([^"]+)" value="([^"]+)"/g)) {
      prefs[m[1]] = parseInt(m[2]);
    }
    res.json({ ok: true, prefs });
  } catch (e) {
    res.json({ ok: false, prefs: {}, error: e.message });
  }
});

// Push all settings to watch
app.post('/api/push/settings', (req, res) => {
  const { anthropic_api_key, brave_api_key, model, system_prompt, max_tokens, rag_mode } = req.body;

  // Validate API key
  if (anthropic_api_key && !/^[a-zA-Z0-9\-_]+$/.test(anthropic_api_key)) {
    return res.json({ ok: false, error: 'Invalid API key format' });
  }
  if (brave_api_key && !/^[a-zA-Z0-9\-_]+$/.test(brave_api_key)) {
    return res.json({ ok: false, error: 'Invalid Brave key format' });
  }

  try {
    // Build settings object — only include non-empty values
    const settings = {};
    if (anthropic_api_key) settings.anthropic_api_key = anthropic_api_key;
    if (brave_api_key) settings.brave_api_key = brave_api_key;
    if (model) settings.model = model;
    if (system_prompt) settings.system_prompt = system_prompt;
    if (max_tokens) settings.max_tokens = parseInt(max_tokens);
    if (rag_mode) settings.rag_mode = rag_mode;

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
    pushPrefsToWatch({ anthropic_api_key: key });
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
    execSync(`adb push ${tmpFile} /data/local/tmp/nullclaw_tmp.json`);
    execSync(`adb shell run-as ${PKG} sh -c 'mkdir -p /data/data/${PKG}/files'`);
    execSync(`adb shell run-as ${PKG} sh -c 'cp /data/local/tmp/nullclaw_tmp.json /data/data/${PKG}/files/nullclaw.json'`);
    execSync(`adb shell rm /data/local/tmp/nullclaw_tmp.json`);
    fs.unlinkSync(tmpFile);
    res.json({ ok: true, message: 'Config pushed — restart ClawWatch on the watch' });
  } catch (e) {
    res.json({ ok: false, error: e.message });
  }
});

// ── Rebuild + install APK ─────────────────────────────

app.post('/api/deploy', (req, res) => {
  res.json({ ok: true, message: 'Build started — check terminal for progress (~30s)' });
  exec(
    'JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk',
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
