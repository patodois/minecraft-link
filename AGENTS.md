# Agent guide

minecraft-link connects Minecraft: Java Edition to other running games for mod users and passthrough integration developers.
This branch targets Minecraft 26.3 and implements host/guest roles through link-java and rewrite-bridge's contract revision 7.

## Toolchain, build and CI

- Run Gradle with JDK 25 on this branch; CI uses Temurin 25 on Linux and macOS.
- The resolver downloads Java 21 for tools that need it; use the checked-in Gradle wrapper.
- Keep a link-java checkout next to this repository (`../link-java`), as CI does.
- `-PlinkJavaDir=<path>` selects another checkout; the README specifies link-java v0.3.0.
- Exact CI build/test command from `.github/workflows/build.yml`:

```sh
./gradlew build --no-daemon
```

This builds Fabric (also used on Quilt), NeoForge and Forge, and runs the common tests.
The README's tests-only command is `./gradlew :common:test --no-daemon`.
Optional scripted client runs use `:fabric:runClient`, `:neoforge:runClient` or `:forge:runClient`.

## Code map

- `common/src/main/`: loader-independent logic, mixins and shaders.
- `common/src/test/`: coordinates, cameras, collisions, sessions and settings tests.
- `fabric/`, `neoforge/`, `forge/`: loader entry points, events and link-java bundling.
- `autotest/`: scripts for real client runs.
- `docs/`: integration details and recorded client checks.

## Project rules

- The render/game thread must not call the link or wait; exchange samples through thread-safe holders.
- Keep link I/O, collision construction and frame reading on their documented worker threads.
- Use only loopback `127.0.0.1` and validate peer address/session, pixel-file paths and sizes.
- Keep mixin compatibility at `JAVA_21`: Forge's Mixin version rejects `JAVA_25`.
- Gradle's game/loader downloads and client run directories stay outside committed source.
- The Gradle JVM is capped at 1 GB; dev clients use `-Xmx2G` as described in the README.
- Preserve the unofficial Minecraft disclaimer; the project ships no Mojang code or game assets.
- Most unit tests need no window; Rust-peer tests run only with their documented peer-port variables.

## Public files

- Never commit game files, extracted assets, renders, decompiled code or secrets.
- The public-tree gate needs Python 3.9+ and Git; run it from the repository root:
  `python3 tools/check_public_tree.py` (checks Git's index, including staged new files).
- CI checks committed changes with `python3 tools/check_public_tree.py --range BASE..HEAD`.

## Contributing, license and AI

- Open an issue to discuss a problem or send a pull request with the change.
- Before a pull request, run the applicable CI commands above and the public-tree gate.
- License: MIT OR Apache-2.0, at your option; see `LICENSE-MIT` and `LICENSE-APACHE`.

The code was written with AI coding agents (Anthropic's Claude and OpenAI's GPT models) under the maintainer's direction.
AI-assisted contributions are welcome when their use is disclosed and the changes are reviewed.
