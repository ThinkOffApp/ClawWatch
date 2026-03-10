import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = "https://kvezyhwbkvpyndkaemsw.supabase.co";
const SUPABASE_ANON_KEY = "sb_publishable_MWtbxI1_Gm_yYTSxHofq2Q_z0nGwItH";

const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
  auth: {
    autoRefreshToken: true,
    detectSessionInUrl: true,
    persistSession: true,
    flowType: "pkce",
  },
});

const preregisterButton = document.getElementById("google-preregister-button");
const signOutButton = document.getElementById("google-signout-button");
const statusText = document.getElementById("preregister-status");
const resultBanner = document.getElementById("preregister-result");
const preregisterModal = document.getElementById("preregister-modal");
const preregisterModalClose = document.getElementById("preregister-modal-close");
const watchModelInputs = Array.from(document.querySelectorAll('input[name="watch-model"]'));
const feedbackInput = document.getElementById("preregister-feedback");

let hasShownThankYou = false;

function showThankYouModal() {
  if (!preregisterModal || hasShownThankYou) return;
  hasShownThankYou = true;
  preregisterModal.hidden = false;
  document.body.style.overflow = "hidden";
}

function closeThankYouModal() {
  if (!preregisterModal) return;
  preregisterModal.hidden = true;
  document.body.style.overflow = "";
}

function track(eventName, params = {}) {
  if (typeof window.gtag === "function") {
    window.gtag("event", eventName, params);
  }
}

function setBanner(message, tone = "info") {
  if (!resultBanner) return;
  resultBanner.hidden = false;
  resultBanner.dataset.tone = tone;
  resultBanner.textContent = message;
}

function clearBanner() {
  if (!resultBanner) return;
  resultBanner.hidden = true;
  resultBanner.textContent = "";
  delete resultBanner.dataset.tone;
}

function getFormState() {
  return {
    watchModels: watchModelInputs.filter((i) => i.checked).map((i) => i.value),
    feedback: feedbackInput?.value.trim() || "",
  };
}

function applyFormState(watchModels = [], feedback = "") {
  const selected = new Set(watchModels);
  watchModelInputs.forEach((i) => { i.checked = selected.has(i.value); });
  if (feedbackInput) feedbackInput.value = feedback;
}

function renderReady() {
  clearBanner();
  if (statusText) statusText.textContent = "Sign in with Google and we will mark your account for the future install-ready ClawWatch release.";
  if (preregisterButton) {
    preregisterButton.disabled = false;
    preregisterButton.innerHTML = '<span class="google-mark">G</span><span>Continue with Google</span>';
  }
  if (signOutButton) signOutButton.hidden = true;
}

function renderBusy(message) {
  clearBanner();
  if (statusText) statusText.textContent = message;
  if (preregisterButton) {
    preregisterButton.disabled = true;
    preregisterButton.innerHTML = '<span class="google-mark">\u2026</span><span>Working\u2026</span>';
  }
  if (signOutButton) signOutButton.hidden = true;
}

function renderRegistered(email) {
  if (statusText) statusText.textContent = "Registered as " + email + ". Update your details below any time.";
  if (preregisterButton) {
    preregisterButton.disabled = false;
    preregisterButton.innerHTML = '<span class="google-mark">\u2713</span><span>Update my details</span>';
  }
  if (signOutButton) signOutButton.hidden = false;
  setBanner("Thank you for your interest! We will notify you when the easy-install ClawWatch is ready.", "success");
}

async function savePreregistration(user) {
  const { watchModels, feedback } = getFormState();
  renderBusy("Saving your preregistration...");

  const row = {
    user_id: user.id,
    email: user.email,
    watch_models: watchModels,
    has_watch: watchModels.length > 0 && !watchModels.includes("no-watch-yet"),
    feedback: feedback,
    source: window.location.host,
    registered_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  };

  const { error } = await supabase.from("preregistrations").upsert(row, { onConflict: "user_id" });

  if (error) {
    console.error("preregistrations upsert failed:", error);
    setBanner("Sign-in worked but saving failed: " + error.message, "error");
    renderReady();
    return;
  }

  track("clawwatch_preregister_complete");
  applyFormState(watchModels, feedback);
  renderRegistered(user.email || "your account");
  showThankYouModal();
}

async function startGoogleSignIn() {
  clearBanner();
  renderBusy("Redirecting to Google sign-in...");
  track("clawwatch_preregister_start");

  const redirectTo = window.location.origin + window.location.pathname + "?preregister=complete#preregister";
  const { data, error } = await supabase.auth.signInWithOAuth({
    provider: "google",
    options: { redirectTo: redirectTo, queryParams: { prompt: "select_account" } },
  });

  if (error) {
    renderReady();
    setBanner(error.message, "error");
  }
}

async function signOut() {
  await supabase.auth.signOut();
  renderReady();
}

async function init() {
  preregisterButton?.addEventListener("click", async function() {
    const { data } = await supabase.auth.getSession();
    if (data.session?.user) {
      await savePreregistration(data.session.user);
    } else {
      await startGoogleSignIn();
    }
  });
  signOutButton?.addEventListener("click", signOut);
  preregisterModalClose?.addEventListener("click", closeThankYouModal);
  preregisterModal?.addEventListener("click", function(e) {
    if (e.target?.dataset?.closeModal === "true") closeThankYouModal();
  });

  const { data, error } = await supabase.auth.getSession();
  if (error) { setBanner(error.message, "error"); return; }

  const isReturning = new URLSearchParams(window.location.search).get("preregister") === "complete";

  if (data.session?.user) {
    if (isReturning) {
      await savePreregistration(data.session.user);
    } else {
      renderRegistered(data.session.user.email || "your account");
    }
  } else {
    renderReady();
  }

  if (isReturning) {
    var clean = window.location.origin + window.location.pathname + (window.location.hash || "");
    window.history.replaceState({}, document.title, clean);
  }

  supabase.auth.onAuthStateChange(async function(event, session) {
    if (event === "SIGNED_IN" && session?.user) {
      await savePreregistration(session.user);
    } else if (event === "SIGNED_OUT") {
      renderReady();
    }
  });
}

init().catch(function(err) {
  renderReady();
  setBanner(err.message || "Failed to initialize.", "error");
});
