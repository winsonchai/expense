# Validation and acceptance

## Automated

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
| Edit / delete saved record | Summary updates; delete confirmation precedes removing local receipt |
| MYR + USD in same month | Separate totals; no cross-currency addition |
| Export CSV, including formula-like merchant | Confirmed expenses only; quoted fields; formulas neutralized |
| Open app offline | Entry, OCR and records work with bundled models |

## Deferred from the original design

Full backup/restore, preview of every PDF page, structured import instrumentation tests, release signing, store distribution and wallet/device acceptance remain follow-up work. This branch establishes the first implementation of the share → review → save loop and basic ledger.
