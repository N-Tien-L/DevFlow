#!/usr/bin/env node
// PreToolUse hook (Edit|Write|MultiEdit).
// Blocks edits to Flyway migrations that are already merged on origin/develop:
// Flyway checksums make an edited, already-applied migration fail on every database.
// Unmerged migrations on the current branch stay editable, so the merge owner can renumber them.
import { execFileSync } from 'node:child_process';
import path from 'node:path';

const MIGRATION_DIR = 'backend/app/src/main/resources/db/migration/';
const BASE_REF = process.env.DEVFLOW_MIGRATION_BASE || 'origin/develop';

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => (raw += chunk));
process.stdin.on('end', () => {
  let input;
  try {
    input = JSON.parse(raw || '{}');
  } catch {
    process.exit(0); // never block on a malformed payload
  }

  const filePath = input?.tool_input?.file_path;
  if (!filePath) process.exit(0);

  const projectDir = toNativePath(process.env.CLAUDE_PROJECT_DIR || input.cwd || process.cwd());
  const rel = path.relative(projectDir, path.resolve(projectDir, filePath)).split(path.sep).join('/');
  if (!rel.startsWith(MIGRATION_DIR) || !/\/V\d+__[^/]+\.sql$/.test(rel)) process.exit(0);

  try {
    execFileSync('git', ['cat-file', '-e', `${BASE_REF}:${rel}`], { cwd: projectDir, stdio: 'ignore' });
  } catch {
    process.exit(0); // not on the base branch yet: a new or unmerged migration
  }

  process.stderr.write(
    `Blocked: ${rel} is already merged on ${BASE_REF}. Flyway verifies checksums, so editing an applied ` +
      'migration breaks every existing database. Add a new V<next>__<description>.sql instead ' +
      '(the merge owner assigns the final number, see AGENTS.md section 8).\n',
  );
  process.exit(2);
});

// Git Bash exports MSYS paths (/c/Users/...); Node on Windows needs C:/Users/...
function toNativePath(p) {
  const m = process.platform === 'win32' && /^\/([a-zA-Z])\/(.*)$/.exec(p);
  return m ? `${m[1].toUpperCase()}:/${m[2]}` : p;
}
