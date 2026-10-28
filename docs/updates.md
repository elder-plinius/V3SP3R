# Manual, channel-pinned update checks

## User behavior

Settings → About → **Check for updates** performs an explicit, unauthenticated GitHub release lookup. It does not poll in the background, send AI-provider keys, download an APK, install anything, uninstall the app, clear data, or switch repositories.

This proposed upstream source build defaults to `elder-plinius/V3SP3R`, channel `upstream-stable`. Fork builds can pin a different repository and channel explicitly at build time. Merging a PR does not change an already installed build's source. A Git commit or merged PR is not itself a published APK release; no upstream release is presumed to carry the metadata contract below.

Only a strictly greater **Android versionCode** can produce **View update release**. Version names, tags, dates, GitHub's “latest” designation and list ordering do not determine upgrade ordering. Older releases report that the installed build is ahead; equal versions are not offered.

An offer also requires matching package name, repository, channel and complete signing-certificate fingerprint set; an appropriate minimum SDK; an attached APK; and all capabilities required by the installed build. Releases lacking explicit metadata are ignored, not guessed from their tag. Drafts and GitHub prereleases are excluded. The custom channel's name does not change that policy.

Matching release metadata is **not verification of downloaded APK bytes**. The button opens the validated GitHub release page; Android's package installer must still validate the actual APK. Never uninstall or clear app data to resolve a signature mismatch. This checker cannot guarantee that a publisher's capability declarations are truthful or that an update has no bugs.

## Build configuration

The proposed source targets versionCode `7`, versionName `1.0.6-grounded-assist`. An older APK already built or installed cannot acquire the button merely because this source changed.

Repository and channel are build-time properties:

- `-Pvesper.updateRepository=psigho/V3SP3R` (example fork override)
- `-Pvesper.updateChannel=psigho-preview` (example fork override)

The upstream defaults are a proposed policy, not a promise that a compatible signed release exists. A maintainer can override them for their release line. An already installed fork does not silently migrate to upstream. Different signing keys also prevent a normal in-place update; a PR does not transfer signing identity. Private keys must never be committed or attached to a release. Retain the existing signing key for ongoing builds of the same installation channel.

`UPDATE_CAPABILITIES` is the installed build's required set. The current set is:

- `multi-provider-v1`
- `exact-model-id-v1`
- `safe-update-check-v1`

Do not remove those capability declarations merely to make an otherwise incompatible release appear eligible. New required capabilities should be versioned deliberately.

## Release metadata contract

Attach the actual signed APK to a normal, published GitHub release. Add exactly one fenced `vesper-update-v1` JSON block to its release notes. All values must describe the **actual attached APK**, not a planned build. Obtain package/version/minimum SDK from the built APK and fingerprints from `apksigner verify --print-certs`. APK filename must match the attached asset. The following is an **illustrative, deliberately invalid placeholder**, not a release announcement or usable manifest:

````markdown
```vesper-update-v1
{
  "schemaVersion": 1,
  "repository": "elder-plinius/V3SP3R",
  "channel": "upstream-stable",
  "packageName": "com.vesper.flipper",
  "versionCode": 4,
  "versionName": "EXAMPLE-NOT-PUBLISHED",
  "minSdk": 26,
  "signerSha256": ["REPLACE_WITH_ACTUAL_64_CHARACTER_LOWERCASE_SHA256"],
  "capabilities": ["multi-provider-v1", "exact-model-id-v1", "safe-update-check-v1"],
  "apkAsset": "vesper.apk"
}
```
````

Numeric fields must be JSON numbers, not quoted strings. Every signer must be listed. Exact identities are compared; metadata from another channel is not a fallback. Use ordinary release tags and ASCII APK filenames; unsafe or percent-encoded asset URLs are not accepted by this initial conservative implementation.

## Network and error handling

- Fixed HTTPS GitHub API origin; no authentication headers or AI credentials.
- Redirects are refused, with a bounded timeout and response size.
- Cancellation cancels the HTTP call.
- One page, up to 100 releases. If GitHub indicates another page, the check fails closed rather than claiming it examined all releases.
- Missing repository, rate limiting, malformed metadata and network errors cannot become an update offer.
- A repository without releases produces a no-releases state, not a downgrade suggestion.

No release, fork push or PR is created by checking for updates or by this source change. Publishing and upstream submission remain separate explicit actions.
