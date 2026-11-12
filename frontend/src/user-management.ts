import { UserWebSocketClient, type User, type UserEvent } from "./user-websocket";

// ============================================================
// DOM-Elemente referenzieren
// ============================================================
const statusBadge = document.querySelector<HTMLSpanElement>("#websocket-status");
const errorBox = document.querySelector<HTMLDivElement>("#user-error");
const tableBody = document.querySelector<HTMLTableSectionElement>("#user-table-body");

const form = document.querySelector<HTMLFormElement>("#user-form");
const usernameInput = document.querySelector<HTMLInputElement>("#new-username");
const passwordInput = document.querySelector<HTMLInputElement>("#new-password");
const roleSelect = document.querySelector<HTMLSelectElement>("#new-role");

// Lokaler Cache aller Benutzer, indiziert nach ihrer ID.
// Wird bei jedem WebSocket-Event ("created"/"updated"/"deleted") aktuell gehalten.
const users = new Map<number, User>();

/**
 * Zeigt eine Fehlermeldung im dafür vorgesehenen Bereich an.
 */
function showError(message: string): void {
  if (!errorBox) return;
  errorBox.textContent = message;
  errorBox.hidden = false;
}

/**
 * Blendet die aktuell angezeigte Fehlermeldung wieder aus.
 */
function clearError(): void {
  if (!errorBox) return;
  errorBox.textContent = "";
  errorBox.hidden = true;
}

// ============================================================
// WebSocket-Client für Live-Updates der Benutzerliste
// ============================================================
const client = new UserWebSocketClient({
  // Wird bei jedem eingehenden Benutzer-Event (Erstellung/Änderung/Löschung) aufgerufen.
  onEvent: handleEvent,
  // Aktualisiert das Status-Badge (z. B. "Verbunden" / "Getrennt") inkl. Bootstrap-Styling.
  onStatusChange: (text, bootstrapClass) => {
    if (!statusBadge) return;
    statusBadge.textContent = text;
    statusBadge.className = `badge ${bootstrapClass}`;
  },
  // Wird aufgerufen, wenn eine Nachricht vom Server nicht verarbeitet werden konnte.
  onInvalidMessage: () => {
    showError("Der Server hat eine ungültige Antwort gesendet.");
  },
});

/**
 * Verarbeitet ein einzelnes Benutzer-Event vom Server:
 * bei "deleted" wird der Benutzer aus dem lokalen Cache entfernt,
 * ansonsten wird er dort erstellt/aktualisiert. Danach wird die Tabelle neu gerendert.
 */
function handleEvent(event: UserEvent): void {
  if (event.type === "deleted") {
    users.delete(event.id);
  } else {
    users.set(event.user.id, event.user);
  }
  renderTable();
}

/**
 * Baut die Benutzertabelle komplett neu auf Basis des lokalen Caches (users) auf.
 * Sortiert alphabetisch nach Benutzername. Jede Zeile enthält ein Rollen-Dropdown
 * (löst bei Änderung sofort ein Update aus) und einen Löschen-Button.
 */
function renderTable(): void {
  if (!tableBody) return;
  tableBody.innerHTML = "";

  const sorted = [...users.values()].sort((a, b) => a.username.localeCompare(b.username));

  for (const user of sorted) {
    const row = document.createElement("tr");

    const usernameCell = document.createElement("td");
    usernameCell.textContent = user.username;

    // Rollen-Dropdown mit den zwei verfügbaren Rollen.
    const roleCell = document.createElement("td");
    const roleSelectEl = document.createElement("select");
    roleSelectEl.className = "form-select form-select-sm";
    roleSelectEl.style.maxWidth = "10rem";
    for (const role of ["USER", "ADMIN"]) {
      const option = document.createElement("option");
      option.value = role;
      option.textContent = role;
      option.selected = role === user.role;
      roleSelectEl.appendChild(option);
    }
    // Bei Auswahländerung wird die Rolle direkt am Server aktualisiert.
    roleSelectEl.addEventListener("change", () => {
      void updateRole(user, roleSelectEl.value);
    });
    roleCell.appendChild(roleSelectEl);

    // Löschen-Button für den jeweiligen Benutzer.
    const actionsCell = document.createElement("td");
    actionsCell.className = "text-end";
    const deleteButton = document.createElement("button");
    deleteButton.className = "btn btn-outline-danger btn-sm";
    deleteButton.textContent = "Löschen";
    deleteButton.addEventListener("click", () => {
      void deleteUser(user);
    });
    actionsCell.appendChild(deleteButton);

    row.append(usernameCell, roleCell, actionsCell);
    tableBody.appendChild(row);
  }
}

/**
 * Lädt einmalig alle Benutzer vom Server (per WebSocket-Kommando "find-all")
 * und befüllt damit den lokalen Cache sowie die Tabelle.
 */
async function loadUsers(): Promise<void> {
  try {
    const payload = await client.sendCommand("find-all");
    if (!Array.isArray(payload)) throw new Error("Ungültige Antwort vom Server.");

    users.clear();
    for (const entry of payload) {
      // Grobe Typprüfung: nur Objekte mit numerischer "id" werden übernommen.
      if (
        typeof entry === "object" &&
        entry !== null &&
        typeof (entry as User).id === "number"
      ) {
        const user = entry as User;
        users.set(user.id, user);
      }
    }
    renderTable();
  } catch (error) {
    showError(error instanceof Error ? error.message : "Benutzer konnten nicht geladen werden.");
  }
}

/**
 * Ändert die Rolle eines Benutzers am Server. Schlägt die Aktualisierung fehl,
 * wird die Tabelle neu gerendert, damit das Dropdown wieder den tatsächlichen
 * (unveränderten) Wert zeigt, statt die fehlgeschlagene Auswahl zu behalten.
 */
async function updateRole(user: User, role: string): Promise<void> {
  clearError();
  try {
    await client.sendCommand("update", { id: user.id, username: user.username, role });
  } catch (error) {
    showError(error instanceof Error ? error.message : "Rolle konnte nicht geändert werden.");
    renderTable(); // Auswahl auf den zuletzt bekannten Stand zurücksetzen
  }
}

/**
 * Löscht einen Benutzer, nachdem der Nutzer dies in einem Bestätigungsdialog bestätigt hat.
 */
async function deleteUser(user: User): Promise<void> {
  clearError();
  if (!window.confirm(`Benutzer "${user.username}" wirklich löschen?`)) return;

  try {
    await client.sendCommand("delete", { id: user.id });
  } catch (error) {
    showError(error instanceof Error ? error.message : "Benutzer konnte nicht gelöscht werden.");
  }
}

// Formular zum Anlegen eines neuen Benutzers.
form?.addEventListener("submit", async (event) => {
  event.preventDefault();
  clearError();

  const username = usernameInput?.value.trim() ?? "";
  const password = passwordInput?.value ?? "";
  const role = roleSelect?.value ?? "USER";

  try {
    await client.sendCommand("create", { username, password, role });
    form.reset();
  } catch (error) {
    showError(error instanceof Error ? error.message : "Benutzer konnte nicht angelegt werden.");
  }
});

// ============================================================
// Initialisierung
// ============================================================

// Weiterleitung zur Login-Seite, falls überhaupt kein Token vorhanden ist — die
// eigentliche ADMIN-Prüfung erfolgt serverseitig, das hier ist nur eine schnelle
// UX-Absicherung gegen einen von vornherein sinnlosen Verbindungsversuch.
if (!localStorage.getItem("token")) {
  window.location.href = "/pages/login.html";
} else {
  void loadUsers();
}