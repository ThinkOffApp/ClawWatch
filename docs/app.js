const SUPABASE_URL = 'https://kvezyhwbkvpyndkaemsw.supabase.co';
const SUPABASE_ANON_KEY = 'sb_publishable_MWtbxI1_Gm_yYTSxHofq2Q_z0nGwItH';

const preregisterButton = document.getElementById('google-preregister-button');
const signOutButton = document.getElementById('google-signout-button');
const statusText = document.getElementById('preregister-status');
const resultBanner = document.getElementById('preregister-result');
const preregisterSection = document.getElementById('preregister');
const preregisterModal = document.getElementById('preregister-modal');
const preregisterModalClose = document.getElementById('preregister-modal-close');
const watchModelInputs = Array.from(document.querySelectorAll('input[name="watch-model"]'));
const feedbackInput = document.getElementById('preregister-feedback');

let supabaseClient = null;
let hasShownThankYou = false;

function showThankYouModal() {
  if (!preregisterModal || hasShownThankYou) return;
  hasShownThankYou = true;
  preregisterModal.hidden = false;
  document.body.style.overflow = 'hidden';
}

function closeThankYouModal() {
  if (!preregisterModal) return;
  preregisterModal.hidden = true;
  document.body.style.overflow = '';
}

function track(eventName, params = {}) {
  if (typeof window.gtag === 'function') {
    window.gtag('event', eventName, params);
  }
}

function setBanner(message, tone = 'info') {
  if (!resultBanner) return;
  resultBanner.hidden = false;
  resultBanner.dataset.tone = tone;
  resultBanner.textContent = message;
}

function clearBanner() {
  if (!resultBanner) return;
  resultBanner.hidden = true;
  resultBanner.textContent = '';
  delete resultBanner.dataset.tone;
}

function getFormState() {
  return {
    watchModels: watchModelInputs.filter((input) => input.checked).map((input) => input.value),
    feedback: feedbackInput?.value.trim() || ''
  };
}

function applyFormState(metadata = {}) {
  const selected = Array.isArray(metadata.clawwatch_watch_models) ? metadata.clawwatch_watch_models : [];
  const selectedSet = new Set(selected);
  watchModelInputs.forEach((input) => {
    input.checked = selectedSet.has(input.value);
  });
  if (feedbackInput) {
    feedbackInput.value = typeof metadata.clawwatch_feedback === 'string' ? metadata.clawwatch_feedback : '';
  }
}

function renderSignedOut() {
  clearBanner();
  if (statusText) {
    statusText.textContent = 'Sign in with Google and we will mark your account for the future install-ready ClawWatch release.';
  }
  if (preregisterButton) {
    preregisterButton.disabled = false;
    preregisterButton.innerHTML = '<span class="google-mark">G</span><span>Continue with Google</span>';
  }
  if (signOutButton) {
    signOutButton.hidden = true;
  }
}

function renderBusy(message) {
  clearBanner();
  if (statusText) {
    statusText.textContent = message;
  }
  if (preregisterButton) {
    preregisterButton.disabled = true;
    preregisterButton.innerHTML = '<span class="google-mark">…</span><span>Working…</span>';
  }
  if (signOutButton) {
    signOutButton.hidden = true;
  }
}

function renderRegistered(user) {
  const email = user.email || 'your Google account';
  if (statusText) {
    statusText.textContent = `You are registered for ClawWatch install updates as ${email}. You can update your watch details below any time.`;
  }
  if (preregisterButton) {
    preregisterButton.disabled = false;
    preregisterButton.innerHTML = '<span class="google-mark">✓</span><span>Update my interest details</span>';
  }
  if (signOutButton) {
    signOutButton.hidden = false;
  }
  setBanner("Thank you for your interest! We'll be back when the easy-to-install ClawWatch is here.", 'success');
}

