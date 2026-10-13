#!/usr/bin/env python3
"""Inspect XEX2 metadata as data; never decrypt or execute the input file."""

import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys


TITLE_NAMES = {
    0x4D53082D: "Gears of War 2",
    0x575207FD: "Mortal Kombat / Mortal Kombat Komplete Edition",
}
TITLE_SOURCES = {
    0x4D53082D: "https://github.com/xenia-project/game-compatibility/issues/163",
    0x575207FD: "https://github.com/xenia-project/game-compatibility/issues/1548",
}
MAX_HEADER_SIZE = 16 * 1024 * 1024


class XexError(ValueError):
    """The input does not have the required XEX2 metadata structure."""


def _hex(value):
    return f"{value:08X}"


def _range(offset, size, limit, description):
    if offset < 0 or size < 0 or offset > limit or size > limit - offset:
        raise XexError(f"{description} is outside the declared XEX header.")


def _header_fields(data, file_size):
    if len(data) < 24:
        raise XexError("Truncated XEX2 fixed header: at least 24 bytes are required.")
    magic, flags, header_size, reserved, security_offset, count = struct.unpack_from(
        ">4s5I", data
    )
    if magic != b"XEX2":
        raise XexError("Invalid magic: expected XEX2.")
    if header_size < 24 or header_size > file_size:
        raise XexError("Declared XEX header size exceeds the file or is too small.")
    if header_size > MAX_HEADER_SIZE:
        raise XexError("Declared XEX header exceeds the inspector's 16 MiB limit.")
    if count > (header_size - 24) // 8:
        raise XexError("Optional-header table exceeds the declared XEX header.")
    return flags, header_size, security_offset, count


def _parse_header(data, file_size, sha256):
    flags, header_size, security_offset, count = _header_fields(data, file_size)
    if len(data) < header_size:
        raise XexError("Truncated XEX2 header.")
    table_end = 24 + count * 8
    records = {}
    optional_headers = []
    for index in range(count):
        key, value = struct.unpack_from(">II", data, 24 + index * 8)
        if key in records:
            raise XexError(f"Duplicate optional-header ID {_hex(key)}.")
        words = key & 0xFF
        item = {"id": _hex(key)}
        if words in (0, 1):
            records[key] = value
            item.update(kind="inline", value=_hex(value))
        else:
            if value < table_end:
                raise XexError(f"Optional-header {_hex(key)} overlaps the fixed header or table.")
            if words == 0xFF:
                _range(value, 4, header_size, f"Optional-header {_hex(key)} size field")
                size = struct.unpack_from(">I", data, value)[0]
                if size < 4:
                    raise XexError(f"Optional-header {_hex(key)} has an invalid record size.")
            else:
                size = words * 4
            _range(value, size, header_size, f"Optional-header {_hex(key)} record")
            records[key] = data[value:value + size]
            item.update(kind="record", offset=_hex(value), size_bytes=size)
        optional_headers.append(item)

    if security_offset < table_end:
        raise XexError("Security metadata overlaps the fixed header or optional-header table.")
    _range(security_offset, 8, header_size, "Security metadata")
    security_size, image_size = struct.unpack_from(">II", data, security_offset)
    if security_size < 0x184:
        raise XexError("Security metadata is too small for an XEX2 security descriptor.")
    _range(security_offset, security_size, header_size, "Security metadata")
    security_load = struct.unpack_from(">I", data, security_offset + 0x110)[0]
    descriptor_count = struct.unpack_from(">I", data, security_offset + 0x180)[0]
    if descriptor_count > (security_size - 0x184) // 24:
        raise XexError("Security page-descriptor table exceeds its declared size.")

    execution = records.get(0x00040006)
    if execution is None:
        raise XexError("Missing execution-info header (00040006); title identity is unavailable.")
    media_id, version, base_version, title_id, platform, executable_table, disc_number, disc_count, savegame_id = struct.unpack(
        ">4I4BI", execution
    )
    execution_info = {
        "title_id": _hex(title_id),
        "title_name": TITLE_NAMES.get(title_id),
        "title_reference": TITLE_SOURCES.get(title_id),
        "media_id": _hex(media_id),
        "version_packed": _hex(version),
        "base_version_packed": _hex(base_version),
        "platform": platform,
        "executable_table": executable_table,
        "disc_number": disc_number,
        "disc_count": disc_count,
        "savegame_id": _hex(savegame_id),
    }

    original_pe = records.get(0x000183FF)
    original_name = None
    if original_pe is not None:
        original_name = original_pe[4:].split(b"\0", 1)[0].decode("utf-8", errors="replace")

    file_format = records.get(0x000003FF)
    format_info = None
    if file_format is not None:
        if len(file_format) < 8:
            raise XexError("File-format metadata is too small.")
        _, encryption, compression = struct.unpack_from(">IHH", file_format)
        if encryption not in (0, 1):
            raise XexError("Unrecognized XEX encryption descriptor.")
        if compression not in (0, 1, 2, 3):
            raise XexError("Unrecognized XEX compression descriptor.")
        format_info = {
            "encryption_type": encryption,
            "encryption_name": ("none", "normal")[encryption],
            "compression_type": compression,
            "compression_name": ("none", "basic", "normal", "delta")[compression],
        }
        if compression == 2:
            if len(file_format) < 36:
                raise XexError("Normal compression descriptor is truncated.")
            window_size, block_size = struct.unpack_from(">II", file_format, 8)
            if block_size < 24 or block_size > file_size - header_size:
                raise XexError("First compressed-block size exceeds the payload or is too small.")
            format_info.update(window_size_bytes=window_size, first_block_size_bytes=block_size)
        elif compression == 1:
            if len(file_format) < 16 or (len(file_format) - 8) % 8:
                raise XexError("Basic compression descriptor has an invalid block table.")
            blocks = [struct.unpack_from(">II", file_format, offset)
                      for offset in range(8, len(file_format), 8)]
            format_info.update(
                block_count=len(blocks),
                data_size_bytes=sum(block[0] for block in blocks),
                zero_size_bytes=sum(block[1] for block in blocks),
            )
            if format_info["data_size_bytes"] > file_size - header_size:
                raise XexError("Basic compression data sizes exceed the available payload.")

    image_base = records.get(0x00010201, security_load)
    original_base = records.get(0x00010001)
    entry = records.get(0x00010100)
    return {
        "schema_version": 1,
        "format": "XEX2",
        "file_size_bytes": file_size,
        "sha256": sha256,
        "module_flags": _hex(flags),
        "is_patch_module": bool(flags & 0x70),
        "header_size_bytes": header_size,
        "payload_offset": _hex(header_size),
        "payload_size_bytes": file_size - header_size,
        "optional_header_count": count,
        "optional_headers": optional_headers,
        "image_base_address": _hex(image_base),
        "original_base_address": _hex(original_base) if original_base is not None else None,
        "entry_point": _hex(entry) if entry is not None else None,
        "image_size_bytes": image_size,
        "original_pe_name": original_name,
        "execution_info": execution_info,
        "file_format_info": format_info,
        "security_info": {
            "offset": _hex(security_offset),
            "size_bytes": security_size,
            "image_flags": _hex(struct.unpack_from(">I", data, security_offset + 0x10C)[0]),
            "load_address": _hex(security_load),
            "region_mask": _hex(struct.unpack_from(">I", data, security_offset + 0x178)[0]),
            "allowed_media_mask": _hex(struct.unpack_from(">I", data, security_offset + 0x17C)[0]),
            "page_descriptor_count": descriptor_count,
        },
        "validation_scope": "Header structure and metadata only; no signature verification, decryption, code analysis, or execution.",
    }


