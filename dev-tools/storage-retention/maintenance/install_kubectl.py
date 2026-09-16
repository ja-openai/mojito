"""Build-time installation from the official versioned release, with a required reviewed digest."""

import hashlib
import os
from pathlib import Path
import re
import sys
import urllib.request


def install(image, architecture, version, checksum):
    if not re.fullmatch(r"[^\s]+@sha256:[0-9a-f]{64}", image):
        raise ValueError("The Mojito base image must be pinned by SHA-256 digest")
    if architecture not in ("amd64", "arm64") or not re.fullmatch(r"v1\.\d+\.\d+", version):
        raise ValueError("Provide a reviewed kubectl version and supported target architecture")
    if not re.fullmatch(r"[0-9a-f]{64}", checksum):
        raise ValueError("Provide the independently reviewed kubectl SHA-256")
    url = f"https://dl.k8s.io/release/{version}/bin/linux/{architecture}/kubectl"
    target = Path("/usr/local/bin/kubectl")
    temporary = target.with_suffix(".download")
    digest, count = hashlib.sha256(), 0
    try:
        with urllib.request.urlopen(url, timeout=30) as response, temporary.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                count += len(chunk)
                if count > 128 * 1024 * 1024:
                    raise ValueError("kubectl download exceeded its size budget")
                digest.update(chunk)
                output.write(chunk)
        if digest.hexdigest() != checksum:
            raise ValueError("kubectl checksum mismatch")
        temporary.chmod(0o555)
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    install(*sys.argv[1:])
