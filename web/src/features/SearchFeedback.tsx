'use client';

import { useId, useRef, useState } from 'react';
import { Button } from '../components/ui';
import type { components } from '../lib/generated/api';
import common from '../app/page.module.css';
import styles from './SearchFeedback.module.css';

type FeedbackOffer = components['schemas']['FeedbackOffer'];
type Rating = 'HELPFUL' | 'NOT_HELPFUL';
type Phase = 'idle' | 'sending' | 'saved' | 'preflight-failed' | 'closed' | 'uncertain';
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const token = /^[A-Za-z0-9_-]{43}$/;

function validForm(value: unknown): value is { csrfHeaderName: string; csrfToken: string } {
  if (!value || typeof value !== 'object') return false;
  const form = value as Record<string, unknown>;
  return typeof form.csrfHeaderName === 'string' && form.csrfHeaderName.toLowerCase() === 'x-csrf-token'
    && typeof form.csrfToken === 'string' && form.csrfToken.length > 0 && form.csrfToken.length <= 4096
    && Array.from(form.csrfToken).every(char => { const code = char.codePointAt(0) ?? 0; return code >= 33 && code <= 126; });
}

async function readForm(response: Response): Promise<unknown> {
  if (!response.body) throw new Error('Feedback form missing');
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let length = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      length += value.length;
      if (length > 8192) { await reader.cancel(); throw new Error('Feedback form too large'); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)) as unknown;
}

export function SearchFeedback({ offer }: { offer: FeedbackOffer }) {
  const id = useId();
  const [phase, setPhase] = useState<Phase>('idle');
  const [rating, setRating] = useState<Rating | null>(null);
  const [message, setMessage] = useState('');
  const running = useRef(false);
  const terminal = phase === 'saved' || phase === 'closed' || phase === 'uncertain';
  const validOffer = typeof offer.eventId === 'string' && uuid.test(offer.eventId)
    && typeof offer.feedbackToken === 'string' && token.test(offer.feedbackToken);

  async function submit(value: Rating) {
    if (running.current || terminal || !validOffer) return;
    running.current = true; setPhase('sending'); setMessage('평가를 저장하고 있습니다.');
    let posting = false;
    try {
      const formResponse = await fetch('/api/catalog/v1/search-feedback-form', {
        cache: 'no-store', credentials: 'same-origin', redirect: 'error', referrerPolicy: 'no-referrer',
        headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(10000),
      });
      if (!formResponse.ok) throw new Error('Feedback form unavailable');
      const form = await readForm(formResponse);
      if (!validForm(form)) throw new Error('Invalid feedback form');
      posting = true;
      const response = await fetch('/api/catalog/v1/search-feedback', {
        method: 'POST', cache: 'no-store', credentials: 'same-origin', redirect: 'error', referrerPolicy: 'no-referrer',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json', [form.csrfHeaderName]: form.csrfToken },
        body: JSON.stringify({ eventId: offer.eventId, feedbackToken: offer.feedbackToken, rating: value }),
        signal: AbortSignal.timeout(10000),
      });
      if (response.status === 204) {
        setRating(value); setPhase('saved'); setMessage('평가를 저장했습니다. 감사합니다.');
      } else if (response.status === 409) {
        setPhase('closed'); setMessage('이미 평가가 등록된 검색입니다. 새 검색 결과에서 평가해 주세요.');
      } else if (response.status === 404 || response.status === 410) {
        setPhase('closed'); setMessage('평가할 수 있는 검색이 만료되었거나 더 이상 제공되지 않습니다. 새 검색 결과에서 평가해 주세요.');
      } else if (response.status === 403 || response.status === 400 || response.status === 422) {
        setPhase('closed'); setMessage('평가 요청의 보안 확인 또는 입력 검증을 완료하지 못했습니다. 새 검색 결과에서 다시 선택해 주세요.');
      } else {
        setPhase('uncertain'); setMessage('저장 여부를 확인하지 못했습니다. 중복 전송을 피하기 위해 다시 보내지 않습니다. 새 검색 결과에서 평가해 주세요.');
      }
    } catch {
      setPhase(posting ? 'uncertain' : 'preflight-failed');
      setMessage(posting ? '저장 여부를 확인하지 못했습니다. 중복 전송을 피하기 위해 다시 보내지 않습니다. 새 검색 결과에서 평가해 주세요.' : '평가를 시작하지 못했습니다. 잠시 후 다시 선택해 주세요.');
    } finally { running.current = false; }
  }

  if (!validOffer) return <p className={common.help} role="status">이번 검색의 평가를 제공할 수 없습니다. 검색 결과는 계속 확인할 수 있습니다.</p>;
  return <section className={styles.root} aria-labelledby={`${id}-heading`} aria-busy={phase === 'sending'}>
    <h2 id={`${id}-heading`}>검색 결과가 도움이 되었나요?</h2>
    <p id={`${id}-help`} className={common.help}>선택 사항입니다. 이 검색의 정제된 표현·공개 결과와 평가를 연결하여 5년간 보관합니다. 이용자 식별정보와 자유서술은 입력받지 않습니다.</p>
    <div className={common.buttonRow}>
      <Button type="button" variant="secondary" aria-describedby={`${id}-help`} aria-pressed={rating === 'HELPFUL'} disabled={phase === 'sending' || terminal} onClick={() => void submit('HELPFUL')}>도움됨</Button>
      <Button type="button" variant="secondary" aria-describedby={`${id}-help`} aria-pressed={rating === 'NOT_HELPFUL'} disabled={phase === 'sending' || terminal} onClick={() => void submit('NOT_HELPFUL')}>도움 안 됨</Button>
    </div>
    {message && <p className={common.help} role={phase === 'uncertain' || phase === 'closed' || phase === 'preflight-failed' ? 'alert' : 'status'}>{message}</p>}
  </section>;
}
