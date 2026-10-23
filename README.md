# minecraft-link (Minecraft 1.12.2, Forge)

**NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**

A client-side [Forge](https://files.minecraftforge.net) mod for Minecraft: Java Edition **1.12.2** (the version
RLCraft and most big modpacks of that era run on) that speaks the **rewrite-bridge passthrough link protocol v1**
(`docs/CONTRACT.md` in the [`rewrite-bridge`](https://github.com/patodois/rewrite-bridge) repository, revision 4), so a running Minecraft can be a **host** or a
**guest** next to any other game or rewrite that speaks it: the Rust tools, the Hytale plugin, anything else.

- **Host** (you play in Minecraft): sends your camera every rendered frame and the collision shapes around you,
  shows the guest's entity in the world, and draws the guest's rendered frames inside the world, hidden correctly
  behind blocks.
- **Guest** (Minecraft publishes its player): `spawn`s your player and sends its pose every tick, and hands the
  movement keys over when the host holds the input focus.

This is the 1.12.2 port of the Fabric mod for 26.3 (branch `main` and commit `7b25351`). The protocol
implementation is [`link-java`](https://github.com/patodois/link-java) (pure Java, Java 8 bytecode, tested against the Rust reference
peers); it is merged into the mod jar. Everything binds and connects to `127.0.0.1` only. There is a sibling
branch for 1.7.10.

## What this mod is not

It contains **no Mojang code, no Mojang assets and no Forge code**. The jar holds only this project's classes and
`link-java`'s. It is compiled against Minecraft and Forge (the build downloads them from Mojang's and Forge's own
servers into your Gradle cache; nothing of theirs is in this repository or in the jar) and does not redistribute
them. It is not affiliated with Mojang, Microsoft or the Forge project.

What Mojang's rules say about mods (read at minecraft.net on 2026-10-23: the
[End User License Agreement](https://www.minecraft.net/en-us/eula) and the
[Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines)):

- A **mod** is "something original" that does not contain a substantial part of Mojang's copyrightable code or
  content. You own what you create, and may use and share it **as long as you do not sell it or try to make money
  from it**, and **do not distribute a modded version of the game**: distribute the mod only, never Minecraft with
  the mod inside. This project's release artifact is the mod jar alone.
- The Usage Guidelines add: no play-to-earn function and nothing that verifies out-of-game ownership; a product that
  mentions Minecraft must say that it is not official ("NOT AN OFFICIAL MINECRAFT [PRODUCT]. NOT APPROVED BY OR
  ASSOCIATED WITH MOJANG OR MICROSOFT") and must not use the Minecraft name as its primary or dominant name, which is
  why the mod's display name is "Rewrite Link for Minecraft" (the repository and the mod id keep the working name
  `minecraft-link`, which you may want to change before publishing anywhere).
- Mojang may take down mods that break the guidelines and may change them at any time.
- Forge itself is not shipped here either. A mod that only calls Forge's API is not bound by Forge's licence terms
  (MinecraftForge Public License section 4); redistributing Forge or MCP data is a different matter and is not done.

This mod only connects to other programs on your own machine and sends nothing to any server. The only guest content
it draws is what the guest's own program renders. It does not read or change another process's memory.

## Credits

The build setup follows the templates of the [GTNewHorizons](https://github.com/GTNewHorizons) community:
[RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle) (LGPL-2.1; the Gradle plugin that
decompiles, deobfuscates and runs Minecraft and Forge for mod development, also on Apple silicon) and its
`example` / `testmod1.12` projects, and [ExampleMod1.7.10](https://github.com/GTNewHorizons/ExampleMod1.7.10)
(MIT) for the layout used by the 1.7.10 branch. None of their code is in the jar. The Apple silicon LWJGL 2 natives
that RetroFuturaGradle downloads for development runs are the
[MinecraftMachina](https://github.com/MinecraftMachina/lwjgl) build.

## Other Minecraft versions

This branch is Minecraft 1.12.2 only. The mod for 26.3 (`main`), 1.20.1 (`mc/1.20.1`) and 1.7.10 (`mc/1.7.10`) have their own branches, and 1.2.5 is coming:
see the table in the [main README](https://github.com/patodois/minecraft-link#minecraft-versions-branches-and-loaders).

## Install

Build the jar (below): `build/libs/minecraft-link-forge-1.12.2-0.1.0.jar`. Use exactly that one, the file without a suffix: not the `-dev` jar, which is
the deobfuscated one for development runs and does not load in a real game.

1. In Prism Launcher make (or use) an instance for **Minecraft 1.12.2 with Forge** (14.23.5.2847 or newer 14.23.5.x;
   Prism's Edit Instance > Version > Install Forge). Java 8.
2. Edit Instance > Mods > Add (or copy the jar into the instance's `minecraft/mods` folder).
3. Start the game and join a world (singleplayer or a server; the mod is client-side only and reads only what your
   client already knows, so a server does not need it: it declares itself client-only and accepts any server).
4. `/link host` (the other game connects to you) or `/link guest` (you connect to the other game's host).

**To see it work without a second game**, build the Rust reference peers once (they need a [Rust toolchain](https://rustup.rs)):

```sh
git clone https://github.com/patodois/rewrite-bridge && cd rewrite-bridge && cargo build --release --features link --examples
```

Join a Superflat world, run `/link host`, and start `target/release/examples/fake_guest --frames --in-front` in that directory: a cube appears 4 m ahead,
on the ground you stand on, and a wall between you and it hides the part behind it. For the other direction run `target/release/examples/fake_host --port 47831`
and `/link guest 47831` in the game: the host prints the player's poses.

Other mods that rewrite the world renderer (OptiFine shaders, Angelica-style replacements) can change the framebuffer
or depth the frame pass reads; see the limits below. It works with plain Forge and with most mods that leave the
renderer alone.

| Command | Meaning |
|---|---|
| `/link host [port]` | listen on `127.0.0.1:port` (default 47800) for one guest |
| `/link guest [port]` | connect to a host on `127.0.0.1:port`, retrying until one answers |
| `/link stop` | say `bye`, half-close, drain and close (on a helper thread: the game never waits) |
| `/link status` | role, peer, input focus, counters, frame and depth information |
| `/link focus host\|guest` | host: give the input focus; guest: ask the host for it |
| `/link` | same as `status` |

The commands are Forge client commands (`ClientCommandHandler`): they run in your client and the text is never sent to
a server. Leaving the world and quitting the game close the link the same way (`despawn` for a guest, then `bye`,
then the half-close of CONTRACT 6.1).

### `config/minecraft-link.json`

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

(The file is plain JSON; the `//` notes above are only for this page.) `offset` is how the two worlds are lined up:
the ground of a 1.12.2 Superflat world (and of the flat world the scripted runs create) is at y = 4, and a Rust guest's ground is y = 0, so a guest
that should stand on that ground may want `"offset": [0, -4, 0]`. (The pages of the 26.3 and 1.20.1 branches say y = -60 for Superflat: worlds
reach down to y = -64 since 1.18, and the flat ground moved down with them.)

## Coordinates (CONTRACT section 4)

Minecraft's world is already right-handed and +Y up with 1 block = 1 m: +X east, +Y up, +Z south, and
(east, up, south) is a right-handed triple, so with the default settings **a position maps to itself** (`flip_*`,
`scale` and `offset` default to the identity). What differs is how Minecraft names directions, and that is what
`Mapping` converts (and unit-tests; it is the same code as on 26.3, 1.12.2 uses the same yaw and pitch conventions):

| Minecraft | Link (contract) |
|---|---|
| `yaw` in degrees, **0 faces +Z (south)**, grows clockwise seen from above: 90 = west (-X), 180 = north (-Z), -90 = east (+X) | rotation of the entity: `Ry(-yaw) * Rx(pitch)`; the front (+Z) is the view direction |
| `pitch` in degrees, 0 level, **+90 looks down**, -90 up | same quaternion; the up axis tips with it |
| view direction `(-sin(yaw)cos(pitch), -sin(pitch), cos(yaw)cos(pitch))` | the entity's local +Z rotated by that quaternion |
| velocity in blocks per tick | metres per second: times 20 |
| the camera: the game's own modelview matrix, which looks down its local -Z | `view` is read from OpenGL (below), not rebuilt from yaw/pitch |

The guest's pose is the **feet position** (the entity origin: `posX/posY/posZ` of the player in 1.12.2), the **view**
rotation, the real displacement over the tick as velocity, the yaw change as angular velocity about +Y, and the
TELEPORT flag on the first pose and when the player moved more than 8 blocks in one tick.

## Host: how it works

All hooks are plain Forge events and client commands: **no coremod, no mixin, no access transformer**.

**Camera**, every rendered frame, from `RenderWorldLastEvent` (after the world is drawn, before the hand and the GUI):

- The projection and the modelview matrix are **read back from OpenGL** (`GL_PROJECTION_MATRIX`,
  `GL_MODELVIEW_MATRIX`), so they are exactly what the game used for the world pass, including view bobbing, the
  hurt tilt, the eye height, the 0.05 m the first-person camera is moved along the view direction, and the field of view
  of the moment. On the test run the camera sent for a player standing at (0.5, 4, 0.5) looking at yaw 25 and pitch 5 was
  at (0.479, 5.616, 0.545): the eye (0.5, 5.62, 0.5) moved 0.05 m along the view direction.
- Minecraft 1.12.2 draws the world relative to the render view entity's interpolated position
  (`lastTickPos + (pos - lastTickPos) * partialTicks`), so the modelview matrix is a rotation without translation.
  The link's `view` is `modelview * translate(-thatPosition)` (then the mapping's flip, scale and offset).
- The projection is `gluPerspective(fov, aspect, 0.05, farPlaneDistance * sqrt 2)`: standard OpenGL, clip depth
  -1..1, **not reversed** (the contract's `depth_range` 0). Near and far are recovered from the matrix
  (`CameraMath.glNearFar`); the contract's classification still measures it, so an unusual matrix is rewritten or
  reported instead of sent wrongly.
- The viewport sizes sent are the **framebuffer's** real size (`Framebuffer.framebufferWidth/Height`), not the window's.

**Collision**, a snapshot of the blocks' collision shapes within `collision_radius` (16) of the player as triangles,
counter-clockwise from outside, chunked by the link library.

- Shapes are what the game uses for entity collision: a block whose `getCollisionBoundingBox` is the full cube (by
  identity, the common case) is a cube, a block without collision is skipped, everything else (slabs, stairs,
  fences, ...) gives the boxes of `IBlockState.addCollisionBoxToList`.
- The scan reads the world on the game thread in slices (a one-millisecond budget per tick, one horizontal layer at
  a time); triangles are built, mapped and hashed on a helper thread. A face is left out when a full block sits behind
  it, and where two boxes of one block touch (the inside of a stair). A new scan starts when the player moved a block,
  or `collision_interval_ms` after the last one; an unchanged result is not sent again (except as a refresh every 5 s).

**Guest entities** are drawn as a **placeholder cube** straight into the world pass (a magenta cube, 1 m wide times
the pose's scale, centred on the pose, its front face yellow), placed every rendered frame from the pose track at
`now - interp_delay_ms` (interpolated, or extrapolated with the pose velocity, at most 250 ms; the TELEPORT flag is
honoured). It is depth-tested against the world, exists only in your client (no entity is added to the level, nothing
is sent to a server or saved) and is hidden while a guest's frames are on screen.

**Guest frames (pixel channel, CONTRACT 9.6 and 9.7).**

1. `frame_channel` names the pixel file; the frame worker maps it read-only (`PixelFile.Reader` in link-java: all
   header and depth checks of the contract, the seqlock).
2. On each `Frame` it copies the slot (colour and linear depth in metres), looks `camera_seq` up in a ring of the last
   256 cameras sent (a second at 240 fps; the contract asks for half a second) and reprojects the frame to the camera being presented now (`Reproject`, on a helper thread).
3. At `RenderWorldLastEvent` the render thread uploads the result to an RGBA8 texture and a 32-bit float texture
   (`GL_LUMINANCE32F_ARB`), copies the game's **depth buffer** into a `GL_DEPTH_COMPONENT24` texture with
   `glCopyTexSubImage2D` (from the bound framebuffer, which is `Minecraft.getFramebuffer()` in an unmodified game), and
   draws one fullscreen quad with a **GLSL 1.20** program (`#version 120`, `texture2D`, `varying`) that converts the
   game's depth to linear metres with the contract's formula for standard OpenGL
   (`2 f n / (f + n - ndc (f - n))`, `ndc = 2 d - 1`) and keeps the guest's pixel only where it is nearer than the
   world, with straight-alpha blending. A guest without a depth plane is drawn as an overlay.
4. The pass is bracketed by `glPushAttrib(GL_ALL_ATTRIB_BITS)` / `glPopAttrib` plus an explicit save and restore of the
   active texture unit, the texture bindings of units 0 to 2 and the current program, so the game's own state cache
   (`GlStateManager`) still matches the real state afterwards. If the GL context cannot do it (no GL 2.0 or no
   `ARB_texture_float`), frames are switched off and `/link status` says why.
5. At first use the mod logs `GL_VERSION`, the GLSL version, renderer, vendor and the two extensions it needs. On the
   test machine: `GL_VERSION 2.1 Metal - 90.5, GLSL 1.20, Apple M5`.

## Threads and safety

- Minecraft 1.12.2 ticks and draws on one thread (the client thread). **Nothing on it ever calls into the link or
  waits**: it writes samples into `AtomicReference`s, reads thread-safe holders, and uploads/draws what the frame
  worker finished.
- Link I/O (`LinkRunner`, 2 ms period), the collision builder and the frame worker are daemon threads.
- Closing (`/link stop`, leaving the world, quitting) runs on a helper thread: despawn, `bye`, half-close, drain 200 ms,
  close. Only at game exit does a JVM shutdown hook wait (at most 2 s) so the bye gets out.
- Only `127.0.0.1` is used, and the peer's datagrams are accepted from its announced address and session only.
- The pixel file path comes from the peer: it must be absolute, a regular file, and pass the contract's size checks
  before it is mapped (read-only).
- **Input focus**: while the other side holds the focus, `EntityPlayerSP.movementInput` is replaced by an empty
  `MovementInput` (nothing pressed) and put back when the focus returns or the session ends. Movement keys, jump and
  sneak are handed over; mouse look, chat and the inventory stay with Minecraft.

## Build and test

You need git and a **JDK 25** to run Gradle (see "Which JDK"); the game and the mod compile with a
**Java 8** toolchain that Gradle downloads (Azul Zulu, which has macOS arm64 builds). The toolchain is
[RetroFuturaGradle](https://github.com/GTNewHorizons/RetroFuturaGradle) 2.0.6 with Gradle 9.8.2, Minecraft 1.12.2,
Forge 14.23.5.2847 (what RetroFuturaGradle 2.0.6 uses; the jar runs on any 14.23.5.x), MCP stable_39.

```sh
git clone https://github.com/patodois/link-java                  # the protocol library, a sibling directory of minecraft-link
git clone -b mc/1.12.2 https://github.com/patodois/minecraft-link
cd minecraft-link
export JAVA_HOME=/opt/homebrew/opt/openjdk@25                    # or any JDK 25
./gradlew build --no-daemon          # build/libs/minecraft-link-forge-1.12.2-0.1.0.jar (reobfuscated, link-java merged in), runs the tests
./gradlew test --no-daemon           # 51 unit tests, no game needed
./gradlew runClient --no-daemon      # the real client, deobfuscated dev environment, offline dev account, -Xmx1500m
./gradlew runObfClient --no-daemon   # the real client with the reobfuscated release jar, as a player's game runs it
```

The build expects `link-java` next to this repository (`../link-java`); it runs its `jar` task (a Gradle included
build) and merges the classes into the mod jar. `gradle.properties` keeps the Gradle JVM at 1 GB and the daemon off.
The first build downloads Minecraft and the assets from Mojang, Forge from the Forge maven and the plugin from the
GTNH maven, and decompiles Minecraft (about 3 GB of Gradle cache, one minute and a half).

Before every `runClient`, `runObfClient` and `runVanillaClient` launch the build writes `soundCategory_master:0.0`
(and a few settings a scripted run needs: no pause on lost focus, windowed, 60 fps, no tutorial) into
`run/options.txt`, keeping the rest of the file.

### Which JDK

One rule for every branch of this repository: **run Gradle with a JDK 25.** RetroFuturaGradle 2.0.6 is compiled for Java 25 and fails on a JDK 21 with
`UnsupportedClassVersionError ... class file version 69.0` (checked with Temurin 21.0.12), and the same JDK 25 runs the builds of the other branches. The game
and the mod are Java 8, but you do not install that: the Gradle toolchain download provides it.

Verified here: the whole build, the unit tests and the jar with Homebrew `openjdk@25` 25.0.5; the dev clients (`runClient`, `runObfClient`) too. Not tried:
JDK 26 or newer.

Unit tests (JUnit 5, plain Java 8): the coordinate mapping, camera matrix conversion (view, the depth conventions,
rewriting, linear depth, near and far from a `gluPerspective` matrix), collision triangles for a full block, a slab and
stairs **both from hand-written boxes and from Minecraft 1.12.2's own block collision boxes** (the game's registries
are bootstrapped, no window), pose interpolation, reprojection, the camera ring and the settings file. The tests use
JOML as a test-only reference for matrices; it is not in the jar.

### Apple silicon notes (development runs)

- RetroFuturaGradle uses the arm64 LWJGL 2 natives; no Rosetta is needed. Visual check (below): colours and viewport
  are right, there is no grey band.
- Minecraft 1.12.2 starts its narrator while it initialises, and the narrator library it ships carries a native for
  Intel Macs only, so the unmodified client crashes before the title screen on Apple silicon
  (`UnsatisfiedLinkError ... need 'arm64'`). For development runs only, `dev/narrator-shim` is a 20-line no-op stand-in
  for `com.mojang.text2speech.Narrator` that replaces the library on the run classpath. It is not in the mod jar.
  Prism launches 1.12.2 on Apple silicon with its own patched libraries, so a player does not need this.

### Scripted client runs

`AutoTest` is a development aid, inactive unless `-Pautotest=<script>` is given. It drives the real client (create a
superflat world, run commands including `/link ...`, press keys, take screenshots) from a plain script:

```sh
./gradlew runClient --no-daemon -Pautotest=/path/script.txt -Pshots=/path/screenshots
```

Script lines: `create_world`, `wait_world`, `wait N` (ticks), `cmd /command` (a `/link` command runs through
`ClientCommandHandler`, anything else on the server), `link host|guest|stop|status|focus ...`, `key forward down|up`,
`look yaw pitch`, `view N`, `gui 0|1`, `pos`, `shot name` (the game's framebuffer), `leave`, `quit`, `log text`.

## Test results (this machine: Apple M5, macOS 26.6.2, GL 2.1 Metal, Minecraft 1.12.2)

| Test | Result |
|---|---|
| Title screen and a flat world, compared with a vanilla `runVanillaClient` title screen | colours right (wool: red, lime, blue, yellow, white; zombie and creeper textures; sky, grass, water), the viewport fills the window, no grey band, no swapped channels |
| Host vs Rust `fake_guest --frames --in-front` (colour + depth) | the guest's cube is drawn in the world 4 m ahead, stays at the same place in the world when the player turns, and **a one-block stone wall placed in front hides its lower part** while the upper part stays visible |
| Host vs `fake_guest` without frames | the placeholder cube is drawn at the pose (front face yellow, turning with the guest's heading) and is hidden behind the same wall |
| Guest vs Rust `fake_host` | the host logs the player's pose; the movement keys did nothing while the host held the focus, moved the player (z 0.5 to 4.81) after the host granted it, and did nothing again after the host took it back |
| `/link stop`, quitting | the peer logs "peer said bye: closing" |
| The reobfuscated release jar under `runObfClient` (Forge with SRG names) | the same host test as above, same result |

Logs of the runs are in `docs/test-logs/`. Screenshots are not committed (they show Mojang's textures).

## Known limits

- Tested on macOS (Apple silicon) with the game's own OpenGL 2.1 context and an unmodified renderer. Windows and
  Linux are not tested; the code uses only GL 2.1 features (GLSL 1.20, `ARB_texture_float`, `ARB_depth_texture`).
- Translucent water, ice and stained glass do not write depth in vanilla, so a guest behind water is drawn over the
  water. The first-person hand and the GUI are drawn after the frame pass and cover the guest's frame.
- OptiFine shaders and mods that replace the main framebuffer or the depth buffer are not handled: the pass copies
  whatever is bound when `RenderWorldLastEvent` fires.
- Guest entities are a placeholder cube (heading only, no roll, no model); the guest's own frames show the real model.
- Reprojection is on the CPU and meant for guest images of a few hundred thousand pixels; cracks can appear on
  stretched surfaces. A pixel file of more than 4 Mpixels (2560 x 1600) is refused with a note in `/link status`, although the contract
  allows 2^26 pixels.
- Guest frames that stop arriving are cleared after a second and the placeholder cube comes back; the textures are released when the link stops.
- Block changes are found by rescanning (every 0.5 s by default), not by an event.
- Mouse and keyboard other than movement stay with Minecraft when the focus is elsewhere.
- No server-side component: it is a client mod.

## Glossary

| Term | Meaning here |
|---|---|
| **host**, **guest** | The two roles of the link. The host is the game you are playing; the guest is a second game or rewrite that adds entities, poses and rendered frames to the host's world. Minecraft can be either. |
| **link**, **rewrite-bridge**, **CONTRACT** | The protocol between the two programs (TCP for control, UDP for state, a shared file for pixels) and the repository that defines it. `docs/CONTRACT.md` there is the whole specification. |
| **link-java** | The Java implementation of that protocol, used by this mod. |
| **pose** | An entity's position, rotation, scale and velocity at a moment, sent every tick. |
| **link metre** | The unit of the link's shared space. `scale` in the config says how many link metres one block is (default 1). |
| **`depth_range`** | A field of the camera message that says how the projection stores depth (0 OpenGL, 1 zero-to-one, 2 reversed). |
| **Forge** | The mod loader of 1.12.2: it starts Minecraft with mods. This branch is a Forge mod, built against Forge 14.23.5.2847 and run by any 14.23.5.x. |
| **RetroFuturaGradle** | The Gradle plugin (by the GTNH community) that downloads, deobfuscates and runs Minecraft and Forge for mod development, including on Apple silicon. |
| **Gradle, toolchain** | The build tool, and its way of finding (or downloading) the JDK a project asks for, independent of the JDK Gradle itself runs on. |
| **included build** | A Gradle build that builds another project (here `link-java`) first and then uses its jar; the classes are merged into the mod jar. |
| **reobfuscated jar, `-dev` jar** | 1.12.2 runs on obfuscated names (SRG). The build compiles against readable names and renames the finished jar; the `-dev` jar keeps the readable names, is for development runs only and does not load in a real game. |
| **LWJGL 2** | The OpenGL and input library 1.12.2 uses; its Apple silicon natives come from the MinecraftMachina build. |
| **dev client, `runClient`, `runObfClient`** | The Minecraft client started from the build with the mod on the class path (`runObfClient`: with the reobfuscated jar, as a player's game runs it), with an offline account, for testing. |
| **Superflat** | A world type with flat ground. In 1.12.2 the ground is at y = 4 (in 1.18 and later at y = -60). |
| **`26.3`** | Mojang's version number since 2026: the year (26) and the number of the release in that year (3). The `main` branch is the mod for it. |

## License

MIT OR Apache-2.0, at your option: see `LICENSE-MIT` and `LICENSE-APACHE`.
