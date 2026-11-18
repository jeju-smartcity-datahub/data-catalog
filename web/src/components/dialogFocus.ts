import type { KeyboardEvent } from 'react';

/** Keep keyboard traversal within an open modal, including browser chrome boundaries. */
export function containDialogFocus(event: KeyboardEvent<HTMLDialogElement>) {
  if (event.key !== 'Tab') return;
  const controls = [...event.currentTarget.querySelectorAll<HTMLElement>('button, a[href], input, select, textarea, [tabindex]')]
    .filter(element => element.tabIndex >= 0 && !element.hasAttribute('disabled') && element.getClientRects().length > 0);
  const first = controls[0], last = controls.at(-1);
  if (!first || !last) return;
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
  if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
}
