import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('package_build', Path(__file__).resolve().parents[1] / 'package_build.py')
package_build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package_build)


class PackagingTests(unittest.TestCase):
    def setUp(self):
        self.previous = Path.cwd()
        self.temp = tempfile.TemporaryDirectory()
        os.chdir(self.temp.name)
        Path('gradle.properties').write_text('version=1.01-newgen\n')
        Path('stonecutter.properties.json').write_text(json.dumps({
            '1.21.11': {'fabric': {}, 'forge': {}, 'neoforge': {}}}))
        Path('build/libs').mkdir(parents=True)

    def tearDown(self):
        os.chdir(self.previous)
        self.temp.cleanup()

    def jar(self, version='1.01-newgen', name='IAS-1.01-newgen+1.21.11-fabric.jar'):
        path = Path('build/libs') / name
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('fabric.mod.json', json.dumps({'version': version}))
        return path

    def test_names_and_loader_collisions(self):
        self.assertEqual(package_build.bundle_name(), 'deobso-newgen-v1.01')
        self.assertEqual(package_build.jar_name('1.21.11-fabric'), 'deobso-newgen-v1.01-1.21.11.jar')
        self.assertEqual(package_build.jar_name('1.21.11-neoforge'), 'deobso-newgen-v1.01-1.21.11-neoforge.jar')
        self.assertEqual(len({package_build.jar_name('1.21.11-' + loader)
                              for loader in ['fabric', 'forge', 'neoforge']}), 3)

    def test_packages_jar_without_modifying_it_and_checksums_new_name(self):
        source = self.jar()
        package_build.package('1.21.11-fabric')
        dest = Path('build/packaged/deobso-newgen-v1.01-1.21.11.jar')
        self.assertEqual(source.read_bytes(), dest.read_bytes())
        self.assertEqual(Path('build/packaged/SHA256SUMS-1.21.11-fabric.txt').read_text(),
                         hashlib.sha256(dest.read_bytes()).hexdigest() + '  ' + dest.name + '\n')
        self.assertEqual(len(list(Path('build/packaged').iterdir())), 2)

    def test_rejects_wrong_embedded_version(self):
        for version in ['v1.01-newgen', '1.01-newgen+1.21.11-fabric', 'newgen-1.21.11']:
            self.jar(version)
            with self.assertRaises(ValueError):
                package_build.package('1.21.11-fabric')

    def test_rejects_missing_or_ambiguous_outputs(self):
        with self.assertRaises(ValueError):
            package_build.package('1.21.11-fabric')
        self.jar()
        self.jar(name='another+1.21.11-fabric.jar')
        with self.assertRaises(ValueError):
            package_build.package('1.21.11-fabric')

    def test_rejects_v_in_central_version(self):
        Path('gradle.properties').write_text('version=v1.01-newgen\n')
        with self.assertRaises(ValueError):
            package_build.bundle_name()


if __name__ == '__main__':
    unittest.main()
