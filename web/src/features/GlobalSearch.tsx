'use client';

import Image from 'next/image';
import { useRef } from 'react';
import { Button, Input } from '../components/ui';
import { containDialogFocus } from '../components/dialogFocus';
import styles from '../app/page.module.css';

export function GlobalSearch() {
  const dialog = useRef<HTMLDialogElement>(null);
  return <>
    <button type="button" className={styles.searchTrigger} aria-label="데이터 검색 열기" onClick={() => dialog.current?.showModal()}><Image src="/assets/search.svg" alt="" width={18} height={18} /></button>
    <dialog ref={dialog} className={styles.searchDialog} aria-labelledby="global-search-heading" onKeyDown={containDialogFocus}>
      <button type="button" className={styles.searchClose} aria-label="데이터 검색 닫기" onClick={() => dialog.current?.close()}><Image src="/assets/close.svg" width={13.5} height={13.5} alt="" /></button>
      <div className={styles.searchDialogInner}><div className={styles.searchHeading}><h2 id="global-search-heading">어떤 데이터를 찾고 계신가요?</h2></div>
        <form action="/datasets" method="get" role="search" aria-label="전역 데이터 검색"><label htmlFor="global-query">키워드 검색</label><div className={styles.searchInput}><Image src="/assets/search-large.svg" alt="" width={24} height={24} /><Input id="global-query" name="q" type="search" maxLength={200} placeholder="검색어를 입력하세요" autoFocus /><Button type="submit">검색</Button></div></form>
      </div>
    </dialog>
  </>;
}
