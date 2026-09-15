#!/usr/bin/env bash
set -euo pipefail
target="${1:?Specify a version-loader target}"
python3 - "$target" <<'PY'
import json, sys
from pathlib import Path
version, loader = sys.argv[1].rsplit('-', 1)
assert loader in json.loads(Path('stonecutter.properties.json').read_text())[version], 'Unknown build target'
PY
mkdir -p build/ci-logs
set +e
./gradlew "-Dru.vidtu.ias.only=$target" ":$target:assemble" --no-daemon --console=plain 2>&1 | tee "build/ci-logs/$target.log"
result=${PIPESTATUS[0]}
set -e
if [ "$result" -ne 0 ]; then
  # Make useful compiler diagnostics readable through the Checks API, even when
  # the environment cannot download zipped Actions logs.
  python3 - "$target" <<'PY'
import sys
from pathlib import Path
lines = Path('build/ci-logs', sys.argv[1] + '.log').read_text(errors='replace').splitlines()
selected = []
for i, line in enumerate(lines):
    if any(word in line for word in ('error:', 'What went wrong:', 'FAILURE:', 'Could not ', 'Unresolved reference', 'Script compilation')):
        selected.extend(lines[i:i+7])
for line in list(dict.fromkeys(selected))[:40]:
    line = line.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print('::error title=Build diagnostic::' + line)
PY
fi
exit "$result"
