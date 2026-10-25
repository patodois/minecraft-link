# minecraft-link

**NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**

A client-side mod for Minecraft: Java Edition on **Fabric** (and **Quilt**, with the same jar), **NeoForge** and **Forge** that speaks the
**rewrite-bridge passthrough link protocol v1** (`docs/CONTRACT.md` in the `rewrite-bridge`
repository, revision 4), so a running Minecraft can be a **host** or a **guest** next to any other
game or rewrite that speaks it: the Rust tools, the Hytale plugin, anything else.

## Minecraft versions, branches and loaders

One branch per Minecraft version; each branch has its own README with the exact versions, build steps and test results.
This page (`main`) is the Minecraft 26.3 mod.

| Minecraft | Branch | Loaders | Status | Download |
|---|---|---|---|---|
| 26.3 | `main` (this page) | Fabric (and Quilt with the same jar), NeoForge, Forge | built and tested on all three | [releases `mc-26.3-v*`](https://github.com/patodois/minecraft-link/releases?q=mc-26.3-v) |
| 1.20.1 | [`mc/1.20.1`](https://github.com/patodois/minecraft-link/tree/mc/1.20.1) | Fabric, Forge, NeoForge (the 47.1.x fork) | built and tested on all three | [releases `mc-1.20.1-v*`](https://github.com/patodois/minecraft-link/releases?q=mc-1.20.1-v) |
| 1.12.2 | [`mc/1.12.2`](https://github.com/patodois/minecraft-link/tree/mc/1.12.2) | Forge | built and tested | [releases `mc-1.12.2-v*`](https://github.com/patodois/minecraft-link/releases?q=mc-1.12.2-v) |
| 1.7.10 | [`mc/1.7.10`](https://github.com/patodois/minecraft-link/tree/mc/1.7.10) | Forge | built and tested | [releases `mc-1.7.10-v*`](https://github.com/patodois/minecraft-link/releases?q=mc-1.7.10-v) |
| 1.2.5 | coming (its own repository, not published yet) | Forge 1.2.5 (`BaseMod`) | coming | none yet |

**Download.** The jars of each version are attached to a release on the [Releases page](https://github.com/patodois/minecraft-link/releases). A release is named after its
tag, `mc-<Minecraft version>-v<mod version>` (for example `mc-26.3-v0.1.0`), and holds the jars to install for that
Minecraft version plus a `SHA256SUMS` file to check them against. The jars are not signed. They contain no Mojang or loader
code. To build them yourself instead, see "Build and test" below.

```sh
git clone https://github.com/patodois/minecraft-link
cd minecraft-link
git checkout mc/1.12.2     # or mc/1.20.1, mc/1.7.10; stay on main for 26.3
```

- **Host** (you play in Minecraft): sends your camera every rendered frame and the collision shapes
  around you, shows the guest's entities in the world, and draws the guest's rendered frames inside
  the world, hidden correctly behind blocks.
- **Guest** (Minecraft publishes its player): `spawn`s your player and sends its pose every tick, and
  hands the movement keys over when the host holds the input focus.

The protocol implementation is [`link-java`](https://github.com/patodois/link-java) (pure Java, tested against the Rust
reference peers), bundled into the mod jar. Everything binds and connects to `127.0.0.1` only. The Rust reference peers
(`fake_guest`, `fake_host`) and the contract are in [`rewrite-bridge`](https://github.com/patodois/rewrite-bridge).

Tested with Minecraft **26.3**, JDK 25 (see "Which JDK"), Gradle 9.8.2 and, per loader:

| Loader | Version | Build tooling |
|---|---|---|
| Fabric | Loader 0.20.1, Fabric API 0.165.0+26.3 | Fabric Loom 1.19.3 |
| Quilt | Quilt Loader 0.31.0-beta.4 with the Fabric jar and Fabric API (no Quilted Fabric API exists for 26.x) | none: it runs the Fabric jar |
| NeoForge | 26.3.0.95 | ModDevGradle 2.0.148 |
| Forge | 66.1.0 | ForgeGradle 7.0.40, JarJar plugin 0.2.3 |

One version number (`mod_version` in `gradle.properties`) goes into every jar. The jars carry the Minecraft version in
their name: `minecraft-link-<loader>-<minecraft version>-<version>.jar`, for example `minecraft-link-fabric-26.3-0.1.1.jar`.

## What this mod is not

It contains **no Mojang code and no Mojang assets**. It is compiled against Minecraft (Loom downloads
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

## Use

1. Get the jar for your loader (the Download column above, or build it: see "Try it") and start Minecraft with it.
2. Join a world (singleplayer or a server; the mod is client-side only and reads only what your client
   already knows).
3. `/link host` (the other game connects to you) or `/link guest` (you connect to the other game's host).

| Command | Meaning |
|---|---|
| `/link host [port]` | listen on `127.0.0.1:port` (default 47800) for one guest |
| `/link guest [port]` | connect to a host on `127.0.0.1:port`, retrying until one answers |
| `/link stop` | say `bye`, half-close, drain and close (on a helper thread: the game never waits) |
| `/link status` | role, peer, input focus, counters, frame and depth information |
| `/link focus host\|guest` | host: give the input focus; guest: ask the host for it |
| `/link` | same as `status` |

Leaving the world and quitting the game close the link the same way (`despawn` for a guest, then
`bye`, then the half-close of CONTRACT 6.1).

## Try it

Get the jars from the [release `mc-26.3-v0.1.0`](https://github.com/patodois/minecraft-link/releases/tag/mc-26.3-v0.1.0) (the files have the names below, without the folder), or build them
once (`./gradlew build`, see "Build and test"); a build leaves them in `<loader>/build/libs/`.

| Instance runs | Drop this jar into the instance's `mods` folder | Also needed |
|---|---|---|
| Fabric | `fabric/build/libs/minecraft-link-fabric-26.3-0.1.1.jar` | [Fabric API](https://modrinth.com/mod/fabric-api) for 26.3 |
| Quilt | the same Fabric jar | the Fabric API jar (Quilted Fabric API has no 26.x release) |
| NeoForge | `neoforge/build/libs/minecraft-link-neoforge-26.3-0.1.1.jar` | nothing |
| Forge | `forge/build/libs/minecraft-link-forge-26.3-0.1.1.jar` | nothing |

Use exactly these jars, the ones without a suffix. The `-sources` jars are for reading, and the Forge build also leaves a
`-slim` jar without link-java: do not use that one.

In [Prism Launcher](https://prismlauncher.org): create an instance (Add Instance, version 26.3, pick the loader in the
right-hand list), select it, **Edit**, **Mods**, **Download mods** or **Add file**, and add the jar (and Fabric API on
Fabric and Quilt); or right-click the instance, **Folder**, **Minecraft Folder**, and drop the jars into `mods`. Start it with an
account, or offline for a test.

Then, to see the occlusion:

1. Create a creative **Superflat** world (cheats on) and stand still, looking along a flat stretch of ground. (Its ground is at
   y = -60, so the numbers in the commands below are for it. The scripted runs in `autotest/` use another world: `create_world`
   opens Minecraft's own flat test world (a flat desert the game builds for its game tests), whose ground is at y = 4, so the
   scripts and the `offset` example under "config" use 4.)
2. Run `/link host` (it listens on 127.0.0.1:47800).
3. In a terminal, get the Rust reference peers once (they need a [Rust toolchain](https://rustup.rs)):

   ```sh
   cargo install rewrite-bridge --version 0.2.1 --features link --examples   # from crates.io: fake_guest and fake_host (and two more examples) land in ~/.cargo/bin
   ```

   then start the reference guest; it puts a cube 4 m ahead of where you look, on the ground
   you stand on (the collision snapshot says where that is):

   ```sh
   fake_guest --frames --in-front
   ```
4. The cube appears. Put a wall between you and it, slightly to one side of the line of sight, for example with the player at
   `x 0.0` looking north (`/tp @s 0.0 -60 0.0 180 0` in a default Superflat), a stone wall two blocks in front of you:

   ```
   /fill 0 -60 -2 2 -59 -2 minecraft:stone
   ```

   (use your own ground height instead of -60; the wall starts at the line of sight and extends to the right). The part of the
   cube behind the wall disappears; `/fill 0 -60 -2 2 -59 -2 minecraft:air` brings it back.
5. `/link status` shows the counters, `/link stop` ends the session.

For the other direction run `fake_host --port 47831` and `/link guest 47831` in the game: the host
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

`offset` is how the two worlds are lined up: Minecraft's flat test world has its ground at y = 4,
a Rust guest's ground is y = 0, so the run used for the screenshots below has `"offset": [0, -4, 0]`.

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
| the camera (`Camera.setRotation`: `rotationYXZ(PI - yaw, -pitch, 0)`, looks down its local -Z) | `view` is taken from Minecraft's view-rotation matrix and the camera position, not rebuilt from yaw/pitch |

Sign checks done in the tests: yaw 0 gives the identity rotation and faces +Z; yaw 90 faces -X; pitch
+90 faces -Y; a camera at yaw 0 puts a point 5 m to the south at view z = -5, and a point to the east
at negative view x (east is to the left when facing south); `Mapping` agrees with Minecraft's own
camera quaternion for random yaw and pitch.

The guest's pose is the **feet position** (the entity origin), the **view** rotation, the real
displacement over the tick as velocity, the yaw change as angular velocity about +Y, and the TELEPORT flag on the first pose and
when the player moved more than 8 blocks in one tick.

## Host: how it works

**Camera**, every rendered frame, from the camera the game is drawing with:

- `view = R * translate(-position)` from the extracted `CameraRenderState` (Minecraft renders
  camera-relative, so it keeps the rotation and the position apart), taken at the start of the level
  render on the render thread. The view space is in **metres** (CONTRACT section 4) for any `scale` in the config: the view is
  multiplied by the uniform scale, so a point 5 link metres ahead has view z = -5 whether a block is 1 link metre or 2 (the depth
  rows of the projection are rebuilt for metres the same way).
- A guest's picture must match the world while the player walks, but Minecraft draws the level with
  `projection * bob` (view bobbing and damage tilt). The renderer mixin below copies that final matrix and
  `bob = projection^-1 * drawn` is folded into `view`.
- **Depth range.** The contract's table names OpenGL (near -1, far +1) as 0, but Minecraft 26.3 does
  not use that: it builds the projection with near and far **swapped** (reversed depth), and the clip range is 0..1
  with Vulkan or OpenGL with `ARB_clip_control` and **-1..1 on macOS's OpenGL 4.1**, which the contract has no code for.
  `CameraMath.classify` measures which of the four conventions the matrix really has (near and far
  planes), a reversed 0..1 matrix is sent as `depth_range` 2, and the reversed -1..1 one is rewritten into the plain OpenGL matrix with the
  same field of view, aspect, near and far (`depth_range` 0). On the Mac of the test runs below that is what happened (`/link status` says "camera depth_range rewritten to 0").
- `near` = 0.05 (`Camera.PROJECTION_Z_NEAR`, the constant Minecraft passes) and `far` = the extracted
  `depthFar` = `max(4 * render distance * 16, cloud range * 16)` blocks. Minecraft has no setting called
  "far plane"; these are the numbers its own projection is built from. `fov_y` is read back from the matrix
  (`2 * atan(1 / m[5])`), so it includes the sprint and underwater modifiers. The viewport is the main render target's size.
- The sample is handed to the link thread through an `AtomicReference`; the link thread sends it. A newer sample replaces one not yet sent.

**Collision**, a snapshot of the blocks' collision shapes within `collision_radius` (16) of the player as
triangles, counter-clockwise from outside, chunked by the link library (226 triangles per datagram).

- The scan reads the world on the game thread in slices (a one-millisecond budget per tick, one
  horizontal layer at a time); the triangles are built, mapped and hashed on a helper thread.
- A face is left out when a full block sits behind it, and where two boxes of one block touch (the inside
  of a stair), so solid ground costs only its top. A flat desert world gave about 7,000 triangles for r = 16 (its rim included).
- A new scan starts when the player moved a block, or `collision_interval_ms` after the last one; an
  unchanged result is not sent again (except as a refresh every 5 s). Minecraft has no client-side block-change event to hook, so changes are found by rescanning.

**Guest entities** are shown as client-side `block_display` entities (never sent to a server, never
saved): a magenta glazed terracotta whose pattern points along the entity's front, centred on the pose, with the pose's
scale. They are placed at the start of every frame from the pose track at `now - interp_delay_ms`,
interpolated between the timestamped poses (slerp for the rotation) or extrapolated with the pose velocity
(at most 250 ms); the TELEPORT flag is honoured. Despawn, `bye` and a lost peer remove them. Only the heading
is shown, not roll. While a guest's frames are on screen the placeholder is hidden.

**Guest frames (pixel channel, CONTRACT 9.6 and 9.7).**

1. `frame_channel` names the pixel file; the frame worker maps it read-only (`PixelFile.Reader` in
   link-java: all header and depth checks of the contract, the seqlock, an unknown `depth_format` read as colour only).
2. On each `Frame` it copies the slot, looks `camera_seq` up in a ring of the last 256 cameras sent (a second at 240 fps; the contract asks for half a second) and reprojects the
   frame (colour and linear depth in metres) from that camera to the one being presented now (`Reproject`, on a helper thread).
3. At the start of a frame the render thread uploads the result to an RGBA8 and an R32F texture. After the main pass of the
   level it draws one fullscreen pass (`assets/minecraft_link/shaders/core/link_frame.fsh`) that converts Minecraft's depth buffer to linear metres with the contract's
   formulas for its convention and keeps the guest's pixel only where it is nearer than the world, with straight-alpha blending.
   A guest without a depth plane, or with a camera not in the ring, is drawn as an overlay or without reprojection.

## Threads and safety

- Minecraft 26.3 extracts and draws on one thread (the game/render thread). **Nothing on it ever calls
  into the link or waits**: it writes samples into `AtomicReference`s, reads thread-safe holders, and uploads/draws what the frame worker finished.
- Link I/O (`LinkRunner`, 2 ms period), the collision builder and the frame worker are daemon threads.
- Closing (`/link stop`, leaving the world, quitting) runs on a helper thread: despawn, `bye`, half-close, drain 200 ms, close.
  Only at game exit does the shutdown hook wait (at most 2 s) so the bye gets out.
- Only `127.0.0.1` is used, and the peer's datagrams are accepted from its announced address and session only.
- The pixel file path comes from the peer: it must be absolute, a regular file, and pass the contract's size checks before it is mapped (read-only).
- **Input focus**: while the other side holds the focus, `LocalPlayer.input` is replaced by an empty `ClientInput` (no keys pressed) and put back when the
  focus returns or the session ends. Movement keys, jump and sneak are handed over; mouse look, chat and the inventory stay with Minecraft.

## Structure: common plus one thin module per loader

```
common/     all logic that touches no loader API (vanilla classes, Mojang names), the mixins, the shader
fabric/     entry point, events, commands glue; jar-in-jar for link-java.  The same jar runs on Quilt.
neoforge/   entry point, events, commands glue; jarJar for link-java
forge/      entry point, events, commands glue; jarJar for link-java
autotest/   scripts for the scripted client runs
```

The layout follows the MultiLoader-Template (CC0, by Jared; its 26.3 branch is where the `common` project on ModDevGradle's vanilla
mode and the loader modules that compile the common sources into their own jars come from). Architectury Loom 1.17
(July 2026) mentions 26.x in its release notes, but it would have to drive all three loaders through one plugin; the official
toolchain of each loader (Loom, ModDevGradle, ForgeGradle 7) is what each team tests for 26.3, so the project uses those, and
ForgeGradle, which the template does not cover, was added by hand.

Minecraft 26.x ships unobfuscated, so there are no mappings and no remapping: the same source compiles on all three
toolchains against the same names. The `common` module compiles against vanilla only (ModDevGradle with NeoForm 26.3-1); each
loader module adds `common/src/main` to its own source set. What the loader modules contain:

| | Fabric (and Quilt) | NeoForge | Forge |
|---|---|---|---|
| Entry point | `ClientModInitializer` | `@Mod(dist = CLIENT)` | `@Mod`, returns early on a server |
| `/link` commands | `ClientCommandRegistrationCallback` | `RegisterClientCommandsEvent` | `RegisterClientCommandsEvent` |
| Client tick | `END_CLIENT_TICK` | `ClientTickEvent.Post` | `TickEvent.ClientTickEvent.Post` |
| Camera sample and guest frame | `LevelRenderEvents.START_MAIN` and `END_MAIN` | `RenderLevelStageEvent.AfterLevel` (both, in that order) | a frame-graph pass from `AddFramePassEvent`: samples while the graph is built, draws when it executes |
| Game exit | `CLIENT_STOPPING` | `GameShuttingDownEvent` | `GameShuttingDownEvent` |
| Leaving a world | common code notices `level == null` on the tick | same | same |
| Config file | `FabricLoader.getConfigDir()` | `FMLPaths.CONFIGDIR` | `FMLPaths.CONFIGDIR` |
| link-java | jar-in-jar (`include`) | `jarJar` | `jarJar` plugin |
| Mixin config | `fabric.mod.json` | `[[mixins]]` in `neoforge.mods.toml` | `MixinConfigs` in the jar manifest (and `--mixin.config` in the dev run) |

### The mixins (`common/.../mixin`)

All three are in the common module and the same on every loader. `GameRendererMixin` has two read-only injections into
`GameRenderer`, nothing modified: `extract` at its head (place the guest entities for this frame, at frame rate
rather than the 20 Hz tick, and upload the guest texture while no render pass is open), and `renderLevel` just before the projection
matrix is handed to the renderer (copy the matrix that includes view bobbing; Minecraft computes it in a local variable and has no getter for it).
`DisplayAccess` and `BlockDisplayAccess` are invokers for three private setters of the display entities (Fabric API
used to widen them; NeoForge and Forge do not). Everything else uses each loader's own events (table above).

The mixin config says `compatibilityLevel` JAVA_21, not JAVA_25: Forge's Mixin 0.8.7 does not know JAVA_25 and rejects the file.

## Build and test

You need git, a JDK ("Which JDK" below), and `link-java` next to this repository (the build includes it as a Gradle composite build; it is
not downloaded from anywhere else):

```sh
git clone https://github.com/patodois/link-java       # the protocol library, a sibling directory of minecraft-link
git clone https://github.com/patodois/minecraft-link
cd minecraft-link
export JAVA_HOME=/opt/homebrew/opt/openjdk@25         # or any JDK 25; see "Which JDK"
./gradlew build --no-daemon                           # all loaders: <loader>/build/libs/minecraft-link-<loader>-26.3-0.1.1.jar, and the tests
./gradlew :common:test --no-daemon                    # the 49 unit tests only
./gradlew :fabric:runClient --no-daemon               # the real client with an offline dev account (-Xmx2G); also :neoforge: and :forge:
```

`-PlinkJavaDir=<path>` points the build at another `link-java` checkout than `../link-java` (for example a clean one while yours has
uncommitted work); `gradle.properties` keeps the Gradle JVM at 1 GB and the daemon off. The loaders download the game and their libraries
into `~/.gradle` (a few GB for all three); nothing of that is committed. ModDevGradle runs some tools on Java 21; the foojay
toolchain resolver in `settings.gradle` fetches one when the machine has none. Before every `runClient` the build writes master volume 0 into
the run directory's `options.txt` (test clients are silent) and switches the first-start accessibility screen off.

The Fabric jar has to be built with `./gradlew :fabric:build`; use only the jar without a suffix (not `-sources`, and not the Forge `-slim`
jar, which has no link-java inside).
GitHub Actions (`.github/workflows/build.yml`) builds everything on Linux and macOS and runs the tests.

Unit tests (JUnit 5, in `common`): the coordinate mapping, camera matrix conversion (view, bobbing, the four depth conventions, rewriting, linear depth),
collision triangles for a full block, a slab and stairs, **both from hand-written boxes and from Minecraft's own block shapes**
(the registries are bootstrapped; no window needed), pose interpolation, reprojection, the camera ring and the settings file. It also runs the guest role (a `Session`) against a link-java host in-process: `spawn` arrives before the first pose, `despawn` on stop, and the host ignores no pose. With `RUST_FAKE_HOST_PORT=<port>` set it runs the same `Session` as a guest of the Rust `fake_host --port <port>` instead (the evidence is that host's log), and with `RUST_FAKE_GUEST_PORT=<port>` as the host of a `fake_guest --port <port>` you started.

### Which JDK

One rule for every branch of this repository: **run Gradle with a JDK 25.** Minecraft 26.3 needs it (Fabric Loom 1.19.3 refuses to start on an older
JVM: "requires at least JVM runtime version 25", checked with Temurin 21.0.12), and the game itself runs on Java 25. The same JDK 25 runs the builds of
the older branches, so you never need another: `mc/1.12.2` and `mc/1.7.10` (RetroFuturaGradle 2.0.6 is compiled for Java 25 and fails on 21) and `mc/1.20.1`
(which also works on 21). Other JDKs that Gradle asks for (21 for some ModDevGradle tools; 17 and 8 on the older branches) it downloads itself through its toolchain resolver.

Verified here, with Homebrew `openjdk@25` 25.0.5: this branch (build, tests and the dev clients of all three loaders) and the build, unit tests and jar
of `mc/1.20.1`, `mc/1.12.2` and `mc/1.7.10`. Not tried: JDK 26 or newer.

### Scripted client runs

`AutoTest` is a development aid, inactive unless `-Pautotest=<script>` is given. It drives the real client (create the built-in flat test world,
run commands, press keys, take screenshots) from a plain script, so the mod can be tested without a person:

```sh
./gradlew :fabric:runClient --no-daemon -Pautotest=/path/script.txt -Pshots=/path/screenshots   # or :neoforge: / :forge:
```

Script lines: `create_world`, `wait_world`, `wait N` (ticks), `cmd /command`, `link host|guest|stop|status|focus ...`, `key forward down|up`,
`look yaw pitch`, `hud on|off`, `pos`, `shot name`, `leave`, `quit`, `log text`. It uses only the game's own screens and commands.

### Test results (this machine, Minecraft 26.3 on macOS, OpenGL, Apple GPU)

All with the dev client of each loader (`runClient`, offline account, flat world, `-Xmx2G`) driven by `autotest/*.txt` against the Rust reference
peers of `rewrite-bridge` (`fake_guest --frames --in-front`, `fake_host`):

| Loader | Host vs `fake_guest --frames --in-front` | Guest vs `fake_host` |
|---|---|---|
| Fabric 0.20.1 | connected, cube drawn 4 m ahead, **right half hidden by a stone wall**; ~500 frames drawn | host logs the poses, z 0.5 to 9.13; movement ignored until the host gave the focus |
| Quilt Loader 0.31.0-beta.4 (Fabric jar, Fabric API jar) | the same, cube hidden by the wall; ~570 frames drawn | not run |
| NeoForge 26.3.0.95 | the same, ~570 frames drawn | the same poses and focus changes |
| Forge 66.1.0 | the same, ~490 frames drawn | the same poses and focus changes |

Earlier runs of the Fabric-only version (Java peer, leaving the world, quitting the game with a link up) are in `docs/test-logs/`; the
single-loader code paths they covered are unchanged and live in `common`.

## Known limits

- Fabric and Quilt use the same jar; Quilt needs Fabric API itself because no Quilted Fabric API exists for 26.x. The mod's metadata accepts
  Fabric Loader from 0.19.5 (what Quilt Loader 0.31.0-beta.4 provides).
- The NeoForge and Forge jars were tested through their dev clients (link-java on the class path); starting the built jars with link-java
  from the jar-in-jar metadata in a real instance has not been tried yet.
- Only tested on macOS with Minecraft's OpenGL backend. The Vulkan backend and Windows/Linux are not tested;
  the shader assumes the screen quad's `texCoord.y` is 0 at the bottom (how Minecraft's own blit shaders treat it).
- Guest entities are a placeholder block (no roll, no model); the guest's own frames are what shows the real model.
- Reprojection is on the CPU and meant for guest images of a few hundred thousand pixels; cracks can appear on stretched surfaces. A pixel
  file of more than 4 Mpixels (2560 x 1600) is refused with a note in `/link status`, although the contract allows 2^26 pixels.
- Guest frames that stop arriving are cleared after a second and the placeholder block comes back; the textures and buffers are
  released when the link stops.
- Block changes are found by rescanning (every 0.5 s by default), not by an event.
- Mouse and keyboard other than movement stay with Minecraft when the focus is elsewhere.
- No server-side component: it is a client mod; entities it shows exist only in your client.
- Frames are drawn in the main pass; the first-person hand, the GUI and post effects are drawn afterwards, so they cover the guest's frame.

## Glossary

| Term | Meaning here |
|---|---|
| **host**, **guest** | The two roles of the link. The host is the game you are playing; the guest is a second game or rewrite that adds entities, poses and rendered frames to the host's world. Minecraft can be either. |
| **link**, **rewrite-bridge**, **CONTRACT** | The protocol between the two programs (TCP for control, UDP for state, a shared file for pixels) and the repository that defines it. `docs/CONTRACT.md` there is the whole specification. |
| **link-java** | The Java implementation of that protocol, used by this mod. |
| **pose** | An entity's position, rotation, scale and velocity at a moment, sent every tick. |
| **link metre** | The unit of the link's shared space. `scale` in the config says how many link metres one block is (default 1). |
| **`depth_range`** | A field of the camera message that says how the projection stores depth (0 OpenGL, 1 zero-to-one, 2 reversed). Minecraft 26.3 uses reversed depth; see "Host: how it works". |
| **Fabric, Quilt, Forge, NeoForge** | The mod loaders: programs that start Minecraft with mods. Quilt runs Fabric mods. NeoForge began as a fork of Forge. |
| **Loom, ModDevGradle, ForgeGradle** | The Gradle plugins of Fabric, NeoForge and Forge. They download Minecraft, make it compilable and give a `runClient` task. |
| **Gradle, toolchain** | The build tool, and its way of finding (or downloading) the JDK a project asks for, independent of the JDK Gradle itself runs on. |
| **composite build** | A Gradle build that includes another project's source (here `link-java`) instead of a published artifact. |
| **jar-in-jar (`jarJar`)** | A mod jar that carries another jar inside it; the loader unpacks it at start. This is how `link-java` ships inside every loader's jar. |
| **Mixin** | A library that lets a mod change a method of Minecraft's classes at load time. This mod uses two small, read-only ones on 26.3. |
| **`26.3`** | Mojang's version number since 2026: the year (26) and the number of the release in that year (3). It is the version after 1.21.x; Minecraft 26.x ships unobfuscated, with Mojang's own names. |
| **dev client, `runClient`** | The Minecraft client started from the build with the mod on the class path, with an offline account, for testing. |
| **superflat** | A world type with flat ground. Ground height depends on the version (y = -60 in a current Superflat, 4 in the game's own flat test world). |

## License

MIT OR Apache-2.0, at your option: see `LICENSE-MIT` and `LICENSE-APACHE`.
