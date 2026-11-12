// Repräsentiert einen Benutzer, wie er vom Server geliefert wird.
export type User = {
  id: number;
  username: string;
  role: string;
};

/**
 * Typ-Guard: prüft zur Laufzeit, ob ein beliebiger Wert die Form eines User-Objekts hat.
 * Wird u. a. genutzt, um Nachrichten vom Server sicher zu casten.
 */
export function isUser(value: unknown): value is User {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return typeof candidate.id === "number"
    && typeof candidate.username === "string"
    && typeof candidate.role === "string";
}

// Events, die der Server per WebSocket "unaufgefordert" (nicht als Antwort auf ein
// Kommando) an alle verbundenen Clients sendet, z. B. wenn ein anderer Admin
// gleichzeitig einen Benutzer anlegt, ändert oder löscht.
export type UserEvent =
  | {type: "created" | "updated"; user: User}
  | {type: "deleted"; id: number};

// Verwaltet ein einzelnes noch offenes Kommando, auf dessen Antwort gewartet wird.
type PendingRequest = {
  resolve: (payload: unknown) => void;
  reject: (error: Error) => void;
};

// Callbacks, die der Aufrufer beim Erstellen des Clients bereitstellen muss.
type UserWebSocketCallbacks = {
  onEvent: (event: UserEvent) => void;
  onStatusChange: (text: string, bootstrapClass: string) => void;
  onInvalidMessage: () => void;
};

/**
 * Verwaltet die WebSocket-Verbindung für die Benutzerverwaltung und ordnet
 * Antworten auf gesendete Kommandos anhand einer Request-ID der jeweiligen
 * Anfrage zu (Request/Response-Muster über eine einzige WebSocket-Verbindung).
 */
export class UserWebSocketClient {
  private readonly callbacks: UserWebSocketCallbacks;
  private socket: WebSocket | null = null;
  // Promise, das auflöst, sobald die Verbindung tatsächlich geöffnet ("open") ist.
  // Verhindert parallele Mehrfach-Verbindungsversuche, während der Verbindungsaufbau läuft.
  private socketReady: Promise<WebSocket> | null = null;
  // Fortlaufender Zähler zur Erzeugung eindeutiger Request-IDs.
  private nextRequestId = 1;
  // Alle Kommandos, auf deren Antwort vom Server noch gewartet wird (Key = requestId).
  private readonly pendingRequests = new Map<string, PendingRequest>();

  public constructor(callbacks: UserWebSocketCallbacks) {
    this.callbacks = callbacks;
  }

  /**
   * Öffnet die WebSocket-Verbindung, falls noch keine (offene) Verbindung besteht,
   * bzw. gibt die bereits bestehende/im Aufbau befindliche Verbindung zurück.
   */
   public connect(): Promise<WebSocket> {
  // Bereits verbunden -> direkt zurückgeben.
  if (this.socket?.readyState === WebSocket.OPEN) {
    return Promise.resolve(this.socket);
  }

  // Verbindungsaufbau läuft bereits -> auf dasselbe Promise warten,
  // statt eine zweite parallele Verbindung zu öffnen.
  if (this.socketReady) return this.socketReady;

  const token = localStorage.getItem("token");
  if (!token) {
    return Promise.reject(new Error("Kein Token vorhanden. Bitte erneut einloggen."));
  }

  this.callbacks.onStatusChange("WebSocket verbindet …", "text-bg-warning");
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const socket = new WebSocket(
    `${protocol}//${window.location.host}/ws/users?token=${encodeURIComponent(token)}`
  );
  this.socket = socket;



    // Eingehende Nachrichten (sowohl Kommando-Antworten als auch Events) verarbeiten.
    socket.addEventListener("message", (event) => {
      this.handleMessage(String(event.data));
    });

    // Bei Verbindungsabbruch aufräumen und offene Anfragen ablehnen.
    socket.addEventListener("close", () => {
      this.handleClose(socket);
    });

    this.socketReady = new Promise<WebSocket>((resolve, reject) => {
      socket.addEventListener("open", () => {
        this.callbacks.onStatusChange("WebSocket verbunden", "text-bg-success");
        resolve(socket);
      });

      socket.addEventListener("error", () => {
        reject(new Error("WebSocket-Verbindung konnte nicht hergestellt werden."));
      });
    });

    return this.socketReady;
  }

  /**
   * Sendet ein Kommando an den Server (z. B. "create", "update", "delete", "find-all")
   * und liefert per Promise die Nutzdaten (payload) der zugehörigen Antwort zurück.
   * Baut bei Bedarf zuerst automatisch die Verbindung auf.
   */
  public async sendCommand(type: string, payload: object = {}): Promise<unknown> {
    const socket = await this.connect();
    const requestId = String(this.nextRequestId++);

    return new Promise((resolve, reject) => {
      // Anfrage merken, damit die Antwort später (in handleCommandResponse)
      // anhand der requestId dem richtigen Promise zugeordnet werden kann.
      this.pendingRequests.set(requestId, {resolve, reject});
      socket.send(JSON.stringify({type, requestId, payload}));
    });
  }

  /**
   * Verteilt eingehende Nachrichten je nach Typ:
   * - "response": Antwort auf ein zuvor gesendetes Kommando
   * - "created"/"updated": Benutzer wurde angelegt/geändert (von irgendeinem Client)
   * - "deleted": Benutzer wurde gelöscht
   * Nicht auswertbare Nachrichten lösen onInvalidMessage aus.
   */
  private handleMessage(messageText: string): void {
    try {
      const message = JSON.parse(messageText) as Record<string, unknown>;

      if (message.type === "response") {
        this.handleCommandResponse(message);
        return;
      }

      if ((message.type === "created" || message.type === "updated") && isUser(message.payload)) {
        this.callbacks.onEvent({type: message.type, user: message.payload});
        return;
      }

      if (message.type === "deleted" && typeof message.payload === "object" && message.payload !== null) {
        const payload = message.payload as Record<string, unknown>;
        if (typeof payload.id === "number") {
          this.callbacks.onEvent({type: "deleted", id: payload.id});
        }
      }
    } catch {
      this.callbacks.onInvalidMessage();
    }
  }

  /**
   * Ordnet eine "response"-Nachricht anhand der requestId der wartenden
   * Anfrage zu und löst deren Promise auf (bzw. lehnt es bei Fehler ab).
   * Nachrichten ohne bekannte/gültige requestId werden ignoriert.
   */
  private handleCommandResponse(message: Record<string, unknown>): void {
    if (typeof message.requestId !== "string") return;

    const pendingRequest = this.pendingRequests.get(message.requestId);
    if (!pendingRequest) return;

    this.pendingRequests.delete(message.requestId);

    if (typeof message.error === "string") {
      pendingRequest.reject(new Error(message.error));
      return;
    }

    pendingRequest.resolve(message.payload);
  }

  /**
   * Wird beim Schließen der Verbindung aufgerufen: setzt den internen Zustand
   * zurück, aktualisiert die Status-Anzeige und lehnt alle noch offenen
   * Anfragen ab, da für sie keine Antwort mehr eintreffen kann.
   */
  private handleClose(socket: WebSocket): void {
    if (this.socket === socket) this.socket = null;
    this.socketReady = null;
    this.callbacks.onStatusChange("WebSocket getrennt", "text-bg-secondary");

    for (const request of this.pendingRequests.values()) {
      request.reject(new Error("Die WebSocket-Verbindung wurde getrennt."));
    }
    this.pendingRequests.clear();
  }
}