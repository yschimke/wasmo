# Installing and updating apps

On your computer's home page, open **Install App** and enter the `.wasmo` archive URL and app name.
To update an app, use the same app name and the new archive URL. Only install archives from sources
you trust: an update runs with the existing app's access to its stored data.

Installing over an existing name reuses that installation, including its SQL databases, object
storage, settings, and app URL. It does not create a second launcher entry. You can also repeat an
install to retry a failed download. The previous release stays selected if downloading or validating
the new archive fails. Installation runs asynchronously after the dialog closes.

Publish updated assets with a new manifest `version`. Resources are stored under that version;
reusing a version with different content overwrites its resources. The app's `afterInstall` hook
receives the previous manifest version (or `0` for a first successful install) and the new version,
so it can migrate existing data. App authors should make migrations safe to retry.

There is currently no delete/uninstall control. Updating in place avoids deleting data to install a
new release. Installing under another name creates an independent installation with its own data.
