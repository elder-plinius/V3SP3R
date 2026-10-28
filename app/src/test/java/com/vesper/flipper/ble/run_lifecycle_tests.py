"""Run production BLE lifecycle methods on the JVM, without Gradle/Android.

Uses the project's already-cached Kotlin compiler and JUnit dependencies. Android
boundary calls are fakes; lifecycle method bodies are extracted verbatim from
FlipperBleService.kt (not reimplemented), plus the complete BleServiceManager.
Full app compilation and device tests remain necessary; no radio is exercised.
Run: python app/src/test/java/com/vesper/flipper/ble/run_lifecycle_tests.py
Reproduce RED without changing the worktree: add --revision <pre-fix-commit>
"""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile

ROOT = next(parent for parent in Path(__file__).resolve().parents
            if (parent / 'settings.gradle.kts').exists())
CACHE = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
SCRATCH = Path(os.environ.get('VESPER_TEST_TMP', os.environ.get('TMPDIR', ROOT / '.gradle/lifecycle-test-work')))
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--revision', help='Read production sources from a Git commit, without checkout')
args = parser.parse_args()


def production_source(name):
    relative = 'app/src/main/java/com/vesper/flipper/ble/' + name
    if args.revision:
        return subprocess.check_output(['git', 'show', args.revision + ':' + relative], cwd=ROOT, text=True)
    return (ROOT / relative).read_text(encoding='utf-8')


def jar(group, name, version=None):
    base = CACHE / group / name
    matches = sorted(base.glob(f'{version or "*"}/*/{name}-*.jar'))
    if not matches:
        raise RuntimeError(f'Missing cached dependency: {group}:{name}:{version or "*"}')
    return matches[-1]


def method(source, declaration):
    start = source.index(declaration)
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable'),
            jar('org.jetbrains.kotlin', 'kotlin-stdlib'),
            jar('org.jetbrains.kotlin', 'kotlin-script-runtime'),
            jar('org.jetbrains.kotlin', 'kotlin-reflect'),
            jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm'),
            jar('org.jetbrains', 'annotations')]
runtime = [jar('org.jetbrains.kotlin', 'kotlin-stdlib'),
           jar('junit', 'junit', '4.13.2'),
           jar('org.hamcrest', 'hamcrest-core'),
           jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm'),
           jar('org.jetbrains', 'annotations')]
java_name = 'java.exe' if os.name == 'nt' else 'java'
java = str(Path(os.environ['JAVA_HOME']) / 'bin' / java_name) if os.environ.get('JAVA_HOME') else 'java'
source = production_source('FlipperBleService.kt')
methods = '\n'.join(method(source, text) for text in [
    'override fun onStartCommand(', 'private fun startForegroundService(',
    'private fun initializeBluetooth(',
    'private fun hasBluetoothPermissions(', 'override fun onDestroy(',
    'private suspend fun ensureUsbPermission(', 'fun startScan(', 'private fun updateNotification('])
companion_methods = method(source, 'fun startService(context:')
# New methods need not exist in the pre-fix source. They are never synthesized.
if 'private fun stopForegroundService(' in source:
    methods += '\n' + method(source, 'private fun stopForegroundService(')
if 'private fun canStartForeground(' in source:
    companion_methods += '\n' + method(source, 'private fun canStartForeground(')
fixture = Path(__file__).with_name('lifecycle_fixture.kt.txt').read_text(encoding='utf-8')
fixture = fixture.replace('// SERVICE_METHODS', methods).replace('// COMPANION_METHODS', companion_methods)
manager = production_source('BleServiceManager.kt')
manager = '\n'.join(line for line in manager.splitlines()
                    if not line.startswith(('import android.', 'import dagger.', 'import javax.inject.')))
SCRATCH.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='ble-lifecycle-', dir=SCRATCH) as work:
    work = Path(work)
    harness = work / 'ExtractedServiceStartupTest.kt'
    harness.write_text(fixture, encoding='utf-8')
    exception_stub = work / 'ForegroundServiceStartNotAllowedException.kt'
    exception_stub.write_text('package android.app\nclass ForegroundServiceStartNotAllowedException : IllegalStateException()\nclass NotificationManager { var posts = 0; fun notify(id: Int, notification: Any) { posts++ } }\n', encoding='utf-8')
    manager_file = work / 'BleServiceManager.kt'
    manager_file.write_text(manager, encoding='utf-8')
    files = [harness, manager_file, exception_stub]

    output = work / 'classes'
    cp = os.pathsep.join(map(str, runtime))
    compile_command = [java, '-cp', os.pathsep.join(map(str, compiler)),
                       'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                       '-no-stdlib', '-no-reflect', '-nowarn', '-jvm-target', '17',
                       '-classpath', cp, '-d', str(output), *map(str, files)]
    subprocess.run(compile_command, check=True)
    classes = ['com.vesper.flipper.ble.ExtractedServiceStartupTest',
               'com.vesper.flipper.ble.ExtractedServiceBindingTest']

    result = subprocess.run([java, '-cp', str(output) + os.pathsep + cp,
                             'org.junit.runner.JUnitCore', *classes])
    raise SystemExit(result.returncode)
