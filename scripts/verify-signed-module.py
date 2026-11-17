"""Verify an Ignition .modl's identity, signing certificate, and payload signatures."""

import argparse
import base64
import hashlib
import re
import subprocess
import tempfile
from pathlib import Path
from xml.etree import ElementTree
from zipfile import ZipFile


SIGNING_ENTRIES = {"certificates.p7b", "signatures.properties"}


def openssl(*arguments, data):
    return subprocess.run(
        ["openssl", *arguments], input=data, stdout=subprocess.PIPE, check=True
    ).stdout


def signing_key(chain, expected_fingerprint):
    certificates = openssl("pkcs7", "-inform", "DER", "-print_certs", data=chain)
    for certificate in re.findall(rb"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", certificates, re.S):
        der = openssl("x509", "-outform", "DER", data=certificate)
        if hashlib.sha256(der).hexdigest() == expected_fingerprint:
            return openssl("x509", "-pubkey", "-noout", data=certificate)
    raise ValueError("The expected release signing certificate is missing")


def verify(module, module_id, version, fingerprint, unsigned):
    with ZipFile(module) as signed, tempfile.TemporaryDirectory() as directory:
        names = signed.namelist()
        if len(names) != len(set(names)):
            raise ValueError("The signed module contains duplicate archive entries")
        descriptor = ElementTree.fromstring(signed.read("module.xml"))
        if (descriptor.findtext(".//id"), descriptor.findtext(".//version")) != (module_id, version):
            raise ValueError(f"Module descriptor does not match {module_id} {version}")

        key_path = Path(directory) / "verification-key.pem"
        key_path.write_bytes(signing_key(signed.read("certificates.p7b"), fingerprint))
        signature_path = Path(directory) / "payload.signature"
        signatures = {}
        for line in signed.read("signatures.properties").decode("ascii").splitlines():
            if not line.strip() or line.startswith("#"):
                continue
            name, value = line.split("=", 1)
            name = name.removeprefix("/")
            if name in signatures:
                raise ValueError(f"Duplicate signature for {name}")
            signatures[name] = base64.b64decode(value.replace(r"\=", "="), validate=True)
        payload = set(names) - SIGNING_ENTRIES
        if signatures.keys() != payload or "module.xml" not in payload:
            raise ValueError("Signatures do not cover the complete module payload")
        if unsigned:
            with ZipFile(unsigned) as original:
                original_payload = [item.filename for item in original.infolist() if not item.is_dir()]
                if len(original_payload) != len(payload) or set(original_payload) != payload:
                    raise ValueError("The signed payload differs from the build output")
                for name in payload:
                    if original.read(name) != signed.read(name):
                        raise ValueError(f"Signing changed the bytes of {name}")
        for name, signature in signatures.items():
            signature_path.write_bytes(signature)
            openssl("dgst", "-sha256", "-verify", str(key_path), "-signature", str(signature_path),
                    data=signed.read(name))
        print(f"{module}: {module_id} {version}, {len(signatures)} valid payload signatures")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("module", type=Path)
    parser.add_argument("--module-id", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--fingerprint", required=True)
    parser.add_argument("--unsigned", type=Path)
    args = parser.parse_args()
    fingerprint = args.fingerprint.replace(":", "").lower()
    if not re.fullmatch(r"[a-f0-9]{64}", fingerprint):
        parser.error("--fingerprint must be a SHA-256 certificate fingerprint")
    verify(args.module, args.module_id, args.version, fingerprint, args.unsigned)
