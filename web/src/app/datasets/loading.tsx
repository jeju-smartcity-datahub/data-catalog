import styles from '../page.module.css';
export default function Loading() { return <main id="main" tabIndex={-1} className={styles.main}><h1>공개 데이터셋</h1><p role="status" className={styles.state}>데이터를 불러오는 중입니다.</p></main>; }
