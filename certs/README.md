# Module signing material

The helper creates a self-signed **development** signing identity:

```bash
./scripts/create-self-signed-module-cert.sh
```

Its defaults include a public development password and example certificate subject. For a release identity, supply your own `MODULE_SIGNING_PASSWORD` through a private environment or secret store and set `MODULE_SIGNING_DNAME` to the intended certificate subject **before creating a new keystore**. Do not place those values in committed files or shell transcripts. An existing keystore still requires its original password; changing an environment variable does not rotate its key or password.

Signing uses `scripts/sign-module.sh`. The helpers also accept `MODULE_SIGNING_DIR`, `MODULE_SIGNING_KEYSTORE`, `MODULE_SIGNING_CERT_PEM`, `MODULE_SIGNING_CHAIN` and `MODULE_SIGNING_ALIAS` as applicable. Retain the same protected signing identity across releases when you intend to preserve certificate continuity. Back it up privately; do not delete or regenerate an existing identity as part of repository cleanup.

Generated keystores, certificates, chains and downloaded signing tools are Git-ignored. Release attachments should contain the signed `.modl` and its checksums, never private keys, keystores, passwords or Gateway backups. Public certificates/signatures embedded in the signed module are expected and do not contain its private signing key.
