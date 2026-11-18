import { useRef, type InputHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import { Input } from '../../components/ui';
import clearIcon from './assets/clear.svg';
import arrow from './assets/page-arrow.svg';
import disabledArrow from './assets/page-arrow-disabled.svg';
import firstDisabled from './assets/page-first-disabled.svg';
import doubleArrow from './assets/page-double.svg';
import styles from './MetadataPresentation.module.css';

export function ClearableInput({ clearLabel, onClear, ...props }: InputHTMLAttributes<HTMLInputElement> & { clearLabel: string; onClear: () => void }) {
  const input = useRef<HTMLInputElement>(null);
  return <span className={styles.clearControl}><Input {...props} ref={input} />{!props.disabled && !props.readOnly && props.value !== '' && props.value != null && <button type="button" className={styles.clearButton} aria-label={clearLabel} onClick={() => { onClear(); input.current?.focus(); }}><img src={clearIcon} alt="" /></button>}</span>;
}
export function ClearableTextarea({ clearLabel, onClear, ...props }: TextareaHTMLAttributes<HTMLTextAreaElement> & { clearLabel: string; onClear: () => void }) {
  const input = useRef<HTMLTextAreaElement>(null);
  return <span className={styles.clearControl}><textarea {...props} ref={input} />{!props.disabled && !props.readOnly && !!props.value && <button type="button" className={styles.clearButton} aria-label={clearLabel} onClick={() => { onClear(); input.current?.focus(); }}><img src={clearIcon} alt="" /></button>}</span>;
}
export function MetadataPagination({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (page: number) => void }) {
  const count = Math.max(1, totalPages);
  const window = (size: number) => { const start = Math.max(0, Math.min(page - Math.floor(size / 2), count - size)); return Array.from({ length: Math.min(size, count) }, (_, i) => start + i); };
  const number = (value: number, compact: boolean) => <button key={value} type="button" className={`${styles.pageButton} ${compact ? styles.compactPage : styles.desktopPage}`} aria-label={`${value + 1} 페이지`} aria-current={value === page ? 'page' : undefined} onClick={() => { if (value !== page) onChange(value); }}><span>{value + 1}</span></button>;
  return <nav className={styles.pagination} aria-label="메타데이터 목록 페이지"><button type="button" className={styles.pageButton} aria-label="첫 페이지" disabled={page === 0} onClick={() => onChange(0)}><img src={page === 0 ? firstDisabled : doubleArrow} alt="" /></button><button type="button" className={styles.pageButton} aria-label="이전 페이지" disabled={page === 0} onClick={() => onChange(page - 1)}><img src={page === 0 ? disabledArrow : arrow} alt="" /></button>{window(5).map(value => number(value, false))}{window(3).map(value => number(value, true))}<button type="button" className={styles.pageButton} aria-label="다음 페이지" disabled={page + 1 >= count} onClick={() => onChange(page + 1)}><img className={styles.reverse} src={page + 1 >= count ? disabledArrow : arrow} alt="" /></button><button type="button" className={styles.pageButton} aria-label="마지막 페이지" disabled={page + 1 >= count} onClick={() => onChange(count - 1)}><img className={styles.reverse} src={doubleArrow} alt="" /></button></nav>;
}
