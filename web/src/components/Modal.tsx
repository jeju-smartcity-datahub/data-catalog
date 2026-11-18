'use client';

import { useEffect, useId, useRef, type ReactNode } from 'react';
import { Button } from './ui';
import styles from './ui.module.css';
import { containDialogFocus } from './dialogFocus';

/** Native dialog supplies focus containment, Escape handling and return focus. */
export function Modal({ open, title, children, onClose, size = 'default' }: { open: boolean; title: string; children: ReactNode; onClose: () => void; size?: 'default' | 'wide' }) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);
  return <dialog ref={ref} className={styles.dialog + (size === 'wide' ? ' ' + styles.dialogWide : '')} aria-labelledby={titleId} onCancel={onClose} onClose={onClose} onKeyDown={containDialogFocus}>
    <h2 id={titleId}>{title}</h2>{children}
    <div className={styles.actions}><Button variant="secondary" onClick={onClose}>닫기</Button></div>
  </dialog>;
}
