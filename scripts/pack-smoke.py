"""Exercise the extracted Gradle distribution with the native launcher."""

import argparse
from ipaddress import IPv4Network
import os
from pathlib import Path
import subprocess
import tempfile
from uuid import uuid4
from zipfile import ZipFile


def run(command, cwd, env=None):
    result = subprocess.run(
        command, cwd=cwd, env=env, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, check=False,
    )
    if result.returncode:
        raise RuntimeError(f"{command[0]} exited {result.returncode}\n{result.stdout}")
    return result.stdout


def require_lines(output, expected):
    missing = [line for line in expected if line not in output.splitlines()]
    if missing:
        raise RuntimeError(f"Missing output {missing!r}\n{output}")


def postgres_smoke(app, launcher, caller, env):
    project = f"settlement-pack-{uuid4().hex}"
    compose = ["docker", "compose", "-p", project, "-f", str(app / "compose.yaml")]
    subnet = env.get("SETTLEMENT_TEST_SUBNET")
    if subnet:
        network = caller / "smoke-network.yaml"
        network.write_text(
            f"networks:\n  default:\n    ipam:\n      config:\n        - subnet: {IPv4Network(subnet)}\n",
            encoding="utf-8",
        )
        compose.extend(["-f", str(network)])
    # A zero host port requests an available port from Docker.
    env = dict(env, SETTLEMENT_PORT="0", SETTLEMENT_DB_USER="settlement_lab",
               SETTLEMENT_DB_PASSWORD="settlement_lab")
    try:
        run([*compose, "up", "-d", "--wait"], caller, env)
        binding = run([*compose, "port", "postgres", "5432"], caller, env).strip()
        port = int(binding.rsplit(":", 1)[1])
        env["SETTLEMENT_JDBC_URL"] = f"jdbc:postgresql://127.0.0.1:{port}/settlement_lab"
        output = run([launcher, "pg", "race", "settle-first", "--drop-reply"], caller, env)
        require_lines(output, ["wait_event_type=Lock", "reply_recovered=true"])
        domain, = [line.removeprefix("domain=") for line in output.splitlines()
                   if line.startswith("domain=")]
        history = str(caller / "race history.json")
        run([launcher, "pg", "export", domain, history], caller, env)
        verified = run([launcher, "verify", history], caller, env)
        if "gross=300 balance=60" not in verified:
            raise RuntimeError(f"Unexpected verification result\n{verified}")
        print(verified.strip())
    finally:
        run([*compose, "down", "--volumes", "--remove-orphans"], caller, env)


def smoke(archive, postgres):
    archive = archive.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="settlement pack ") as scratch:
        temp = Path(scratch)
        app = temp / archive.stem
        caller = temp / "caller outside distribution"
        caller.mkdir()
        with ZipFile(archive) as bundle:
            if bundle.testzip() is not None:
                raise RuntimeError("Distribution ZIP failed its CRC check")
            members = bundle.namelist()
            if any(not name.startswith(archive.stem + "/") for name in members):
                raise RuntimeError("Archive must contain one versioned application directory")
        if os.name == "nt":
            run(["tar.exe", "-xf", str(archive), "-C", str(temp)], caller)
            launcher = app / "bin/settlement-lab.bat"
        else:
            run(["unzip", "-q", str(archive), "-d", str(temp)], caller)
            launcher = app / "bin/settlement-lab"
        required = ["README.md", "compose.yaml", "bin/settlement-lab", "bin/settlement-lab.bat",
                    f"lib/{archive.stem}.jar"]
        for name in required:
            if not (app / name).is_file():
                raise RuntimeError(f"Missing distribution file: {name}")
        source = Path(__file__).resolve().parents[1]
        notices = [path for path in (source / "notices").rglob("*") if path.is_file()]
        if not notices:
            raise RuntimeError("The source notices inventory is empty")
        for path in [source / "LICENSE", source / "THIRD_PARTY_NOTICES.md", *notices]:
            target = app / path.relative_to(source)
            if not target.is_file() or target.read_text("utf-8") != path.read_text("utf-8"):
                raise RuntimeError(f"Absent or altered distribution notice: {target.name}")
        env = dict(os.environ, GRADLE_USER_HOME=str(temp / "unused-gradle-home"))
        for name in ("GRADLE_HOME", "CLASSPATH"):
            env.pop(name, None)
        output = run([str(launcher), "demo", "cycle"], caller, env)
        require_lines(output, ["Batch cycle [AB, BC, CA]: COMMITTED (gross settled=300)",
                               "Closing balances (SIM): A=20 B=20 C=20 | total=60",
                               "  AB SETTLED batch=cycle", "  BC SETTLED batch=cycle",
                               "  CA SETTLED batch=cycle"])
        print("Native launcher verified the three-member cycle from outside the distribution.")
        selection = run([str(launcher), "demo", "selection"], caller, env)
        require_lines(selection, ["fifo-v1 queue_positions=[] gross=0 gap=300 (100%)",
                                  "heuristic-v1 queue_positions=[1, 2, 3] gross=300 gap=0 (0%)",
                                  "Hand-calculated optimum=5",
                                  "heuristic-v1 queue_positions=[1] gross=4 gap=1 (20%)"])
        print("Packaged selectors reproduced the blocked ring and the suboptimal choice.")
        if postgres:
            postgres_smoke(app, str(launcher), caller, env)
        if Path(env["GRADLE_USER_HOME"]).exists():
            raise RuntimeError("The distributed launcher unexpectedly used Gradle")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--postgres", action="store_true", help="also smoke the bundled Compose lab")
    args = parser.parse_args()
    smoke(args.archive, args.postgres)
