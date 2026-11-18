import { spawn } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { devEnv, root } from './dev-env.mjs';

const actions = {
  start: [],
  dev: ['spring-boot:run', '-Dspring-boot.run.profiles=dev'],
  'dev-local-auth': ['spring-boot:run', '-Dspring-boot.run.profiles=dev,local-auth'],
  build: ['--batch-mode', 'package'],
  test: ['--batch-mode', 'verify'],
  contracts: ['--batch-mode', 'verify'],
  dependencies: ['--batch-mode', 'dependency:tree', '-DoutputFile=target/dependency-tree.txt'],
};
const args = actions[process.argv[2]];
if (!args) throw new Error(`Choose: ${Object.keys(actions).join(', ')}`);
const env = devEnv();
if ((['dev', 'dev-local-auth', 'start'].includes(process.argv[2]) || env.CATALOG_EXTERNAL_TEST_DB === 'true') && !env.POSTGRES_PASSWORD && !env.DB_PASSWORD) {
  throw new Error('Run npm run env:init first.');
}
if (process.argv[2] === 'dev-local-auth') {
  if (env.SPRING_PROFILES_ACTIVE?.split(',').some(profile => !['dev', 'local-auth'].includes(profile))) throw new Error('Local login cannot run with non-development profiles.');
  env.SERVER_ADDRESS = '127.0.0.1';
  env.SESSION_COOKIE_SECURE = 'false';
}
const java = join(root, '.tools', 'jdk-21.0.12.1+1');
if (!env.JAVA_HOME && existsSync(join(java, 'bin', 'javac.exe'))) env.JAVA_HOME = java;
env.DB_URL ??= `jdbc:postgresql://127.0.0.1:${env.POSTGRES_PORT || '15432'}/${env.POSTGRES_DB || 'datacatalog'}`;
env.DB_USERNAME ??= env.POSTGRES_USER || 'datacatalog';
env.DB_PASSWORD ??= env.POSTGRES_PASSWORD;
// Explicit opt-in: normal verify uses Testcontainers; db:test selects the isolated local test cluster.
if (env.CATALOG_EXTERNAL_TEST_DB === 'true') {
  env.TEST_DB_URL ??= `jdbc:postgresql://127.0.0.1:${env.POSTGRES_TEST_PORT || '15433'}/datacatalog_test`;
  env.TEST_DB_USERNAME ??= env.POSTGRES_USER || 'datacatalog';
  env.TEST_DB_PASSWORD ??= env.POSTGRES_PASSWORD;
}
const packaged = process.argv[2] === 'start';
const executable = packaged
  ? (env.JAVA_HOME ? join(env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java')
  : (process.platform === 'win32' ? 'mvnw.cmd' : 'bash');
// Windows keeps the running JAR open. Run a snapshot so Maven can replace target safely.
let runtimeJar;
if (packaged) {
  const runtimeDirectory = join(root, '.runtime', 'server');
  mkdirSync(runtimeDirectory, { recursive: true });
  runtimeJar = join(runtimeDirectory, `datacatalog-${Date.now()}-${process.pid}.jar`);
  copyFileSync(join(root, 'server', 'target', 'datacatalog-server-0.1.0-SNAPSHOT.jar'), runtimeJar);
}
const launchArgs = packaged ? ['-jar', runtimeJar]
  : (process.platform === 'win32' ? args : ['./mvnw', ...args]);
const child = spawn(executable, launchArgs, {
  cwd: join(root, 'server'), env, stdio: 'inherit', shell: !packaged && process.platform === 'win32',
});
child.on('error', error => { console.error(error.message); process.exitCode = 1; });
child.on('exit', code => { process.exitCode = code ?? 1; });
