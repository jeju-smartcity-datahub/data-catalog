import type { Metadata } from 'next';
import Link from 'next/link';
import '../styles/globals.css';
import styles from './page.module.css';
import { GlobalSearch } from '../features/GlobalSearch';

export const metadata: Metadata = {
  title: { default: '데이터 카탈로그', template: '%s | 데이터 카탈로그' },
  description: '공개된 지역 데이터의 설명과 제공 정보를 확인하는 데이터 카탈로그',
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="ko"><body>
    <a className="skip-link" href="#main">본문으로 건너뛰기</a>
    <header className={styles.header}><div className={styles.headerInner}>
      <Link className={styles.brand} href="/datasets" aria-label="데이터 카탈로그 홈">데이터 카탈로그</Link>
      <GlobalSearch />
    </div></header>
    {children}
    <footer className={styles.footer}><div className={styles.footerInner}><strong>데이터 카탈로그</strong><Link href="/datasets">데이터 탐색</Link></div></footer>
  </body></html>;
}
