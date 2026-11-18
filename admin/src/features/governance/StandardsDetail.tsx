import { adminUrl } from '../../lib/paths';
import { useEffect, useState, type ReactNode } from 'react';
import { Badge, Button } from '../../components/ui';
import { adminGet, errorMessage, type Dictionary, type DictionaryEntry } from '../../lib/admin';
import type { components } from '../../lib/generated/api';
import styles from './StandardsDetail.module.css';

type Relationships = components['schemas']['EntryRelationships'];
type Detail = { relationships: Relationships; domain?: DictionaryEntry; domainAttributes?: components['schemas']['DomainAttributes']; words: DictionaryEntry[] };
const states: Record<string, string> = { DRAFT: '초안', IN_REVIEW: '승인 대기', APPROVED: '승인', REJECTED: '반려' };

export function StandardsDetail({ entry, dictionary, hideHeading = false }: { entry: DictionaryEntry; dictionary: Dictionary; hideHeading?: boolean }) {
  const [detail, setDetail] = useState<Detail | null>(null);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    async function load() {
      const { data: relationships } = await adminGet<Relationships>(`/dictionary-entries/${entry.id}/relationships`, controller.signal);
      if (!relationships.term) return { relationships, words: [] };
      const term = relationships.term;
      const [domain, domainAttributes, words] = await Promise.all([
        adminGet<DictionaryEntry>(`/dictionary-entries/${term.domainEntryId}`, controller.signal),
        adminGet<Relationships>(`/dictionary-entries/${term.domainEntryId}/relationships`, controller.signal),
        Promise.all([...new Set(term.wordEntryIds)].map(id => adminGet<DictionaryEntry>(`/dictionary-entries/${id}`, controller.signal))),
      ]);
      const byId = new Map(words.map(item => [item.data.id, item.data]));
      return { relationships, domain: domain.data, domainAttributes: domainAttributes.data.domain, words: term.wordEntryIds.map(id => byId.get(id)!) };
    }
    load().then(result => { if (!controller.signal.aborted) { setDetail(result); setError(''); } }).catch(error => { if (!controller.signal.aborted) setError(errorMessage(error)); });
    return () => controller.abort();
  }, [entry.id, retry]);
  const attributes = detail?.relationships.domain || detail?.domainAttributes;
  const word = detail?.relationships.word;
  const term = detail?.relationships.term;
  function cell(label: string, value: ReactNode, full = false) { return <div className={`${styles.cell} ${full ? styles.full : ''}`} key={label}><dt>{label}</dt><dd>{value === undefined || value === null || value === '' ? '—' : value}</dd></div>; }
  return <section className={styles.detail} aria-label="표준사전 상세 정보">
    {!hideHeading && <><div className={styles.heading}><h3>{entry.name}</h3><Badge>{states[entry.state] || entry.state}</Badge></div><p className={styles.description}>{entry.definition}</p></>}
    {error ? <div className={styles.error} role="alert">{error}<Button variant="secondary" onClick={() => { setError(''); setDetail(null); setRetry(value => value + 1); }}>속성 다시 불러오기</Button></div> : !detail ? <p role="status">상세 속성을 불러오는 중입니다.</p> : <dl className={styles.attributes}>
      {cell('코드', entry.code)}{cell('자료형', entry.dataType)}
      {entry.kind === 'WORD' && <>{cell('영문명', word?.englishName)}{cell('영문 약어', word?.abbreviation)}{cell('동의어', word?.synonyms.join(', '), true)}</>}
      {entry.kind === 'TERM' && <>{cell('물리명', term?.physicalName)}{cell('도메인명', detail.domain?.name)}{cell('구성 단어', detail.words.length ? <ol className={styles.words}>{detail.words.map((item, index) => <li key={`${item.id}-${index}`}>{item.name}</li>)}</ol> : undefined, true)}</>}
      {entry.kind !== 'WORD' && <>{cell('최대 길이', attributes?.maxLength)}{cell('정밀도', attributes?.precision)}{cell('소수 자릿수', attributes?.scale)}{cell('단위', attributes?.unit)}{cell('표현 형식', attributes?.format)}{cell('허용값', attributes?.allowedValues?.join(', '))}</>}
      {cell('사전', dictionary.name)}{cell('사전 버전', dictionary.versionLabel)}
      <div className={`${styles.cell} ${styles.full}`}><dt>출처</dt><dd>{dictionary.sourceUri ? safeSource(dictionary.sourceUri) ? <a href={dictionary.sourceUri} target="_blank" rel="noreferrer">공식 자료 보기 <img src={adminUrl('/assets/external-link.svg')} alt="새 창" /></a> : dictionary.sourceUri : dictionary.scope === 'ORGANIZATION' ? '기관 사전' : '출처 정보 없음'}</dd></div>
    </dl>}
  </section>;
}

function safeSource(source: string): boolean { try { const url = new URL(source); return url.protocol === 'https:' || url.protocol === 'http:'; } catch { return false; } }
