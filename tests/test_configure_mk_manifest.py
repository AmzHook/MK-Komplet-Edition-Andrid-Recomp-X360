"""Prevent version-specific function metadata from reaching the wrong binary."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import tomllib
import unittest
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parents[1] / "tools"
sys.path.insert(0, str(TOOLS))
spec = importlib.util.spec_from_file_location("configure_mk_manifest", TOOLS / "configure_mk_manifest.py")
configuration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(configuration)


class ManifestBindingTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.xex = self.root / "default.xex"
        self.manifest = self.root / "game.toml"
        self.original = '[project]\nname = "mk"\n\n[entrypoint]\nfile_path = "default.xex"\nincludes = []\n'
        self.manifest.write_text(self.original)
        self.metadata = {"execution_info": {"title_id": "575207FD"}, "sha256": configuration.SUPPORTED_SHA256}

    def apply(self, metadata=None):
        with patch.object(configuration, "inspect_xex_file", return_value=metadata or self.metadata):
            configuration.configure(self.manifest, self.xex)

    def test_exact_binary_attaches_once_and_preserves_other_settings(self):
        self.apply()
        first = self.manifest.read_text()
        self.apply()
        self.assertEqual(self.manifest.read_text(), first)
        parsed = tomllib.loads(first)
        self.assertEqual(parsed["project"]["name"], "mk")
        self.assertEqual(parsed["entrypoint"]["file_path"], "default.xex")
        self.assertEqual(len(parsed["entrypoint"]["includes"]), 1)

    def test_another_binary_does_not_receive_addresses(self):
        self.apply({"execution_info": {"title_id": "575207FD"}, "sha256": "0" * 64})
        self.assertEqual(self.manifest.read_text(), self.original)

    def test_another_title_is_rejected_without_mutation(self):
        with self.assertRaisesRegex(ValueError, "not identified as MK"):
            self.apply({"execution_info": {"title_id": "4D53082D"}, "sha256": configuration.SUPPORTED_SHA256})
        self.assertEqual(self.manifest.read_text(), self.original)

    def test_manifest_for_different_input_is_rejected(self):
        changed = self.original.replace("default.xex", "another.xex")
        self.manifest.write_text(changed)
        with self.assertRaisesRegex(ValueError, "differs"):
            self.apply()
        self.assertEqual(self.manifest.read_text(), changed)

    def test_existing_include_is_preserved(self):
        self.manifest.write_text(self.original.replace("includes = []", 'includes = ["custom.toml"]'))
        self.apply()
        includes = tomllib.loads(self.manifest.read_text())["entrypoint"]["includes"]
        self.assertEqual(includes[0], "custom.toml")
        self.assertEqual(len(includes), 2)

    def test_complex_format_is_preserved_on_failure(self):
        changed = self.original.replace("includes = []", 'includes = [\n "custom.toml",\n]')
        self.manifest.write_text(changed)
        with self.assertRaisesRegex(ValueError, "Preserving complex"):
            self.apply()
        self.assertEqual(self.manifest.read_text(), changed)


if __name__ == "__main__":
    unittest.main()
