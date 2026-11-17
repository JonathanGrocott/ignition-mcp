# Publishing signed modules

Keep the Gateway module and Designer Assistant in this repository. They have separate module IDs and version numbers. Publish the compatible pair on one GitHub release so an administrator can download both from the same tested commit. The first pair prepared on the Designer Assistant branch is **Ignition MCP 0.5.1** and **Designer Assistant 0.4.7** for **Ignition 8.3.9**.

## Branch builds and GitHub Releases

The [Build workflow](../.github/workflows/build.yml) builds, tests, and signs both modules on branch pushes. The two archives under **Artifacts** contain the signed `.modl` files and checksums. They use a development signing certificate and the workflow requests 30 days of retention. Use the artifacts from the same run for evaluation on a test Gateway. [GitHub artifact retention](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/remove-workflow-artifacts)

The current [Build and Release workflow](../.github/workflows/release.yml) publishes only the Gateway artifact and uses the signing helper's development fallback on a clean runner. Update that workflow to sign and attach both modules with the release identity before using it for the first paired release. A `v*` tag currently triggers publication. Coordinate any new release tag with the workflow change.

The latest published release is [Ignition MCP 0.5.0](https://github.com/JonathanGrocott/ignition-mcp/releases/tag/v0.5.0). It contains the Gateway module. The [Designer Assistant evaluation build](https://github.com/JonathanGrocott/ignition-mcp/actions/runs/43994853317) contains the paired versions described in this guide.

## Build and verify a candidate

Choose a commit on which the branch CI and Windows discovery job have passed. Update the [changelog](../CHANGELOG.md) and [Designer Assistant guide](DESIGNER_ASSISTANT.md) with the exact paired versions and download link when publishing.

Build the candidate with JDK 17 from a clean checkout.

```sh
./gradlew clean test :zipModule verifyModulePackage :designer-assistant:zipModule
node --test gateway/src/test/js/mcp-admin.test.cjs
```

Sign on a trusted workstation or in a protected GitHub release job with the existing release keystore and chain. Keep signing credentials outside the repository. Before signing, confirm the chain's SHA-256 fingerprint matches the previous public release if maintaining certificate continuity. The 0.5.0 module's signing certificate fingerprint is `63:3C:42:A8:2E:98:01:A0:3C:3D:A9:98:82:BB:E3:7A:8E:5E:49:15:11:51:3F:BB:C6:6A:1E:B7:86:58:C6:2A`. For a planned certificate change, document the new fingerprint and the expected trust prompt. See the [signing material guide](../certs/README.md).

```sh
: "${MODULE_SIGNING_KEYSTORE:?Set the release keystore path}"
: "${MODULE_SIGNING_CHAIN:?Set the release certificate chain path}"
: "${MODULE_SIGNING_ALIAS:?Set the release key alias}"
: "${MODULE_SIGNING_PASSWORD:?Load the release key password privately}"
test -s "$MODULE_SIGNING_KEYSTORE" && test -s "$MODULE_SIGNING_CHAIN" || exit 1
export MODULE_SIGNING_KEYSTORE MODULE_SIGNING_CHAIN MODULE_SIGNING_ALIAS MODULE_SIGNING_PASSWORD
MODULE_IN="$PWD/build/ignition-mcp.unsigned.modl" \
MODULE_OUT="$PWD/build/ignition-mcp.modl" \
./scripts/sign-module.sh
MODULE_IN="$PWD/designer-assistant/build/ignition-designer-assistant.unsigned.modl" \
MODULE_OUT="$PWD/designer-assistant/build/ignition-designer-assistant.modl" \
./scripts/sign-module.sh
(cd build && shasum -a 256 ignition-mcp.modl > ignition-mcp.modl.sha256)
(cd designer-assistant/build && shasum -a 256 ignition-designer-assistant.modl > ignition-designer-assistant.modl.sha256)
```

Inspect each signed module's `module.xml` for the intended ID and version. Verify every payload signature against the signing certificate. Install the exact signed pair on an isolated 8.3.9 Gateway, complete any restart, and verify the running versions. Open Designer, test provider authentication and authorized project reads, and exercise review and apply on a disposable resource. Record the platforms and providers exercised in [validation](VALIDATION.md).

## Publish the pair

For the first paired release, stage these four assets in a draft release. In the release notes, list both versions, the supported Ignition version, the signing fingerprint, and the [installation instructions](DESIGNER_ASSISTANT.md).

- `ignition-mcp.modl`
- `ignition-mcp.modl.sha256`
- `ignition-designer-assistant.modl`
- `ignition-designer-assistant.modl.sha256`

Keep the release tied to the exact source commit and use the Gateway version for the tag, such as `v0.5.1`. Use a prerelease if full Windows Designer or live provider acceptance is still pending. After publication, download both modules and verify the published checksums and signer fingerprints against the tested pair. [Managing GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)
