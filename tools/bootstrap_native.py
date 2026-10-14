#!/usr/bin/env python3
"""Install verified Android native tools and pinned public ReXGlue runtime sources.

No game files are downloaded or executed. Android archives are verified against
Google's HTTPS SDK repository metadata before extraction; TLS stays enabled.
"""

import concurrent.futures
import hashlib
import os
from pathlib import Path
import shutil
import stat
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(os.environ.get("MK_NATIVE_TOOLS", "/workspace/.tools/mk-native"))
SDK_COMMIT = "38b40e7424e5d3ce1afa38dca10e9a84b1aa2317"
SDK_URL = "https://github.com/PauloUbirajara/asura-wrath-rexglue-sdk"
REPOSITORY_URL = "https://dl.google.com/android/repository/repository2-3.xml"
PACKAGES = (("ndk;28.2.13676358", "sdk/ndk/28.2.13676358"),
            ("cmake;3.31.6", "sdk/cmake/3.31.6"))
SUBMODULES = ("cli11", "libmspack", "FFmpeg", "tomlplusplus", "simde",
              "xxHash", "spdlog", "fmt", "utfcpp", "imgui", "sdl3",
              "vulkan-headers", "vulkan-memory-allocator", "spirv-headers",
              "spirv-tools", "glslang", "o1heap", "inja")
PATCHES = tuple(Path(__file__).resolve().parent.parent / "native/patches" / name
                for name in ("android-fiber.patch", "indirect-call-diagnostics.patch",
                             "floating-point-stub-logging.patch", "file-open-observer.patch",
                             "android-idle-waits.patch"))


def digest_file(path, algorithm):
    digest = hashlib.new(algorithm)
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url, filename, algorithm, expected):
    target = ROOT / filename
    if target.exists() and digest_file(target, algorithm) == expected:
        return target
    partial = target.with_suffix(target.suffix + ".download")
    digest = hashlib.new(algorithm)
    with urllib.request.urlopen(url, timeout=60) as response, partial.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
            output.write(chunk)
    if digest.hexdigest() != expected:
        partial.unlink()
        raise RuntimeError("Official SDK checksum mismatch: " + filename)
    partial.replace(target)
    print("Verified:", filename, flush=True)
    return target


def extract(archive, destination):
    target = ROOT / destination
    archive_hash = digest_file(archive, "sha256")
    marker = target / ".verified-archive"
    if marker.exists() and marker.read_text().strip() == archive_hash:
        return
    scratch = ROOT / (destination.replace("/", "-") + ".extract")
    if scratch.exists():
        shutil.rmtree(scratch)
    scratch.mkdir()
    with zipfile.ZipFile(archive) as content:
        for entry in content.infolist():
            output = scratch / entry.filename
            if not output.resolve().is_relative_to(scratch.resolve()):
                raise RuntimeError("Unsafe archive path: " + entry.filename)
            mode = entry.external_attr >> 16
            if entry.is_dir():
                output.mkdir(parents=True, exist_ok=True)
            elif stat.S_ISLNK(mode):
                output.parent.mkdir(parents=True, exist_ok=True)
                link = content.read(entry).decode()
                if not (output.parent / link).resolve().is_relative_to(scratch.resolve()):
                    raise RuntimeError("Unsafe archive link: " + entry.filename)
                output.symlink_to(link)
            else:
                output.parent.mkdir(parents=True, exist_ok=True)
                with content.open(entry) as source, output.open("wb") as output_file:
                    shutil.copyfileobj(source, output_file)
                if stat.S_IMODE(mode):
                    output.chmod(stat.S_IMODE(mode))
    children = list(scratch.iterdir())
    # NDK has a top-level android-ndk directory; SDK CMake is already flat.
    payload = children[0] if len(children) == 1 and children[0].is_dir() else scratch
    if target.exists():
        shutil.rmtree(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    payload.rename(target)
    if scratch.exists():
        scratch.rmdir()
    marker.write_text(archive_hash + "\n")


def prepare_sources():
    source = ROOT / "rexglue-sdk"
    cloned = not source.exists()
    if cloned:
        subprocess.run(["git", "clone", "--no-checkout", "--filter=blob:none", SDK_URL, str(source)], check=True)
    current = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if cloned or current != SDK_COMMIT:
        status = subprocess.check_output(["git", "-C", str(source), "status", "--porcelain"], text=True)
        if status and not cloned:
            raise RuntimeError("Preserving changed SDK checkout: " + str(source))
        subprocess.run(["git", "-C", str(source), "checkout", "--detach", SDK_COMMIT], check=True)
    subprocess.run(["git", "-C", str(source), "submodule", "update", "--init", "--recursive", "--depth", "1", "--jobs", "3", "--", *["thirdparty/" + item for item in SUBMODULES]], check=True)
    for patch in PATCHES:
        if not patch.is_file():
            raise RuntimeError("Missing required SDK patch: " + str(patch))
        reverse = subprocess.run(["git", "-C", str(source), "apply", "--reverse", "--check", str(patch)], capture_output=True)
        if reverse.returncode == 0:
            print("SDK patch already applied:", patch.name, flush=True)
            continue
        forward = subprocess.run(["git", "-C", str(source), "apply", "--check", str(patch)], capture_output=True)
        if forward.returncode:
            raise RuntimeError("SDK patch does not match; preserving existing source changes: " + patch.name)
        subprocess.run(["git", "-C", str(source), "apply", str(patch)], check=True)
        print("SDK patch applied:", patch.name, flush=True)
    print("Pinned public runtime source:", source, SDK_COMMIT, flush=True)


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(REPOSITORY_URL, timeout=60) as response:
        repository = ET.fromstring(response.read())
    tasks = []
    for package, destination in PACKAGES:
        record = next(item for item in repository.findall("remotePackage") if item.attrib["path"] == package)
        archive = next(item for item in record.findall("./archives/archive") if item.findtext("host-os") == "linux")
        complete = archive.find("complete")
        checksum = complete.find("checksum")
        filename = complete.findtext("url")
        tasks.append(("https://dl.google.com/android/repository/" + filename,
                      filename, checksum.attrib["type"], checksum.text, destination))
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        sources = pool.submit(prepare_sources)
        archives = [(pool.submit(download, *task[:4]), task[4]) for task in tasks]
        for future, destination in archives:
            extract(future.result(), destination)
        sources.result()
    print("Native tools ready:", ROOT, flush=True)


if __name__ == "__main__":
    main()
