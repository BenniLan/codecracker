// ============================================================
// DOM-Elemente referenzieren
// ============================================================
const token = localStorage.getItem("token");
const abortGame = document.querySelector<HTMLButtonElement>("#abort-game-button");
const dialog = document.querySelector<HTMLDialogElement>("#confirm-dialog");
const abortButton = document.querySelector<HTMLButtonElement>("#dialog-abort-button");
const backButton = document.querySelector<HTMLButtonElement>("#dialog-back-button");
const resetButton = document.querySelector<HTMLButtonElement>("#reset-button");

const roundLabel = document.querySelector<HTMLHeadingElement>("#round-label");
const timeRemainingLabel = document.querySelector<HTMLSpanElement>("#time-remaining");
const errorBox = document.querySelector<HTMLDivElement>("#game-error");
const historyList = document.querySelector<HTMLUListElement>("#code-history-list");

const gameOverDialog = document.querySelector<HTMLDialogElement>("#game-over-dialog");
const gameOverIcon = document.querySelector<HTMLDivElement>("#game-over-icon");
const gameOverSubtitle = document.querySelector<HTMLParagraphElement>("#game-over-subtitle");

const gameOverTableBody = document.querySelector<HTMLTableSectionElement>("#game-over-table-body");
const gameOverCloseButton = document.querySelector<HTMLButtonElement>("#game-over-close-button");

// Schließt den "Spiel vorbei"-Dialog und leitet zurück zum Dashboard.
gameOverCloseButton?.addEventListener("click", () => {
  gameOverDialog?.close();
  window.location.href = "/pages/dashboard.html";
});

// Gibt an, ob das Spiel gerade aktiv läuft (steuert z. B. den "beforeunload"-Hinweis).
let gameActive = true;

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
// Bestätigungsdialog zum Abbrechen des Spiels
// ============================================================

// Öffnet den Bestätigungsdialog, wenn der Nutzer das Spiel abbrechen möchte.
abortGame?.addEventListener("click", () => {
  dialog?.showModal();
});

// Nutzer bestätigt den Abbruch: Server wird informiert (falls verbunden)
// und anschließend geht es zurück zum Dashboard.
abortButton?.addEventListener("click", () => {
  dialog?.close();
  gameActive = false;

  if (gameSocket?.readyState === WebSocket.OPEN) {
    gameSocket.send(JSON.stringify({ type: "abort-game", payload: {} }));

    // Kurze Verzögerung, damit die Nachricht noch gesendet werden kann,
    // bevor die Seite verlassen wird.
    window.setTimeout(() => {
      window.location.href = "/pages/dashboard.html";
    }, 150);
  } else {
    window.location.href = "/pages/dashboard.html";
  }
});

// Nutzer bricht den Abbruch-Dialog ab (zurück zum Spiel).
backButton?.addEventListener("click", () => {
  dialog?.close();
});

// ============================================================
// Farbauswahl / Code-Eingabe
// ============================================================
const colorButtons = document.querySelectorAll<HTMLButtonElement>(".color-swatch");
const positionSlots = document.querySelectorAll<HTMLDivElement>(".position-slot");
const confirmCodeButton = document.querySelector<HTMLButtonElement>("#confirm-code-button");

const CODE_LENGTH = 4;
// Aktuell ausgewählte Farben pro Position (null = noch nicht belegt).
const selectedColors: (string | null)[] = new Array(CODE_LENGTH).fill(null);
let hasSubmittedThisRound = false;
let lastSubmittedGuess: string[] | null = null;

/**
 * Liefert den Index der ersten noch leeren (nicht belegten) Position.
 * Gibt -1 zurück, wenn bereits alle Positionen belegt sind.
 */
function nextEmptyIndex(): number {
  return selectedColors.findIndex((color) => color === null);
}

/**
 * Aktualisiert die visuelle Darstellung der Positions-Slots
 * anhand von selectedColors und (de)aktiviert den Bestätigen-Button.
 */
function renderPosition(): void {
  positionSlots.forEach((slot, index) => {
    const color = selectedColors[index];

    if (color) {
      slot.style.backgroundColor = colorHex(color);
      slot.classList.add("filled");
    } else {
      slot.style.backgroundColor = "";
      slot.classList.remove("filled");
    }
  });

  const allFilled = selectedColors.every((color) => color !== null);
  if (confirmCodeButton) confirmCodeButton.disabled = !allFilled || hasSubmittedThisRound;
}

/**
 * Setzt alle ausgewählten Farben zurück (z. B. per Reset-Button).
 */
