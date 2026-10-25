# Native runtime components

`app/src/main/jniLibs` contains official Termux native executables and dependencies, packaged under Android native-library filenames so Android extracts them to its executable library directory. They run as child processes; Cupbox's own Java UI and bot code remains separate.

The complete PRoot, BusyBox and talloc source archives are in [sources](sources). The corresponding binary/package hashes are in [native-manifest.json](../../docs/native-manifest.json). Upstream package copyright notices and GPL license texts are bundled into the APK under `assets/licenses`.

| Termux package | Version | Upstream source |
| --- | --- | --- |
| proot | 5.1.107.101 | https://github.com/termux/proot/tree/v5.1.107.101 |
| busybox | 1.38.0-1 | https://busybox.net/downloads/busybox-1.38.0.tar.bz2 |
| libtalloc | 2.5.0 | https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz |
| libandroid-shmem | 0.7 | https://github.com/termux/libandroid-shmem/tree/v0.7 |
| libandroid-selinux | 14.0.0.11-1 | https://android.googlesource.com/platform/external/selinux |
| pcre2 | 10.49 | https://github.com/PCRE2Project/pcre2/releases/tag/pcre2-10.49 |

Termux recipes, configurations and patches are maintained under [termux-packages/packages](https://github.com/termux/termux-packages/tree/master/packages). `scripts/prepare-native.py` documents the exact extraction/renaming performed on the official `.deb` files. No native code changes are applied by Cupbox. APK filenames are not SONAME changes; required names are supplied by app-private symbolic links.

Ubuntu base images are fetched and verified at build time by `scripts/prepare-bundles.py` and bundled into the APK. Image licenses and corresponding source locations are documented in `assets/licenses/UBUNTU-BASE.txt`. Linux QQ, NapCat and launcher source are fetched separately during user-requested initialization, with fixed version URLs and SHA-256 verification in `LocalRuntime.java` and `RuntimePins.java`.
