const authenticationExpiredEvent = 'catalog:authentication-expired';

/** A protected API rejected the current server session. This event carries no credentials. */
export function notifyAuthenticationExpired() {
  window.dispatchEvent(new Event(authenticationExpiredEvent));
}

export function onAuthenticationExpired(listener: () => void) {
  window.addEventListener(authenticationExpiredEvent, listener);
  return () => window.removeEventListener(authenticationExpiredEvent, listener);
}
