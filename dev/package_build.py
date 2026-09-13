#!/usr/bin/env python3
"""Keep only deployable JARs for this target; give each target its own checksums."""
import hashlib
import json
import shutil
import sys
from pathlib import Path


def package(target):
    version, loader = target.rsplit('-', 1)
    if loader not in json.loads(Path('stonecutter.properties.json').read_text())[version]:
        raise ValueError('Unknown target')
    jars = sorted(Path('build/libs').glob(f'*+{target}.jar'))
    if not jars:
        raise FileNotFoundError(f'No deployable JAR produced for {target}')
    output = Path('build/packaged')
    output.mkdir(parents=True, exist_ok=True)
    checksums = []
    for jar in jars:
        name = jar.name
        if name.startswith('IAS-'):
            name = 'deobso-account-changer-' + name[len('IAS-'):]
        dest = output / name
        shutil.copyfile(jar, dest)
        checksums.append(f'{hashlib.sha256(dest.read_bytes()).hexdigest()}  {name}\n')
    (output / f'SHA256SUMS-{target}.txt').write_text(''.join(checksums))


if __name__ == '__main__':
    package(sys.argv[1])
