// ============================================================
// Authentifizierung / Token
// ============================================================
const token = localStorage.getItem("token");

// Ohne gültigen Token direkt zur Login-Seite weiterleiten.
if (!token) {
  window.location.href = "/pages/login.html";
}

// Nutzdaten (Payload), die im JWT enthalten sind.
type JwtPayload = {
  sub?: string;   // Benutzername
  role?: string;  // Rolle, z. B. "ADMIN"
};

/**
 * Dekodiert den Payload-Teil eines JWTs (Base64Url) ohne Signaturprüfung.
 * Wird nur für die Anzeige genutzt (Benutzername/Rolle), nicht zur Autorisierung.
 */
function decodeJwtPayload(jwt: string): JwtPayload | null {
  try {
    const payloadBase64 = jwt.split(".")[1];
    if (!payloadBase64) return null;

    const json = atob(payloadBase64.replace(/-/g, "+").replace(/_/g, "/"));
    return JSON.parse(json) as JwtPayload;
  } catch {
    return null;
  }
}

// ============================================================
// DOM-Elemente referenzieren
// ============================================================
const usernameLabel = document.querySelector<HTMLElement>("#current-username");
const roleLabel = document.querySelector<HTMLElement>("#current-role");
const managementButton = document.querySelector<HTMLButtonElement>("#admin-management-button");
const resetButton = document.querySelector<HTMLButtonElement>("#reset-button");

// Admin-Aktion: Spiel/Lobby zurücksetzen.
resetButton?.addEventListener("click", () => {
  lobbySocket?.send(JSON.stringify({type: "reset-game"}))
})

// Token auslesen und Benutzername/Rolle anzeigen.
// Admin-spezifische Buttons (Verwaltung, Reset) werden nur für Admins eingeblendet.
if (token) {
  const payload = decodeJwtPayload(token);
  if (usernameLabel) usernameLabel.textContent = payload?.sub ?? "Unbekannt";
  if (roleLabel) roleLabel.textContent = payload?.role ?? "";

  if(managementButton){
    managementButton.hidden = payload?.role !== "ADMIN";
  }
  if(resetButton){
    resetButton.hidden = payload?.role !== "ADMIN";
  }
}

// ============================================================
// Logout
// ============================================================
const logoutButton = document.querySelector<HTMLButtonElement>("#logout-button");

// Token entfernen und zurück zur Login-Seite.
logoutButton?.addEventListener("click", () => {
  localStorage.removeItem("token");
  window.location.href = "/pages/login.html";
});

// ============================================================
// Lobby
// ============================================================

// Ein Spieler in der Lobby-Liste mit seinem aktuellen Status.
type LobbyPlayer = {
  username: string;
  role: string;
  status: "IDLE" | "PENDING" | "READY" | "DECLINED";
};

const lobbyList = document.querySelector<HTMLUListElement>("#lobby-list");
const lobbySubtitle = document.querySelector<HTMLElement>("#lobby-subtitle");
const startGameButton = document.querySelector<HTMLButtonElement>("#start-game-button");

// Startet ein neues Spiel und sperrt den Button, bis eine Antwort vom Server kommt.
startGameButton?.addEventListener("click", () => {
  lobbySocket?.send(JSON.stringify({type: "start-game"}))
  if (startGameButton) startGameButton.disabled = true;
});

/**
 * Übersetzt den Lobby-Status eines Spielers in einen deutschen Anzeigetext.
 * Gibt null zurück, wenn für diesen Status kein Badge angezeigt werden soll (z. B. "IDLE").
 */
function statusLabel(status: LobbyPlayer["status"]): string | null {
  switch(status){
    case "PENDING": return "ausstehend";
    case "DECLINED": return "abgelehnt";
    case "READY": return "bereit";
    default: return null;
  }
}

// Merkt sich die zuletzt bekannte Spieleranzahl, u. a. für den Start-Button-Zustand.
let lastPlayerCount = 0;

/**
 * Baut die Lobby-Liste anhand der aktuellen Spielerliste neu auf.
 * Zeigt pro Spieler ein Badge mit Status ("bereit", "ausstehend", ...)
 * oder – falls bereits ein Spiel läuft – "spielt" bzw. "du" (für den eigenen Eintrag).
 */