function clearColors(): void {
  positionSlots.forEach((slot, index) => {
    selectedColors[index] = null;
    slot.style.backgroundColor = "";
    slot.classList.remove("filled");
  });
  if (confirmCodeButton) confirmCodeButton.disabled = true;
}

/**
 * Übersetzt einen Farbnamen (vom Server/Spiellogik) in einen Hex-Farbwert für die Anzeige.
 */
function colorHex(color: string): string {
  switch (color) {
    case "RED": return "#c0392b";
    case "GREEN": return "#1e7d43";
    case "BLUE": return "#2f6fd1";
    case "YELLOW": return "#f0b400";
    default: return "";
  }
}

// Klick auf einen Farb-Button: Farbe wird an die nächste freie Position gesetzt.
colorButtons.forEach((button) => {
  button.addEventListener("click", () => {
    if (hasSubmittedThisRound) return;
    const index = nextEmptyIndex();
    if (index === -1) return; // Alle belegt

    const color = button.dataset.color;
    if (!color) return;

    selectedColors[index] = color;
    renderPosition();
  });
});

// Reset-Button: setzt die aktuelle Auswahl zurück (nur solange noch nicht abgeschickt).
resetButton?.addEventListener("click", () => {
  if (hasSubmittedThisRound) return;
  clearColors();
});

// Warnt den Nutzer vor dem Verlassen der Seite, solange das Spiel noch aktiv ist.
window.addEventListener("beforeunload", (event) => {
  if (gameActive) {
    event.preventDefault();
  }
});

// Lädt die Seite neu, falls sie aus dem Browser-Cache (bfcache) wiederhergestellt wird,
// damit der Spielzustand nicht veraltet/inkonsistent angezeigt wird.
window.addEventListener("pageshow", (event) => {
  if (event.persisted) {
    window.location.reload();
  }
});

// ============================================================
// Runden-Countdown
// ============================================================
let countdownInterval: number | null = null;
let roundDeadline = 0;

/**
 * Startet den Countdown für die aktuelle Runde.
 * @param remainingMs Verbleibende Zeit in Millisekunden bis zum automatischen Rundenende.
 */
function startCountdown(remainingMs: number): void {
  roundDeadline = Date.now() + remainingMs;
  if (countdownInterval !== null) window.clearInterval(countdownInterval);
  updateCountdownLabel();
  countdownInterval = window.setInterval(updateCountdownLabel, 250);
}

/**
 * Aktualisiert die Anzeige der verbleibenden Zeit und löst bei Ablauf
 * automatisch das Absenden des aktuellen Codes aus (falls vollständig).
 */
function updateCountdownLabel(): void {
  const remainingMs = Math.max(0, roundDeadline - Date.now());
  const remainingSeconds = Math.ceil(remainingMs / 1000);
  if (timeRemainingLabel) {
    timeRemainingLabel.textContent = `Verbleibende Zeit: ${remainingSeconds} s`;
  }

  if (remainingMs <= 0) {
    if (countdownInterval !== null) window.clearInterval(countdownInterval);
    autoSubmitIfComplete();
  }
}

/**
 * Wird aufgerufen, wenn die Rundenzeit abgelaufen ist.
 * Sendet den Code automatisch ab, sofern der Nutzer bereits alle Positionen befüllt hat.
 */
function autoSubmitIfComplete(): void {
  if (hasSubmittedThisRound) return;
  const allFilled = selectedColors.every((color) => color !== null);
  if (allFilled) submitGuess();
}

// ============================================================
// WebSocket-Verbindung zum Spiel-Server
// ============================================================
let gameSocket: WebSocket | null = null;

/**
 * Baut die WebSocket-Verbindung zum Spiel auf.
 * Ohne gültigen Token wird der Nutzer zur Login-Seite weitergeleitet.
 */
function connectGame(): void {
  if (!token) {
    window.location.href = "/pages/login.html";
    return;
  }

  // wss:// bei HTTPS, sonst ws:// (für lokale Entwicklung ohne SSL).
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const socket = new WebSocket(
    `${protocol}//${window.location.host}/ws/game?token=${encodeURIComponent(token)}`
  );
  gameSocket = socket;

  socket.addEventListener("message", (event) => {
    handleMessage(String(event.data));
  });

  socket.addEventListener("close", () => {
    if (gameActive) showError("Verbindung zum Spiel getrennt.");
  });

  socket.addEventListener("error", () => {
    showError("WebSocket-Verbindung konnte nicht hergestellt werden.");
  });
}

/**
 * Verarbeitet eingehende Nachrichten vom Server und leitet sie
 * je nach Typ an die passende Handler-Funktion weiter.
 */
