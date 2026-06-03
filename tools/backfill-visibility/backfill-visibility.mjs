#!/usr/bin/env node
/**
 * One-time backfill: set `visibility = "public"` on expenditure documents
 * that have NO `visibility` field.
 *
 * WHY: the Android client lists expenditures with two rule-satisfying queries
 * (`visibility == "public"` + `contributorUid == me`). A doc written without a
 * `visibility` field (legacy / web-app-created) is treated as public by the
 * Firestore rules but is NOT matched by `visibility == "public"`, so other
 * members never see it. Stamping the field fixes that completeness gap.
 *
 * SAFETY:
 *   • Dry-run by default — pass --apply to actually write.
 *   • Only touches docs where `visibility` is ABSENT. Existing "public"/"private"
 *     values are never overwritten, so private expenses stay private.
 *   • Skips `Global Income` entries by default: those are per-user, and making
 *     them public would leak personal income into other members' lists. Pass
 *     --include-income to override (rarely what you want).
 *   • Requires the Admin SDK (service-account credentials) because the security
 *     rules only let a member write their OWN docs — a client can't backfill
 *     other members' entries.
 *
 * USAGE:
 *   npm install                       # installs firebase-admin (>= 12)
 *   # Provide credentials one of two ways:
 *   #   a) export GOOGLE_APPLICATION_CREDENTIALS=/path/to/serviceAccount.json
 *   #   b) pass --key /path/to/serviceAccount.json
 *   node backfill-visibility.mjs                 # DRY RUN — reports what it would do
 *   node backfill-visibility.mjs --apply         # actually writes
 *
 * Optional flags:
 *   --key <path>          service-account JSON (else GOOGLE_APPLICATION_CREDENTIALS)
 *   --project <id>        Firebase project id   (default: gen-lang-client-0856853334)
 *   --database <id>       named Firestore DB    (default: ai-studio-6193fa2f-201b-4627-bfb4-124dbca4a1c2)
 *   --include-income      also stamp `Global Income` entries as public
 *   --apply               perform writes (omit for a dry run)
 */

import { readFileSync } from 'node:fs';
import { initializeApp, cert, applicationDefault } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';

// ---- defaults match this project (see firebase.json / .firebaserc) ----------
const DEFAULT_PROJECT = 'gen-lang-client-0856853334';
const DEFAULT_DATABASE = 'ai-studio-6193fa2f-201b-4627-bfb4-124dbca4a1c2';
const INCOME_CATEGORY = 'Global Income';
const BATCH_LIMIT = 400; // Firestore hard cap is 500 writes per batch.

// ---- tiny arg parser --------------------------------------------------------
function parseArgs(argv) {
  const out = { apply: false, includeIncome: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--apply') out.apply = true;
    else if (a === '--include-income') out.includeIncome = true;
    else if (a === '--key') out.key = argv[++i];
    else if (a === '--project') out.project = argv[++i];
    else if (a === '--database') out.database = argv[++i];
    else if (a === '--help' || a === '-h') out.help = true;
    else {
      console.error(`Unknown argument: ${a}`);
      out.help = true;
    }
  }
  return out;
}

function buildCredential(keyPath) {
  if (keyPath) {
    const json = JSON.parse(readFileSync(keyPath, 'utf8'));
    return cert(json);
  }
  if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
    return applicationDefault();
  }
  throw new Error(
    'No credentials. Pass --key <serviceAccount.json> or set ' +
      'GOOGLE_APPLICATION_CREDENTIALS to a service-account key path.',
  );
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log(readFileSync(new URL(import.meta.url)).toString().split('*/')[0]);
    return;
  }

  const projectId = args.project || DEFAULT_PROJECT;
  const databaseId = args.database || DEFAULT_DATABASE;

  const app = initializeApp({ credential: buildCredential(args.key), projectId });
  const db = getFirestore(app, databaseId);

  console.log(`Project : ${projectId}`);
  console.log(`Database: ${databaseId}`);
  console.log(`Mode    : ${args.apply ? 'APPLY (writing)' : 'DRY RUN (no writes)'}`);
  console.log(`Income  : ${args.includeIncome ? 'included' : 'skipped'}`);
  console.log('');

  // Iterate groups → expenditures subcollection. Avoids collection-group index
  // requirements and keeps memory bounded per group.
  const groups = await db.collection('groups').get();
  console.log(`Scanning ${groups.size} group(s)...\n`);

  let scanned = 0;
  let missing = 0;
  let toUpdate = 0;
  let skippedIncome = 0;
  let updated = 0;

  let batch = db.batch();
  let batchCount = 0;

  async function flush() {
    if (batchCount === 0) return;
    if (args.apply) {
      await batch.commit();
      updated += batchCount;
    }
    batch = db.batch();
    batchCount = 0;
  }

  for (const group of groups.docs) {
    const exps = await group.ref.collection('expenditures').get();
    for (const exp of exps.docs) {
      scanned++;
      const data = exp.data();
      const hasVisibility = Object.prototype.hasOwnProperty.call(data, 'visibility')
        && data.visibility !== null
        && data.visibility !== '';
      if (hasVisibility) continue;

      missing++;
      if (!args.includeIncome && data.category === INCOME_CATEGORY) {
        skippedIncome++;
        continue;
      }

      toUpdate++;
      const path = `groups/${group.id}/expenditures/${exp.id}`;
      console.log(
        `  ${args.apply ? 'SET ' : 'WOULD SET'} visibility=public  ${path}` +
          `  (cat=${data.category ?? '?'}, contributor=${data.contributorUid ?? '?'})`,
      );

      if (args.apply) {
        batch.update(exp.ref, { visibility: 'public' });
        batchCount++;
        if (batchCount >= BATCH_LIMIT) await flush();
      }
    }
  }
  await flush();

  console.log('\n──────── summary ────────');
  console.log(`expenditures scanned : ${scanned}`);
  console.log(`missing visibility   : ${missing}`);
  console.log(`  → income skipped   : ${skippedIncome}`);
  console.log(`  → to set public    : ${toUpdate}`);
  console.log(args.apply ? `updated (committed)  : ${updated}` : 'updated (committed)  : 0 (dry run)');
  if (!args.apply && toUpdate > 0) {
    console.log('\nRe-run with --apply to write these changes.');
  }
}

main().catch((err) => {
  console.error('\nBackfill failed:', err.message);
  process.exitCode = 1;
});
