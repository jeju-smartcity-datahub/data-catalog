import type { ButtonHTMLAttributes, ComponentPropsWithRef, ReactNode } from 'react';
import styles from './ui.module.css';

export function Button({ variant = 'primary', className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'danger' }) {
  return <button {...props} className={`${styles.button} ${variant === 'primary' ? '' : styles[variant]} ${className}`} />;
}
export function Input({ className = '', ...props }: ComponentPropsWithRef<'input'>) {
  return <input {...props} className={`${styles.input} ${className}`} />;
}
export function RequiredMark() {
  return <span className={styles.requiredMark}><span aria-hidden="true">*</span><span className="sr-only"> 필수</span></span>;
}
export function Badge({ children, tone = 'info' }: { children: ReactNode; tone?: 'info' | 'success' | 'warning' | 'neutral' | 'danger' }) { return <span className={`${styles.badge} ${styles[`badge${tone}`] || ''}`}>{children}</span>; }
