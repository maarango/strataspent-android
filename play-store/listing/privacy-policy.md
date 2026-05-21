# StrataSpent — Privacy Policy

_Last updated: 2026-05-20_

StrataSpent ("the app") helps you track shared expenses across groups.
This policy explains what data we handle and why.

## Data we collect

| Data | Why | Where it goes |
|------|-----|---------------|
| Email address & display name | Identify you across devices and groups | Firebase Authentication (Google) |
| Group membership and expenditure entries | Show shared balances to group members | Cloud Firestore (Google) |
| Standing income amount (optional) | Compute your personal financial flow | Cloud Firestore (Google) |
| FCM device token | Deliver reminder push notifications | Cloud Firestore (Google) |
| Receipt photos you choose to scan | Extract category/amount/date via OCR | Sent to Google Gemini; not retained by the app |
| Voice transcripts | Parse natural-language expense descriptions | Sent to Google Gemini; not retained by the app |

We do not collect analytics, advertising IDs, or third-party trackers.

## Data we do NOT collect

- We do not access your contacts.
- We do not access your location.
- We do not access photos other than the one you explicitly select when
  scanning a receipt.
- We do not record raw audio for voice input; only the transcript Google's
  speech recognizer returns is processed.

## Data sharing

- Other members of any group you join can read every expenditure logged
  to that group (except entries you mark "private").
- Receipt images and voice transcripts are sent to Google's Gemini API
  for parsing. Google's data-use policy for Generative AI APIs applies.
- We do not sell or share your data with advertisers.

## Data retention

- Expense and group data persists in Cloud Firestore for as long as the
  group exists. Removing a group deletes its history.
- Account deletion: contact the app maintainer to delete your
  Firebase Auth account; this also removes your user document. Group
  membership references your uid/email; removing a member from a group
  is handled in-app by the group creator.

## Children

StrataSpent is not directed at children under 13. We do not knowingly
collect information from children.

## Changes to this policy

Material changes will be reflected in this document with an updated
"last updated" date.

## Contact

For privacy questions, contact the maintainer through the GitHub
repository.
