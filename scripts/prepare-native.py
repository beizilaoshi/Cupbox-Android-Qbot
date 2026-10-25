"""Extract pinned Termux packages into APK native executable slots (no build-time shell)."""
import io, json, tarfile, hashlib
from pathlib import Path

root = Path(__file__).resolve().parent.parent
packages = root / '.tools/native-packages'
manifest = []
for arch, abi in [('aarch64', 'arm64-v8a'), ('x86_64', 'x86_64')]:
    dest = root / 'app/src/main/jniLibs' / abi
    dest.mkdir(parents=True, exist_ok=True)
    for package, mapping in [
        ('proot', {'bin/proot': 'libproot.so', 'libexec/proot/loader': 'libloader.so'}),
        ('busybox', {'bin/busybox': 'libbusybox.so', 'lib/libbusybox.so.1.38.0': 'libbusybox-core.so'}),
        ('talloc', {'lib/libtalloc.so.2': 'libtalloc.so'}),
        ('shmem', {'lib/libandroid-shmem.so': 'libandroid-shmem.so'}),
        ('selinux', {'lib/libandroid-selinux.so': 'libandroid-selinux.so'}),
        ('pcre2', {'lib/libpcre2-8.so': 'libpcre2-8.so'})
    ]:
        source = packages / (package + '-' + arch + '.deb')
        data = source.read_bytes()
        assert data[:8] == b'!<arch>\n'
        offset = 8
        while offset < len(data):
            header = data[offset:offset+60]
            name = header[:16].decode().strip().rstrip('/')
            size = int(header[48:58])
            body = data[offset+60:offset+60+size]
            offset += 60 + size + size % 2
            if name.startswith('data.tar'):
                with tarfile.open(fileobj=io.BytesIO(body), mode='r:*') as archive:
                    members = archive.getmembers()
                    if arch == 'aarch64':
                        license_dir = root / 'app/src/main/assets/licenses'
                        license_dir.mkdir(parents=True, exist_ok=True)
                        for item in members:
                            if item.isfile() and item.name.endswith('/copyright'):
                                (license_dir / (package+'-copyright.txt')).write_bytes(archive.extractfile(item).read())
                    for suffix, output in mapping.items():
                        candidates = [m for m in members if m.name.endswith('/'+suffix)]
                        if not candidates and package == 'talloc':
                            candidates = [m for m in members if '/libtalloc.so.2.' in m.name and m.isfile()]
                        if not candidates:
                            raise ValueError((package, suffix, [m.name for m in members]))
                        stream = archive.extractfile(candidates[0])
                        (dest / output).write_bytes(stream.read())
                        manifest.append({'abi': abi, 'file': output, 'package': source.name,
                                         'sha256': hashlib.sha256((dest / output).read_bytes()).hexdigest(),
                                         'packageSha256': hashlib.sha256(data).hexdigest()})
                break
(root / 'docs/native-manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
print('Prepared', len(manifest), 'native binaries')
