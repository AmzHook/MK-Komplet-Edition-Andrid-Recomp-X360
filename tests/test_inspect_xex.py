"""Synthetic XEX metadata tests; no copyrighted executable fixtures."""

import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "tools" / "inspect_xex.py"
SPEC = importlib.util.spec_from_file_location("inspect_xex", MODULE_PATH)
inspector = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(inspector)


def fixture(title_id=0x575207FD, include_execution=True):
    """Build a non-executable metadata-only fixture with a dummy payload."""
    header_size = 0x400
    security_offset = 0x80
    data = bytearray(header_size + 64)
    records = [(0x00010001, 0x82000000),
               (0x00010100, 0x82001000),
               (0x00010201, 0x82000000),
               (0x000003FF, 0x290),
               (0x000183FF, 0x2C0)]
    if include_execution:
        records.append((0x00040006, 0x270))
    struct.pack_into(">4s5I", data, 0, b"XEX2", 1, header_size, 0,
                     security_offset, len(records))
    for index, record in enumerate(records):
        struct.pack_into(">II", data, 24 + index * 8, *record)
    struct.pack_into(">II", data, security_offset, 0x184, 0x20000)
    struct.pack_into(">I", data, security_offset + 0x110, 0x82000000)
    struct.pack_into(">III", data, security_offset + 0x178, 0xFFFFFFFF, 0xFFFFFFFF, 0)
    if include_execution:
        struct.pack_into(">4I4BI", data, 0x270, 0x12345678, 5, 4,
                         title_id, 2, 0, 1, 1, 0)
    struct.pack_into(">IHHII", data, 0x290, 36, 1, 2, 32768, 64)
    # Deliberately put nonzero bytes where an encrypted block hash would be.
    # The report must contain only descriptors, never hash/key material.
    data[0x2A0:0x2B4] = b"H" * 20
    name = b"SyntheticGame.exe\0"
    struct.pack_into(">I", data, 0x2C0, len(name) + 4)
    data[0x2C4:0x2C4 + len(name)] = name
    data[header_size:] = b"P" * 64
    return data


