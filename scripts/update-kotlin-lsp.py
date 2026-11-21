#!/usr/bin/env python3
"""Update JetBrains Kotlin LSP and Zed's existing binary.path (Linux, Python 3.12+).

Run ./scripts/update-kotlin-lsp.py, then restart Zed's language server.
Use --check to see the latest release without changing anything.
Old builds remain installed; Zed settings get a backup before each change.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import sys
import tarfile
import tempfile
from urllib.error import URLError
from urllib.request import Request, urlopen


RELEASE_URL = "https://api.github.com/repos/Kotlin/kotlin-lsp/releases/latest"


def download(url, destination):
    request = Request(url, headers={"User-Agent": "lofit-kotlin-lsp-updater"})
    with urlopen(request, timeout=60) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)


def binary_path_span(source):
    """Locate just the setting's value, preserving JSONC comments and formatting."""
    tokens = [
        match
        for match in re.finditer(
            r'//[^\r\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|[{}\[\]:,]|[^\s{}\[\]:,]+',
            source,
            re.DOTALL,
        )
        if not match.group().startswith(("//", "/*"))
    ]
    found = []

    def value(index, path):
        start = index
        token = tokens[index].group()
        if token == "{":
            index += 1
            while tokens[index].group() != "}":
                key = json.loads(tokens[index].group())
                if not isinstance(key, str) or tokens[index + 1].group() != ":":
                    raise ValueError("Invalid Zed settings object")
                index = value(index + 2, (*path, key))
                if tokens[index].group() == ",":
                    index += 1
                elif tokens[index].group() != "}":
                    raise ValueError("Invalid Zed settings object")
        elif token == "[":
            index += 1
            while tokens[index].group() != "]":
                index = value(index, (*path, None))
                if tokens[index].group() == ",":
                    index += 1
                elif tokens[index].group() != "]":
                    raise ValueError("Invalid Zed settings array")
        else:
            json.loads(token)
        index += 1
        if path == ("lsp", "kotlin-lsp", "binary", "path"):
            found.append((tokens[start].start(), tokens[index - 1].end()))
        return index

    try:
        if value(0, ()) != len(tokens) or len(found) != 1:
            raise ValueError("Need one lsp.kotlin-lsp.binary.path in Zed settings")
        start, end = found[0]
        if not isinstance(json.loads(source[start:end]), str):
            raise ValueError("Zed's Kotlin LSP binary.path must be a string")
        return start, end
    except (IndexError, RecursionError) as error:
        raise ValueError("Invalid Zed settings") from error


def release_info(directory):
    machine = platform.machine()
    architecture = {"x86_64": "x64", "aarch64": "arm64", "arm64": "arm64"}.get(machine)
    if platform.system() != "Linux" or architecture is None:
        raise ValueError(f"Unsupported platform: {platform.system()} {machine}")
    metadata = directory / "release.json"
    download(RELEASE_URL, metadata)
    release = json.loads(metadata.read_bytes())
    tag = release.get("tag_name", "")
    match = re.fullmatch(r"(?:kotlin-lsp/)?v(\d+(?:\.\d+)+)", tag)
    if match is None:
        raise ValueError(f"Unexpected release tag: {tag}")
    body = release.get("body", "").partition("Standalone Kotlin LSP")[2]
    archive = re.search(
        rf"\[Download for Linux-{architecture}\]"
        r"\((https://download\.jetbrains\.com/[^\s)]+\.tar\.gz)\)",
        body,
    )
    if archive is None or f"({archive[1]}.sha256)" not in body:
        raise ValueError("Release has no standalone Linux archive with SHA-256 checksum")
    return match[1], archive[1]


def validate_install(directory, version):
    launcher = directory / "bin/intellij-server"
    info = json.loads((directory / "product-info.json").read_bytes())
    if info.get("buildNumber") != version or not launcher.is_file():
        raise ValueError(f"Incomplete or mismatched Kotlin LSP install: {directory}")
    launcher.chmod(launcher.stat().st_mode | 0o111)
    return launcher


def install(version, url, destination):
    if destination.exists():
        return validate_install(destination, version)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".update-", dir=destination.parent) as temporary:
        staging = Path(temporary)
        checksum = staging / "archive.sha256"
        archive = staging / "archive.tar.gz"
        download(url + ".sha256", checksum)
        digest = re.match(r"([a-fA-F0-9]{64})(?:\s|$)", checksum.read_text())
        if digest is None:
            raise ValueError("Invalid SHA-256 checksum from JetBrains")
        expected = digest[1].lower()
        print(f"Downloading Kotlin LSP {version}...", flush=True)
        download(url, archive)
        with archive.open("rb") as source:
            actual = hashlib.file_digest(source, "sha256").hexdigest()
        if actual != expected:
            raise ValueError("SHA-256 mismatch; existing install and Zed settings kept")
        unpacked = staging / "unpacked"
        with tarfile.open(archive, "r:gz") as bundle:
            bundle.extractall(unpacked, filter="data")
        launchers = list(unpacked.rglob("bin/intellij-server"))
        if len(launchers) != 1:
            raise ValueError("Archive must contain exactly one bin/intellij-server")
        server = launchers[0].parent.parent
        validate_install(server, version)
        server.rename(destination)
    return destination / "bin/intellij-server"


def update_settings(settings, original, updated):
    # Fail if Zed saved other settings while the archive was downloading.
    if settings.read_bytes() != original:
        raise ValueError("Zed settings changed during update; run the script again")
    with tempfile.NamedTemporaryFile(
        prefix=settings.name + ".kotlin-lsp-", suffix=".bak", dir=settings.parent, delete=False
    ) as backup:
        backup.write(original)
        backup_path = Path(backup.name)
    shutil.copymode(settings, backup_path)
    descriptor, name = tempfile.mkstemp(prefix=".kotlin-lsp-", dir=settings.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(updated)
        shutil.copymode(settings, temporary)
        temporary.replace(settings)
    finally:
        temporary.unlink(missing_ok=True)
    print(f"Settings backup: {backup_path}")


def main():
    home = Path.home()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="check latest release; change nothing")
    parser.add_argument(
        "--settings", type=Path,
        default=Path(os.environ.get("XDG_CONFIG_HOME") or home / ".config") / "zed/settings.json",
        help="Zed settings file (default: ~/.config/zed/settings.json)",
    )
    parser.add_argument(
        "--install-dir", type=Path,
        default=Path(os.environ.get("XDG_DATA_HOME") or home / ".local/share") / "kotlin-lsp",
        help="directory for versioned installs (default: ~/.local/share/kotlin-lsp)",
    )
    args = parser.parse_args()
    settings = args.settings.expanduser().resolve()
    original = settings.read_bytes()
    source = original.decode("utf-8")
    start, end = binary_path_span(source)
    with tempfile.TemporaryDirectory(prefix="kotlin-lsp-release-") as temporary:
        version, url = release_info(Path(temporary))
    if args.check:
        print(f"Configured: {json.loads(source[start:end])}\nLatest: {version}")
        return
    destination = args.install_dir.expanduser().resolve() / f"kotlin-server-{version}"
    launcher = install(version, url, destination)
    updated = (source[:start] + json.dumps(str(launcher)) + source[end:]).encode("utf-8")
    if updated != original:
        update_settings(settings, original, updated)
    print(f"Kotlin LSP {version} ready. Restart Zed's language server.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, tarfile.TarError, URLError) as error:
        print(f"Update failed: {error}", file=sys.stderr)
        sys.exit(1)
