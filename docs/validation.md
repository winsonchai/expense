# Validation and acceptance

## Version 0.4.0 photo selection

Automated checks and device acceptance are separate. New core tests check selected amounts (thousands separators, precision, negatives, overflow and ambiguous values), selected dates/references and preview letterboxing coordinates.

Device acceptance remains pending:

- Scan receipt: camera opens, full-size photo returns, original is privately retained, and the frozen-photo selector opens after OCR.
- Cancel camera, camera unavailable, zero-byte capture, or OCR failure: no saved expense; import/manual entry remains usable.
- Quick save enabled: a captured receipt stays a draft and waits for explicit Save expense.
- Select Amount / Receipt No. / Date and tap matching words: only the selected field changes; leading zeroes in references remain intact. Invalid or ambiguous amounts/dates show feedback and do not replace existing values.
- Imported portrait/landscape/EXIF-rotated photos: boxes and taps align with the displayed upright image, including centered letterbox margins.
- Small text: Show detected text opens accessible larger buttons; entered values remain editable.
- Rotate while the camera, recognition or selector is open; background/resume; reopen draft: no duplicate receipt import, field changes remain available.
- Done/back returns to the form; save updates records/statistics; duplicate-receipt warnings and account/currency validation still apply.
- Build with the retained preview signing key and confirm the APK can update the previous 0.3 preview without uninstalling or losing records.

## Version 0.3.0 installation boundary

The supplied preview package installs alongside the old prototype because the original debug signing key was not retained. Existing installed-app data stays with that application and is not imported by the preview. The schema migration test below validates the original application ID's data upgrade, separately from installing the isolated preview.

## Version 0.3.0 local results

- 13 core unit tests passed; 3 SQLite upgrade tests passed.
- The original-package debug build and Android lint passed with no errors.
- Preview-package build, signature and permission checks are recorded at delivery after completion.
- The previous 0.2 APK was inspected to confirm its version and original table shape. Its signing certificate differs from the newly generated test key; the delivered preview therefore has a different package ID and separate data.
- Device UI acceptance remains pending. Automated SQLite migration tests are not Android instrumentation tests.

## Version 0.3.0 checks

Run `./gradlew :core:test :app:assembleDebug :app:lintDebug`, `python3 scripts/test_migration.py` and the packaged permission check. The migration test exercises the shipped SQL against an original-schema SQLite database, verifies stored data and compares the resulting schema with Room's exported v2 schema.

New device acceptance cases (not yet verified on a device): English labels on every tab/dialog/error; account edits and success acknowledgement; Quick save off/on persistence after restart; complete receipts saved once with Quick save on; duplicates/missing fields/refunds/declined payments held as drafts; invoice/time display, search and CSV; update with old records still present and linked to their accounts.

## Original prototype automated checks

Run `./gradlew :core:test :app:assembleDebug :app:lintDebug`.

Core tests cover labelled totals vs. balances/discounts; ambiguous totals, currencies and invalid dates; wallet-brand vs. explicit-payment-method distinction; failed/transfer/refund warnings; exact minor units, zero/negative values, excess precision and overflow; and CSV quoting/formula neutralization.

Local checks completed on 2026-11-21:

- Core unit tests: 7 passed, 0 failed.
- `:app:assembleDebug` and `:app:lintDebug`: successful; no lint errors. Lint reports non-blocking warnings; the packaged manifest was additionally inspected for its icon, backup rules and permissions.
- Packaged APK permission check: passed. Only `ACCESS_NETWORK_STATE` and the app-private AndroidX receiver permission are declared; no Internet or high-risk intake permissions.
- APK signature verification: passed. Package `com.winsonchai.expense`, version `0.1.0`, minimum Android API 26, target API 35.
- `git diff --check`: passed.

The GitHub Actions workflow repeats automated checks for PRs and uploads test artifacts. A successful build is not evidence of wallet/device compatibility.

## Device acceptance — not yet verified

Use synthetic receipts first. Never commit personal receipts, card details, or account credentials to this public repository.

| Case | Expected result |
| --- | --- |
| Text share, cold start / app already open | One new draft, original text visible; no saved expense until confirmed |
| Image share from gallery | Image copied, preview and OCR; editable fields |
| PDF share from file manager, 1–5 pages | First-page preview, OCR across pages, original retained |
| ShopeePay / TNG receipt | Check whether actual wallet supports system share, which MIME type/permissions it sends, and whether app appears |
| Manual expense, restart app | Exact amount and selected account persist |
| Enter draft fields, leave and reopen | Draft changes persist; excluded from totals |
| Rotate during OCR; reopen an interrupted draft | No duplicate import on rotation; interrupted processing leaves a recoverable draft |
| Share another receipt while processing | Clear message to retry once current processing finishes |
| Balance, discount and total present | Correct labelled total suggested; user still reviews |
| Multiple totals / currencies | Amount not silently chosen; warning shown |
| Missing / multiple / invalid date | Date left blank for user; no screenshot date inference |
| Failed payment, refund, top-up, transfer | Warning; no automatic saved expense |
| Exact same file shared twice | Duplicate warning; saving requires explicit acknowledgement |
| Same date and amount, different receipt | Not rejected just for same amount/date |
| Link-only / empty / long text | Link warning or intake error; manual entry possible |
| More than 20 MiB / 6-page PDF / corrupt / encrypted file | Useful error; no confirmed expense; retained draft where possible |
| No URI read grant / file deleted by source | Read error; app remains usable |
| Photo with EXIF rotation | Preview / OCR use corrected orientation |
| Deactivated account | Hidden for new selections; historical record keeps association |
| Edit an existing payment account | Brand/bank, nickname and card last four can be changed; saving updates the same account ID and retains expense links |
| Edit account with blank name/bank or invalid last four | Save is disabled with a validation message; cancel discards edits; clearing optional last four is allowed |
| Rotate while editing an account; save fails | Entered fields survive rotation; a failed write leaves the editor open for retry |
| Add a valid payment account | After the database write completes, an “已新增账户” dialog names the saved account; “确定” dismisses it |
| Invalid account or failed account write | Validation/error message appears; no success dialog is shown |
| Tap add repeatedly while saving; rotate with success dialog open | One account is created per submission; success dialog survives rotation and stays dismissed after confirmation |
| Edit / delete saved record | Summary updates; delete confirmation precedes removing local receipt |
| MYR + USD in same month | Separate totals; no cross-currency addition |
| Export CSV, including formula-like merchant | Confirmed expenses only; quoted fields; formulas neutralized |
| Open app offline | Entry, OCR and records work with bundled models |

## Deferred from the original design

Full backup/restore, preview of every PDF page, structured import instrumentation tests, release signing, store distribution and wallet/device acceptance remain follow-up work. This branch establishes the first implementation of the share → review → save loop and basic ledger.
