'use client';

import Image from 'next/image';
import { useState } from 'react';
import { Modal } from '../components/Modal';
import { Button } from '../components/ui';
import type { DatasetDetail } from '../lib/catalog';
import { copyText } from '../lib/clipboard';
import styles from '../app/page.module.css';

export function MetadataActions({ data }: { data: DatasetDetail }) {
  const [open, setOpen] = useState(false);
  const [feedback, setFeedback] = useState('');
  const text = JSON.stringify(data, null, 2);
  async function copy() {
    try { await copyText(text); setFeedback('메타정보를 복사했습니다.'); }
    catch { setFeedback('복사하지 못했습니다. JSON 보기에서 내용을 선택해 복사해 주세요.'); }
  }
  return <div className={styles.metadataActions}>
    <div className={styles.buttonRow}><Button variant="secondary" onClick={() => setOpen(true)}><Image src="/assets/metadata-json.svg" alt="" width={20} height={20} />메타정보 JSON 보기</Button><Button variant="secondary" onClick={copy}><Image src="/assets/copy.svg" alt="" width={20} height={20} />메타정보 복사</Button></div>
    {feedback && <p className={styles.help} role="status">{feedback}</p>}
    <Modal open={open} title="공개 메타정보 JSON" size="wide" onClose={() => setOpen(false)}><section className={styles.json} tabIndex={0} aria-label="공개 메타정보 JSON 내용"><pre className={styles.jsonText}>{text}</pre></section></Modal>
  </div>;
}
