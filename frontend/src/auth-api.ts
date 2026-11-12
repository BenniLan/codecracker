export type AuthResult = {
  token: string;
};

function isAuthResult(value: unknown): value is AuthResult {
  if (typeof value !== "object" || value === null) return false;
  return typeof (value as Record<string, unknown>).token === "string";
}

async function readJsonError(response: Response, fallback: string): Promise<never> {
  const body: unknown = await response.json().catch(() => null);
  const message = (body as { error?: string } | null)?.error ?? fallback;
  throw new Error(message);
}

/** Registers a new user through the REST API. */
export async function registerUser(username: string, password: string): Promise<void> {
  const response = await fetch("/api/register", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });

  if (!response.ok) await readJsonError(response, "Registrierung fehlgeschlagen.");
}

/** Logs in an existing user and returns the JWT. */
export async function loginUser(username: string, password: string): Promise<AuthResult> {
  const response = await fetch("/api/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });

  if (!response.ok) await readJsonError(response, "Login fehlgeschlagen.");

  const result: unknown = await response.json();
  if (!isAuthResult(result)) throw new Error("Die REST-Antwort enthält kein gültiges Token.");
  return result;
}