def inspect_xex(data):
    """Return safe metadata for an in-memory XEX; useful for synthetic fixtures."""
    return _parse_header(data, len(data), hashlib.sha256(data).hexdigest())


def inspect_xex_file(path):
    """Hash the whole file while loading only its bounded metadata header."""
    with Path(path).open("rb") as source:
        file_size = source.seek(0, 2)
        source.seek(0)
        fixed_header = source.read(24)
        _, header_size, _, _ = _header_fields(fixed_header, file_size)
        source.seek(0)
        header = source.read(header_size)
        source.seek(0)
        digest = hashlib.sha256()
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return _parse_header(header, file_size, digest.hexdigest())


def _title_argument(value):
    normalized = value.removeprefix("0x").removeprefix("0X")
    if len(normalized) != 8 or any(char not in "0123456789abcdefABCDEF" for char in normalized):
        raise argparse.ArgumentTypeError("Title ID must contain eight hexadecimal digits, such as 575207FD.")
    return int(normalized, 16)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("xex", type=Path, help="XEX to inspect as data (its path is not included in the report)")
    parser.add_argument("--expected-title-id", type=_title_argument,
                        help="Return nonzero unless execution metadata identifies this title")
    args = parser.parse_args(argv)
    try:
        metadata = inspect_xex_file(args.xex)
    except XexError as error:
        print(json.dumps({"status": "invalid_xex", "error": str(error)}, indent=2))
        return 1
    except OSError as error:
        # OSError's full string includes paths; report only its non-sensitive reason.
        print(json.dumps({"status": "input_error", "error": error.strerror or "Unable to read input file."}, indent=2))
        return 1
    report = {"metadata": metadata}
    if args.expected_title_id is not None:
        actual = metadata["execution_info"]["title_id"]
        expected = _hex(args.expected_title_id)
        matches = actual == expected
        report["title_check"] = {"expected_title_id": expected, "actual_title_id": actual, "matches": matches}
        if not matches:
            report["status"] = "title_mismatch"
            report["error"] = f"Expected Title ID {expected}, found {actual}; do not use this input for the expected game's code generation."
            print(json.dumps(report, indent=2, ensure_ascii=False))
            return 1
    report["status"] = "metadata_valid"
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
