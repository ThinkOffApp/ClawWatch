const express = require('express');
const fs = require('fs');
const path = require('path');
const { exec } = require('child_process');

const app = express();
app.use(express.json());

const STATE_FILE = path.join(__dirname, '.admin-state.json');
const QUEUE_FILE = path.join(__dirname, 'ide-agent-queue.jsonl');

function loadState() {
    try { return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8')); } catch { return {}; }
}

// In-memory deduplication set
const processedIds = new Set();
let botIdentity = null;

// Initialize bot identity from GroupMind
async function fetchBotIdentity() {
    const state = loadState();
    if (!state.groupmind_api_key) return;

    try {
        const response = await fetch('https://groupmind.one/api/v1/users/me', {
            headers: { 'X-API-Key': state.groupmind_api_key }
        });
        if (response.ok) {
            const data = await response.json();
            botIdentity = data.username || data.id || '@clawwatch';
            console.log(`[Webhook] Bot Identity confirmed as: ${botIdentity}`);
        }
    } catch (e) {
        console.error('[Webhook] Failed to fetch bot identity', e.message);
    }
}

fetchBotIdentity();

function appendToQueue(job) {
    fs.appendFileSync(QUEUE_FILE, JSON.stringify(job) + '\n');
}

// 1) Webhook Receiver corresponding to `listen` module
app.post('/webhook/groupmind', (req, res) => {
    // Respond quickly to avoid webhook timeout
    res.json({ ok: true, queued: true });

    const state = loadState();
    const payload = req.body;

    // Guard 1: Kill-switch check
    if (state.poller_kill_switch) {
        console.log('[Guard] Kill-switch is active. Dropping message.');
        return;
    }

    // Message payload extraction (handles array or single object)
    const msgs = Array.isArray(payload.messages) ? payload.messages : [payload];

    for (const msg of msgs) {
        if (!msg || !msg.body) continue;

        const msgId = msg.id || `${Date.now()}-${Math.random()}`;
        const room = msg.room || req.query.room;

        // Guard 2: Idempotency/dedupe
        if (processedIds.has(msgId)) continue;
        processedIds.add(msgId);
        // keep set reasonably sized
        if (processedIds.size > 1000) {
            const iterator = processedIds.values();
            processedIds.delete(iterator.next().value);
        }

        // Guard 3: Room allowlist enforcement
        const allowedRooms = (state.groupmind_rooms || "").split(',').map(r => r.trim()).filter(Boolean);
        if (allowedRooms.length > 0 && room && !allowedRooms.includes(room)) {
            console.log(`[Guard] Room ${room} is not in allowlist. Dropping message.`);
            continue;
        }

        // Guard 4: Self/agent-loop prevention using exact ID match
        const sender = msg.from || '';
        if (botIdentity && sender === botIdentity) {
            console.log(`[Guard] Self/loop prevention triggered for sender ${sender}. Dropping message.`);
            continue;
        }

        // Guard 5: Mention parsing at start
        const bodyTrimmed = msg.body.trim();
        const strictMatch = /^@clawwatch\b/i.test(bodyTrimmed);

        if (!strictMatch) continue;

        // Strip @clawwatch from prompt
        const cleanPrompt = bodyTrimmed.replace(/^@clawwatch\b\s*/i, '');

        console.log(`[Webhook] Valid trigger from ${sender} in ${room}. Queueing: "${cleanPrompt}"`);

        // 2) Message Buffering (`queue` module)
        appendToQueue({
            id: msgId,
            room: room,
            prompt: cleanPrompt,
            timestamp: Date.now()
        });
    }
});

let isProcessingQueue = false;

// 3) Action Dispatching (Queue Processor)
async function processQueue() {
    if (isProcessingQueue) return;

    if (!fs.existsSync(QUEUE_FILE)) return;

    const content = fs.readFileSync(QUEUE_FILE, 'utf8');
    if (!content.trim()) return;

    isProcessingQueue = true;

    const lines = content.split('\n').filter(Boolean);
    const jobs = lines.map(l => { try { return JSON.parse(l); } catch { return null; } }).filter(Boolean);

    // Clear the queue file now that we have loaded it into memory
    fs.writeFileSync(QUEUE_FILE, '');

    const state = loadState();
    const watchTarget = state.watchTarget;

    for (const job of jobs) {
        try {
            console.log(`[Dispatcher] Popping job ${job.id} for device...`);

            // Generate clean shell extras
            const promptEscaped = job.prompt.replace(/"/g, '\\"').replace(/\$/g, '\\$');
            const roomEscaped = job.room.replace(/"/g, '\\"').replace(/\$/g, '\\$');

            let targetArg = watchTarget ? `-s ${watchTarget}` : '';

            // Guard 6: Error and timeout handling wrapper for native ADB dispatch
            const adbPath = '/Users/petrus/Library/Android/sdk/platform-tools/adb';
            const adbCmd = `${adbPath} ${targetArg} shell am start -n com.thinkoff.clawwatch/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER --es prompt "${promptEscaped}" --es room "${roomEscaped}"`;

            await new Promise((resolve, reject) => {
                exec(adbCmd, { timeout: 15000 }, (error, stdout, stderr) => {
                    if (error) {
                        console.error(`[Dispatcher Error] ADB call failed:`, error.message);
                        reject(error);
                    } else {
                        console.log(`[Dispatcher] Successfully woke watch for room ${job.room}`);
                        resolve();
                    }
                });
            });

        } catch (error) {
            console.log(`[Dispatcher] Failed to dispatch job ${job.id} to ADB hardware. Silently dropping to prevent autoresponder loops.`);
        }
    }

    isProcessingQueue = false;
}

// Poll the local queue file frequently
setInterval(processQueue, 2000);

const PORT = process.env.PORT || 8787;
app.listen(PORT, '0.0.0.0', () => {
    console.log(`ClawWatch Webhook Receiver (IDE Kit Pattern) listening on port ${PORT}`);
});