class InspectorTests(unittest.TestCase):
    def test_valid_metadata_and_inline_addresses(self):
        data = fixture()
        metadata = inspector.inspect_xex(data)
        self.assertEqual(metadata["format"], "XEX2")
        self.assertEqual(metadata["sha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(metadata["file_size_bytes"], len(data))
        self.assertEqual(metadata["image_base_address"], "82000000")
        self.assertEqual(metadata["original_base_address"], "82000000")
        self.assertEqual(metadata["entry_point"], "82001000")
        self.assertEqual(metadata["original_pe_name"], "SyntheticGame.exe")
        self.assertEqual(metadata["execution_info"]["title_id"], "575207FD")
        self.assertEqual(metadata["execution_info"]["media_id"], "12345678")
        self.assertEqual(metadata["execution_info"]["version_packed"], "00000005")
        self.assertEqual(metadata["execution_info"]["base_version_packed"], "00000004")
        self.assertEqual(metadata["execution_info"]["disc_number"], 1)
        self.assertEqual(metadata["execution_info"]["disc_count"], 1)
        self.assertEqual(metadata["file_format_info"]["encryption_name"], "normal")
        self.assertEqual(metadata["file_format_info"]["compression_name"], "normal")
        self.assertEqual(metadata["file_format_info"]["window_size_bytes"], 32768)
        self.assertNotIn("HHHHHHHH", json.dumps(metadata))
        self.assertNotIn("PPPPPPPP", json.dumps(metadata))

    def test_truncated_fixed_header(self):
        with self.assertRaisesRegex(inspector.XexError, "Truncated"):
            inspector.inspect_xex(b"XEX2")

    def test_truncated_declared_header(self):
        with self.assertRaisesRegex(inspector.XexError, "header size exceeds"):
            inspector.inspect_xex(fixture()[:0x300])

    def test_malformed_magic(self):
        data = fixture()
        data[:4] = b"NOPE"
        with self.assertRaisesRegex(inspector.XexError, "Invalid magic"):
            inspector.inspect_xex(data)

    def test_optional_table_out_of_bounds(self):
        data = fixture()
        struct.pack_into(">I", data, 20, 0x1000)
        with self.assertRaisesRegex(inspector.XexError, "Optional-header table"):
            inspector.inspect_xex(data)

    def test_optional_pointer_out_of_bounds(self):
        data = fixture()
        struct.pack_into(">I", data, 24 + 3 * 8 + 4, 0x3FF)
        with self.assertRaisesRegex(inspector.XexError, "outside"):
            inspector.inspect_xex(data)

    def test_optional_record_length_out_of_bounds(self):
        data = fixture()
        struct.pack_into(">I", data, 0x2C0, 0x1000)
        with self.assertRaisesRegex(inspector.XexError, "outside"):
            inspector.inspect_xex(data)

    def test_record_overlaps_header_table(self):
        data = fixture()
        struct.pack_into(">I", data, 24 + 3 * 8 + 4, 24)
        with self.assertRaisesRegex(inspector.XexError, "overlaps"):
            inspector.inspect_xex(data)

    def test_duplicate_optional_record(self):
        data = fixture()
        struct.pack_into(">I", data, 24 + 2 * 8, 0x00010001)
        with self.assertRaisesRegex(inspector.XexError, "Duplicate"):
            inspector.inspect_xex(data)

    def test_missing_execution_info(self):
        with self.assertRaisesRegex(inspector.XexError, "Missing execution-info"):
            inspector.inspect_xex(fixture(include_execution=False))

    def test_security_descriptor_out_of_bounds(self):
        data = fixture()
        struct.pack_into(">I", data, 0x80, 0x400)
        with self.assertRaisesRegex(inspector.XexError, "Security metadata.*outside"):
            inspector.inspect_xex(data)

    def test_security_page_table_out_of_bounds(self):
        data = fixture()
        struct.pack_into(">I", data, 0x80 + 0x180, 1)
        with self.assertRaisesRegex(inspector.XexError, "page-descriptor table"):
            inspector.inspect_xex(data)

    def test_normal_compression_block_out_of_bounds(self):
        data = fixture()
        struct.pack_into(">I", data, 0x29C, 65)
        with self.assertRaisesRegex(inspector.XexError, "compressed-block size"):
            inspector.inspect_xex(data)

    def test_normal_compression_descriptor_truncation(self):
        data = fixture()
        struct.pack_into(">I", data, 0x290, 8)
        with self.assertRaisesRegex(inspector.XexError, "descriptor is truncated"):
            inspector.inspect_xex(data)

    def test_unknown_title_is_preserved(self):
        metadata = inspector.inspect_xex(fixture(title_id=0x1234ABCD))
        self.assertEqual(metadata["execution_info"]["title_id"], "1234ABCD")
        self.assertIsNone(metadata["execution_info"]["title_name"])

    def invoke(self, data, *options):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "synthetic.xex"
            path.write_bytes(data)
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                exit_code = inspector.main([str(path), *options])
            text = output.getvalue()
            self.assertNotIn(str(path), text)
            return exit_code, json.loads(text)

    def test_cli_title_match(self):
        exit_code, report = self.invoke(fixture(), "--expected-title-id", "575207FD")
        self.assertEqual(exit_code, 0)
        self.assertEqual(report["status"], "metadata_valid")
        self.assertTrue(report["title_check"]["matches"])

    def test_cli_title_mismatch_keeps_metadata(self):
        exit_code, report = self.invoke(fixture(0x4D53082D), "--expected-title-id", "575207FD")
        self.assertNotEqual(exit_code, 0)
        self.assertEqual(report["status"], "title_mismatch")
        self.assertEqual(report["metadata"]["execution_info"]["title_name"], "Gears of War 2")
        self.assertEqual(report["title_check"]["actual_title_id"], "4D53082D")
        self.assertFalse(report["title_check"]["matches"])
        self.assertIn("do not use", report["error"])

    def test_cli_invalid_file_is_json(self):
        exit_code, report = self.invoke(b"NOPE")
        self.assertNotEqual(exit_code, 0)
        self.assertEqual(report["status"], "invalid_xex")

    def test_cli_missing_file_does_not_expose_path(self):
        path = Path("/tmp/nonexistent-inspector-fixture-123456.xex")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            exit_code = inspector.main([str(path)])
        self.assertNotEqual(exit_code, 0)
        self.assertNotIn(str(path), output.getvalue())
        self.assertEqual(json.loads(output.getvalue())["status"], "input_error")


if __name__ == "__main__":
    unittest.main()
