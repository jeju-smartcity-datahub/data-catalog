import { spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { createHash, randomBytes } from 'node:crypto';
import { join, resolve } from 'node:path';
import { devEnv, root } from './dev-env.mjs';

// Local rehearsal only. Never restores over an existing development or production database.
const action = process.argv[2];
if (!['backup', 'verify'].includes(action)) throw new Error('Choose backup or verify.');
const configuration = devEnv();
const port = configuration.POSTGRES_PORT || '15432';
const database = configuration.POSTGRES_DB || 'datacatalog';
const username = configuration.POSTGRES_USER || 'datacatalog';
if (!/^\d{4,5}$/.test(port) || Number(port) > 65535 || !/^[a-z][a-z0-9_]*$/.test(database) || !/^[a-z][a-z0-9_]*$/.test(username)) throw new Error('Invalid local database configuration.');
if (!configuration.POSTGRES_PASSWORD) throw new Error('Run env:init first.');
const env = { ...process.env, PGPASSWORD: configuration.POSTGRES_PASSWORD, PGCONNECT_TIMEOUT: '5' };
const executable = name => process.platform === 'win32' ? join(root, '.tools/postgresql-17.11/pgsql/bin', `${name}.exe`) : name;
const common = ['--host=127.0.0.1', `--port=${port}`, `--username=${username}`, '--no-password'];
function run(name, args) {
  const result = spawnSync(executable(name), [...common, ...args], { env, encoding: 'utf8', maxBuffer: 4 * 1024 * 1024, windowsHide: true });
  // Child errors can contain server details. Keep raw output out of logs and reports.
  if (result.error || result.status !== 0) throw new Error(`${name} failed (exit ${result.status ?? 'unavailable'}). Check local PostgreSQL availability and permissions.`);
  return result.stdout.trim();
}
const backupDirectory = resolve(root, '.runtime/backups');
mkdirSync(backupDirectory, { recursive: true });
if (action === 'backup') {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const filename = `catalog-${stamp}.dump`;
  const path = join(backupDirectory, filename);
  const started = Date.now();
  run('pg_dump', [`--dbname=${database}`, '--format=custom', '--no-owner', '--no-acl', `--file=${path}`]);
  const bytes = readFileSync(path);
  const manifest = { filename, createdAt: new Date().toISOString(), sha256: createHash('sha256').update(bytes).digest('hex'), bytes: bytes.length, durationMs: Date.now() - started, source: 'local-development', format: 'pg_dump-custom' };
  writeFileSync(`${path}.json`, `${JSON.stringify(manifest, null, 2)}\n`, { flag: 'wx' });
  writeFileSync(join(backupDirectory, 'latest.json'), `${JSON.stringify({ filename }, null, 2)}\n`);
  console.log(JSON.stringify({ action, ...manifest }));
} else {
  const latest = join(backupDirectory, 'latest.json');
  if (!existsSync(latest)) throw new Error('Run db:backup before db:restore:verify.');
  const { filename } = JSON.parse(readFileSync(latest, 'utf8'));
  if (typeof filename !== 'string' || !/^catalog-[\dTZ-]+\.dump$/.test(filename)) throw new Error('Invalid backup filename.');
  const path = join(backupDirectory, filename);
  const manifest = JSON.parse(readFileSync(`${path}.json`, 'utf8'));
  if (createHash('sha256').update(readFileSync(path)).digest('hex') !== manifest.sha256) throw new Error('Backup checksum mismatch.');
  const restoredDatabase = `catalog_restore_${randomBytes(6).toString('hex')}_test`;
  const started = Date.now();
  // createdb fails if this generated name already exists. pg_restore never uses --clean.
  run('createdb', [restoredDatabase]);
  try {
    run('pg_restore', [`--dbname=${restoredDatabase}`, '--single-transaction', '--exit-on-error', '--no-owner', '--no-acl', path]);
    const rows = run('psql', [`--dbname=${restoredDatabase}`, '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-c', "SELECT json_build_object('migrations', (SELECT count(*) FROM public.flyway_schema_history WHERE success), 'datasets', (SELECT count(*) FROM catalog.dataset), 'publications', (SELECT count(*) FROM catalog.publication), 'currentPointers', (SELECT count(*) FROM catalog.dataset d JOIN catalog.publication p ON p.id=d.now_pstg_id AND p.dtset_id=d.id));"]);
    const result = { action: 'restore-verification', filename, restoredDatabase, checkedAt: new Date().toISOString(), durationMs: Date.now() - started, counts: JSON.parse(rows), retained: true };
    writeFileSync(join(backupDirectory, 'last-restore-verification.json'), `${JSON.stringify(result, null, 2)}\n`);
    console.log(JSON.stringify(result));
  } catch (error) {
    console.error(`Restore rehearsal failed; isolated database retained for inspection: ${restoredDatabase}`);
    throw error;
  }
}
