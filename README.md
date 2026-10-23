# minecraft-link

**NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**

A client-side mod for Minecraft: Java Edition **1.20.1** on **Fabric**, **Forge** and **NeoForge** (the 1.20.1 fork, 47.1.x) that speaks the
**rewrite-bridge passthrough link protocol v1** (`docs/CONTRACT.md` in the `rewrite-bridge`
repository, revision 4), so a running Minecraft can be a **host** or a **guest** next to any other
game or rewrite that speaks it: the Rust tools, the Hytale plugin, anything else.

This is the Minecraft 1.20.1 branch (`mc/1.20.1`) of the multi-loader layout of the 26.3 mod: the same `common` / `fabric` / `forge` / `neoforge`
modules, retargeted to 1.20.1 (OpenGL 3.2 core, Mojang's own `RenderSystem` and shaders, Java 17).

- **Host** (you play in Minecraft): sends your camera every rendered frame and the collision shapes
  around you, shows the guest's entities in the world, and draws the guest's rendered frames inside
  the world, hidden correctly behind blocks.
- **Guest** (Minecraft publishes its player): `spawn`s your player and sends its pose every tick, and
  hands the movement keys over when the host holds the input focus.

The protocol implementation is [`link-java`](https://github.com/patodois/link-java) (pure Java, Java 8 bytecode, tested against the Rust
reference peers, which are in [`rewrite-bridge`](https://github.com/patodois/rewrite-bridge)), bundled into the mod jar. Everything binds and connects to `127.0.0.1` only (link-java does that itself, whatever
`java.net.preferIPv6Addresses` says).

Tested with Minecraft **1.20.1**, Gradle 8.14.3 (on JDK 25 or 21, see "Which JDK"), Java 17 for the game and, per loader:

| Loader | Version | Build tooling |
|---|---|---|
| Fabric | Loader 0.20.1, Fabric API 0.92.12+1.20.1 | Fabric Loom 1.13.6 |
| Forge | 47.4.28 (the latest and recommended 1.20.1 build) | ForgeGradle 6.0.54 |
| NeoForge | 47.1.106 (1.20.1) | ModDevGradle legacy 2.0.148 |

One version number (`mod_version` in `gradle.properties`) goes into every jar. The jars carry the Minecraft version in their name:
`minecraft-link-<loader>-<minecraft version>-<version>.jar`, for example `minecraft-link-forge-1.20.1-0.1.0.jar`. Quilt was not tried on 1.20.1.

## What this mod is not

It contains **no Mojang code and no Mojang assets**. It is compiled against Minecraft (the build tools, Loom, ForgeGradle and ModDevGradle, download
the game files from Mojang's servers into your own Gradle cache at build time; nothing of Mojang's
is in this repository or in the jar) and does not redistribute it. It is not affiliated with Mojang
or Microsoft.

What Mojang's rules say about mods (read at minecraft.net on 2026-10-23: the
[End User License Agreement](https://www.minecraft.net/en-us/eula) and the
[Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines)):

- A **mod** is "something original" that does not contain a substantial part of Mojang's copyrightable
  code or content. You own what you create, and may use and share it **as long as you do not sell it
  or try to make money from it**, and **do not distribute a modded version of the game** (a hacked
  client or server): distribute the mod only, never Minecraft with the mod inside.
- The Usage Guidelines add: no play-to-earn function and nothing that verifies out-of-game ownership
  (for example NFTs) to unlock in-game content; a product that mentions Minecraft must say that it is
  not official ("NOT AN OFFICIAL MINECRAFT [PRODUCT]. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR
  MICROSOFT") and must not use the Minecraft name as its primary or dominant name, which is why the
  mod's display name is "Rewrite Link for Minecraft" (the repository and the mod id keep the working
  name `minecraft-link`, which you may want to change before publishing anywhere).
- Mojang may take down mods that break the guidelines and may change them at any time; they say they do
  not take responsibility for updates that break mods.

This mod only connects to other programs on your own machine and sends nothing to any server. The
only guest content it draws is what the guest's own program renders. It does not read or change another
process's memory.

## Other Minecraft versions

This branch is Minecraft 1.20.1 only. The mod for 26.3 (`main`), 1.12.2 (`mc/1.12.2`) and 1.7.10 (`mc/1.7.10`) have their own branches, and 1.2.5 is coming:
see the table in the [main README](https://github.com/patodois/minecraft-link#minecraft-versions-branches-and-loaders).

## Use

1. Build or install the jar for your loader (below, and "Try it") and start Minecraft 1.20.1 with it.
2. Join a world (singleplayer or a server; the mod is client-side only and reads only what your client
   already knows).
3. `/link host` (the other game connects to you) or `/link guest` (you connect to the other game's host).

| Command | Meaning |
|---|---|
| `/link host [port]` | listen on `127.0.0.1:port` (default 47800) for one guest |
| `/link guest [port]` | connect to a host on `127.0.0.1:port`, retrying until one answers |
| `/link stop` | say `bye`, half-close, drain and close (on a helper thread: the game never waits) |
| `/link status` | role, peer, input focus, counters, frame and depth information, the GL version |
| `/link focus host\|guest` | host: give the input focus; guest: ask the host for it |
| `/link` | same as `status` |

Leaving the world and quitting the game close the link the same way (`despawn` for a guest, then
`bye`, then the half-close of CONTRACT 6.1).

## Try it

Build the jars once (`./gradlew build`, see "Build and test"); they are in `<loader>/build/libs/`.

| Instance runs | Drop this jar into the instance's `mods` folder | Also needed |
|---|---|---|
| Fabric | `fabric/build/libs/minecraft-link-fabric-1.20.1-0.1.0.jar` | [Fabric API](https://modrinth.com/mod/fabric-api) for 1.20.1 (0.92.x) |
| Forge | `forge/build/libs/minecraft-link-forge-1.20.1-0.1.0.jar` | nothing |
| NeoForge | `neoforge/build/libs/minecraft-link-neoforge-1.20.1-0.1.0.jar` | nothing |

Use exactly these jars, the ones without a suffix: not `-dev`, not `-sources`, and not the `-slim` jar that the Forge build also leaves (it has no link-java inside).

**Prism Launcher** ([prismlauncher.org](https://prismlauncher.org)), Forge:

1. **Add Instance**, pick **Minecraft 1.20.1**, then **Forge** in the loader list on the right and the 47.4.28 build (or any 47.x), **OK**.
2. Select the instance, **Edit**, **Mods**, **Add file** (or right-click the instance, **Folder**, **Minecraft Folder**, and drop the jar into `mods`),
   and add `minecraft-link-forge-1.20.1-0.1.0.jar`.
3. Start it with an account, or offline for a test. Prism picks Java 17 for 1.20.1 by itself.

**Prism Launcher**, Fabric:

1. **Add Instance**, **Minecraft 1.20.1**, loader **Fabric** (Loader 0.14.21 or newer), **OK**.
2. **Edit**, **Mods**, **Download mods** (Modrinth) and search for **Fabric API**, pick the 0.92.x build for 1.20.1, or add the jar by hand; then
   **Add file** and add `minecraft-link-fabric-1.20.1-0.1.0.jar`.
3. Start it.

NeoForge 1.20.1 (47.1.x) works the same way with `minecraft-link-neoforge-1.20.1-0.1.0.jar`, if your Prism Launcher offers NeoForge for 1.20.1 (it was tested with
NeoForge's own installer, not through Prism).

Then, to see the occlusion:

1. Create a creative **Superflat** world (cheats on) and stand still, looking along a flat stretch of ground (its ground is at y = -60).
2. Run `/link host` (it listens on 127.0.0.1:47800).
3. In a terminal, get and build the Rust reference peers once (they need a [Rust toolchain](https://rustup.rs)):

   ```sh
   git clone https://github.com/patodois/rewrite-bridge && cd rewrite-bridge && cargo build --release --features link --examples
   ```

   then start the reference guest from that directory; it puts a cube 4 m ahead of where you look, on the ground
   you stand on (the collision snapshot says where that is):

   ```sh
   target/release/examples/fake_guest --frames --in-front
   ```
4. The cube appears. Put a wall between you and it, slightly to one side of the line of sight, for example with the player at
   `x 0.0` looking north (`/tp @s 0.0 -60 7.5 180 0` in a default Superflat), a stone wall two blocks in front of you:

   ```
   /fill 0 -60 5 2 -59 5 minecraft:stone
   ```

   (use your own ground height instead of -60; the wall starts at the line of sight and extends to the right). The part of the
   cube behind the wall disappears; `/fill 0 -60 5 2 -59 5 minecraft:air` brings it back.
5. `/link status` shows the counters, `/link stop` ends the session.

For the other direction run `target/release/examples/fake_host --port 47831` (same directory) and `/link guest 47831` in the game: the host
prints the player's poses.

`autotest/host_wall.txt` and `autotest/guest.txt` are the scripted versions of both (see "Scripted client runs").

## `config/minecraft-link.json`

Written with the defaults on first start. Unknown keys are ignored.

```json
{
  "role": "host",              // default role (the commands choose a role themselves)
  "port": 47800,               // control port: the host listens on it, the guest connects to it
  "udp_port": 0,               // 0 = any free port
  "flip_x": false,             // mirror the world along X / Z when the other game's axes differ
  "flip_z": false,
  "scale": 1.0,                // link metres per block
  "offset": [0, 0, 0],         // link position of the Minecraft origin (after flip and scale)
  "interp_delay_ms": 40,       // host: guest entities are shown this far in the past, to interpolate
  "collision_radius": 16,      // host: blocks around the player in the collision snapshot
  "collision_interval_ms": 500,// host: least time between rescans (how block changes are noticed)
  "collision_move_blocks": 1.0,// host: rescan at once after the player moved this far
  "guest_entity": 1,           // guest: entity id, and what `spawn` announces
  "guest_provider": "Minecraft",
  "guest_asset": "minecraft:player",
  "heartbeat_ms": 250,
  "timeout_ms": 2000,
  "show_frames": true          // host: draw the guest's pixel-file frames
}
```

`offset` is how the two worlds are lined up: the feet of a player standing on a 1.20.1 Superflat world are at y = -60,
a Rust guest's ground is y = 0, so the runs used for the screenshots below have `"offset": [0, 60, 0]`.

## Coordinates (CONTRACT section 4)

Minecraft's world is already right-handed and +Y up with 1 block = 1 m: +X east, +Y up, +Z south, and
(east, up, south) is a right-handed triple, so with the default settings **a position maps to
itself** (`flip_*`, `scale` and `offset` default to the identity). What differs is how Minecraft names
directions, and that is what `Mapping` converts (and unit-tests):

| Minecraft | Link (contract) |
|---|---|
| `yaw` in degrees, **0 faces +Z (south)**, grows clockwise seen from above: 90 = west (-X), 180 = north (-Z), -90 = east (+X) | rotation of the entity: `Ry(-yaw) * Rx(pitch)`; the front (+Z) is the view direction |
| `pitch` in degrees, 0 level, **+90 looks down**, -90 up | same quaternion; the up axis tips with it |
| view direction `(-sin(yaw)cos(pitch), -sin(pitch), cos(yaw)cos(pitch))` | the entity's local +Z rotated by that quaternion |
| velocity in blocks per tick | metres per second: times 20 |
| the camera (`GameRenderer.renderLevel` puts `Rx(pitch) * Ry(yaw + 180)` on the level renderer's pose stack and subtracts the camera position separately; it looks down -Z) | `view = Rx(pitch) * Ry(yaw + 180) * translate(-position)`, built from `Camera.getXRot`, `getYRot` and `getPosition` (`CameraMath.viewRotation`) |

Sign checks done in the tests: yaw 0 gives the identity rotation and faces +Z; yaw 90 faces -X; pitch
+90 faces -Y; a camera at yaw 0 puts a point 5 m to the south at view z = -5, and a point to the east
at negative view x (east is to the left when facing south); `Mapping` agrees with Minecraft's own
camera quaternion for random yaw and pitch, and the view rotation agrees with a real `PoseStack` filled the way `GameRenderer` does.

The guest's pose is the **feet position** (the entity origin), the **view** rotation, the real
displacement over the tick as velocity, the yaw change as angular velocity about +Y, and the TELEPORT flag on the first pose and
when the player moved more than 8 blocks in one tick.

## Host: how it works

**Camera**, every rendered frame, from the camera the game has just drawn with (the end of the level render: Forge's `RenderLevelStageEvent`
AFTER_LEVEL, Fabric's `WorldRenderEvents.LAST`):

- `view = Rx(pitch) * Ry(yaw + 180) * translate(-position)` from `Camera.getXRot`, `getYRot` and `getPosition`: Minecraft renders
  camera-relative, so it keeps the rotation (on the level renderer's pose stack) and the position apart. The rotation is built from
  the angles rather than read from the event's pose stack because Forge's AFTER_LEVEL event does not carry that stack (its top matrix
  is the projection there); a test checks the formula against a real `PoseStack`.
- A guest's picture must match the world while the player walks, but Minecraft draws the level with
  `projection * bob` (view bobbing and damage tilt), one matrix, handed to the level renderer and to the events as the projection
  matrix. The clean projection is not kept anywhere (and its field of view comes from a private method), but it can be recovered
  exactly: the last row of a perspective matrix is `(0, 0, -1, 0)`, so `CameraMath.splitProjectionBob` reads `projection` and `bob` out of the
  product, and `bob` is folded into `view`.
- **Depth range.** 1.20.1 uses the contract's own convention: a standard OpenGL perspective (`Matrix4f.perspective(fov, aspect, 0.05, far)`,
  near at -1, far at +1, **not** reversed; the window depth is 0..1 as always), so `depth_range` 0 goes out as it is and nothing is rewritten
  (`/link status` says "camera depth_range 0"). `CameraMath.classify` still measures which convention the matrix really has, so another
  renderer would be described correctly (and the one combination the contract has no code for, reversed with -1..1, is rewritten as in the 26.3 mod).
- `near` = 0.05 (`GameRenderer.PROJECTION_Z_NEAR`) and `far` = `GameRenderer.getDepthFar()` = `4 * render distance * 16` blocks. `fov_y` is read
  back from the matrix (`2 * atan(1 / m[5])`), so it includes the sprint and underwater modifiers. The viewport is the main render target's size.
- The sample is handed to the link thread through an `AtomicReference`; the link thread sends it. A newer sample replaces one not yet sent.

**Collision**, a snapshot of the blocks' collision shapes (`BlockState.getCollisionShape`, a `VoxelShape`, as boxes) within `collision_radius` (16) of the
player as triangles, counter-clockwise from outside, chunked by the link library (226 triangles per datagram).

- The scan reads the world on the game thread in slices (a one-millisecond budget per tick, one
  horizontal layer at a time); the triangles are built, mapped and hashed on a helper thread.
- A face is left out when a full block sits behind it, and where two boxes of one block touch (the inside
  of a stair), so solid ground costs only its top. A Superflat world gives about 4,600 triangles for r = 16.
- A new scan starts when the player moved a block, or `collision_interval_ms` after the last one; an
  unchanged result is not sent again (except as a refresh every 5 s). Minecraft has no client-side block-change event to hook, so changes are found by rescanning.

**Guest entities** are shown as client-side `block_display` entities (never sent to a server, never
saved): a magenta glazed terracotta whose pattern points along the entity's front, centred on the pose, with the pose's
scale. Display entities exist in 1.20.1, but their block state, transformation and view range have private setters; the mod sets them the
way the `/summon` command does, through the entity's NBT (`Entity.load`), so it needs no mixin and no access transformer. They are placed at the start of
every frame's level render (Forge AFTER_SKY, Fabric `WorldRenderEvents.START`, before the entities are drawn) from the pose track at
`now - interp_delay_ms`, interpolated between the timestamped poses (slerp for the rotation) or extrapolated with the pose velocity
(at most 250 ms); the TELEPORT flag is honoured. Despawn, `bye` and a lost peer remove them. Only the heading
is shown, not roll. While a guest's frames are on screen the placeholder is hidden (`"show_frames": false` keeps it).

**Guest frames (pixel channel, CONTRACT 9.6 and 9.7).**

1. `frame_channel` names the pixel file; the frame worker maps it read-only (`PixelFile.Reader` in
   link-java: all header and depth checks of the contract, the seqlock, an unknown `depth_format` read as colour only).
2. On each `Frame` it copies the slot, looks `camera_seq` up in a ring of the last 256 cameras sent (a second at 240 fps; the contract asks for half a second) and reprojects the
   frame (colour and linear depth in metres) from that camera to the one being presented now (`Reproject`, on a helper thread).
3. At the end of the level render the render thread uploads the result to an RGBA8 and an R32F texture and draws one fullscreen
   triangle into the main render target with a GLSL 150 program of its own (`assets/minecraft_link/shaders/core/link_frame.vsh` and `.fsh`).
   The fragment shader samples **the main target's depth texture directly** (1.20.1 keeps its depth in a texture, so there is no copy), converts it to
   linear metres with the contract's formula for OpenGL depth, and keeps the guest's pixel only where it is nearer than the world,
   with straight-alpha blending. Depth test and depth writes are off for the pass, so reading the depth texture of the framebuffer being drawn
   into is not a feedback loop. A guest without a depth plane, or with a camera not in the ring, is drawn as an overlay or without reprojection.
4. Minecraft 1.20.1 caches part of the GL state in `GlStateManager`. The pass reads the real state first (framebuffer, viewport, program, vertex
   array, texture units 0 to 2, blend, depth, cull, scissor, colour mask, unpack alignment) and puts it back through `GlStateManager` afterwards, so the
   game's cache and the driver still agree; the hand, the HUD and the chat are drawn afterwards and look as without the mod.

## Threads and safety

- Minecraft 1.20.1 ticks, builds and draws on one thread (the game/render thread). **Nothing on it ever calls
  into the link or waits**: it writes samples into `AtomicReference`s, reads thread-safe holders, and uploads/draws what the frame worker finished.
- Link I/O (`LinkRunner`, 2 ms period), the collision builder and the frame worker are daemon threads.
- Closing (`/link stop`, leaving the world, quitting) runs on a helper thread: despawn, `bye`, half-close, drain 200 ms, close.
  Only at game exit does the shutdown hook wait (at most 2 s) so the bye gets out.
- Only `127.0.0.1` is used, and the peer's datagrams are accepted from its announced address and session only.
- The pixel file path comes from the peer: it must be absolute, a regular file, and pass the contract's size checks before it is mapped (read-only).
- **Input focus**: while the other side holds the focus, `LocalPlayer.input` is replaced by an empty `Input` (no keys pressed) and put back when the
  focus returns or the session ends. Movement keys, jump and sneak are handed over; mouse look, chat and the inventory stay with Minecraft.

## Structure: common plus one thin module per loader

```
common/     all logic that touches no loader API (vanilla classes, Mojang names), the shaders
fabric/     entry point, events, commands glue; jar-in-jar for link-java
forge/      entry point, events, commands glue; jarJar for link-java
neoforge/   entry point, events, commands glue; jarJar for link-java
autotest/   scripts for the scripted client runs
```

The layout follows the MultiLoader-Template (CC0, by Jared; its 1.20.x branches have the `common` project plus loader modules that compile the
common sources into their own jars), with the toolchain of each loader's own team instead of VanillaGradle and Mixin's annotation processor:
Loom for Fabric, ForgeGradle 6 for Forge, ModDevGradle's legacy plugin for NeoForge 1.20.1 and for `common`.

1.20.1 is obfuscated at run time (Fabric runs on Intermediary names, Forge and NeoForge on SRG names), but every source file is written in Mojang's official
names and compiled against them. Each toolchain remaps the finished jar: Loom's `remapJar`, ForgeGradle's `reobfJar` and `reobfJarJar`, ModDevGradle's `reobfJar`. The `common` module compiles
against vanilla only (ModDevGradle legacy in vanilla mode, no decompiling); each loader module adds `common/src/main` to its own source set.
What the loader modules contain:

| | Fabric | Forge | NeoForge 1.20.1 |
|---|---|---|---|
| Entry point | `ClientModInitializer` | `@Mod`, returns early on a server | `@Mod`, returns early on a server |
| `/link` commands | `ClientCommandRegistrationCallback` | `RegisterClientCommandsEvent` | `RegisterClientCommandsEvent` |
| Client tick | `END_CLIENT_TICK` | `TickEvent.ClientTickEvent` (END) | `TickEvent.ClientTickEvent` (END) |
| Guest placement, before entities | `WorldRenderEvents.START` | `RenderLevelStageEvent` AFTER_SKY | `RenderLevelStageEvent` AFTER_SKY |
| Camera sample and guest frame | `WorldRenderEvents.LAST` | `RenderLevelStageEvent` AFTER_LEVEL | `RenderLevelStageEvent` AFTER_LEVEL |
| Game exit | `CLIENT_STOPPING` | `GameShuttingDownEvent` | `GameShuttingDownEvent` |
| Leaving a world | common code notices `level == null` on the tick | same | same |
| Config file | `FabricLoader.getConfigDir()` | `FMLPaths.CONFIGDIR` | `FMLPaths.CONFIGDIR` |
| link-java | jar-in-jar (`include`) | `jarJar` (ForgeGradle 6) | `jarJar` (ModDevGradle) |
| Metadata | `fabric.mod.json` | `META-INF/mods.toml` | `META-INF/mods.toml` |

NeoForge 1.20.1 (47.1.x) is a fork of Forge 47 that still uses the `net.minecraftforge` packages and `mods.toml`, so its entry point is the Forge one, built against NeoForge.

**No mixins.** The 26.3 mod needed two (a head hook to place the guest entities at frame rate, and one to copy the projection with the bobbing) plus
invokers for private display-entity setters. On 1.20.1 the level-stage events give the camera and the final projection matrix, the placement runs from
an event before the entities are drawn, and the display entities are set up through NBT, so there are none, which also avoids the refmap and
mapping-remapping setup that Mixin needs on an obfuscated runtime.

## Build and test

You need git and a JDK ("Which JDK" below), and `link-java` next to this repository (the build includes it as a Gradle composite build;
it is not downloaded from anywhere else):

```sh
git clone https://github.com/patodois/link-java            # the protocol library, a sibling directory of minecraft-link
git clone -b mc/1.20.1 https://github.com/patodois/minecraft-link
cd minecraft-link
export JAVA_HOME=/opt/homebrew/opt/openjdk@25              # or any JDK 25; see "Which JDK"
./gradlew build --no-daemon                                # all loaders: <loader>/build/libs/minecraft-link-<loader>-1.20.1-0.1.0.jar, and the tests
./gradlew :common:test --no-daemon                         # the 51 unit tests only
./gradlew :fabric:runClient --no-daemon                    # the real client with an offline dev account (-Xmx2G, Java 17); also :forge: and :neoforge:
```

### Which JDK

One rule for every branch of this repository: **run Gradle with a JDK 25.** The 26.3 branch and the 1.12.2 and 1.7.10 branches refuse anything older
(Fabric Loom 1.19 and RetroFuturaGradle 2.0.6 are compiled for Java 25), so JDK 25 is the one that works everywhere. On this branch a JDK 21 works too
(Loom 1.13 wants 21 or newer; Gradle 8.14.3 officially lists JDK 24 as its newest and 25 worked all the same). The game and the mod are Java 17, but you do
not install that: Gradle's toolchain resolver (`settings.gradle`) downloads a JDK 17 when the machine has none.

Verified here: the whole build, the unit tests and the jars with Homebrew `openjdk@25` 25.0.5 and with Temurin 21.0.12. The dev-client and real-profile runs
recorded below were made with Gradle on JDK 21. Not tried: JDK 17 and JDK 26 or newer.

Gradle 8.14.3, not the 9.x of the 26.3 mod: ForgeGradle 6 is built for Gradle 8, and Loom 1.13.6 is the newest Loom that still runs on it (Loom 1.14 and
later need Gradle 9).

`-PlinkJavaDir=<path>` points the build at another `link-java` checkout than `../link-java`; `gradle.properties` keeps the Gradle JVM at 1 GB and the daemon off. The loaders download the game and their libraries
into `~/.gradle` (about 2.5 GB for the three of them); nothing of that is committed. Before every `runClient` the build writes master volume 0 into
the run directory's `options.txt` (test clients are silent) and switches off the first-start accessibility screen, the pause menu that
appears when the window is not focused, and the tutorial hints.

The Fabric jar has to be built with `./gradlew :fabric:build` (`remapJar` makes the Intermediary-named release jar; do not use the `-dev` jar).
Forge and NeoForge jars are reobfuscated to SRG names by `reobfJarJar` and
`reobfJar`. GitHub Actions (`.github/workflows/build.yml`) builds everything on Linux and macOS and runs the tests (not run from this branch yet).

Unit tests (JUnit 5, in `common`): the coordinate mapping, camera matrix conversion (the view rotation against a real `PoseStack`, bobbing and
`splitProjectionBob`, the four depth conventions, rewriting, linear depth), collision triangles for a full block, a slab and stairs, **both from hand-written
boxes and from Minecraft's own block shapes** (the registries are bootstrapped; no window needed), pose interpolation, reprojection, the camera ring and
the settings file.

### Scripted client runs

`AutoTest` is a development aid, inactive unless `-Pautotest=<script>` is given. It drives the real client (create a Superflat world, run commands,
press keys, take screenshots) from a plain script, so the mod can be tested without a person:

```sh
./gradlew :fabric:runClient --no-daemon -Pautotest=/path/script.txt -Pshots=/path/screenshots   # or :forge: / :neoforge:
```

Script lines: `create_world`, `wait_world`, `wait N` (ticks), `cmd /command`, `link host|guest|stop|status|focus ...`, `key forward down|up`,
`look yaw pitch`, `hud on|off`, `pos`, `shot name`, `leave`, `quit`, `log text`. It uses only the game's own world-creation call, commands and screenshot function
(`Screenshot.takeScreenshot`, a `glReadPixels` of the main render target).

### Test results (this machine, Minecraft 1.20.1 on macOS, Apple M5, 1708 x 960 framebuffer)

GL as reported by the game: `GL_VERSION 4.1 Metal - 90.5`, `GLSL 4.10`, renderer `Apple M5` (Minecraft asks for a 3.2 core context and macOS gives 4.1; the mod's shader is `#version 150`).
All with the dev client of each loader (`runClient`, offline account, Superflat, `-Xmx2G`, Java 17) driven by `autotest/*.txt` against the Rust reference
peers of `rewrite-bridge` (`fake_guest --frames --in-front`, `fake_host`), with `"offset": [0, 60, 0]` in the config:

| Loader | Host vs `fake_guest --frames --in-front` | Guest vs `fake_host` | Peak RSS (host / guest) |
|---|---|---|---|
| Fabric 0.20.1 + API 0.92.12 | connected, cube drawn 4 m ahead on the ground, **right half hidden by a stone wall**, whole again when the wall is removed, **not hidden by a wall behind it**; HUD, chat and hand intact; ~525 frames drawn in the 10 s window | host logs the poses, z 0.5 to 9.13; movement ignored until the host gave the focus | 1864 / 1214 MB |
| Forge 47.4.28 | the same, ~1030 frames drawn | the same poses and focus changes (x drifted by 0.35 because the dev window had the real mouse) | 2157 / 1656 MB |
| NeoForge 47.1.106 | the same, ~990 frames drawn | the same poses and focus changes | 2137 / 1844 MB |

With `"show_frames": false` the placeholder block display (magenta glazed terracotta, turning with the guest's pose) is drawn instead, and the wall hides its right half too
(all three loaders, no frames drawn).

**The built jars** were also started in real loader profiles made by each loader's own installer (Forge 47.4.28 and NeoForge 47.1.106 installers, Fabric installer 1.1.2 with
Fabric API 0.92.12; a plain launch script with the game's arguments, outside Prism): Forge's SRG-named `reobfJarJar` jar, NeoForge's reobfuscated jar and Fabric's Intermediary-named
`remapJar` jar, each with link-java taken out of the jar's own `META-INF/jarjar` (Fabric: `META-INF/jars`). Same script, same result (wall hides the right half, wall behind the cube does not, HUD intact), 520 to 980 frames drawn, peak RSS
2.1 GB (Fabric 2096, Forge 2082, NeoForge 2067 MB). Forge's and NeoForge's real profiles start the game with `-Djava.net.preferIPv6Addresses=system`; the link connected to the
peer on 127.0.0.1 all the same, because link-java binds there itself.

The logs of these runs are in `docs/test-logs/1.20.1/`; the screenshots (taken with the game's own screenshot function) are kept outside the repository.
Earlier runs of the 26.3 mod (Java peer, leaving the world, quitting the game with a link up) are in `docs/test-logs/`; the single-loader code paths they covered are unchanged and live in `common`.

## Known limits

- Tested on macOS (Apple silicon) with Minecraft's OpenGL backend only; Windows and Linux are not tested. Quilt is not tried on 1.20.1.
- The built jars were run in profiles made by the loaders' installers, not through Prism Launcher (the Prism steps above are the usual way to add a mod there).
- Not tried with Fabulous graphics, a shader pack (Iris, OptiFine) or a renderer mod (Sodium, Embeddium): the guest's frame is drawn into the main render target at the end of
  the level render, so what a mod does to that target before the hand is drawn decides how it looks.
- The camera is built from Minecraft's yaw, pitch and position and the projection it drew with. A camera roll added by another mod (Forge's `ViewportEvent.ComputeCameraAngles`) is not in it.
- Guest entities are a placeholder block (no roll, no model); the guest's own frames are what shows the real model.
- Reprojection is on the CPU and meant for guest images of a few hundred thousand pixels; cracks can appear on stretched surfaces. A pixel
  file of more than 4 Mpixels (2560 x 1600) is refused with a note in `/link status`, although the contract allows 2^26 pixels.
- Guest frames that stop arriving are cleared after a second and the placeholder block comes back; the textures are released when the link stops.
- Block changes are found by rescanning (every 0.5 s by default), not by an event.
- Mouse and keyboard other than movement stay with Minecraft when the focus is elsewhere.
- No server-side component: it is a client mod; entities it shows exist only in your client.
- Frames are drawn at the end of the level render; the first-person hand, the GUI and the HUD are drawn afterwards, so they cover the guest's frame.
- `/link host` no longer warns about an IPv6 loopback address: link-java binds and connects to 127.0.0.1 explicitly, whatever `java.net.preferIPv6Addresses` says.

## Glossary

| Term | Meaning here |
|---|---|
| **host**, **guest** | The two roles of the link. The host is the game you are playing; the guest is a second game or rewrite that adds entities, poses and rendered frames to the host's world. Minecraft can be either. |
| **link**, **rewrite-bridge**, **CONTRACT** | The protocol between the two programs (TCP for control, UDP for state, a shared file for pixels) and the repository that defines it. `docs/CONTRACT.md` there is the whole specification. |
| **link-java** | The Java implementation of that protocol, used by this mod. |
| **pose** | An entity's position, rotation, scale and velocity at a moment, sent every tick. |
| **link metre** | The unit of the link's shared space. `scale` in the config says how many link metres one block is (default 1). |
| **`depth_range`** | A field of the camera message that says how the projection stores depth (0 OpenGL, 1 zero-to-one, 2 reversed). |
| **Fabric, Forge, NeoForge** | The mod loaders: programs that start Minecraft with mods. NeoForge 47.1.x is the fork of Forge 47 that still runs 1.20.1. |
| **Loom, ModDevGradle, ForgeGradle** | The Gradle plugins of Fabric, NeoForge and Forge. They download Minecraft, make it compilable and give a `runClient` task. |
| **Gradle, toolchain** | The build tool, and its way of finding (or downloading) the JDK a project asks for, independent of the JDK Gradle itself runs on. |
| **composite build** | A Gradle build that includes another project's source (here `link-java`) instead of a published artifact. |
| **jar-in-jar (`jarJar`)** | A mod jar that carries another jar inside it; the loader unpacks it at start. This is how `link-java` ships inside every loader's jar. |
| **Mixin** | A library that lets a mod change a method of Minecraft's classes at load time. This branch needs none. |
| **Intermediary, SRG, `reobf`** | The obfuscated names Fabric and Forge use when 1.20.1 runs. The source uses Mojang's names; the build renames the finished jar. |
| **dev client, `runClient`** | The Minecraft client started from the build with the mod on the class path, with an offline account, for testing. |
| **Superflat** | A world type with flat ground. In 1.20.1 the ground is at y = -60 (a Rust guest's ground is y = 0, hence `"offset": [0, 60, 0]` in the config). |
| **`26.3`** | Mojang's version number since 2026: the year (26) and the number of the release in that year (3). The `main` branch is the mod for it. |

## License

MIT OR Apache-2.0, at your option: see `LICENSE-MIT` and `LICENSE-APACHE`.