function renderLobby(players: LobbyPlayer[], playingUsernames: string[]): void {
  if (!lobbyList) return;
  lastPlayerCount = players.length;
  lobbyList.replaceChildren();

  if (lobbySubtitle) {
    lobbySubtitle.textContent = `Aktuell angemeldete Benutzer (${players.length})`;
  }

  // Keine anderen Spieler online: Hinweistext anzeigen und abbrechen.
  if (players.length === 0) {
    const emptyState = document.createElement("li");
    emptyState.className = "list-group-item text-body-secondary";
    emptyState.textContent = "Niemand sonst online.";
    lobbyList.appendChild(emptyState);
    return;
  }

   const currentUsername = usernameLabel?.textContent ?? "";

  // Für jeden Spieler einen Listeneintrag mit Namen und optionalem Status-Badge erzeugen.
  for (const player of players) {
    const item = document.createElement("li");
    item.className = "list-group-item d-flex justify-content-between align-items-center";

    const nameSpan = document.createElement("span");
    nameSpan.textContent = player.username;
    item.appendChild(nameSpan);

     // Wenn der Spieler gerade in einem laufenden Spiel ist, hat das Vorrang
     // vor dem normalen Lobby-Status ("spielt" bzw. "du" für sich selbst).
     let badgeText: string | null = null;
     if(playingUsernames.includes(player.username)){
      badgeText = player.username === currentUsername ? "du" : "spielt";
     }else{
      badgeText = statusLabel(player.status);
     }


    if(badgeText){
      const badge = document.createElement("span")
      badge.className = "badge text-bg-light text-body-secondary";
      badge.textContent = badgeText;
      item.appendChild(badge);
    }
    lobbyList.appendChild(item);
  }

  // "Spiel starten" ist erst ab 2 Spielern möglich.
  if (startGameButton) {
    startGameButton.disabled = players.length < 2;
  }
}

let lobbySocket: WebSocket | null = null;

/**
 * Baut die WebSocket-Verbindung zur Lobby auf und registriert die
 * Nachrichten-Verarbeitung für Lobby-Updates, Spieleinladungen usw.
 */
function connectLobby(): void {
  if (!token) return;

  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const socket = new WebSocket(
    `${protocol}//${window.location.host}/ws/lobby?token=${encodeURIComponent(token)}`
  );
  lobbySocket = socket;

  // Payload einer "lobby-update"-Nachricht vom Server.
  type LobbyUpdatePayload = {
    players: LobbyPlayer[];
    gameInProgress: boolean;
    playingUsernames: string[];
  }

  socket.addEventListener("message", (event) => {
    try {
      const message = JSON.parse(String(event.data)) as {type: string; payload: unknown};

      if (message.type === "lobby-update") {
        // Regelmäßiges Update: Spielerliste und Spielstatus neu rendern.
        const payload = message.payload as LobbyUpdatePayload;
        renderLobby(payload.players, payload.playingUsernames);
        updateGameInProgressUI(payload.gameInProgress, payload.playingUsernames);
      } else if(message.type === "game-invite"){
        // Ein anderer Spieler hat ein Spiel gestartet -> Einladung anzeigen.
        showInvite(message.payload as {initiator : string})
      } else if(message.type === "game-started"){
        // Spiel wurde bestätigt gestartet -> zur Spielseite wechseln.
        window.location.href = "/pages/game.html";
      } else if(message.type === "game-cancelled"){
        // Einladung wurde zurückgezogen/abgebrochen.
        hideInvite();
      }else if(message.type === "error"){
        // Serverseitiger Fehler, z. B. beim Starten des Spiels.
        console.error((message.payload as {message: string}).message);
        if (startGameButton) startGameButton.disabled = false;
      }
    } catch {
      // ungültige Nachricht ignorieren
    }
  });

  socket.addEventListener("close", () => {
    if (lobbySubtitle) lobbySubtitle.textContent = "Verbindung zur Lobby getrennt.";
  });
}

// Admin-Button: navigiert zur Benutzerverwaltung.
managementButton?.addEventListener("click", () => {
  window.location.href = "/pages/user-management.html";
})

// ============================================================
// Spiel-Einladung (Invite)
// ============================================================
const gameInviteBox = document.querySelector<HTMLDivElement>("#game-invite");
const inviteMessage = document.querySelector<HTMLParagraphElement>("#invite-message");
const readyButton = document.querySelector<HTMLButtonElement>("#ready-button");
const declineButton = document.querySelector<HTMLButtonElement>("#decline-button");


