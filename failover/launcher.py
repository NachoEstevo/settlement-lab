"""Locate the installed Java application in a release or a developer checkout."""

import os
from pathlib import Path


def resolve_launcher(explicit=None):
    root = Path(__file__).resolve().parent.parent
    name = 'settlement-lab.bat' if os.name == 'nt' else 'settlement-lab'
    candidates = ([Path(explicit)] if explicit is not None else
                  [root / 'bin' / name, root / 'build' / 'install' / 'settlement-lab' / 'bin' / name])
    for candidate in candidates:
        if candidate.is_file() and (os.name == 'nt' or os.access(candidate, os.X_OK)):
            return candidate.resolve()
    raise FileNotFoundError('Settlement Lab launcher missing or not executable. '
                            'Run gradlew installDist in the checkout or pass --launcher PATH.')
