# Backfill: stamp `visibility=public` on field-less expenditures

A one-time maintenance script. Expenditure docs written without a `visibility`
field are treated as public by the Firestore rules, but the Android list query
(`visibility == "public"` + `contributorUid == me`) can't match a missing
field — so other members never see them. This stamps the field so they show.

Uses the **Firebase Admin SDK**: the security rules only let a member write
their own docs, so a backfill across all members' entries must run with
admin (service-account) credentials, which bypass rules.

## Get a service-account key

Firebase Console → Project Settings → **Service accounts** →
**Generate new private key**. Save the JSON somewhere outside the repo (it is a
secret — do not commit it).

## Run

```bash
cd tools/backfill-visibility
npm install

# Point at the key (either works):
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/serviceAccount.json
#   or pass --key /path/to/serviceAccount.json on each command below

# 1) DRY RUN — prints exactly what it would change, writes nothing:
npm run dry-run

# 2) Review the output, then APPLY:
npm run apply
```

On Windows PowerShell, set the env var with:
`$env:GOOGLE_APPLICATION_CREDENTIALS = "C:\path\to\serviceAccount.json"`

## Behaviour / safety

- **Dry-run by default.** Nothing is written unless you pass `--apply`.
- **Never overwrites an existing value** — `public` and `private` docs are left
  untouched, so private expenses stay private.
- **Skips `Global Income`** by default: income is per-user, and making it public
  would leak it into other members' lists. Pass `--include-income` to override.
- Defaults target this project's named database
  (`ai-studio-6193fa2f-201b-4627-bfb4-124dbca4a1c2`) and project
  (`gen-lang-client-0856853334`); override with `--database` / `--project`.

## Flags

| Flag | Default | Meaning |
|------|---------|---------|
| `--apply` | off (dry run) | Perform the writes |
| `--key <path>` | `$GOOGLE_APPLICATION_CREDENTIALS` | Service-account JSON |
| `--project <id>` | `gen-lang-client-0856853334` | Firebase project |
| `--database <id>` | `ai-studio-6193fa2f-…` | Named Firestore database |
| `--include-income` | off | Also stamp `Global Income` entries |
