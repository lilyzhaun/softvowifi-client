"""Ephemeral signing material; only the public certificate fingerprint may be printed."""

import base64
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

KEYSTORE = Path(".secrets/vowifi-observer.keystore")
PASSWORD = Path(".secrets/vowifi-signing.password")


def prepare():
    keystore = base64.b64decode(os.environ["APK_KEYSTORE_BASE64"], validate=True)
    password = os.environ["APK_KEYSTORE_PASSWORD"].encode()
    if not keystore or not password or b"\n" in password or b"\r" in password:
        raise ValueError("invalid signing material")
    KEYSTORE.parent.mkdir(mode=0o700, exist_ok=True)
    for path, raw in ((KEYSTORE, keystore), (PASSWORD, password)):
        with path.open("xb") as output:
            os.fchmod(output.fileno(), 0o600)
            output.write(raw)


def cleanup():
    for path in (KEYSTORE, PASSWORD):
        path.unlink(missing_ok=True)


def verify(apk):
    expected = os.environ["APK_SIGNING_CERT_SHA256"].lower()
    if re.fullmatch(r"[0-9a-f]{64}", expected) is None:
        raise ValueError("signing certificate pin is missing")
    sdk = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ["ANDROID_HOME"])
    result = subprocess.run(
        [str(sdk / "build-tools/36.0.0/apksigner"), "verify", "--verbose", "--print-certs", apk],
        check=True, capture_output=True, text=True, timeout=60,
    ).stdout
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", result)
    if certificates != [expected] or "Verified using v3 scheme (APK Signature Scheme v3): true" not in result:
        raise ValueError("APK signature does not match approved certificate")
    print("APK signing certificate verified")


def fingerprint():
    raw = subprocess.run(
        ["keytool", "-exportcert", "-keystore", str(KEYSTORE), "-storepass:file", str(PASSWORD),
         "-alias", "androiddebugkey"], capture_output=True, check=True, timeout=30,
    ).stdout
    print(hashlib.sha256(raw).hexdigest())


if __name__ == "__main__":
    {"prepare": prepare, "cleanup": cleanup, "verify": lambda: verify(sys.argv[2]),
     "fingerprint": fingerprint}[sys.argv[1]]()
