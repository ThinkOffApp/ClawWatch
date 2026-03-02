const express = require('express');
const { execSync, exec } = require('child_process');
const fs = require('fs');
const path = require('path');

const app = express();
app.use(express.json());
app.use(express.static(__dirname));

const CONFIG_PATH = path.join(__dirname, '../app/src/main/assets/nullclaw.json');
const PREFS_PATH  = path.join(__dirname, '../app/src/main/assets/nullclaw.json');
const PKG         = 'com.thinkoff.clawwatch';

// ── Config ────────────────────────────────────────────

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

// ── ADB status ────────────────────────────────────────

app.get('/api/watch', (req, res) => {
  try {
    const out = execSync('adb devices', { timeout: 5000 }).toString();
    const lines = out.split('\n').filter(l => l.includes('\t'));
    const connected = lines.some(l => l.includes('device') && !l.includes('offline'));
    const device = lines.find(l => l.includes('device'))?.split('\t')[0] || null;
    res.json({ ok: true, connected, device });
  } catch (e) {
    res.json({ ok: true, connected: false, device: null });
  }
});

// ── Push config to watch ──────────────────────────────

app.post('/api/push/config', (req, res) => {
  try {
    // Push nullclaw.json to watch data dir
    execSync(`adb shell run-as ${PKG} sh -c 'mkdir -p /data/data/${PKG}/files'`);
    execSync(`adb push ${CONFIG_PATH} /sdcard/nullclaw_tmp.json`);
    execSync(`adb shell run-as ${PKG} sh -c 'cp /sdcard/nullclaw_tmp.json /data/data/${PKG}/files/nullclaw.json && rm /sdcard/nullclaw_tmp.json'`);
    res.json({ ok: true, message: 'Config pushed — restart ClawWatch on the watch' });
  } catch (e) {
    res.json({ ok: false, error: e.message });
  }
});

// ── Push API key ──────────────────────────────────────

app.post('/api/push/key', (req, res) => {
  const { key } = req.body;
  if (!key || key.length < 20) return res.json({ ok: false, error: 'Key too short' });
  // Validate: only allow chars found in real API keys (alphanumeric, dash, underscore)
  if (!/^[a-zA-Z0-9\-_]+$/.test(key)) return res.json({ ok: false, error: 'Invalid key format' });

  try {
    // Fix: write to temp file and adb push — never interpolate key into shell command
    const tmpFile = path.join(require('os').tmpdir(), 'clawwatch_prefs.xml');
    const xml = `<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n    <string name="anthropic_api_key">${key}</string>\n</map>\n`;
    fs.writeFileSync(tmpFile, xml, { mode: 0o600 });
    execSync(`adb push ${tmpFile} /sdcard/clawwatch_tmp.xml`);
    execSync(`adb shell run-as ${PKG} sh -c 'mkdir -p /data/data/${PKG}/shared_prefs'`);
    execSync(`adb shell run-as ${PKG} sh -c 'cp /sdcard/clawwatch_tmp.xml /data/data/${PKG}/shared_prefs/clawwatch_prefs.xml'`);
    execSync(`adb shell rm /sdcard/clawwatch_tmp.xml`);
    fs.unlinkSync(tmpFile);
    res.json({ ok: true, message: 'API key pushed — restart ClawWatch on the watch' });
  } catch (e) {
    res.json({ ok: false, error: e.message });
  }
});

// ── Rebuild + install APK ─────────────────────────────

app.post('/api/deploy', (req, res) => {
  res.json({ ok: true, message: 'Build started…' });
  exec(
    'JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk',
    { cwd: path.join(__dirname, '..') },
    (err, stdout, stderr) => {
      console.log(err ? 'Deploy failed: ' + stderr : 'Deploy OK');
    }
  );
});

const PORT = 4747;
app.listen(PORT, () => {
  console.log(`ClawWatch Admin → http://localhost:${PORT}`);
});
