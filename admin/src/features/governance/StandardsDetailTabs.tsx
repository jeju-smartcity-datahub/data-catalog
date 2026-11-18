import type { KeyboardEvent } from 'react';
import styles from './StandardsDetail.module.css';

/** Automatic activation, one tab stop, and associated panels for the two detail views. */
export function StandardsDetailTabs({ id, label, value, tabs, onChange }: { id: string; label: string; value: string; tabs: { value: string; label: string; disabled?: boolean }[]; onChange: (value: string) => void }) {
  function move(event: KeyboardEvent<HTMLButtonElement>, current: string) {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const enabled = tabs.filter(tab => !tab.disabled);
    const index = enabled.findIndex(tab => tab.value === current);
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? enabled.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + enabled.length) % enabled.length;
    const tab = enabled[next];
    onChange(tab.value);
    document.getElementById(`${id}-tab-${tab.value}`)?.focus();
  }
  return <div className={styles.detailTabs} role="tablist" aria-label={label}>{tabs.map(tab => <button key={tab.value} type="button" role="tab" id={`${id}-tab-${tab.value}`} aria-controls={`${id}-panel-${tab.value}`} aria-selected={value === tab.value} tabIndex={value === tab.value ? 0 : -1} disabled={tab.disabled} onClick={() => onChange(tab.value)} onKeyDown={event => move(event, tab.value)}>{tab.label}</button>)}</div>;
}
