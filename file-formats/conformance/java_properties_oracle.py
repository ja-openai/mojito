#!/usr/bin/env python3
"""Compare original properties fixtures with the JDK's actual Properties.load parser."""

from __future__ import annotations

import base64
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
JAVA_HELPER = """
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;

public class PropertiesOracle {
  public static void main(String[] args) throws Exception {
    Properties properties = new Properties();
    try (var reader = Files.newBufferedReader(Path.of(args[0]), Charset.forName(args[1]))) {
      properties.load(reader);
    }
    Base64.Encoder encoder = Base64.getEncoder();
    for (String key : properties.stringPropertyNames()) {
      String encodedKey = encoder.encodeToString(key.getBytes(StandardCharsets.UTF_8));
      String value = properties.getProperty(key);
      String encodedValue = encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8));
      System.out.println(encodedKey + ":" + encodedValue);
    }
  }
}
"""


def main() -> int:
    javac = shutil.which("javac")
    java = shutil.which("java")
    if javac is None or java is None:
        raise SystemExit(
            "A JDK is unavailable; install Java to run the properties oracle"
        )

    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    cases = [case for case in manifest["cases"] if case["format"] == "java_properties"]
    accepted = rejected = snapshots = normalized = skeletons = 0

    with tempfile.TemporaryDirectory(prefix="mojito-jdk-properties-") as directory:
        workspace = Path(directory)
        helper = workspace / "PropertiesOracle.java"
        helper.write_text(JAVA_HELPER, encoding="utf-8")
        subprocess.run([javac, "-d", str(workspace), str(helper)], check=True)

        for case in cases:
            encoding = case.get("encoding", "UTF-8")
            source = (ROOT / case["input"]).read_text(encoding="utf-8")
            if case.get("lineEndings") == "CR":
                source = source.replace("\r\n", "\n").replace("\n", "\r")
            elif case.get("lineEndings") == "CRLF":
                source = source.replace("\r\n", "\n").replace("\n", "\r\n")
            destination = workspace / f"{case['id']}.properties"
            destination.write_bytes(source.encode(encoding))
            result = subprocess.run(
                [
                    java,
                    "-cp",
                    str(workspace),
                    "PropertiesOracle",
                    str(destination),
                    encoding,
                ],
                capture_output=True,
                text=True,
            )
            policy = case.get("propertiesOracle")
            should_accept = policy == "accept" or (
                policy != "reject" and "expected" in case
            )
            if (result.returncode == 0) != should_accept:
                expectation = "accept" if should_accept else "reject"
                print(
                    f"{case['id']}: java.util.Properties should {expectation} this fixture "
                    f"but exited {result.returncode}\n{result.stdout}{result.stderr}",
                    file=sys.stderr,
                )
                return 1

            if result.returncode:
                rejected += 1
                continue
            accepted += 1
            if "propertiesCompiled" not in case:
                continue
            expected = json.loads(
                (ROOT / case["propertiesCompiled"]).read_text(encoding="utf-8")
            )
            actual = parsed_properties(result.stdout)
            if actual != expected:
                print(
                    f"{case['id']}: JDK properties snapshot mismatch\n"
                    f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                    f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}",
                    file=sys.stderr,
                )
                return 1
            snapshots += 1
            if "propertiesNormalized" in case:
                destination.write_bytes(
                    (ROOT / case["propertiesNormalized"]).read_bytes()
                )
                repeated = subprocess.run(
                    [
                        java,
                        "-cp",
                        str(workspace),
                        "PropertiesOracle",
                        str(destination),
                        "UTF-8",
                    ],
                    capture_output=True,
                    text=True,
                )
                if (
                    repeated.returncode
                    or parsed_properties(repeated.stdout) != expected
                ):
                    print(
                        f"{case['id']}: normalized properties do not match the original JDK dictionary\n"
                        f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                        f"actual: {json.dumps(parsed_properties(repeated.stdout), ensure_ascii=False, indent=2)}\n"
                        f"{repeated.stderr}",
                        file=sys.stderr,
                    )
                    return 1
                normalized += 1

        for case in manifest.get("sourceSkeletons", []):
            if case["format"] != "java_properties":
                continue
            encoding = case.get("encoding", "UTF-8")
            for resource, snapshot in (
                ("input", "propertiesCompiled"),
                ("localized", "propertiesLocalizedCompiled"),
            ):
                source = (ROOT / case[resource]).read_text(encoding="utf-8")
                if case.get("lineEndings") == "CR":
                    source = source.replace("\r\n", "\n").replace("\n", "\r")
                elif case.get("lineEndings") == "CRLF":
                    source = source.replace("\r\n", "\n").replace("\n", "\r\n")
                destination = workspace / f"{case['id']}-{resource}.properties"
                destination.write_bytes(source.encode(encoding))
                result = subprocess.run(
                    [
                        java,
                        "-cp",
                        str(workspace),
                        "PropertiesOracle",
                        str(destination),
                        encoding,
                    ],
                    capture_output=True,
                    text=True,
                )
                expected = json.loads(
                    (ROOT / case[snapshot]).read_text(encoding="utf-8")
                )
                actual = parsed_properties(result.stdout)
                if result.returncode or actual != expected:
                    print(
                        f"{case['id']}: source-preserving {resource} properties "
                        f"do not match the JDK dictionary\n"
                        f"expected: {json.dumps(expected, ensure_ascii=False, indent=2)}\n"
                        f"actual: {json.dumps(actual, ensure_ascii=False, indent=2)}\n"
                        f"{result.stderr}",
                        file=sys.stderr,
                    )
                    return 1
            skeletons += 1

    print(
        f"JDK Properties verified {accepted + rejected} original property fixtures "
        f"({accepted} accepted, {rejected} rejected, {snapshots} parsed-value snapshots, "
        f"{normalized} normalized writer round trips) and {skeletons} source-preserving "
        "skeletons against their original and localized native dictionaries."
    )
    return 0


def parsed_properties(output: str) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in output.splitlines():
        key, value = line.split(":", maxsplit=1)
        values[base64.b64decode(key).decode("utf-8")] = base64.b64decode(value).decode(
            "utf-8"
        )
    return values


if __name__ == "__main__":
    raise SystemExit(main())
