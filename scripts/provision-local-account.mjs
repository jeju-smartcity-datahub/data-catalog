import { spawn } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { delimiter, join } from 'node:path';
import { devEnv, root } from './dev-env.mjs';

const args = process.argv.slice(2);
if (args.length === 1 && args[0] === '--help') {
  console.log('Development-only account creation: npm run account:local:create -- --username <id> --roles <roles> [--organizations <UUIDs>]');
  console.log('Requires an interactive terminal, existing local DB with V17, and explicit roles. Password is entered twice without echo. No default account or password.');
  process.exit(0);
}
if (!process.stdin.isTTY || !process.stdout.isTTY) throw new Error('Run in an interactive terminal. Redirected secret input is not supported.');
if (args.length < 4 || args.length % 2 !== 0) throw new Error('Use --help for the explicit account options.');
const valid = { '--username': /^[a-z0-9][a-z0-9._-]{2,79}$/, '--roles': /^[A-Z_]+(?:,[A-Z_]+)*$/, '--organizations': /^[0-9a-fA-F-]{36}(?:,[0-9a-fA-F-]{36})*$/ };
const seen = new Set();
for (let index = 0; index < args.length; index += 2) {
  const option = args[index];
  if (!valid[option]?.test(args[index + 1]) || seen.has(option)) throw new Error('Invalid account options. Passwords must only be entered in the hidden prompt.');
  seen.add(option);
}
const env = devEnv();
if (env.SPRING_PROFILES_ACTIVE && env.SPRING_PROFILES_ACTIVE.split(',').some(value => !['dev', 'local-auth'].includes(value))) throw new Error('This command cannot run with non-development profiles.');
env.SPRING_PROFILES_ACTIVE = 'dev,local-auth';
env.DB_URL ??= `jdbc:postgresql://127.0.0.1:${env.POSTGRES_PORT || '15432'}/${env.POSTGRES_DB || 'datacatalog'}`;
env.DB_USERNAME ??= env.POSTGRES_USER || 'datacatalog';
env.DB_PASSWORD ??= env.POSTGRES_PASSWORD;
const javaHome = env.JAVA_HOME || join(root, '.tools', 'jdk-21.0.12.1+1');
if (!existsSync(join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java'))) throw new Error('Configure Java 21 first.');
env.JAVA_HOME = javaHome;
const classpathFile = join(root, 'server', 'target', 'provision-classpath.txt');
function run(executable, commandArgs, shell = false) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, commandArgs, { cwd: join(root, 'server'), env, stdio: 'inherit', shell });
    child.once('error', reject);
    child.once('exit', code => code === 0 ? resolve() : reject(new Error('Account command stopped. No automatic retry.')));
  });
}
const mvnArgs = ['--batch-mode', '-q', '-DskipTests', 'compile', 'dependency:build-classpath', '-Dmdep.outputFile=target/provision-classpath.txt', '-Dmdep.includeScope=runtime'];
await run(process.platform === 'win32' ? 'mvnw.cmd' : 'bash', process.platform === 'win32' ? mvnArgs : ['./mvnw', ...mvnArgs], process.platform === 'win32');
const classpath = [join(root, 'server', 'target', 'classes'), readFileSync(classpathFile, 'utf8').trim()].join(delimiter);
await run(join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java'), ['-cp', classpath, 'kr.co.dtonic.datacatalog.bootstrap.LocalAccountProvisioner', ...args]);
