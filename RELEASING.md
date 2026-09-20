# Releasing the Analyzer Bridge

Publishing a GitHub Release is what ships the image. Tagging alone does nothing:
`docker-build-master.yml` triggers on `release: types: [published]`, and until that
fires, no versioned image exists for anyone to pin.

## Steps

1. **Bump the version on a release branch.** Edit `<version>` in `pom.xml` only.
   `astm-http-lib/pom.xml` carries its own version and has never moved with the
   parent. Branch name `release/<version>`, commit message
   `chore(release): bump version to <version>`.

2. **Open a PR against `develop` and merge it.** Releases are cut from `develop`;
   `master` has not tracked it since 3.0.4 and is not part of this flow.

3. **Publish a GitHub Release** whose tag is the bare version (`3.1.1`, no `v`),
   targeting the merge commit the previous step created on `develop`, titled
   `Analyzer Bridge <version>`. Publishing, not drafting, is what triggers the build.

4. **Confirm the image.** `docker pull itechuw/openelis-analyzer-bridge:<version>`.

## What publishing moves

`docker-build-master.yml` pushes the versioned tag **and force-moves `:latest`** on
both `itechuw/openelis-analyzer-bridge` and the legacy `itechuw/astm-http-bridge`
name. Anything tracking `:latest` therefore moves the moment the release is
published, so publish deliberately.

`:develop` is separate: `docker-build-dev.yml` republishes it on every push to
`develop`, which is what the Madagascar distro and the UAT servers float on.

## Precedent

| Version | Bump commit | Release target | Published |
|---|---|---|---|
| 3.1.0 | `05c49fa` (PR #53) | `cc978c1` (the merge commit on `develop`) | 2026-09-09 |
| 3.0.5 | PR #51 | `develop` | 2026-08-27 |
