import Link from 'next/link';
import styles from '../../page.module.css';

export default function DatasetNotFound() {
  return <main id="main" tabIndex={-1} className={styles.main}><section className={styles.state}><h1>공개된 데이터셋을 찾을 수 없습니다</h1><p>주소를 확인하거나 데이터 목록에서 다시 찾아 주세요.</p><Link href="/datasets" className={styles.action}>데이터 목록으로</Link></section></main>;
}
