#!/usr/bin/env python3
"""Build local MF2 artifacts and test installed consumers; never publish packages."""
from __future__ import annotations

import argparse
from email.parser import BytesParser
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import tomllib
import venv
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGES = {
    "python": "python",
    "javascript": "javascript",
    "java": "java",
    "kotlin": "kotlin",
    "go": "go",
    "rust": "rust/mojito-mf2",
    "swift": "swift/MessageFormat2",
    "php": "php",
}
NOTICES = ("LICENSE", "NOTICE", "UNICODE-LICENSE.txt")
PYTHON_LICENSE_EXPRESSION = "Apache-2.0 AND Unicode-3.0"
PLURAL = ".input {$n :number}\n.match $n\nfew {{few}}\n* {{other}}"
IGNORED = shutil.ignore_patterns(
    ".git",
    "node_modules",
    ".build",
    "target",
    "__pycache__",
    ".mypy_cache",
    "*.pyc",
    "*.egg-info",
    "dist",
    "build",
    "vendor",
    ".DS_Store",
)


class Smoke:
    def __init__(self, runtime: str, output: Path):
        self.runtime = runtime
        self.output = output / runtime
        self.output.mkdir(parents=True, exist_ok=False)
        self.artifacts = self.output / "artifacts"
        self.artifacts.mkdir()
        self.source = self.output / "source"
        self.consumer = self.output / "consumer"
        self.consumer.mkdir()
        shutil.copytree(ROOT / PACKAGES[runtime], self.source, ignore=IGNORED)
        source_hash = hashlib.sha256()
        for source_file in sorted(self.source.rglob("*")):
            if source_file.is_file():
                source_hash.update(
                    source_file.relative_to(self.source).as_posix().encode() + b"\0"
                )
                source_hash.update(hashlib.sha256(source_file.read_bytes()).digest())
        self.source_sha256 = source_hash.hexdigest()
        self.commands = []
        self.results = []
        self.env = os.environ.copy()
        self.env.pop("PYTHONPATH", None)
        self.env["PYTHONDONTWRITEBYTECODE"] = "1"
        self.env["COMPOSER_NO_INTERACTION"] = "1"
        self.env["COMPOSER_HOME"] = str(self.output / "composer-home")
        self.env["COMPOSER_CACHE_DIR"] = str(self.output / "composer-cache")
        for notice in NOTICES:
            path = self.source / notice
            if not path.is_file() or not path.read_bytes().strip():
                raise ValueError(f"Missing package notice: {path}")
        if (
            self.source.joinpath("LICENSE").read_bytes()
            != (ROOT.parent / "LICENSE").read_bytes()
        ):
            raise ValueError(
                "Package Apache LICENSE differs from the repository license"
            )
        if b"Unicode" not in self.source.joinpath("NOTICE").read_bytes():
            raise ValueError("NOTICE must identify the generated Unicode CLDR data")
        if (
            b"UNICODE LICENSE V3"
            not in self.source.joinpath("UNICODE-LICENSE.txt").read_bytes()
        ):
            raise ValueError("Expected complete Unicode license text")

    def run(self, command, cwd=None, env=None):
        command = [str(arg) for arg in command]
        index = len(self.commands) + 1
        log = self.output / f"{index:02d}-{Path(command[0]).name}.log"
        self.commands.append(
            {"command": command, "cwd": str(cwd or self.source), "log": str(log)}
        )
        print(f'[{self.runtime}] {" ".join(command)}', flush=True)
        with log.open("w") as stream:
            result = subprocess.run(
                command,
                cwd=cwd or self.source,
                env=env or self.env,
                text=True,
                stdout=stream,
                stderr=subprocess.STDOUT,
            )
        if result.returncode:
            raise RuntimeError(f"{command[0]} failed ({result.returncode}); see {log}")
        return log.read_text()

    def artifact(self, path, *, python_license_expression=None):
        path = Path(path)
        entries = {}
        if path.suffix in {".whl", ".jar", ".zip"}:
            with zipfile.ZipFile(path) as archive:
                entries = {
                    name: archive.read(name)
                    for name in archive.namelist()
                    if not name.endswith("/")
                }
        else:
            with tarfile.open(path) as archive:
                entries = {
                    entry.name: archive.extractfile(entry).read()
                    for entry in archive
                    if entry.isfile()
                }
        for notice in NOTICES:
            expected = self.source.joinpath(notice).read_bytes()
            matches = [
                data for name, data in entries.items() if Path(name).name == notice
            ]
            if expected not in matches:
                raise ValueError(
                    f"{path.name} does not contain the exact {notice} notice"
                )
        if python_license_expression is not None:
            metadata_entries = [
                data
                for name, data in entries.items()
                if len(Path(name).parts) == 2
                and (
                    name.endswith(".dist-info/METADATA")
                    if path.suffix == ".whl"
                    else Path(name).name == "PKG-INFO"
                )
            ]
            if len(metadata_entries) != 1 or BytesParser().parsebytes(
                metadata_entries[0], headersonly=True
            ).get_all("License-Expression") != [python_license_expression]:
                raise ValueError(
                    f"{path.name} must declare License-Expression: "
                    f"{python_license_expression}"
                )
        result = {
            "path": str(path),
            "bytes": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "entries": len(entries),
        }
        self.results.append(result)
        return path

    def python(self):
        metadata = tomllib.loads((self.source / "pyproject.toml").read_text())[
            "project"
        ]
        assert metadata["name"] == "mojito-mf2" and metadata["version"]
        assert metadata["license"] == PYTHON_LICENSE_EXPRESSION and set(NOTICES) <= set(
            metadata["license-files"]
        )
        self.run(
            [
                sys.executable,
                "-m",
                "build",
                "--no-isolation",
                "--outdir",
                self.artifacts,
                self.source,
            ]
        )
        wheel = self.artifact(
            next(self.artifacts.glob("*.whl")),
            python_license_expression=PYTHON_LICENSE_EXPRESSION,
        )
        self.artifact(
            next(self.artifacts.glob("*.tar.gz")),
            python_license_expression=PYTHON_LICENSE_EXPRESSION,
        )
        venv.create(self.consumer / "venv", with_pip=True, symlinks=True)
        python = self.consumer / "venv/bin/python"
        self.run(
            [python, "-m", "pip", "install", "--no-index", "--no-deps", wheel],
            self.consumer,
        )
        (self.consumer / "check.py").write_text(
            """from mojito_mf2 import parse_to_model, format_message
from importlib.metadata import distribution
from pathlib import Path
p = parse_to_model("Hello {$name}!")
r = format_message(p.model, {"name":"World"})
assert r.value == "Hello World!" and not r.errors, r
p = parse_to_model("""
            + repr(PLURAL)
            + """)
r = format_message(p.model, {"n":2}, locale="ru")
assert r.value == "few" and not r.errors, r
files = distribution("mojito-mf2").files
assert {"LICENSE", "NOTICE", "UNICODE-LICENSE.txt"} <= {Path(str(f)).name for f in files}
print("PASS installed wheel API, generated CLDR, and notices")
"""
        )
        self.run([python, "-I", self.consumer / "check.py"], self.consumer)

    def javascript(self):
        metadata = json.loads((self.source / "package.json").read_text())
        assert metadata["name"] == "@mojito-mf2/core" and metadata["version"]
        assert metadata["license"] == "Apache-2.0" and metadata.get("engines", {}).get(
            "node"
        )
        self.run(
            ["npm", "pack", "--ignore-scripts", "--pack-destination", self.artifacts]
        )
        artifact = self.artifact(next(self.artifacts.glob("*.tgz")))
        (self.consumer / "package.json").write_text(
            json.dumps({"private": True, "type": "module"})
        )
        self.run(
            [
                "npm",
                "install",
                "--offline",
                "--ignore-scripts",
                "--no-audit",
                "--no-fund",
                artifact,
            ],
            self.consumer,
        )
        (self.consumer / "check.mjs").write_text(
            """import assert from 'node:assert/strict';
import { parseToModel, formatMessage } from '@mojito-mf2/core';
import { formatMessage as formatOnly } from '@mojito-mf2/core/formatter';
const model = parseToModel('Hello {$name}!').model;
for (const format of [formatMessage, formatOnly]) {
  const result = format(model, {name:'World'});
  assert.equal(result.value, 'Hello World!'); assert.deepEqual(result.errors, []);
}
const result = formatOnly(parseToModel("""
            + json.dumps(PLURAL)
            + """).model, {n:2}, {locale:'ru'});
assert.equal(result.value, 'few'); assert.deepEqual(result.errors, []);
console.log('PASS installed npm root/subpath APIs and generated CLDR');
"""
        )
        self.run(["node", "check.mjs"], self.consumer)

    def jvm(self):
        metadata = ET.parse(self.source / "pom.xml").getroot()
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        value = lambda name: metadata.find("m:" + name, ns).text
        version = value("version")
        artifact_id = value("artifactId")
        assert (
            metadata.find("m:licenses/m:license", ns) is not None
        ), "POM license metadata required"
        self.run(["mvn", "-B", "-ntp", "package", "-DskipTests"])
        jar = self.artifacts / f"{artifact_id}-{version}.jar"
        shutil.copyfile(self.source / "target" / jar.name, jar)
        self.artifact(jar)
        classpath = str(jar)
        if self.runtime == "kotlin":
            self.run(
                [
                    "mvn",
                    "-B",
                    "-ntp",
                    "dependency:build-classpath",
                    f"-Dmdep.outputFile={self.output}/dependencies.txt",
                ]
            )
            classpath += (
                os.pathsep + (self.output / "dependencies.txt").read_text().strip()
            )
            code = """var model = Mf2Parser.parseToModel(source).getModel();
      var result = Mf2Formatter.formatMessage(model, arguments, locale, Mf2BidiIsolation.NONE,
          Mf2FunctionRegistry.portable(), c -> c.getFallbackValue(), c -> c.getFallbackValue());
      if (!result.getValue().equals(expected) || !result.getErrors().isEmpty()) throw new AssertionError(result);"""
        else:
            code = """var model = Mf2Parser.parseToModel(source).model();
      var result = model.format(arguments, Mf2FormatOptions.builder().locale(locale).functions(Mf2FunctionRegistry.portable()).build());
      if (!result.value().equals(expected) || !result.errors().isEmpty()) throw new AssertionError(result);"""
        (self.consumer / "Consumer.java").write_text(
            """import com.box.l10n.mojito.mf2.*;
import java.util.Map;
public class Consumer {
  static void check(String source, Map<String,Object> arguments, String locale, String expected) throws Exception {
      """
            + code
            + """
  }
  public static void main(String[] args) throws Exception {
    check("Hello {$name}!", Map.of("name", "World"), "en", "Hello World!");
    check("""
            + json.dumps(PLURAL)
            + """, Map.of("n", 2), "ru", "few");
    System.out.println("PASS installed JAR public API and generated CLDR");
  }
}
"""
        )
        self.run(
            ["javac", "-encoding", "UTF-8", "-cp", classpath, "Consumer.java"],
            self.consumer,
        )
        self.run(
            ["java", "-cp", str(self.consumer) + os.pathsep + classpath, "Consumer"],
            self.consumer,
        )

    def rust(self):
        metadata = tomllib.loads((self.source / "Cargo.toml").read_text())["package"]
        assert (
            metadata["name"] == "mojito-mf2"
            and metadata["version"]
            and metadata["license"] == "Apache-2.0"
        )
        self.run(["cargo", "package", "--locked"])
        crate = self.artifacts / f"{metadata['name']}-{metadata['version']}.crate"
        shutil.copyfile(self.source / "target/package" / crate.name, crate)
        self.artifact(crate)
        extracted = self.output / "installed"
        extracted.mkdir()
        with tarfile.open(crate) as archive:
            archive.extractall(extracted, filter="data")
        dependency = extracted / f"{metadata['name']}-{metadata['version']}"
        (self.consumer / "Cargo.toml").write_text(
            '[package]\nname="mf2-package-consumer"\nversion="0.0.0"\nedition="2021"\n[dependencies]\nmojito-mf2={path='
            + json.dumps(str(dependency))
            + "}\n"
        )
        # Preserve the tested artifact's dependency versions in the new consumer.
        shutil.copyfile(dependency / "Cargo.lock", self.consumer / "Cargo.lock")
        (self.consumer / "src").mkdir()
        (self.consumer / "src/main.rs").write_text(
            """use mojito_mf2::{parse_to_model, format_message_with_options, ArgumentValue, Arguments, FormatOptions};
fn main() {
 let model = parse_to_model("Hello {$name}!").model.unwrap();
 let arguments: Arguments = [("name".to_string(),ArgumentValue::String("World".to_string()))].into_iter().collect();
 let result = format_message_with_options(&model, arguments, &FormatOptions::new("en")).unwrap();
 assert_eq!(result.value,"Hello World!"); assert!(result.errors.is_empty());
 let model = parse_to_model("""
            + json.dumps(PLURAL)
            + """).model.unwrap();
 let arguments: Arguments = [("n".to_string(),ArgumentValue::number(2))].into_iter().collect();
 let result = format_message_with_options(&model, arguments, &FormatOptions::new("ru")).unwrap();
 assert_eq!(result.value,"few"); assert!(result.errors.is_empty());
 println!("PASS extracted crate API and generated CLDR");
}
"""
        )
        self.run(["cargo", "run", "--quiet"], self.consumer)

    def swift(self):
        archive = self.artifacts / "MessageFormat2-source.zip"
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as package:
            for path in sorted(self.source.rglob("*")):
                if path.is_file():
                    package.write(
                        path,
                        "MessageFormat2/" + path.relative_to(self.source).as_posix(),
                    )
        self.artifact(archive)
        extracted = self.output / "installed"
        extracted.mkdir()
        with zipfile.ZipFile(archive) as package:
            package.extractall(extracted)
        (self.consumer / "Package.swift").write_text(
            """// swift-tools-version: 6.0
import PackageDescription
let package = Package(name: "Consumer", platforms: [.macOS(.v14)],
    dependencies: [.package(path: """
            + json.dumps(str(extracted / "MessageFormat2"))
            + """)],
    targets: [.executableTarget(name:"Consumer", dependencies:[.product(name:"MessageFormat2", package:"MessageFormat2")])])
"""
        )
        source = self.consumer / "Sources/Consumer"
        source.mkdir(parents=True)
        (source / "main.swift").write_text(
            """import MessageFormat2
let model = parseToModel("Hello {$name}!").model!
let result = try formatMessage(model, arguments: ["name": .string("World")])
precondition(result.value == "Hello World!" && result.errors.isEmpty)
let plural = parseToModel("""
            + json.dumps(PLURAL)
            + """).model!
let selected = try formatMessage(plural, arguments: ["n": .number("2")], locale:"ru")
precondition(selected.value == "few" && selected.errors.isEmpty)
print("PASS extracted SwiftPM API and generated CLDR")
"""
        )
        self.run(["swift", "run", "Consumer"], self.consumer)

    def go(self):
        go = os.environ.get("GO", "go")
        metadata = self.run([go, "mod", "edit", "-json"])
        module = json.loads(metadata)["Module"]["Path"]
        version = "v0.0.0-packagesmoke"
        self.env.setdefault("GOPATH", str(self.output / "go-work"))
        proxy = self.output / "proxy"
        version_dir = proxy / module / "@v"
        version_dir.mkdir(parents=True)
        artifact = version_dir / f"{version}.zip"
        with zipfile.ZipFile(artifact, "w", zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(self.source.rglob("*")):
                if path.is_file():
                    archive.write(
                        path,
                        f"{module}@{version}/"
                        + path.relative_to(self.source).as_posix(),
                    )
        shutil.copyfile(artifact, self.artifacts / artifact.name)
        self.artifact(self.artifacts / artifact.name)
        (version_dir / f"{version}.mod").write_bytes(
            (self.source / "go.mod").read_bytes()
        )
        (version_dir / f"{version}.info").write_text(
            json.dumps({"Version": version, "Time": "2000-01-01T00:00:00Z"})
        )
        (version_dir / "list").write_text(version + "\n")
        # Populate a local proxy for declared dependencies before using a fresh consumer cache.
        dependencies = self.run([go, "list", "-deps", "-json", "."])
        decoder = json.JSONDecoder()
        offset = 0
        seen = set()
        while offset < len(dependencies):
            while offset < len(dependencies) and dependencies[offset].isspace():
                offset += 1
            if offset == len(dependencies):
                break
            package, size = decoder.raw_decode(dependencies[offset:])
            offset += size
            dependency = package.get("Module")
            if not dependency or dependency.get("Main") or dependency["Path"] in seen:
                continue
            seen.add(dependency["Path"])
            downloaded = json.loads(
                self.run(
                    [
                        go,
                        "mod",
                        "download",
                        "-json",
                        dependency["Path"] + "@" + dependency["Version"],
                    ]
                )
            )
            escaped = lambda s: "".join(
                "!" + c.lower() if c.isupper() else c for c in s
            )
            target = proxy / escaped(dependency["Path"]) / "@v"
            target.mkdir(parents=True, exist_ok=True)
            for field, suffix in [
                ("Info", ".info"),
                ("GoMod", ".mod"),
                ("Zip", ".zip"),
            ]:
                shutil.copyfile(
                    downloaded[field],
                    target / (escaped(dependency["Version"]) + suffix),
                )
        (self.consumer / "go.mod").write_text(
            "module mf2-package-consumer\n\ngo 1.24.0\n\nrequire "
            + module
            + " "
            + version
            + "\n"
        )
        (self.consumer / "main.go").write_text(
            """package main
import ("fmt"; mf2 "github.com/box/mojito/mf2/go")
func main() {
 p:=mf2.ParseToModel("Hello {$name}!");r:=mf2.FormatMessage(p.Model,map[string]any{"name":"World"},mf2.Options{Locale:"en"})
 if r.Value!="Hello World!" || r.HasErrors(){panic(r)}
 p=mf2.ParseToModel("""
            + json.dumps(PLURAL)
            + """);r=mf2.FormatMessage(p.Model,map[string]any{"n":2},mf2.Options{Locale:"ru"})
 if r.Value!="few" || r.HasErrors(){panic(r)}
 fmt.Println("PASS module ZIP in fresh cache and generated CLDR")
}
"""
        )
        environment = {
            **self.env,
            "GOPROXY": proxy.as_uri(),
            "GOSUMDB": "off",
            "GOMODCACHE": str(self.output / "consumer-modcache"),
        }
        self.run([go, "mod", "tidy"], self.consumer, environment)
        self.run([go, "run", "."], self.consumer, environment)

    def php(self):
        composer = shutil.which("composer")
        command = (
            [composer]
            if composer
            else (
                ["php", os.environ["COMPOSER_PHAR"]]
                if os.environ.get("COMPOSER_PHAR")
                else None
            )
        )
        if command is None:
            raise RuntimeError("Composer is required; install it or set COMPOSER_PHAR")
        metadata = json.loads((self.source / "composer.json").read_text())
        assert (
            metadata["name"] == "mojito/messageformat2"
            and metadata["license"] == "Apache-2.0"
        )
        self.run([*command, "validate", "--no-check-publish"])
        self.run(
            [
                *command,
                "archive",
                "--format=zip",
                "--dir=" + str(self.artifacts),
                "--file=mojito-messageformat2",
            ]
        )
        artifact = self.artifact(next(self.artifacts.glob("*.zip")))
        package = {
            key: metadata[key] for key in ("name", "type", "autoload", "require")
        }
        package.update(
            version="dev-main", dist={"type": "zip", "url": artifact.as_uri()}
        )
        (self.consumer / "composer.json").write_text(
            json.dumps(
                {
                    "name": "local/mf2-package-consumer",
                    "require": {metadata["name"]: "dev-main"},
                    "repositories": [{"type": "package", "package": package}],
                }
            )
        )
        self.run(
            [
                *command,
                "install",
                "--no-interaction",
                "--no-progress",
                "--no-plugins",
                "--no-scripts",
            ],
            self.consumer,
        )
        (self.consumer / "check.php").write_text(
            """<?php
require __DIR__.'/vendor/autoload.php';
use function Mojito\\MessageFormat2\\parse_to_model;
use function Mojito\\MessageFormat2\\format_message;
$p=parse_to_model('Hello {$name}!');$r=format_message($p['model'],['name'=>'World']);
if($r['value']!=='Hello World!' || $r['hasErrors'])throw new RuntimeException(json_encode($r));
$p=parse_to_model("""
            + "'"
            + PLURAL
            + "'"
            + """);$r=format_message($p['model'],['n'=>2],['locale'=>'ru']);
if($r['value']!=='few' || $r['hasErrors'])throw new RuntimeException(json_encode($r));
echo "PASS Composer distribution/autoload and generated CLDR\\n";
"""
        )
        self.run(["php", "check.php"], self.consumer)

    def execute(self):
        try:
            self.run([sys.executable, "--version"])
            version_commands = {
                "javascript": ["node", "--version"],
                "java": ["java", "-version"],
                "kotlin": ["java", "-version"],
                "rust": ["cargo", "--version"],
                "swift": ["swift", "--version"],
                "go": [os.environ.get("GO", "go"), "version"],
                "php": ["php", "--version"],
            }
            if self.runtime in version_commands:
                self.run(version_commands[self.runtime])
            if self.runtime in {"java", "kotlin"}:
                self.jvm()
            else:
                getattr(self, self.runtime)()
            status = "passed"
        except Exception as error:
            status = "failed"
            self.error = str(error)
            raise
        finally:
            (self.output / "report.json").write_text(
                json.dumps(
                    {
                        "runtime": self.runtime,
                        "status": status,
                        "error": getattr(self, "error", None),
                        "sourceSha256": self.source_sha256,
                        "artifacts": self.results,
                        "commands": self.commands,
                    },
                    indent=2,
                )
                + "\n"
            )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", choices=[*PACKAGES, "all"], required=True)
    parser.add_argument(
        "--output",
        type=Path,
        help="New per-runtime subdirectory under this artifact directory",
    )
    options = parser.parse_args()
    output = (
        options.output or Path(tempfile.mkdtemp(prefix="mojito-mf2-packaging-"))
    ).resolve()
    output.mkdir(parents=True, exist_ok=True)
    for runtime in PACKAGES if options.runtime == "all" else [options.runtime]:
        Smoke(runtime, output).execute()
    print(f"Package artifact/consumer checks passed; evidence: {output}")


if __name__ == "__main__":
    main()
