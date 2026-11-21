# Prototype privacy behavior

- Receipt content is used for local OCR and user-confirmed expense recording.
- No backend, analytics or advertising SDK is configured. The manifest merger explicitly removes the Internet permission contributed by OCR dependencies; the final APK is checked for its absence.
- Files are copied to the application's private `files/receipts` directory using generated filenames. Content URIs from user-authorized sharing/document selection are read; arbitrary filesystem paths and remote URLs are not fetched.
- The importer checks actual bytes, bounded stream length and image/PDF decoding rather than trusting a source filename or declared file size.
- Room stores drafts, saved expenses, raw text and account nicknames in private application data. Account fields do not include full card numbers, passwords, PINs or OTPs.
- Deleting a record deletes its database row and attempts to remove its private original. Uninstalling the app clears application data.
- Automatic Android backup is disabled. Local device security protects app data; this prototype does not add database encryption or a separate biometric lock.
- CSV export writes to the destination the user chooses, and that destination may be a cloud provider. The export contains saved transaction fields and account nicknames; original attachments and drafts are excluded.
- This is development documentation for the current prototype, not a completed store privacy declaration.
