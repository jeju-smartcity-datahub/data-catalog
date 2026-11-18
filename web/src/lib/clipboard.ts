export async function copyText(text: string): Promise<void> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return;
    }
  } catch {
    // HTTP installations use the browser's selection-based copy command below.
  }
  const previousFocus = document.activeElement;
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.readOnly = true;
  textarea.style.position = 'fixed';
  textarea.style.left = '-9999px';
  textarea.style.top = '0';
  document.body.appendChild(textarea);
  try {
    textarea.focus();
    textarea.select();
    textarea.setSelectionRange(0, textarea.value.length);
    if (!document.execCommand('copy')) throw new Error('Clipboard copy failed');
  } finally {
    textarea.remove();
    if (previousFocus instanceof HTMLElement) previousFocus.focus();
  }
}
