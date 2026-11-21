# Expense

Android personal expense tracker for Malaysia, with MYR as the default currency.
Share a receipt to the app, review the extracted fields, and save the expense. The interface is English. Quick save can skip the review screen for complete, unambiguous receipts.
Built with Kotlin, Jetpack Compose, Room and bundled on-device ML Kit OCR.

## First prototype

- Android share target for one image, text receipt or PDF; also import through the system document picker.
- Private copy of the original receipt, image / PDF first-page preview, Chinese and Latin OCR.
- Review screen with amount, currency, transaction date, category, merchant, payment method, account and notes.
- Persistent drafts, editing, deletion with confirmation, identical-receipt duplicate warning.
- Home summary, searchable records, category/account filters, monthly totals by category, payment method and account.
- Wallet accounts seeded for ShopeePay, TNG eWallet, GrabPay and Boost. Add other wallets, banks and cards; rename nicknames or deactivate accounts without breaking historical references.
- CSV export through Android's file picker. Saved records only; spreadsheet formulas in text fields are neutralized.

The app requests no Internet, SMS, notification access, accessibility, screen capture, camera or broad storage permissions. OCR models are bundled. Original receipts and the Room database stay in app-private storage. Android automatic backup is disabled for this prototype.

## Version 0.3.0

- English interface, application name, categories, payment methods, error messages and generated exports. Original receipt text and user-authored names/notes are preserved.
- **Settings → Quick save imported receipts**: off by default. When enabled, receipts with a clear amount, currency and transaction date save automatically. Duplicates, incomplete/ambiguous fields, negative amounts and payment-status warnings stay as drafts. Saved records remain editable. Merchant/category/account may be unspecified until edited.
- Optional **Receipt / Invoice No.** and **Transaction time** fields appear in the editor, records, search and CSV. Labelled reference numbers and unambiguous 12/24-hour times are extracted when available. Missing numbers/times never use the screenshot timestamp as a substitute.
- Account editing supports provider/bank, nickname and optional card last four; successful additions show **Account added**.
- Database version 1 → 2 migration preserves account IDs, expense links, exact amounts and receipt paths; built-in labels move to English without changing user-authored content. No destructive migration fallback.

### Installing the new preview alongside an earlier test build

The provided 0.3 preview uses package `com.winsonchai.expense.preview` and label **Expense Preview**. It installs alongside the earlier prototype and keeps its data separate. Keep the old app installed to retain its records; CSV is not a full backup and this preview does not automatically import the old app's records. The earlier test signing key was not retained, so an in-place update of that installed test build is unavailable.

The original `com.winsonchai.expense` application ID remains the debug/default source target, with a tested schema 1 → 2 migration. Its APK cannot replace an older installation unless signed with that installation's key. CI publishes the separate preview APK for testing. CI uses its own temporary debug key, so it cannot necessarily update the separately supplied APK. For a consistent local preview signature, set `EXPENSE_PREVIEW_KEYSTORE` to the same private test keystore for each build; never commit signing keys.

To build the installable preview use `./gradlew :core:test :app:assemblePreview :app:lintPreview` and install `app/build/outputs/apk/preview/app-preview.apk`.

**Status:** development prototype. See [validation](docs/validation.md) for the checks completed and device tests still required. ShopeePay and TNG compatibility must be verified against their actual share output; wallet integration is not assumed.

## Build and install

Requirements: Android Studio with JDK 17+, Android SDK 35 and Build Tools 35.0.0. Minimum supported device: Android 8.0 (API 26).

1. Open this repository in Android Studio and allow Gradle sync.
2. Let Android Studio create `local.properties` for your SDK location.
3. Connect an Android device or start an emulator, then Run `app`.

Or use the command line:

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
python3 scripts/test_migration.py
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions runs the same checks and uploads a debug APK and test/lint reports. Open the repository's **Actions → Android checks → successful run → Artifacts → expense-preview**. Download and unzip the artifact to obtain the APK. The debug package is for testing; store publication, release signing and a stable release key are not configured.

## Use

1. In a wallet receipt page, select **Share → Expense** if offered by that wallet.
2. Review the original / recognized text and fill missing fields. Imported dates stay empty when not recognized; the screenshot time is never substituted.
3. Check amount, currency and account. Platform branding by itself does not select a wallet account.
4. Confirm that the receipt represents a completed purchase, then save. Drafts are excluded from statistics.
5. Alternatively choose **Add expense** or **Import receipt** on the home screen.

Limits: one file at a time, 20 MiB maximum, PDF up to 5 pages, shared text up to 100,000 characters. Images are downsampled before OCR. PDFs are rendered and recognized page by page. Invalid/encrypted PDFs and OCR failures remain drafts with an explanation and manual entry available. Only the first PDF page is previewed in this prototype; extracted text covers accepted pages.

## Accounting behavior and limitations

- Money is stored as integer minor units, with currency precision validated; currencies are summarized separately without exchange-rate conversion.
- Text extraction uses conservative receipt rules. Unambiguous labelled totals take priority over balances; uncertain amounts/dates require review. Numeric OCR extraction currently targets dot decimals with two places and RM / supported ISO currency codes. Unsupported formats can be entered manually.
- Refunds, failed/pending payments, top-ups, transfers and card repayments trigger a warning when recognized. The app records positive expenses only; it does not implement an asset ledger or refund accounting.
- Receipt hashes identify identical bytes / identical shared text. They do not detect every duplicate transaction, and same date/amount alone is not used to reject purchases.
- Merchant and category default to manual review. No bank APIs, automatic transaction monitoring, voice commands or protected-screen capture are implemented.
- CSV is an analysis export, **not a restorable backup**: it excludes attachments and drafts. Full backup/restore, app lock, database encryption and cloud synchronization are not implemented. Uninstalling the app removes local records and originals.

## Structure

- `core/`: pure Kotlin receipt parsing, exact money validation and CSV escaping, with unit tests.
- `app/`: Compose UI, Room storage, share/picker intake, local receipt copy and OCR.
- `docs/`: scope, privacy and manual acceptance tests.
- `.github/workflows/android.yml`: repeatable checks and debug APK artifacts.

## Reference documentation

- [Android: receive shared data](https://developer.android.com/training/sharing/receive)
- [ML Kit: on-device text recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [Room](https://developer.android.com/training/data-storage/room)
- [AGP 8.9 compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