function handleMessage(messageText: string): void {
  try {
    const message = JSON.parse(messageText) as { type: string; payload: unknown };

    switch (message.type) {
      case "round-start":
        // Neue Runde beginnt.
        handleRoundStart(message.payload as {
          round: number; maxRounds: number; codeLength: number; colors: string[]; remainingMs: number;
        });
        break;
      case "round-result":
        // Ergebnis der zuletzt abgeschickten Rate-Runde.
        handleRoundResult(message.payload as {
          round: number; submitted: boolean; black?: number; white?: number; exact?: boolean;
        });
        break;
      case "round-advance":
        // Es geht direkt in die nächste Runde über (ohne separates Ergebnis-Event).
        handleRoundAdvance(message.payload as { round: number; maxRounds: number; remainingMs: number });
        break;
      case "game-over":
        // Spiel ist beendet, Endergebnis anzeigen.
        handleGameOver(message.payload as GameOverPayload);
        break;
      case "game-aborted":
        // Spiel wurde (von wem auch immer) abgebrochen.
        gameActive = false;
        window.location.href = "/pages/dashboard.html";
        break;
      case "error":
        // Serverseitiger Fehler, z. B. ungültige Eingabe.
        showError((message.payload as { message: string }).message);
        break;
    }
  } catch {
    showError("Der Server hat eine ungültige Antwort gesendet.");
  }
}

/**
 * Behandelt den Start einer neuen Runde: Anzeige aktualisieren,
 * Eingabe zurücksetzen und Countdown neu starten.
 */
function handleRoundStart(payload: { round: number; maxRounds: number; remainingMs: number }): void {
  clearError();
  if (roundLabel) roundLabel.textContent = `Runde ${payload.round} von ${payload.maxRounds}`;
  hasSubmittedThisRound = false;
  clearColors();
  startCountdown(payload.remainingMs);
}

/**
 * Fügt das Ergebnis der letzten Rate-Runde (schwarze/weiße Stifte) der Historie hinzu.
 */
function handleRoundResult(payload: {
  round: number; submitted: boolean; black?: number; white?: number; exact?: boolean;
}): void {
  if (!payload.submitted || !lastSubmittedGuess || !historyList) return;

  const item = document.createElement("li");
  item.className = "list-group-item";

  const roundLabelEl = document.createElement("div");
  roundLabelEl.className = "fw-semibold small mb-2";
  roundLabelEl.textContent = `Runde ${payload.round}`;
  item.appendChild(roundLabelEl);

  const row = document.createElement("div");
  row.className = "d-flex justify-content-between align-items-center";

  // Geratener Code als farbige Punkte, analog zu den position-slots im Spielfeld.
  const codeRow = document.createElement("div");
  codeRow.className = "d-flex gap-2";
  lastSubmittedGuess.forEach((color) => {
    const dot = document.createElement("span");
    dot.className = "history-color-dot";
    dot.style.backgroundColor = colorHex(color);
    codeRow.appendChild(dot);
  });

  // Feedback als Pegs statt Text: gefüllter Punkt = richtige Farbe+Position,
  // hohler Punkt = richtige Farbe, falsche Position (siehe Legende in game.html).
  const feedback = document.createElement("div");
  feedback.className = "d-flex flex-column align-items-end small text-body-secondary";

  const blackRow = document.createElement("span");
  const blackDot = document.createElement("span");
  blackDot.className = "feedback-dot feedback-dot--filled";
  blackRow.append(blackDot, document.createTextNode(` ${payload.black ?? 0}`));

  const whiteRow = document.createElement("span");
  const whiteDot = document.createElement("span");
  whiteDot.className = "feedback-dot";
  whiteRow.append(whiteDot, document.createTextNode(` ${payload.white ?? 0}`));

  feedback.append(blackRow, whiteRow);

  row.append(codeRow, feedback);
  item.appendChild(row);

  historyList.prepend(item);
}

/**
 * Behandelt den Übergang zur nächsten Runde (analog zu handleRoundStart).
 */
function handleRoundAdvance(payload: { round: number; maxRounds: number; remainingMs: number }): void {
  clearError();
  if (roundLabel) roundLabel.textContent = `Runde ${payload.round} von ${payload.maxRounds}`;
  hasSubmittedThisRound = false;
  clearColors();
  startCountdown(payload.remainingMs);
}

// Zusammenfassung eines einzelnen Spielers am Spielende.
type PlayerSummary = {
  username: string;
  result: "WIN" | "DRAW" | "LOSE";
  points: number;
  totalPoints: number | null;
};

// Daten, die der Server beim Spielende ("game-over") mitschickt.
type GameOverPayload = {
  result: "WIN" | "DRAW" | "LOSE";
  points: number;
  round: number;
  winners: string[];
  secretCode: string[];
  players: PlayerSummary[];
};

