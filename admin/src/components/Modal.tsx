'use client';

import { adminUrl } from '../lib/paths';

import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from 'react';
import { Button } from './ui';
import styles from './ui.module.css';

/** Native modal makes the background inert; Tab wrapping keeps focus inside the content. */
export function Modal({ open, title, children, onClose, wide = false, size, busy = false, footer, footerMode = 'append', className = '', closeIconSrc = '/assets/close.svg', variant, descriptionId, titleAdornment }: { open: boolean; title: string; children: ReactNode; onClose: () => void; wide?: boolean; size?: 'medium' | 'evidence' | 'details'; busy?: boolean; footer?: ReactNode; footerMode?: 'append' | 'replace'; className?: string; closeIconSrc?: string; variant?: 'confirm'; descriptionId?: string; titleAdornment?: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    if (open && !dialog.open) {
      dialog.showModal();
      dialog.querySelector<HTMLButtonElement>('[data-initial-focus="true"]:not(:disabled)')?.focus();
    }
    if (!open && dialog.open) dialog.close();
    return () => { if (dialog.open) dialog.close(); if (open && previousFocus?.isConnected) previousFocus.focus(); };
  }, [open]);
  function keepFocus(event: KeyboardEvent<HTMLDialogElement>) {
    if (event.key !== 'Tab' || !ref.current) return;
    const controls = [...ref.current.querySelectorAll<HTMLElement>('button,input,select,textarea,a[href],[tabindex]')]
      .filter(element => element.tabIndex >= 0 && !element.hasAttribute('disabled') && element.getClientRects().length > 0);
    if (!controls.length) { event.preventDefault(); return; }
    const first = controls[0]; const last = controls[controls.length - 1];
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  }
  const sizeClass = size === 'evidence' ? styles.evidenceDialog : size === 'details' ? styles.detailsDialog : size === 'medium' ? styles.mediumDialog : wide ? styles.wideDialog : '';
  return <dialog ref={ref} className={`${styles.dialog} ${sizeClass} ${variant === 'confirm' ? styles.confirmDialog : ''} ${className}`} aria-labelledby={titleId} aria-describedby={descriptionId} aria-busy={busy} onKeyDown={keepFocus} onCancel={event => { event.preventDefault(); if (!busy) onClose(); }}>
    <div className={styles.modalHeading}><div className={styles.modalTitleGroup}><h2 id={titleId}>{title}</h2>{titleAdornment}</div>{variant !== 'confirm' && <button type="button" className={styles.closeButton} aria-label={`${title} 닫기`} disabled={busy} onClick={onClose}><img src={closeIconSrc.startsWith('/assets/') ? adminUrl(closeIconSrc) : closeIconSrc} alt="" /></button>}</div>{children}
    <div className={styles.actions}>{footer}{variant !== 'confirm' && footerMode !== 'replace' && <Button type="button" variant="secondary" disabled={busy} data-initial-focus={!wide ? 'true' : undefined} onClick={onClose}>닫기</Button>}</div>
  </dialog>;
}
