import styles from '../../page.module.css';

export default function LoadingDataset() {
  return <main id="main" tabIndex={-1} className={styles.main}><h1>데이터 상세</h1><section className={styles.state} role="status" aria-live="polite"><h2>게시된 데이터 정보를 불러오는 중입니다</h2><p>잠시만 기다려 주세요.</p></section></main>;
}
