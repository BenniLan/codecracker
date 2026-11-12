import { registerUser, loginUser } from "./auth-api";

const form = document.querySelector<HTMLFormElement>("#auth-form");
const usernameInput = document.querySelector<HTMLInputElement>("#username");
const passwordInput = document.querySelector<HTMLInputElement>("#password");
const submitButton = document.querySelector<HTMLInputElement>("#submit-button");
const formTitle = document.querySelector<HTMLHeadingElement>("#form-title");
const errorBox = document.querySelector<HTMLDivElement>("#auth-error");
const toggleLink = document.querySelector<HTMLDivElement>("#toggle-mode");

let mode: "login" | "register" = "login";

function showError(message: string): void {
  if (!errorBox) return;
  errorBox.textContent = message;
  errorBox.hidden = false;
}

function clearError(): void {
  if (!errorBox) return;
  errorBox.textContent = "";
  errorBox.hidden = true;
}

function updateModeUI(): void {
    if (submitButton) submitButton.textContent = mode === "login" ? "Einloggen" : "Registrieren";
    if (formTitle) formTitle.textContent = mode === "login" ? "Einloggen" : "Registrieren";
    if(toggleLink){
      toggleLink.textContent = mode === "login" ? "Noch keinen Account? Registrieren" : "Bereits registriert? Zum Login";
    }
    clearError();
}

toggleLink?.addEventListener("click", (event) => {
  event.preventDefault();
  mode = mode === "login" ? "register" : "login";
  updateModeUI();
});

form?.addEventListener("submit", async (event) => {
  event.preventDefault();
  clearError();

  const username = usernameInput?.value.trim() ?? "";
  const password = passwordInput?.value.trim() ?? "";

  try {
    if (mode === "register") {
      await registerUser(username, password);
      mode = "login";
      updateModeUI();
      showError("Registrierung erfolgreich. Bitte jetzt einloggen");
      return;
    }

    const result = await loginUser(username, password);
    localStorage.setItem("token", result.token);
    window.location.href = "/pages/dashboard.html";
  } catch (error) {
    showError(error instanceof Error ? error.message : "Unbekannter Fehler");
  }
});