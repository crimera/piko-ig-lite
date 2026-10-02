# piko-ig-lite

Lean [piko](https://github.com/crimera/piko) patch bundle for Instagram.

This repository exists so the Instagram patches can move fast without dragging the
Twitter/NewX patch catalog along. It consumes the shared safeguards from
[piko-patches-library](https://github.com/crimera/piko-patches-library) and the typed
Dalvik emission layer from [morphe-bytecode](https://github.com/crimera/morphe-bytecode).

## Status

Scaffold: build system, extension modules and lint gates are wired. The initial patch
set (media downloads + hide ads) is being ported from piko.

## Architecture

The resolution and version-bump strategy is documented in
[docs/ig-patch-architecture.md](docs/ig-patch-architecture.md).

## Layout

```
patches/               Morphe patch project
extensions/instagram/  Instagram extension runtime and model wrappers
extensions/shared/     Shared extension runtime (piko fork + Morphe extensions library)
```

## Build

```bash
./gradlew :patches:build --no-daemon
```

## Patch

```bash
./patch-ig.sh path/to/instagram.apk
```

To iterate against a local morphe-patcher checkout instead of the shipped CLI jar, build the
runner once with `(cd ../morphe-patcher && ./gradlew :cli:installDist)` and use:

```bash
./patch-ig-cli.sh path/to/instagram.apk   # output: ~/Downloads/piko-ig-lite-patched-cli.apk
```

## Verification

```bash
./gradlew :patches:test --no-daemon
./gradlew :patches:lintResolvers --no-daemon
./gradlew :patches:checkExtensionDescriptors --no-daemon
```

## License

GPL-3.0-or-later. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
