#!/usr/bin/env node
// PostToolUse hook (Edit|Write|MultiEdit). Fast, file-scoped feedback after an agent edit:
//  - frontend/**/*.{ts,tsx,js,jsx}: `eslint --fix` on that one file, remaining problems go back to Claude
//  - backend/**/*.gradle.kts: `:verifyModuleBoundaries`, the only place an impl -> impl dependency can appear
// Exit code 2 feeds stderr back to Claude; anything else stays silent.
import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';

const isWindows = process.platform === 'win32';

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => (raw += chunk));
process.stdin.on('end', () => {
  let input;
  try {
    input = JSON.parse(raw || '{}');
  } catch {
    process.exit(0);
  }

  const filePath = input?.tool_input?.file_path;
  if (!filePath) process.exit(0);

  const projectDir = toNativePath(process.env.CLAUDE_PROJECT_DIR || input.cwd || process.cwd());
  const abs = path.resolve(projectDir, filePath);
  const rel = path.relative(projectDir, abs).split(path.sep).join('/');
  if (!existsSync(abs)) process.exit(0);

  if (/^frontend\/(src|tests?)\/.*\.(ts|tsx|js|jsx)$/.test(rel)) {
    const frontendDir = path.join(projectDir, 'frontend');
    if (!existsSync(path.join(frontendDir, 'node_modules'))) process.exit(0);
    const res = spawnSync('npx', ['eslint', '--fix', path.relative(frontendDir, abs)], {
      cwd: frontendDir,
      encoding: 'utf8',
      shell: isWindows,
    });
    if (res.status !== 0) {
      process.stderr.write(`eslint reported problems in ${rel} (auto-fixable ones were fixed):\n${res.stdout}${res.stderr}`);
      process.exit(2);
    }
    process.exit(0);
  }

  if (/^backend\/(.+\/)?build\.gradle\.kts$|^backend\/settings\.gradle\.kts$/.test(rel)) {
    const backendDir = path.join(projectDir, 'backend');
    // Absolute path: Windows shells may not search the current directory for executables.
    const gradlew = path.join(backendDir, isWindows ? 'gradlew.bat' : 'gradlew');
    const res = spawnSync(isWindows ? `"${gradlew}"` : gradlew, [':verifyModuleBoundaries', '-q', '--console=plain'], {
      cwd: backendDir,
      encoding: 'utf8',
      shell: isWindows,
    });
    if (res.status !== 0) {
      process.stderr.write(`:verifyModuleBoundaries failed after editing ${rel}:\n${res.stdout}${res.stderr}`);
      process.exit(2);
    }
  }
  process.exit(0);
});

// Git Bash exports MSYS paths (/c/Users/...); Node on Windows needs C:/Users/...
function toNativePath(p) {
  const m = process.platform === 'win32' && /^\/([a-zA-Z])\/(.*)$/.exec(p);
  return m ? `${m[1].toUpperCase()}:/${m[2]}` : p;
}
