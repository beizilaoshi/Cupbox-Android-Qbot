"""Fetch and verify redistributable Ubuntu base images before building the offline-base APK."""
from pathlib import Path
import hashlib
import shutil
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
PACKAGES = {
    "aarch64": ("ubuntu-arm64.tar.xz", "91acaa786b8e2fbba56a9fd0f8a1188cee482b5c7baeed707b29ddaa9a294daa"),
    "x86_64": ("ubuntu-x86_64.tar.xz", "3a769bce23985effb504140d43b6ddd73dac9e261d1932894afa31de81e45414"),
}

def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()

for arch, (cache_name, expected) in PACKAGES.items():
    target = ROOT / "app/src/main/assets/runtime/bundles" / f"ubuntu-{arch}.tar.xz"
    if target.exists() and sha(target) == expected:
        print(f"Verified bundled Ubuntu {arch}")
        continue
    cached = ROOT / ".tools/native-packages" / cache_name
    cached.parent.mkdir(parents=True, exist_ok=True)
    if not cached.exists() or sha(cached) != expected:
        url = f"https://github.com/termux/proot-distro/releases/download/v4.18.0/ubuntu-noble-{arch}-pd-v4.18.0.tar.xz"
        partial = cached.with_suffix(".download")
        urllib.request.urlretrieve(url, partial)
        if sha(partial) != expected:
            partial.unlink()
            raise RuntimeError(f"Ubuntu {arch} checksum mismatch")
        partial.replace(cached)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(cached, target)
    print(f"Prepared bundled Ubuntu {arch}")
