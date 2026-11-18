import { Button } from '../../components/ui';
import { pageWindow } from './hubPageWindow';
import common from '../metadata/Metadata.module.css';

export function HubPagination({ label, total, page, onChange, disabled = false }: { label: string; total: number; page: number; onChange: (page: number) => void; disabled?: boolean }) {
  const window = pageWindow(total, page);
  return <nav className={common.pagination} aria-label={`${label} 페이지`}>
    <Button type="button" variant="secondary" disabled={disabled || !window.page} onClick={() => onChange(0)} aria-label={`${label} 처음 페이지`}>처음</Button>
    <Button type="button" variant="secondary" disabled={disabled || !window.page} onClick={() => onChange(window.page - 1)} aria-label={`${label} 이전 페이지`}>이전</Button>
    <span aria-live="polite">{window.page + 1} / {window.pages}페이지 · {window.start}–{window.end} / {total}건</span>
    <Button type="button" variant="secondary" disabled={disabled || window.page + 1 >= window.pages} onClick={() => onChange(window.page + 1)} aria-label={`${label} 다음 페이지`}>다음</Button>
    <Button type="button" variant="secondary" disabled={disabled || window.page + 1 >= window.pages} onClick={() => onChange(window.pages - 1)} aria-label={`${label} 마지막 페이지`}>마지막</Button>
  </nav>;
}