async function ensureInterest(user, { showModal = false, forceUpdate = false } = {}) {
  const metadata = user.user_metadata || {};
  if (metadata.clawwatch_interest_at && !forceUpdate) {
    applyFormState(metadata);
    renderRegistered(user);
    if (showModal) {
      showThankYouModal();
    }
    return;
  }

  const { watchModels, feedback } = getFormState();
  renderBusy(metadata.clawwatch_interest_at ? 'Updating your ClawWatch details…' : 'Saving your ClawWatch preregistration…');

  const { data, error } = await supabaseClient.auth.updateUser({
    data: {
      clawwatch_interest: true,
      clawwatch_interest_at: metadata.clawwatch_interest_at || new Date().toISOString(),
      clawwatch_interest_source: window.location.host,
      clawwatch_interest_status: 'preregistered',
      clawwatch_watch_models: watchModels,
      clawwatch_has_watch: watchModels.length > 0 && !watchModels.includes('no-watch-yet'),
      clawwatch_feedback: feedback
    }
  });

  if (error) {
    if (statusText) {
      statusText.textContent = 'Google sign-in worked, but saving your preregistration failed.';
    }
    if (preregisterButton) {
      preregisterButton.disabled = false;
      preregisterButton.innerHTML = '<span class="google-mark">G</span><span>Try Google sign-in again</span>';
    }
    setBanner(error.message, 'error');
    return;
  }

  track('clawwatch_preregister_complete');
  applyFormState(data.user?.user_metadata || metadata);
  renderRegistered(data.user || user);
  if (showModal) {
    showThankYouModal();
  }
  if (window.location.hash === '#preregister') {
    preregisterSection?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}

async function startGoogleSignIn() {
  clearBanner();
  renderBusy('Redirecting to Google sign-in…');
  track('clawwatch_preregister_start');

  const redirectTo = `${window.location.origin}${window.location.pathname}?preregister=complete#preregister`;
  const { data, error } = await supabaseClient.auth.signInWithOAuth({
    provider: 'google',
    options: {
      skipBrowserRedirect: true,
      redirectTo,
      queryParams: {
        prompt: 'select_account',
        access_type: 'offline'
      }
    }
  });

  if (error) {
    renderSignedOut();
    setBanner(error.message, 'error');
    return;
  }

  const authUrl = data?.url;
  if (!authUrl) {
    renderSignedOut();
    setBanner('Google sign-in could not be started.', 'error');
    return;
  }

  const popup = window.open(authUrl, 'clawwatch-preregister', 'popup=yes,width=520,height=720');
  if (!popup) {
    renderSignedOut();
    setBanner('Please allow popups for ClawWatch sign-in.', 'error');
    return;
  }

  const poll = window.setInterval(async () => {
    if (!popup || popup.closed) {
      window.clearInterval(poll);
      const { data: sessionData } = await supabaseClient.auth.getSession();
      if (sessionData.session?.user) {
        await ensureInterest(sessionData.session.user, { showModal: true });
      } else {
        renderSignedOut();
      }
    }
  }, 500);
}

async function handlePreregisterAction() {
  const { data } = await supabaseClient.auth.getSession();
  if (data.session?.user) {
    await ensureInterest(data.session.user, { showModal: true, forceUpdate: true });
    return;
  }
  await startGoogleSignIn();
}

async function signOut() {
  const { error } = await supabaseClient.auth.signOut();
  if (error) {
    setBanner(error.message, 'error');
    return;
  }
  renderSignedOut();
}

async function init() {
  const { createClient } = await import('https://esm.sh/@supabase/supabase-js@2');
  supabaseClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
    auth: {
      autoRefreshToken: true,
      detectSessionInUrl: true,
      persistSession: true,
      flowType: 'pkce'
    }
  });

  preregisterButton?.addEventListener('click', handlePreregisterAction);
  signOutButton?.addEventListener('click', signOut);
  preregisterModalClose?.addEventListener('click', closeThankYouModal);
  preregisterModal?.addEventListener('click', (event) => {
    if (event.target?.dataset?.closeModal === 'true') {
      closeThankYouModal();
    }
  });

  window.addEventListener('message', async (event) => {
    if (event.origin !== window.location.origin) return;
    if (event.data?.type !== 'clawwatch-preregister-complete') return;
    const { data: sessionData } = await supabaseClient.auth.getSession();
    if (sessionData.session?.user) {
      await ensureInterest(sessionData.session.user, { showModal: true });
    }
  });

  const { data, error } = await supabaseClient.auth.getSession();
  if (error) {
    setBanner(error.message, 'error');
    return;
  }

  const preregComplete = new URLSearchParams(window.location.search).get('preregister') === 'complete';

  if (preregComplete && window.opener) {
    window.opener.postMessage({ type: 'clawwatch-preregister-complete' }, window.location.origin);
  }

  if (data.session?.user) {
    applyFormState(data.session.user.user_metadata || {});
    await ensureInterest(data.session.user, { showModal: preregComplete });
  } else {
    renderSignedOut();
  }

  if (preregComplete) {
    const cleanUrl = `${window.location.origin}${window.location.pathname}${window.location.hash || ''}`;
    window.history.replaceState({}, document.title, cleanUrl);
    if (window.opener) {
      window.setTimeout(() => window.close(), 120);
    }
  }

  supabaseClient.auth.onAuthStateChange(async (event, session) => {
    if (event === 'SIGNED_IN' && session?.user) {
      applyFormState(session.user.user_metadata || {});
      await ensureInterest(session.user, { showModal: true });
      return;
    }

    if (event === 'SIGNED_OUT') {
      renderSignedOut();
    }
  });
}

init().catch((error) => {
  renderSignedOut();
  setBanner(error.message || 'Failed to initialize preregistration.', 'error');
});
