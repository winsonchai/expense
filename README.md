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

## Version 0.6.0 — restore saved expenses from CSV

- In the old app, choose **Export CSV** and retain that file. In the new app, choose **Settings → Import CSV**, select the file, review the preview and press **Import records**.
- Original nine-column exports and later exports with time/receipt number are accepted. Columns are matched by name; unknown extra columns are ignored, and optional missing fields stay empty or use existing defaults. UTF-8 BOM, quoted commas, double quotes, multiline notes and supported legacy Chinese labels/headers are handled.
- Dates, times, currencies, positive exact amounts and account details are validated. Invalid rows are listed with their CSV record numbers; importing the valid subset requires an explicit choice to skip the invalid rows. Malformed CSV/UTF-8 or missing required headers reject the file without writing anything.
- Existing record IDs are always skipped. Matching expense details, including matches inside the file, are possible duplicates and skipped by default. Disable **Skip possible duplicates** only for verified separate purchases with identical details. Duplicate checks are repeated against current data inside the import transaction; existing expenses are never overwritten.
- Matching payment accounts are reused; missing accounts are created for records actually imported. Account creation and expense insertion use one Room transaction. No database schema change is needed.
- New exports add stable expense IDs, separate account fields and CSV format metadata for future versions. Spreadsheet-safety escaping remains enabled, with explicit metadata for lossless new-format round trips. Safety apostrophes already present in legacy exports are retained because their original intent cannot be reconstructed.
- CSV restores **saved expenses and their linked account details**. It does not contain receipt images/PDFs, unsaved drafts, unlinked accounts, settings or original creation timestamps, and is not a complete app backup.
- Maximum file size: 5 MiB; maximum data records: 10,000. Use an unmodified Export CSV file where possible. Ambiguous old account labels containing the separator require explicit account_provider/account_nickname columns rather than guessing.
- The supplied **Expense Tap Preview 0.6** keeps the 0.5 package ID and retained signing key, so it can update that test app. Export from an older separate app and import into this app to move its saved records. Keep the old app until you have checked the imported records.

## Version 0.5.0 — tap, classify, edit and confirm

- Tap text directly on the receipt in the expense form or enlarged photo. A confirmation window suggests Amount, Receipt No., Date, Time, Merchant or Other from the selected text and its receipt line.
- Change the field type and edit the recognized value before pressing **Confirm**. Cancel discards the selection without changing the form. Confirmation fills only the chosen field; **Save expense** still saves the expense.
- **Other** asks what the data represents and appends `label: value` to Notes, retaining existing notes. A missed text box or **Enter a field manually** opens the same editor for manual entry.
- **Enlarge photo / detected text** provides a larger photo and accessible word/line buttons for small or multi-word text. OCR and photo coordinates share the same upright image.
- Suggestions are local rules and can be wrong. Invalid amounts, dates and times cannot replace form values; references retain leading zeroes. No database migration is needed.
- The supplied **Expense Tap Preview** uses `com.winsonchai.expense.preview.tap` and installs alongside 0.4. The distributed 0.4 APK's certificate differs from the retained preview key, so an update with that key is unavailable. Keep the old app to retain its records; this separate test app starts with its own data. Future local Tap Preview builds can use the same retained test key.
- Build this separate test app with `EXPENSE_PREVIEW_KEYSTORE` set to the retained private test key and `./gradlew :core:test :app:assembleTapPreview :app:lintTapPreview`.

## Version 0.4.0 — photo and tap to fill

- **Home → Scan receipt** opens the phone's camera to capture a full-resolution still photo. Confirm the photo to freeze it and open the field selection screen.
- Choose **Amount**, **Receipt No.** or **Date**, then tap a recognized word box to fill that field. The selected field stays editable. Use **Show detected text** for larger text buttons when boxes are too small.
- **Done · Review expense** returns to the existing expense form; select currency and account, check the details, then **Save expense**. Camera receipts stay drafts even when Quick save is enabled.
- The same **Tap receipt to fill fields** action is available for imported image receipts. PDF/text intake keeps its existing review flow.
- This version introduced frozen-photo scanning. Version 0.5 adds field suggestions; live video overlays and automatic receipt cropping remain outside this flow.
- The system camera receives temporary access to one private cache file through a non-exported FileProvider. Expense adds no camera, microphone or storage permissions. Temporary captures are removed after intake/cancellation; abandoned captures older than a day are cleared on the next camera launch.

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
- CSV can restore saved expense details and their linked account fields through **Settings → Import CSV**. It excludes attachments and drafts and is not a complete backup. Full backup/restore, app lock, database encryption and cloud synchronization are not implemented. Uninstalling the app removes local records and originals.

## Structure

- `core/`: pure Kotlin receipt parsing, exact money validation, CSV parsing/export and duplicate selection, with unit tests.
- `app/`: Compose UI, Room storage, share/picker intake, local receipt copy and OCR.
- `docs/`: scope, privacy and manual acceptance tests.
- `.github/workflows/android.yml`: repeatable checks and debug APK artifacts.

## Reference documentation

- [Android: receive shared data](https://developer.android.com/training/sharing/receive)
- [ML Kit: on-device text recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [Room](https://developer.android.com/training/data-storage/room)
- [AGP 8.9 compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
