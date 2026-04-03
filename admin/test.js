
    let ragMode = 'kotlin';
    const RAG_DESCS = {
      'off': 'No web search. Claude answers from its training data only.',
      'kotlin': '<b>Auto-search:</b> Detects queries needing live info (news, weather, prices, scores) and pre-searches before sending to Claude. Uses Brave Search (if key set) or DuckDuckGo (free fallback).',
      'opus_tool': '<b>Opus Tool Use:</b> Claude decides when to search by calling a web_search tool. More accurate intent detection but uses extra tokens for the tool call round-trip.'
    };

    function setRag(mode) {
      ragMode = mode;
      document.getElementById('ragMode').value = mode;
      document.querySelectorAll('.rag-pill').forEach(p => p.classList.remove('active'));
      document.getElementById('rag-' + mode).classList.add('active');
      document.getElementById('ragDesc').innerHTML = RAG_DESCS[mode] || '';
    }

    async function pushAll() {
      const settings = {
        anthropic_api_key: document.getElementById('apiKey').value.trim() || undefined,
        tavily_api_key: document.getElementById('tavilyKey').value.trim() || undefined,
        brave_api_key: document.getElementById('braveKey').value.trim() || undefined,
        antfarm_api_key: document.getElementById('antFarmKey').value.trim() || undefined,
        antfarm_rooms: document.getElementById('antFarmRooms').value.trim() || undefined,
        poller_dry_run: document.getElementById('pollerDryRun').checked,
        poller_kill_switch: document.getElementById('pollerKillSwitch').checked,
        model: document.getElementById('model').value,
        avatar_type: document.getElementById('avatarType').value,
        system_prompt: document.getElementById('systemPrompt').value,
        max_tokens: parseInt(document.getElementById('maxTokens').value),
        rag_mode: ragMode
      };
      // Remove undefined
      Object.keys(settings).forEach(k => settings[k] === undefined && delete settings[k]);

      const r = await fetch('/api/push/settings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(settings)
      }).then(r => r.json());
      toast(r.ok ? r.message : r.error, r.ok ? 'ok' : 'err');
    }

    async function deploy() {
      toast('Build started — check terminal', 'ok');
      fetch('/api/deploy', { method: 'POST' });
    }

    function toast(msg, type) {
      const el = document.getElementById('toast');
      el.textContent = msg;
      el.className = `toast ${type} show`;
      setTimeout(() => el.classList.remove('show'), 3500);
    }

    async function loadFromWatch() {
      const r = await fetch('/api/prefs').then(r => r.json());
      if (!r.ok) { toast('Could not read watch prefs', 'err'); return; }
      const p = r.prefs;
      if (p.model) document.getElementById('model').value = p.model;
      if (p.avatar_type) document.getElementById('avatarType').value = p.avatar_type;
      if (p.system_prompt) document.getElementById('systemPrompt').value = p.system_prompt;
      if (p.max_tokens) {
        document.getElementById('maxTokens').value = p.max_tokens;
        document.getElementById('maxTokensRange').value = p.max_tokens;
        document.getElementById('maxTokensVal').textContent = p.max_tokens;
      }
      if (p.rag_mode) setRag(p.rag_mode);
      if (p.antfarm_rooms) document.getElementById('antFarmRooms').value = p.antfarm_rooms;
      if (p.antfarm_api_key) document.getElementById('antFarmKey').value = p.antfarm_api_key;
      document.getElementById('pollerDryRun').checked = !!p.poller_dry_run;
      document.getElementById('pollerKillSwitch').checked = !!p.poller_kill_switch;
      toast('Loaded from watch', 'ok');
    }

    // Watch status poll
    async function checkWatch() {
      const w = await fetch('/api/watch').then(r => r.json()).catch(() => ({ connected: false }));
      document.getElementById('dot').className = 'dot' + (w.connected ? ' connected' : '');
      document.getElementById('watchStatus').textContent = w.connected
        ? (w.device || 'Connected') : 'Not connected';
      if (w.target && !document.getElementById('watchTarget').value)
        document.getElementById('watchTarget').value = w.target;
    }

    async function connectWatch() {
      const target = document.getElementById('watchTarget').value.trim();
      if (!target) return;
      const r = await fetch('/api/watch/connect', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ target })
      }).then(r => r.json());
      toast(r.ok ? r.message : r.error, r.ok ? 'ok' : 'err');
      checkWatch();
    }

    checkWatch();
    setInterval(checkWatch, 5000);
  