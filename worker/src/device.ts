// A phone's Android ID, used so a reinstall finds the same account again. Only a keyed hash of it is ever stored.

/** The ID if it looks like one (Android IDs are 16 hex characters), else null. */
export function cleanDeviceId(value: unknown): string | null {
  return typeof value === "string" && /^[A-Za-z0-9_-]{8,64}$/.test(value) ? value : null;
}
