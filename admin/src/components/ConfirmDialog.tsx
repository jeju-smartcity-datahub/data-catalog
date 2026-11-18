import { useId, type ReactNode } from 'react';
import { Modal } from './Modal';
import { Button } from './ui';

type ConfirmDialogProps = {
  open: boolean;
  title?: string;
  description: ReactNode;
  onCancel: () => void;
  onConfirm: () => void;
  busy?: boolean;
  error?: string;
  confirmLabel?: string;
  busyLabel?: string;
};

/** Shared destructive confirmation. The caller owns the real mutation and its result. */
export function ConfirmDialog({ open, title = '삭제', description, onCancel, onConfirm, busy = false, error, confirmLabel = '삭제', busyLabel = '삭제 중…' }: ConfirmDialogProps) {
  const descriptionId = useId();
  return <Modal open={open} title={title} variant="confirm" descriptionId={descriptionId} busy={busy} onClose={onCancel} footer={<>
    <Button type="button" variant="secondary" data-initial-focus="true" disabled={busy} onClick={onCancel}>취소</Button>
    <Button type="button" variant="danger" disabled={busy} onClick={onConfirm}>{busy ? busyLabel : confirmLabel}</Button>
  </>}>
    <p id={descriptionId}>{description}</p>
    {error && <p role="alert">{error}</p>}
    {busy && <p role="status">{busyLabel}</p>}
  </Modal>;
}
