const preregisterButton = document.getElementById('google-preregister-button');
const signOutButton = document.getElementById('google-signout-button');
const statusText = document.getElementById('preregister-status');
const resultBanner = document.getElementById('preregister-result');
const preregisterModal = document.getElementById('preregister-modal');
const preregisterModalClose = document.getElementById('preregister-modal-close');
const watchModelInputs = Array.from(document.querySelectorAll('input[name="watch-model"]'));
const feedbackInput = document.getElementById('preregister-feedback');

const PREREG_DRAFT_KEY = 'clawwatch-preregister-draft';
const PREREG_PENDING_KEY = 'clawwatch-preregister-pending';
const XFOR_PREREGISTER_START_URL = 'https://xfor.bot/api/v1/clawwatch/start';

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
    feedback: feedbackInput?.value.trim() || '',
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

function renderReady() {
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

function buildSharedAuthRedirectUrl() {
  const { watchModels, feedback } = getFormState();
  const params = new URLSearchParams({
    return_to: `${window.location.origin}${window.location.pathname}?preregister=complete#preregister`,
    watch_models: watchModels.join(','),
    feedback,
  });
  return `${XFOR_PREREGISTER_START_URL}?${params.toString()}`;
}

function startGoogleSignIn() {
  renderBusy('Redirecting to Google sign-in…');
  track('clawwatch_preregister_start');
  saveDraftState();
  setPendingPreregistration(true);
  window.location.assign(buildSharedAuthRedirectUrl());
}

function init() {
  preregisterButton?.addEventListener('click', startGoogleSignIn);
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

  const url = new URL(window.location.href);
  const preregStatus = url.searchParams.get('preregister');

  if (preregStatus === 'complete') {
    if (savedDraft) {
      applyDraftState(savedDraft);
    }
    clearDraftState();
    setPendingPreregistration(false);
    renderReady();
    setBanner("Thank you for your interest! We'll be back when the easy-to-install ClawWatch is here.", 'success');
    showThankYouModal();
  } else if (preregStatus === 'error') {
    renderReady();
    setPendingPreregistration(false);
    setBanner('Google sign-in finished, but ClawWatch preregistration was not stored. Please try again.', 'error');
  } else {
    renderReady();
  }

  if (preregStatus) {
    const cleanUrl = `${window.location.origin}${window.location.pathname}${window.location.hash || ''}`;
    window.history.replaceState({}, document.title, cleanUrl);
  }
}

init();
