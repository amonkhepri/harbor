#!/usr/bin/env python3
"""Fetch immutable upstream inputs; record hashes without vendoring entire sources."""
import hashlib
import json
from pathlib import Path
import urllib.request

out = Path(__file__).resolve().parents[3] / 'build/mx011-cancellation/sources'
out.mkdir(parents=True, exist_ok=True)
sdk = 'https://raw.githubusercontent.com/matrix-org/matrix-rust-sdk/b629ba1e689fc45f588e485ee7aec901726cc2a8/'
uni = 'https://raw.githubusercontent.com/mozilla/uniffi-rs/e5f4821410bea19e71984ea5e06a7bc8b11ed9e5/'
inputs = {
    'release.json': 'https://api.github.com/repos/matrix-org/matrix-rust-components-kotlin/releases/tags/sdk-v26.08.05',
    'sdk.pom': 'https://repo.maven.apache.org/maven2/org/matrix/rustcomponents/sdk-android/26.08.05/sdk-android-26.08.05.pom',
    'deadpool-sync.crate': 'https://static.crates.io/crates/deadpool-sync/deadpool-sync-0.2.0.crate',
    'deadpool-runtime.crate': 'https://static.crates.io/crates/deadpool-runtime/deadpool-runtime-0.3.1.crate',
    'tokio.crate': 'https://static.crates.io/crates/tokio/tokio-1.53.1.crate',
}
for path in ['Cargo.lock', 'bindings/matrix-sdk-ffi/src/client.rs',
             'bindings/matrix-sdk-ffi/Cargo.toml', 'crates/matrix-sdk/src/client/mod.rs',
             'crates/matrix-sdk-sqlite/src/state_store.rs', 'crates/matrix-sdk-sqlite/src/utils.rs']:
    inputs[path.replace('/', '_')] = sdk + path
for path in ['uniffi_core/src/ffi/rustfuture/future.rs', 'uniffi_core/src/ffi/rustfuture/scheduler.rs',
             'uniffi_core/src/ffi/rustfuture/mod.rs', 'uniffi_bindgen/src/bindings/kotlin/templates/Async.kt',
             'uniffi_macros/src/export/scaffolding.rs']:
    inputs[path.replace('/', '_')] = uni + path
for name, url in inputs.items():
    data = urllib.request.urlopen(url, timeout=30).read()
    (out / name).write_bytes(data)
    if name == 'release.json':
        assert 'b629ba1e689fc45f588e485ee7aec901726cc2a8' in json.loads(data)['body']
    print(hashlib.sha256(data).hexdigest(), name, url, flush=True)
# Verify registry archive contents against this release's lock, including version.
import re
lock = (out / 'Cargo.lock').read_text()
for name, version in [('tokio', '1.53.1'), ('deadpool-sync', '0.2.0'), ('deadpool-runtime', '0.3.1')]:
    expected = re.search(r'name = "' + name + r'"\nversion = "' + re.escape(version)
                         + r'"\nsource = "[^"]+"\nchecksum = "([^"]+)"', lock)[1]
    actual = hashlib.sha256((out / (name + '.crate')).read_bytes()).hexdigest()
    assert actual == expected, name
print('PASS release Cargo.lock registry checksums')
