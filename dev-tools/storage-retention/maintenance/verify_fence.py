#!/usr/bin/env python3
"""Copy the reviewed ConfigMap projection into a private regular file, then verify it."""

import json
import os
from pathlib import Path
import sys
import tempfile

SOURCE = Path("/var/run/mojito-maintenance-approval/manifest.json")
DIRECTORY = Path("/tmp/mojito-maintenance")


def main():
    with SOURCE.open("rb") as stream:
        data = stream.read(65537)
    if len(data) > 65536:
        raise ValueError("Manifest exceeds budget")
    manifest = json.loads(data)
    for field, suffix in (("context", "CONTEXT"), ("namespace", "NAMESPACE"),
                          ("runId", "RUN_ID"), ("fenceId", "FENCE_ID"),
                          ("destinationRoot", "DESTINATION_ROOT"), ("approvalReference", "APPROVAL_REFERENCE")):
        expected = os.environ.get("MOJITO_MAINTENANCE_" + suffix)
        if not expected or manifest.get(field) != expected:
            raise ValueError("Manifest differs from the reviewed pod request")
    directory = DIRECTORY
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=directory, prefix="manifest-", delete=False) as temporary:
        temporary.write(data)
    path = directory / "manifest.json"
    os.replace(temporary.name, path)
    environment = dict(os.environ, MOJITO_STORAGE_FENCE_MANIFEST=str(path))
    os.execve("/opt/mojito-maintenance/verify-maintenance-fence.py",
              ["verify-maintenance-fence.py", *sys.argv[1:]], environment)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, AttributeError):
        print("Maintenance manifest could not be prepared", file=sys.stderr)
        sys.exit(1)
