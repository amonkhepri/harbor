#!/usr/bin/env python3
"""Compile the isolated host fixture using cached, version-pinned JVM libraries."""
import hashlib
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[3]
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
out = root / 'build/mx011-cancellation/host'
out.mkdir(parents=True, exist_ok=True)
def jar(group, name, version):
    files = list((cache / group / name / version).glob(f'*/{name}-{version}.jar'))
    if len(files) != 1:
        raise RuntimeError(f'Expected one cached jar: {group}:{name}:{version}')
    return files[0]
stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.25')
coroutines = jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.7.3')
annotations = jar('org.jetbrains', 'annotations', '13.0')
compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '1.9.25'), stdlib,
            jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '1.9.25'),
            jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
            jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'), annotations]
cp = os.pathsep.join(map(str, [stdlib, coroutines, annotations]))
for p in compiler + [coroutines]:
    print(p.name, hashlib.sha256(p.read_bytes()).hexdigest(), flush=True)
subprocess.run(['java', '-cp', os.pathsep.join(map(str, compiler)),
                'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                '-jvm-target', '1.8', '-classpath', cp, '-d', str(out),
                str(Path(__file__).with_name('BridgeProof.kt'))], check=True)
subprocess.run(['java', '-cp', str(out) + os.pathsep + cp, 'BridgeProofKt'], check=True, timeout=30)
