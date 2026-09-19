#!/usr/bin/env python3
"""Bounded, offline javap inspection; no SDK code is loaded or executed."""
import hashlib, os, pathlib, re, subprocess, tempfile, zipfile
out = pathlib.Path(__file__).resolve().parent
repo = out.parents[2]
cached = pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1/org.matrix.rustcomponents/sdk-android/26.08.05'
aar = pathlib.Path(os.environ['MATRIX_AAR']) if 'MATRIX_AAR' in os.environ else next(cached.glob('*/*.aar'))
javap = os.environ.get('JAVAP', '/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javap')
verifier = pathlib.Path(os.environ.get('MATRIX_VERIFIER', '/Users/amunratis/AndroidStudioProjects/HarborAgent/agent-tools/verify_matrix_sdk_api_shape.sh'))
env = dict(os.environ, TARGET_REPO=str(repo))
r = subprocess.run(['bash', str(verifier), '--aar', str(aar), '--javap', javap], env=env, capture_output=True, text=True)
(out/'verifier.txt').write_text(r.stdout+r.stderr+f'Exit status: {r.returncode}\n')
(out/'identity.txt').write_text(f'AAR: {aar}\nSHA256: {hashlib.sha256(aar.read_bytes()).hexdigest()}\nVerifier SHA256: {hashlib.sha256(verifier.read_bytes()).hexdigest()}\n'+subprocess.check_output([javap, '-version'], text=True))
classes = 'ClientInterface SyncServiceInterface SyncServiceBuilderInterface TaskHandleInterface SyncListenerV2 SyncServiceStateObserver SyncServiceState SyncSettingsV2 SyncResponseV2 SyncResponseRoomsV2'.split()
bytecode = ['Client', 'SyncService', 'SyncServiceBuilder', 'TaskHandle', 'TaskHandle$UniffiCleanAction', 'SyncSettingsV2', 'SyncResponseV2', 'SyncResponseRoomsV2', 'FfiConverterCallbackInterface', 'UniffiHandleMap', 'uniffiCallbackInterfaceSyncListenerV2$onUpdate', 'uniffiCallbackInterfaceSyncListenerV2$onUpdate$callback$makeCall$1', 'uniffiCallbackInterfaceSyncListenerV2$uniffiFree', 'uniffiCallbackInterfaceSyncServiceStateObserver$onUpdate', 'uniffiCallbackInterfaceSyncServiceStateObserver$onUpdate$callback$makeCall$1', 'Matrix_sdk_ffiKt', 'Client$syncOnceV2$3', 'Client$syncOnceV2$4', 'Client$syncOnceV2$5', 'Client$syncOnceV2$6', 'ClientException', 'UniffiNullRustCallStatusErrorHandler', 'SyncService$start$5', 'SyncService$stop$5', 'SyncServiceBuilder$finish$5']
with tempfile.TemporaryDirectory() as tmp:
    jar = pathlib.Path(tmp)/'classes.jar'
    with zipfile.ZipFile(aar) as z: jar.write_bytes(z.read('classes.jar'))
    with zipfile.ZipFile(jar) as z:
        (out/'sync-class-index.txt').write_text('\n'.join(n for n in z.namelist() if n.startswith('org/matrix/rustcomponents/sdk/') and re.search('Sync|TaskHandle', n))+'\n')
    def dump(c, code=False):
        return subprocess.check_output([javap, '-p']+(['-c'] if code else [])+['-classpath', str(jar), 'org.matrix.rustcomponents.sdk.'+c], text=True)
    (out/'signatures.txt').write_text('\n'.join(dump(c) for c in classes))
    for c in bytecode:
        text = dump(c, True)
        # Keep only sync and common object-lifetime methods on the large Client
        # and only coroutine bridge methods on the large generated file facade.
        if c in ('Client', 'Matrix_sdk_ffiKt'):
            blocks = re.split(r'(?=^  (?:public|private|protected|static) .*\n)', text, flags=re.M)
            pattern = r'syncOnceV2|syncV2|syncService|destroy\(|close\(|callWithPointer|uniffiClonePointer|Client\(' if c == 'Client' else r'uniffiRustCallAsync|uniffiRustCallWithError|uniffiCheckCallStatus|uniffiTraitInterfaceCall'
            text = '\n'.join(b for b in blocks if re.search(pattern, b.split('\n')[0]))
        (out/(c+'.javap.txt')).write_text(text)
print(f'Wrote evidence to {out}; verifier exit {r.returncode}')
