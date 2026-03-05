import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const STATE_FILE = path.join(__dirname, '.admin-state.json');

// Memory cursor to track last seen message per room
const memCursor = {};

function loadState() {
    try {
        return JSON.parse(fs.readFileSync(STATE_FILE, 'utf8'));
    } catch {
        return {};
    }
}

async function pollAntFarm() {
    const state = loadState();

    if (!state.antfarm_api_key) {
        return; // Wait silently until key is configured
    }

    if (state.poller_kill_switch) {
        return; // Killed via admin panel
    }

    const rooms = (state.antfarm_rooms || "").split(',').map(r => r.trim()).filter(Boolean);
    if (rooms.length === 0) {
        return; // No rooms configured
    }

    for (const room of rooms) {
        try {
            const url = `https://antfarm.world/api/v1/rooms/${room}/messages?limit=10`;
            const resp = await fetch(url, {
                headers: { 'X-API-Key': state.antfarm_api_key }
            });

            if (!resp.ok) continue;

            const data = await resp.json();
            const msgs = Array.isArray(data.messages) ? data.messages : [];
            msgs.reverse(); // Process chronological

            for (const msg of msgs) {
                // Initialize cursor to current max to avoid retro-pinging old messages on boot
                if (!memCursor[room]) {
                    memCursor[room] = msg.created_at;
                    continue;
                }

                if (msg.created_at <= memCursor[room]) continue;

                memCursor[room] = msg.created_at;

                // Push new valid messages to the local webhook
                const bodyTrimmed = (msg.body || '').trim();
                if (/^@clawwatch\b/i.test(bodyTrimmed)) {
                    console.log(`[Poller] New mention from ${msg.from} in ${room}. Pushing to local webhook.`);
                    if (state.poller_dry_run) {
                        console.log(`[DryRun] Would webhook: ${bodyTrimmed}`);
                        continue;
                    }

                    try {
                        await fetch(`http://127.0.0.1:8787/webhook/antfarm?room=${room}`, {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify(msg)
                        });
                    } catch (err) {
                        console.error(`[Poller] Local webhook delivery failed for ${msg.id}: ${err.message}`);
                    }
                }
            }
        } catch (err) {
            console.error(`[Poller] Error polling room ${room}: ${err.message}`);
        }
    }
}

console.log("ClawWatch Ant Farm Poller background daemon started.");
// Poll every 5 seconds
setInterval(pollAntFarm, 5000);