/**
 * Zeigt die Einladungsbox an, wenn ein anderer Spieler ein Spiel gestartet hat.
 */
function showInvite(payload: {initiator: string}): void{
  if(gameInviteBox) gameInviteBox.hidden = false;
  if(inviteMessage) inviteMessage.textContent = `${payload.initiator} startet ein Spiel.`;
  if(startGameButton) startGameButton.disabled = true;
}



/**
 * Blendet die Einladungsbox wieder aus und aktiviert den Start-Button erneut.
 */
function hideInvite(): void{
  if(gameInviteBox) gameInviteBox.hidden = true;
  if(startGameButton) startGameButton.disabled = false;
}

// ============================================================
// Anzeige: laufendes Spiel
// ============================================================
const gameStatusBox = document.querySelector<HTMLDivElement>("#game-status");
const gameStatusDetail = document.querySelector<HTMLParagraphElement>("#game-status-detail");

/**
 * Aktualisiert die Anzeige, ob gerade ein Spiel läuft, und ob der aktuelle
 * Nutzer selbst daran teilnimmt. Deaktiviert außerdem den Start-Button,
 * solange ein Spiel läuft oder zu wenige Spieler online sind.
 */
function updateGameInProgressUI(gameInProgress: boolean, playingUsernames: string[]): void {
  if(startGameButton){
    startGameButton.disabled = gameInProgress || lastPlayerCount < 2;
  }
  if(!gameStatusBox) return;

  gameStatusBox.hidden = !gameInProgress;

  if (gameInProgress && gameStatusDetail) {
    const currentUsername = usernameLabel?.textContent ?? "";
    const isPlaying = playingUsernames.includes(currentUsername);
    gameStatusDetail.textContent = isPlaying
      ? "Du nimmst teil."
      : "Du nimmst nicht teil.";
  }

}


// Einladung annehmen: Server informieren und Einladungsbox ausblenden.
readyButton?.addEventListener("click", () => {
  lobbySocket?.send(JSON.stringify({type: "respond", payload: {accepted: true}}))
  hideInvite();
})

// Einladung ablehnen: Server informieren und Einladungsbox ausblenden.
declineButton?.addEventListener("click", () => {
  lobbySocket?.send(JSON.stringify({type: "respond", payload: {accepted: false}}))
  hideInvite();
})

// ============================================================
// Highscore
// ============================================================
type HighscoreEntry = { username: string; score: number};

/**
 * Lädt die Highscore-Liste vom Server und rendert sie als Tabelle.
 * Zeigt bei Fehlern bzw. leerer Liste einen entsprechenden Hinweistext an.
 */
async function loadHighscore(): Promise<void> {
  const highScoreBody = document.querySelector<HTMLTableSectionElement>("#highscore-body");
  if(!highScoreBody || !token) return;

  try{
    const respose = await fetch("/api/highscore", {
      headers: { "Authorization": `Bearer ${token}` },
    });

    if (!respose.ok) {
      highScoreBody.replaceChildren();
      const row = document.createElement("tr");
      row.innerHTML = `<td colspan="3" class="text-body-secondary">Highscore konnte nicht geladen werden.</td>`;
      highScoreBody.appendChild(row);
      return;
    }

    const entries = await respose.json() as HighscoreEntry[];

    highScoreBody.replaceChildren();

    if (entries.length === 0) {
      const row = document.createElement("tr");
      row.innerHTML = `<td colspan="3" class="text-body-secondary">Noch keine Einträge.</td>`;
      highScoreBody.appendChild(row);
      return;
    }

    // Einträge sind bereits sortiert vom Server -> Platzierung ist einfach der Index + 1.
   entries.forEach((entry, index) => {
  const row = document.createElement("tr");
  for (const text of [String(index + 1), entry.username, String(entry.score)]) {
    const cell = document.createElement("td");
    cell.textContent = text;
    row.appendChild(cell);
  }
  highScoreBody.appendChild(row);
});
  } catch {
    highScoreBody.replaceChildren();
    const row = document.createElement("tr");
    row.innerHTML = `<td colspan="3" class="text-body-secondary">Highscore konnte nicht geladen werden.</td>`;
    highScoreBody.appendChild(row);
  }
}

// ============================================================
// Initialisierung
// ============================================================
loadHighscore();
connectLobby();