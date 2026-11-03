#!/usr/bin/env python3
"""Build once and run disjoint AndroidJUnitRunner shards on isolated emulators."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime
import fcntl
import hashlib
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "lofit.app.test"
RUNNER = f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"


@dataclass
class TestResult:
    name: str
    code: int
    stack: str = ""


def parse_results(output):
    tests, errors, bundle = [], [], {}
    last_key = None
    for line in output.splitlines():
        match = re.match(r"INSTRUMENTATION_STATUS: (\w+)=(.*)", line)
        if match:
            last_key, value = match.groups()
            bundle[last_key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(": ", 1)[1])
            if code in (0, -1, -2, -3, -4):
                if "class" not in bundle or "test" not in bundle:
                    errors.append(f"Test result lacks class/method: {bundle}")
                else:
                    tests.append(TestResult(
                        bundle["class"] + "#" + bundle["test"], code, bundle.get("stack", "")
                    ))
            bundle, last_key = {}, None
        elif line.startswith("INSTRUMENTATION_RESULT: shortMsg="):
            errors.append(line)
        elif line.startswith("INSTRUMENTATION_"):
            last_key = None
        elif last_key:
            bundle[last_key] += "\n" + line
    if re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)$", output, re.M) != ["-1"]:
        errors.append("Instrumentation did not finish normally")
    return tests, errors


def validate_tests(tests, expected):
    actual = Counter(test.name for test in tests)
    wanted = Counter(expected)
    errors = []
    if actual != wanted:
        if wanted - actual:
            errors.append("Missing tests: " + ", ".join(sorted((wanted - actual).elements())))
        if actual - wanted:
            errors.append("Unexpected/repeated tests: " + ", ".join(sorted((actual - wanted).elements())))
    return errors


def validate_shards(all_tests, shards):
    names = [test for shard in shards for test in shard]
    if not all_tests or any(not shard for shard in shards):
        raise RuntimeError("No tests selected, or an empty shard; use fewer shards")
    if len(set(all_tests)) != len(all_tests) or Counter(names) != Counter(all_tests):
        raise RuntimeError("Shard discovery has missing or duplicate tests")


def properties(path):
    return dict(line.strip().split("=", 1) for line in path.read_text().splitlines()
                if "=" in line and not line.lstrip().startswith("#"))


def command(args, *, env=None, timeout=120):
    result = subprocess.run([str(arg) for arg in args], cwd=ROOT, env=env,
                            capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Command failed: {args}\n{result.stdout}\n{result.stderr}")
    return result.stdout


def adb_command(adb, serial, *args, **kwargs):
    return command([adb, "-s", serial, *args],
                   env={**os.environ, "ANDROID_SERIAL": serial}, **kwargs)


def create_emulators(sdk, count, template_name):
    emulator = sdk / "emulator/emulator"
    avd_home = Path(os.environ.get("ANDROID_AVD_HOME",
                    str(Path(os.environ.get("ANDROID_USER_HOME", str(Path.home() / ".android"))) / "avd")))
    names = command([emulator, "-list-avds"]).splitlines()
    if template_name is None:
        if len(names) != 1:
            raise RuntimeError("Choose a template AVD with --avd NAME")
        template_name = names[0]
    if template_name not in names:
        raise RuntimeError(f"Unknown template AVD: {template_name}")
    template = properties(Path(properties(avd_home / f"{template_name}.ini")["path"]) / "config.ini")
    if not (sdk / template["image.sysdir.1"] / "system.img").is_file():
        raise RuntimeError("Template AVD's system image is not installed")
    # Fresh data disks, same system image and display profile. Never copy the user's app data.
    test_home = ROOT / "build/test-avds"
    test_home.mkdir(parents=True, exist_ok=True)
    prefix = "lofit-" + hashlib.sha256(str(ROOT).encode()).hexdigest()[:8]
    serials = []
    for index in range(count):
        name = f"{prefix}-shard-{index}"
        directory = test_home / f"{name}.avd"
        if not directory.exists():
            directory.mkdir()
            config = {**template, "AvdId": name, "avd.ini.displayname": name,
                      "hw.cpu.ncore": "4", "hw.ramSize": "2048", "showDeviceFrame": "no"}
            (directory / "config.ini").write_text("".join(f"{k}={v}\n" for k, v in config.items()))
            (test_home / f"{name}.ini").write_text(
                f"avd.ini.encoding=UTF-8\npath={directory}\ntarget={template['target']}\n")
        else:
            config = properties(directory / "config.ini")
            profile = ("image.sysdir.1", "hw.lcd.width", "hw.lcd.height", "hw.lcd.density")
            if any(config.get(key) != template.get(key) for key in profile):
                raise RuntimeError("Existing test AVDs use a different profile. Stop them and remove build/test-avds before changing templates.")
        print(f"Starting {name}…", flush=True)
        output = command([ROOT / "scripts/start-emulator.sh", name],
                         env={**os.environ, "ANDROID_AVD_HOME": str(test_home),
                              "LOFIT_EMULATOR_HEADLESS": "1"}, timeout=180)
        match = re.search(r"Emulator ready: (emulator-\d+)", output)
        if not match:
            raise RuntimeError(f"Emulator startup did not report a serial:\n{output}")
        serials.append(match[1])
    return serials


def instrumentation(adb, serial, selection, *, shard=None, count=None, discover=False, coverage=None):
    args = [adb, "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "package", "lofit"]
    if selection:
        args += ["-e", "class", shlex.quote(selection)]
    if shard is not None:
        args += ["-e", "numShards", str(count), "-e", "shardIndex", str(shard)]
    if discover:
        args += ["-e", "log", "true"]
    if coverage:
        args += ["-e", "coverage", "true", "-e", "coverageFile", coverage]
    return [str(arg) for arg in [*args, RUNNER]]


def discover(adb, serial, selection, **kwargs):
    tests, errors = parse_results(command(instrumentation(adb, serial, selection, discover=True, **kwargs)))
    if errors or any(test.code != 0 for test in tests):
        raise RuntimeError("Test discovery failed: " + str(errors or tests))
    return [test.name for test in tests]


def write_report(path, serial, tests, errors, elapsed):
    suite = ET.Element("testsuite", name=serial, tests=str(len(tests) + len(errors)),
                       failures=str(sum(test.code == -2 for test in tests)),
                       errors=str(len(errors) + sum(test.code == -1 for test in tests)),
                       skipped=str(sum(test.code in (-3, -4) for test in tests)), time=f"{elapsed:.3f}")
    for test in tests:
        cls, name = test.name.split("#", 1)
        case = ET.SubElement(suite, "testcase", classname=cls, name=name)
        if test.code != 0:
            tag = {-1: "error", -2: "failure", -3: "skipped", -4: "skipped"}[test.code]
            ET.SubElement(case, tag).text = test.stack
    for error in errors:
        case = ET.SubElement(suite, "testcase", classname="shard.runner", name="complete_execution")
        ET.SubElement(case, "error").text = error
    ET.indent(suite)
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def run_shard(adb, serial, index, count, selection, expected, report, timeout):
    started = time.monotonic()
    log = report / f"shard-{index}.log"
    coverage_name = f"shard-{report.name}-{index}.ec"
    remote = f"/data/user/0/{PACKAGE}/files/{coverage_name}"
    errors = []
    with log.open("w") as output:
        try:
            result = subprocess.run(instrumentation(adb, serial, selection, shard=index, count=count,
                                                   coverage=remote), stdout=output, stderr=subprocess.STDOUT,
                                    env={**os.environ, "ANDROID_SERIAL": serial}, timeout=timeout)
            if result.returncode:
                errors.append(f"adb exited with {result.returncode}")
        except subprocess.TimeoutExpired:
            errors.append(f"Shard timed out after {timeout}s")
            adb_command(adb, serial, "shell", "am", "force-stop", PACKAGE)
    tests, protocol_errors = parse_results(log.read_text())
    errors += protocol_errors + validate_tests(tests, expected)
    try:
        coverage = subprocess.run([str(adb), "-s", serial, "exec-out", "run-as", PACKAGE,
                                   "cat", f"files/{coverage_name}"], capture_output=True, timeout=30, check=True).stdout
        if not coverage.startswith(b"\x01\xc0\xc0"):
            raise RuntimeError("Invalid or empty JaCoCo coverage")
        (report / f"shard-{index}.ec").write_bytes(coverage)
        adb_command(adb, serial, "shell", "run-as", PACKAGE, "rm", f"files/{coverage_name}")
    except (subprocess.SubprocessError, RuntimeError) as error:
        errors.append(f"Coverage collection failed: {error}")
    elapsed = time.monotonic() - started
    write_report(report / f"TEST-shard-{index}.xml", serial, tests, errors, elapsed)
    failed = bool(errors) or any(test.code in (-1, -2) for test in tests)
    skipped = sum(test.code in (-3, -4) for test in tests)
    print(f"Shard {index}: {'FAIL' if failed else 'PASS'}, {len(tests)} tests, {skipped} skipped, {elapsed:.1f}s", flush=True)
    if failed:
        print("\n".join(errors + [test.stack for test in tests if test.code in (-1, -2)]), flush=True)
    return failed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--shards", type=int, default=2)
    parser.add_argument("--avd", help="template AVD; defaults to the sole installed AVD")
    parser.add_argument("--serial", action="append", help="existing emulator to use; repeat once per shard")
    parser.add_argument("--class", dest="selection", help="comma-separated classes or Class#method selectors")
    parser.add_argument("--timeout", type=int, default=900, help="timeout in seconds per shard")
    args = parser.parse_args()
    if args.shards < 1 or args.timeout < 1:
        parser.error("--shards and --timeout must be positive")
    if args.selection and not re.fullmatch(r"[\w.$#,]+", args.selection):
        parser.error("Invalid class selector")
    if args.serial and (len(args.serial) != args.shards or len(set(args.serial)) != args.shards or
                        any(not re.fullmatch(r"emulator-\d+", s) for s in args.serial)):
        parser.error("Provide one distinct emulator serial per shard; physical devices are not supported")
    build = ROOT / "build"
    build.mkdir(exist_ok=True)
    with (build / "sharded-tests.lock").open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("Another sharded test run is already using this checkout")
        sdk = Path(properties(ROOT / "local.properties")["sdk.dir"])
        adb = sdk / "platform-tools/adb"
        serials = args.serial or create_emulators(sdk, args.shards, args.avd)
        print("Building debug APKs…", flush=True)
        subprocess.run([str(ROOT / "gradlew"), ":app:assembleDebug", ":app:assembleDebugAndroidTest"],
                       cwd=ROOT, env={**os.environ, "ANDROID_SERIAL": serials[0]}, check=True)
        def prepare(serial):
            for apk in ("debug/app-debug.apk", "androidTest/debug/app-debug-androidTest.apk"):
                adb_command(adb, serial, "install", "-r", ROOT / "app/build/outputs/apk" / apk)
            for scale in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
                adb_command(adb, serial, "shell", "settings", "put", "global", scale, "0")
            adb_command(adb, serial, "shell", "settings", "put", "secure", "show_ime_with_hard_keyboard", "1")
            adb_command(adb, serial, "shell", "svc", "power", "stayon", "true")
            adb_command(adb, serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
            adb_command(adb, serial, "shell", "wm", "dismiss-keyguard")
            adb_command(adb, serial, "shell", "am", "force-stop", PACKAGE)
        with ThreadPoolExecutor(max_workers=args.shards) as pool:
            list(pool.map(prepare, serials))
        all_tests = discover(adb, serials[0], args.selection)
        shards = [discover(adb, serial, args.selection, shard=i, count=args.shards)
                  for i, serial in enumerate(serials)]
        validate_shards(all_tests, shards)
        base = build / "reports/sharded-tests"
        base.mkdir(parents=True, exist_ok=True)
        report = Path(tempfile.mkdtemp(prefix=datetime.now().strftime("%Y%m%d-%H%M%S-"), dir=base))
        (report / "expected-tests.txt").write_text("\n".join(sorted(all_tests)) + "\n")
        print(f"Running {len(all_tests)} tests in shards {[len(s) for s in shards]}. Reports: {report}", flush=True)
        started = time.monotonic()
        with ThreadPoolExecutor(max_workers=args.shards) as pool:
            futures = [pool.submit(run_shard, adb, serial, i, args.shards, args.selection,
                                   shards[i], report, args.timeout) for i, serial in enumerate(serials)]
            try:
                failed = any([future.result() for future in futures])
            except KeyboardInterrupt:
                for serial in serials:
                    adb_command(adb, serial, "shell", "am", "force-stop", PACKAGE)
                raise
        files = [report / f"shard-{i}.ec" for i in range(args.shards)]
        if all(path.is_file() for path in files):
            # JaCoCo readers accept multiple execution streams and union matching class probes.
            (report / "coverage.ec").write_bytes(b"".join(path.read_bytes() for path in files))
        print(f"{'FAIL' if failed else 'PASS'}: {len(all_tests)} tests, {time.monotonic() - started:.1f}s. Reports: {report}")
        return int(failed)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (RuntimeError, subprocess.SubprocessError, OSError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
