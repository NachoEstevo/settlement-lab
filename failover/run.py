"""Run both PostgreSQL failover policies and preserve independent client evidence."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
from datetime import datetime, timezone

from launcher import resolve_launcher


def positive_int(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError('must be at least one')
    return number


def save_json(path, value):
    with path.open('x', encoding='utf-8') as output:
        json.dump(value, output, indent=2, allow_nan=False)
        output.write('\n')


def version(*args):
    return subprocess.run(args, check=True, text=True, timeout=30,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout.strip()


def file_sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def environment(launcher):
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin' / 'java') if java_home else 'java'
    harness = Path(__file__).resolve().parent
    jars = sorted((launcher.parent.parent / 'lib').glob('settlement-lab-*.jar'))
    return {
        'started_utc': datetime.now(timezone.utc).isoformat(),
        'host': platform.platform(), 'host_architecture': platform.machine(),
        'python': platform.python_version(), 'java': version(java, '-version'),
        'docker': json.loads(version('docker', 'info', '--format',
            '{"server_version":{{json .ServerVersion}},"os":{{json .OperatingSystem}},'
            '"architecture":{{json .Architecture}},"cpus":{{.NCPU}},"memory_bytes":{{.MemTotal}}}')),
        'compose': version('docker', 'compose', 'version', '--short'),
        'application_jars_sha256': {jar.name: file_sha256(jar) for jar in jars},
        'harness_files_sha256': {p.name: file_sha256(p) for p in sorted(harness.iterdir())
                                 if p.suffix in {'.py', '.sh', '.yaml'} and not p.name.startswith('test_')},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=['both', 'async', 'sync'], default='both')
    parser.add_argument('--repetitions', type=positive_int, default=3, help='trials per policy (default 3)')
    parser.add_argument('--out', required=True, type=Path, help='new directory for the evidence bundle')
    parser.add_argument('--launcher', type=Path, help='installed Settlement Lab executable')
    parser.add_argument('--subnet', help='unused Docker subnet, e.g. 10.246.226.0/28')
    args = parser.parse_args()

    from experiment import run_once
    from verify import verify_trial

    launcher = resolve_launcher(args.launcher)
    modes = ['async', 'sync'] if args.mode == 'both' else [args.mode]
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    save_json(out / 'environment.json', environment(launcher))
    results = {}
    for repetition in range(1, args.repetitions + 1):
        for mode in modes:
            name = f'{mode}-{repetition:02d}'
            print(f'Running {name} -> {out / name}', flush=True)
            run_once(mode, repetition, out / name, launcher, args.subnet)
            results[name] = verify_trial(out / name, launcher)
            print(f'Verified {name}', flush=True)

    save_json(out / 'summary.json', results)
    save_json(out / 'manifest.json', {'version': 1, 'modes': modes,
                                      'repetitions': args.repetitions, 'trials': list(results)})
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
        sys.exit(f'Failover run failed: {exc}')