/**
 * Übersetzt das Ergebnis eines Spielers in einen deutschen Anzeigetext.
 */
function resultLabel(result: PlayerSummary["result"]): string {
  switch (result) {
    case "WIN": return "Gewonnen";
    case "DRAW": return "Unentschieden";
    case "LOSE": return "Verloren";
  }
}

/**
 * Formatiert Punkte mit Vorzeichen (z. B. "+3" oder "-1").
 */
function pointsLabel(points: number): string {
  return points > 0 ? `+${points}` : `${points}`;
}

/**
 * Zeigt den "Spiel vorbei"-Dialog mit Ergebnis-Übersicht aller Spieler an
 * und deaktiviert die Spiel-Bedienelemente.
 */
function handleGameOver(payload: GameOverPayload): void {
  gameActive = false;
  if (countdownInterval !== null) window.clearInterval(countdownInterval);
  clearColors();
  if (confirmCodeButton) confirmCodeButton.disabled = true;
  colorButtons.forEach((button) => (button.disabled = true));

  // Icon + Titelzeile: grüner Haken, sobald es mindestens einen Gewinner gab,
  // sonst ein neutrales Icon für "niemand hat den Code erraten".
  const hasWinner = payload.winners.length > 0;
  if (gameOverIcon) {
    gameOverIcon.className = hasWinner ? "game-over-icon game-over-icon--win" : "game-over-icon game-over-icon--none";
  }

  if (gameOverSubtitle) {
    gameOverSubtitle.textContent = !hasWinner
      ? "Niemand hat den Code erraten"
      : payload.winners.length === 1
        ? `${payload.winners[0]} hat gewonnen`
        : `Unentschieden zwischen ${payload.winners.join(", ")}`;
  }

  // Ergebnistabelle für alle Spieler dynamisch aufbauen.
  if (gameOverTableBody) {
    gameOverTableBody.replaceChildren();
    payload.players.forEach((player) => {
      const row = document.createElement("tr");

      const nameCell = document.createElement("td");
      nameCell.textContent = player.username;

      const resultCell = document.createElement("td");
      resultCell.className = "fw-semibold";
      resultCell.textContent = resultLabel(player.result);

      const pointsCell = document.createElement("td");
      pointsCell.className = "text-end";
      pointsCell.textContent = pointsLabel(player.points);

      const totalCell = document.createElement("td");
      totalCell.className = "text-end";
      totalCell.textContent = player.totalPoints === null ? "—" : `${player.totalPoints}`;

      row.append(nameCell, resultCell, pointsCell, totalCell);
      gameOverTableBody.appendChild(row);
    });
  }

  gameOverDialog?.showModal();
}

/**
 * Sendet den aktuell ausgewählten Code über den WebSocket an den Server.
 * Bricht ab, wenn keine Verbindung besteht oder der Code noch unvollständig ist.
 */
function submitGuess(): void {
  if (!gameSocket || gameSocket.readyState !== WebSocket.OPEN) {
    showError("Keine Verbindung zum Spiel.");
    return;
  }

  const colors = selectedColors.filter((color): color is string => color !== null);
  if (colors.length !== CODE_LENGTH) return;

  lastSubmittedGuess = [...colors];
  hasSubmittedThisRound = true;
  if (confirmCodeButton) confirmCodeButton.disabled = true;

  gameSocket.send(JSON.stringify({
    type: "submit-guess",
    payload: { colors },
  }));
}

// Bestätigen-Button: schickt den aktuellen Code manuell ab.
confirmCodeButton?.addEventListener("click", () => {
  clearError();
  submitGuess();
});

// ============================================================
// Initialisierung
// ============================================================

/**
 * Prüft beim Laden der Seite, ob der Nutzer überhaupt an einem laufenden
 * Spiel teilnimmt. Falls nicht (oder kein gültiger Token vorhanden ist),
 * erfolgt eine Weiterleitung zum Dashboard. Andernfalls wird die
 * WebSocket-Verbindung aufgebaut.
 */
async function checkParticipants(): Promise<void> {
  if (!token) return;
  try {
    const response = await fetch("/api/game/participation", {
      headers: { "Authorization": `Bearer ${token}` },
    });
    if (!response.ok) {
      window.location.href = "/pages/dashboard.html";
      return;
    }
    const result = await response.json() as { isParticipant: boolean };
    if (!result.isParticipant) {
      window.location.href = "/pages/dashboard.html";
      return;
    }
    connectGame();
  } catch {
    window.location.href = "/pages/dashboard.html";
  }
}

checkParticipants();
renderPosition();