import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).with_name('validate-permission-artifacts.py')


class PermissionArtifactTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)

    def validate(self, entries):
        path = Path(self.directory.name) / 'plugin.jar'
        with zipfile.ZipFile(path, 'w') as jar:
            for name, content in entries:
                jar.writestr(name, content)
        spec = importlib.util.spec_from_file_location('validator', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.validate_artifact(path)

    def fixture(self, source='plugin-chat', version='2.6.1'):
        path = f'META-INF/grounds/permissions/{source}.json'
        manifest = {'source': source, 'sourceVersion': version, 'permissions': [
            {'key': source + '.use', 'label': 'Use plugin', 'description': 'Allows use.', 'supportedScopes': ['GLOBAL']}
        ]}
        return [('META-INF/grounds/permissions.index', path + '\n'), (path, json.dumps(manifest))]

    def test_two_manifests_survive_a_combined_index(self):
        first, second = self.fixture(), self.fixture('game-bedwars', '1.2.3')
        entries = [('META-INF/grounds/permissions.index', first[0][1] + second[0][1]), first[1], second[1]]
        self.assertEqual({'plugin-chat', 'game-bedwars'}, set(self.validate(entries)))

    def test_rejects_unexpanded_version(self):
        with self.assertRaisesRegex(ValueError, 'version'):
            self.validate(self.fixture(version='${version}'))

    def test_rejects_missing_indexed_resource(self):
        with self.assertRaisesRegex(ValueError, 'Missing'):
            self.validate(self.fixture()[:1])

    def test_rejects_duplicate_zip_paths(self):
        entries = self.fixture()
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.validate(entries + entries[1:])

    def test_rejects_traversal(self):
        with self.assertRaisesRegex(ValueError, 'path'):
            self.validate([('META-INF/grounds/permissions.index', '../other.json\n')])

    def test_rejects_source_mismatch(self):
        entries = self.fixture()
        entries[1] = (entries[1][0], entries[1][1].replace('"source": "plugin-chat"', '"source": "other-plugin"'))
        with self.assertRaisesRegex(ValueError, 'source'):
            self.validate(entries)

    def test_rejects_unsupported_scopes(self):
        entries = self.fixture()
        entries[1] = (entries[1][0], entries[1][1].replace('GLOBAL', 'PROJECT'))
        with self.assertRaisesRegex(ValueError, 'scope'):
            self.validate(entries)


if __name__ == '__main__':
    unittest.main()
