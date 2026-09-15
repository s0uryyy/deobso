#!/usr/bin/env python3
"""Name distributable JARs consistently; validate Fabric metadata before upload."""
import hashlib
import json
import shutil
import sys
import zipfile
from pathlib import Path


def mod_version():
    value = next(line.split('=', 1)[1].strip() for line in
                 Path('gradle.properties').read_text().splitlines() if line.startswith('version='))
    if not value.endswith('-newgen') or value.startswith('v'):
        raise ValueError('Expected mod version without v, e.g. 1.01-newgen')
    return value


def bundle_name():
    return 'deobso-newgen-v' + mod_version().removesuffix('-newgen')


def jar_name(target):
    minecraft, loader = target.rsplit('-', 1)
    suffix = '' if loader == 'fabric' else '-' + loader
    return f'{bundle_name()}-{minecraft}{suffix}.jar'


def package(target):
    minecraft, loader = target.rsplit('-', 1)
    if loader not in json.loads(Path('stonecutter.properties.json').read_text())[minecraft]:
        raise ValueError('Unknown target')
    jars = sorted(Path('build/libs').glob(f'*+{target}.jar'))
    if len(jars) != 1:
        raise ValueError(f'Expected exactly one deployable JAR for {target}, found {len(jars)}')
    jar = jars[0]
    if loader == 'fabric':
        with zipfile.ZipFile(jar) as archive:
            metadata = json.loads(archive.read('fabric.mod.json'))
            if metadata['version'] != mod_version():
                raise ValueError('Fabric metadata version must match gradle.properties without target suffix or v')
    output = Path('build/packaged')
    output.mkdir(parents=True, exist_ok=True)
    name = jar_name(target)
    dest = output / name
    shutil.copyfile(jar, dest)
    (output / f'SHA256SUMS-{target}.txt').write_text(
        f'{hashlib.sha256(dest.read_bytes()).hexdigest()}  {name}\n')


if __name__ == '__main__':
    if sys.argv[1] == '--bundle-name':
        print(bundle_name())
    else:
        package(sys.argv[1])
