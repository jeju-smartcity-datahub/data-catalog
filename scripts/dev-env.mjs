import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

export const root = fileURLToPath(new URL('../', import.meta.url));
export function devEnv() {
  const values = {};
  const file = new URL('../.env.dev', import.meta.url);
  if (existsSync(file)) {
    for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
      if (!line.trim() || line.trimStart().startsWith('#')) continue;
      const match = line.match(/^([A-Z][A-Z0-9_]*)=(.*)$/);
      if (!match) throw new Error('Invalid .env.dev assignment');
      values[match[1]] = match[2].replace(/^(['"])(.*)\1$/, '$2');
    }
  }
  return { ...values, ...process.env };
}
