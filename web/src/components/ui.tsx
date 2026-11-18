import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from 'react';
import styles from './ui.module.css';

export function Button({ variant = 'primary', className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'primary' | 'secondary' | 'danger' }) {
  return <button {...props} className={`${styles.button} ${variant === 'primary' ? '' : styles[variant]} ${className}`} />;
}
export function Input({ className = '', ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input {...props} className={`${styles.input} ${className}`} />;
}
const categoryTones: Record<string, string> = { 환경: 'green', 행정: 'yellow', 관광: 'purple', 생활: 'sky', 산업: 'gray', 안전: 'brightBlue' };
export function Badge({ children, category = false }: { children: ReactNode; category?: boolean }) {
  const tone = category && typeof children === 'string' ? categoryTones[children] : undefined;
  return <span className={styles.badge + (tone ? ' ' + styles[tone] : '')}>{children}</span>;
}
