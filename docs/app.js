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
const PREREG_DRAFT_KEY = 'clawwatch-preregister-draft';
const PREREG_PENDING_KEY = 'clawwatch-preregister-pending';

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

function saveDraftState() {
  try {
    window.localStorage.setItem(PREREG_DRAFT_KEY, JSON.stringify(getFormState()));
  } catch {}
}

function loadDraftState() {
  try {
    const raw = window.localStorage.getItem(PREREG_DRAFT_KEY);
    if (!raw) return null;
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

function clearDraftState() {
  try {
    window.localStorage.removeItem(PREREG_DRAFT_KEY);
  } catch {}
}

function setPendingPreregistration(isPending) {
  try {
    if (isPending) {
      window.localStorage.setItem(PREREG_PENDING_KEY, '1');
    } else {
      window.localStorage.removeItem(PREREG_PENDING_KEY);
    }
  } catch {}
}

function hasPendingPreregistration() {
  try {
    return window.localStorage.getItem(PREREG_PENDING_KEY) === '1';
  } catch {
    return false;
  }
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

function applyDraftState(draft = {}) {
  const selected = Array.isArray(draft.watchModels) ? draft.watchModels : [];
  const selectedSet = new Set(selected);
  watchModelInputs.forEach((input) => {
    input.checked = selectedSet.has(input.value);
  });
  if (feedbackInput) {
    feedbackInput.value = typeof draft.feedback === 'string' ? draft.feedback : '';
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
  clearDraftState();
  setPendingPreregistration(false);
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
  saveDraftState();
  setPendingPreregistration(true);

  const redirectTo = `${window.location.origin}${window.location.pathname}?preregister=complete#preregister`;
  const { data, error } = await supabaseClient.auth.signInWithOAuth({
    provider: 'google',
    options: {
      redirectTo,
      queryParams: {
        prompt: 'select_account',
        access_type: 'offline'
      }
    }
  });

  if (error) {
    setPendingPreregistration(false);
    renderSignedOut();
    setBanner(error.message, 'error');
    return;
  }
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
  const savedDraft = loadDraftState();
  if (savedDraft) {
    applyDraftState(savedDraft);
  }

  const { data, error } = await supabaseClient.auth.getSession();
  if (error) {
    setBanner(error.message, 'error');
    return;
  }

  const preregComplete = new URLSearchParams(window.location.search).get('preregister') === 'complete';
  const pendingPreregistration = hasPendingPreregistration();

  if (data.session?.user) {
    applyFormState(data.session.user.user_metadata || {});
    if (preregComplete || pendingPreregistration) {
      if (savedDraft) {
        applyDraftState(savedDraft);
      }
      await ensureInterest(data.session.user, {
        showModal: true,
        forceUpdate: true
      });
    } else {
      renderRegistered(data.session.user);
    }
  } else {
    renderSignedOut();
  }

  if (preregComplete) {
    const cleanUrl = `${window.location.origin}${window.location.pathname}${window.location.hash || ''}`;
    window.history.replaceState({}, document.title, cleanUrl);
  }

  supabaseClient.auth.onAuthStateChange(async (event, session) => {
    if (event === 'SIGNED_IN' && session?.user) {
      applyFormState(session.user.user_metadata || {});
      if (hasPendingPreregistration()) {
        const draft = loadDraftState();
        if (draft) {
          applyDraftState(draft);
        }
      }
      await ensureInterest(session.user, { showModal: true, forceUpdate: hasPendingPreregistration() });
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
