#!/usr/bin/env python3
"""Install pinned Android build tools outside the checkout, verifying downloads."""
import concurrent.futures
import hashlib
import os
from pathlib import Path
import shutil
import tarfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(os.environ.get("MK_ANDROID_TOOLS", "/workspace/.tools/mk-android"))
RELEASE = "jdk-21.0.8+9"
JDK_FILE = "OpenJDK21U-jdk_x64_linux_hotspot_21.0.8_9.tar.gz"
JDK_URL = "https://github.com/adoptium/temurin21-binaries/releases/download/" + RELEASE.replace("+", "%2B") + "/" + JDK_FILE


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()


def download(url, filename, algorithm, expected):
    ROOT.mkdir(parents=True, exist_ok=True)
    target = ROOT / filename
    if target.exists() and hashlib.new(algorithm, target.read_bytes()).hexdigest() == expected:
        return target
    temporary = target.with_suffix(target.suffix + ".download")
    digest = hashlib.new(algorithm)
    with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            output.write(chunk)
    if digest.hexdigest() != expected:
        temporary.unlink()
        raise RuntimeError("Checksum mismatch: " + filename)
    temporary.replace(target)
    print("Verified:", filename, flush=True)
    return target


def main():
    repository = ET.fromstring(fetch("https://dl.google.com/android/repository/repository2-3.xml"))
    tasks = []
    for package, destination in (("platforms;android-35", "sdk/platforms/android-35"), ("build-tools;35.0.0", "sdk/build-tools/35.0.0")):
        record = next(p for p in repository.findall("remotePackage") if p.attrib["path"] == package)
        archive = next(a for a in record.findall("./archives/archive") if a.findtext("host-os") in (None, "linux"))
        complete = archive.find("complete")
        checksum = complete.find("checksum")
        filename = complete.findtext("url")
        tasks.append(("https://dl.google.com/android/repository/" + filename, filename, checksum.attrib["type"], checksum.text, destination))
    jdk_hash = fetch(JDK_URL + ".sha256.txt").decode().split()[0]
    tasks.append((JDK_URL, JDK_FILE, "sha256", jdk_hash, "jdk"))
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        futures = [(pool.submit(download, *task[:4]), task[4]) for task in tasks]
        for future, destination in futures:
            archive = future.result()
            target = ROOT / destination
            marker = target / ".verified-archive"
            archive_hash = hashlib.sha256(archive.read_bytes()).hexdigest()
            if marker.exists() and marker.read_text() == archive_hash:
                continue
            scratch = ROOT / (destination.replace("/", "-") + ".extract")
            if scratch.exists():
                shutil.rmtree(scratch)
            scratch.mkdir()
            if archive.suffix == ".zip":
                with zipfile.ZipFile(archive) as content:
                    content.extractall(scratch)
            else:
                with tarfile.open(archive) as content:
                    content.extractall(scratch, filter="data")
            children = list(scratch.iterdir())
            if len(children) != 1 or not children[0].is_dir():
                raise RuntimeError("Unexpected archive layout: " + archive.name)
            if target.exists():
                shutil.rmtree(target)
            target.parent.mkdir(parents=True, exist_ok=True)
            children[0].rename(target)
            scratch.rmdir()
            marker.write_text(archive_hash)
            if destination.startswith("sdk/build-tools"):
                for name in ("aapt", "aapt2", "d8", "apksigner", "zipalign"):
                    executable = target / name
                    executable.chmod(executable.stat().st_mode | 0o111)
    print("Android tools ready:", ROOT, flush=True)


if __name__ == "__main__":
    main()
