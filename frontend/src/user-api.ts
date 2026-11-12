export type User = {
  id: number;
  username: string;
  role: string;
};

export function isUser(value: unknown): value is User {
  if (typeof value !== "object" || value === null) return false;

  const candidate = value as Record<string, unknown>;
  return typeof candidate.id === "number"
    && typeof candidate.username === "string"
    && typeof candidate.role === "string"
}

export function isUserArray(value: unknown): value is User[] {
  return Array.isArray(value) && value.every(isUser);
}

async function readUser(response: Response, errorMessage: string): Promise<User> {
  if (!response.ok) throw new Error(errorMessage);

  const result: unknown = await response.json();
  if (!isUser(result)) throw new Error("Die REST-Antwort enthält kein gültigen User.");
  return result;
}

/** Loads all Users through the REST API. */
export async function fetchUsers(): Promise<User[]> {
  const response = await fetch("/api/users");
  if (!response.ok) throw new Error("Users konnten nicht geladen werden.");

  const result: unknown = await response.json();
  if (!isUserArray(result)) throw new Error("Die REST-Antwort enthält ungültige Users.");
  return result;
}

/** Creates a User through the REST API. */
export async function createUser(username: string, password: string): Promise<User> {
  const response = await fetch("/api/users", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({username, password}),
  });

  return readUser(response, "User konnte nicht erstellt werden.");
}

/** Updates a User through the REST API. */
export async function updateUser(user: User, password: string): Promise<User> {
  const response = await fetch(`/api/Users/${user.id}`, {
    method: "PUT",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({username: user.username, password, role: user.role}),
  });

  return readUser(response, "User konnte nicht aktualisiert werden.");
}

/** Deletes a User through the REST API. */
export async function deleteUser(id: number): Promise<void> {
  const response = await fetch(`/api/users/${id}`, {method: "DELETE"});
  if (!response.ok) throw new Error("User konnte nicht gelöscht werden.");
}
