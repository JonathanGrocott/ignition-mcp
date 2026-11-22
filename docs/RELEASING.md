# Publishing signed modules

Keep the Gateway module and Designer Assistant in this repository. They have separate module IDs and, from 0.6.0, share one version number set by `moduleVersion` in `build.gradle.kts`. Ignition MCP works on its own; Designer Assistant is optional and needs Ignition MCP of the same version on the same Gateway. It shows a warning in the dock when the Gateway module is missing or from a different release. Publish both modules on one GitHub release so an administrator can download them from the same tested commit. The first pair prepared on the Designer Assistant branch is **Ignition MCP 0.5.1** and **Designer Assistant 0.4.7** for **Ignition 8.3.9**. Later pairs keep 8.3.9 as the minimum and support every later 8.3.x patch. CI compiles and tests each change against 8.3.9, 8.3.10 and the newest published 8.3.x SDK.

## Branch builds and GitHub Releases

The [Build workflow](../.github/workflows/build.yml) builds and tests both modules against each supported 8.3.x SDK on pushes and pull requests to `main`, and weekly. The `dev-signed-modules-not-for-release` archive under **Artifacts** contains both `.modl` files and checksums. They use a development signing certificate generated for each run and the workflow requests 30 days of retention. Use the artifacts from the same run for evaluation on a test Gateway. [GitHub artifact retention](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/remove-workflow-artifacts)

The [Build and Release workflow](../.github/workflows/release.yml) runs on a version tag. It checks the tag against the Gateway version, builds and tests both modules, signs them with the release certificate, verifies the signed payloads against the build outputs, and stages all four assets in a draft. The signing job uses the `release-signing` GitHub environment, which is restricted to release tags. Configure these environment secrets from the existing release identity.

- `MODULE_SIGNING_KEYSTORE_BASE64`
- `MODULE_SIGNING_CHAIN_BASE64`
- `MODULE_SIGNING_ALIAS`
- `MODULE_SIGNING_PASSWORD`

The signing job requires all four values and verifies the expected certificate fingerprint on each signed module. It stops if a release or draft for the tag already exists. Review the draft and exact artifacts before publishing.

## Build and verify a candidate

Choose a commit on which the branch CI and Windows discovery job have passed. Update the [changelog](../CHANGELOG.md) and [Designer Assistant guide](DESIGNER_ASSISTANT.md) with the exact paired versions and download link when publishing.

Build the candidate from a clean checkout. Gradle compiles and tests with a JDK 17 toolchain whatever JDK runs Gradle, and downloads JDK 17 into `~/.gradle/jdks` when none is detected.

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

Use `scripts/verify-signed-module.py` to verify the descriptor, expected signing fingerprint and every payload signature. Pass `--unsigned` to also compare the signed payload with the build output. Install the exact signed pair on an isolated Gateway, complete any restart, and verify the running versions. Open Designer, test provider authentication and authorized project reads, and exercise review and apply on a disposable resource. Record the platforms and providers exercised in [validation](VALIDATION.md).

## Publish the pair

For the first paired release, stage these four assets in a draft release. In the release notes, list both versions, the supported Ignition version, the signing fingerprint, and the [installation instructions](DESIGNER_ASSISTANT.md).

- `ignition-mcp.modl`
- `ignition-mcp.modl.sha256`
- `ignition-designer-assistant.modl`
- `ignition-designer-assistant.modl.sha256`

Keep the release tied to the exact source commit and use the Gateway version for the tag, such as `v0.5.1`. After validating the draft assets, publish the draft as a prerelease if full Windows Designer or live provider acceptance is still pending. Download both modules from the public release and verify the checksums and signer fingerprints against the tested pair. [Managing GitHub Releases](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)
