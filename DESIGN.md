# ArtNetMapper — Design

A Processing 4 library that maps regions of a sketch to DMX fixtures over Art-Net,
in the spirit of FadeCandy's OPC client: fixtures are defined in code, each light
element samples a point of the canvas, and data is sent automatically after `draw()`.

Decisions below were agreed in a design interview on 2026-11-19. Later versions
revisit them through `EVALUATION.md`.

## Scope

- v1 handles pixel fixtures only: pars, strips (light bars), matrices.
- Moving heads (pan/tilt/gobo) are deferred to v2.
- Protocol: Art-Net only. sACN may come later behind the same API.
- Own packet code, no third-party dependencies (artnet4j rejected: GPL-3.0,
  binds UDP 6454, no interface scan, unmaintained).
- License: MIT.
- Target: Processing 4.5.7, Java 17. Build: Gradle + Homebrew `openjdk@17`.

## Discovery

```java
String[] nodes = ArtNetMapper.list();       // blocks ~1.5 s, prints a numbered list
String[] nodes = ArtNetMapper.list(3000);   // longer wait
ArtNetMapper dmx = new ArtNetMapper(this, nodes[0]);       // like Serial.list()[0]
ArtNetMapper dmx = new ArtNetMapper(this, "2.0.0.1");      // or a typed IP
```

- ArtPoll is broadcast on every IPv4 interface. Nodes that reply are listed from any
  interface and labelled `ArtPoll`.
- Interface scan: only small point-to-point links (a subnet holding just this host
  and one peer, e.g. a USB node's virtual Ethernet `en6` with `2.0.0.2`/`2.0.0.1`)
  are probed. Peers found this way are labelled `guessed (no ArtPoll reply)`.
  Large networks (home Wi-Fi, office LAN) are never swept.
- No node found: `list()` returns an empty array and prints a hint, and `nodes[0]`
  throws `ArrayIndexOutOfBoundsException`, as with `Serial.list()`. To work without
  hardware, pass an IP directly. (Changed after v0.1 testing: the `FIRST` constant
  was removed as unclear; this replaces the earlier "no output mode" for empty lists.)
- The given IP is always sent to. If a local interface is on its subnet, it is reported
  "connected"; if that interface disappears (USB node unplugged), sending pauses and
  resumes when it returns. An IP on no local subnet is treated as routed and sent to
  anyway, with a one-line note.
- One `ArtNetMapper` object per node; a sketch may create several.

## Fixtures (v0.2)

A fixture is a number of channels at a DMX address. What each channel does comes
from the light's manual; the sketch decides per channel.

```java
Fixture par = dmx.fixture(8, 1);            // 8 channels at DMX address 1 (universe 0)
par.setPos(150, 150);
par.colorChannels(1, 2, 3, 4);              // optional: r, g, b, w follow the canvas
par.setVal(0, 255);                         // dimmer, sent exactly as written

Fixture bar = dmx.strip(3, 1, 9, 30);       // 3 ch/pixel, universe 1, address 9, 30 pixels
bar.line(50, 400, 550, 400);
bar.colorChannels(0, 1, 2);
bar.pixel(12).setVals(255, 0, 0);           // one pixel hard-coded

Fixture wall = dmx.matrix(3, 2, 1, 8, 8);   // 8x8 pixels, universe 2, address 1
wall.rect(130, 130, 240, 240).rotate(radians(15))
    .wiring(ArtNetMapper.SERPENTINE, ArtNetMapper.TOP_LEFT, ArtNetMapper.ROWS);
wall.colorChannels(0, 1, 2);

// any time, in setup() or draw():
par.setPos(mouseX, mouseY);
par.setVals(255, 0, 0, 0, 0, 0, 0, 0);
par.release();
```

- Created from the node: `fixture(channels, [universe,] address)`,
  `strip(channelsPerPixel, [universe,] address, count)`,
  `matrix(channelsPerPixel, [universe,] address, cols, rows)`. Universe optional
  (default 0), counted from 0. DMX address counted from 1 (as printed on the light).
- Internal channels are counted from 0, in the manual's order.
- Every channel starts at 0. `setVal(ch, v)` (int or float, clamped 0-255) sets one
  channel on every pixel; `setVals(v0, v1, ...)` sets channels 0, 1, 2...;
  `pixel(i).setVal(...)` / `pixel(i).setVals(...)` target one pixel (DMX order).
- `colorChannels(r, g, b[, w])` links internal channels to the canvas color at the
  fixture's position: 3 channels = RGB, 4 = RGBW. With no call, the fixture is fully
  manual and doesn't need a position.
- A value set by hand on a canvas-linked channel wins until `release()`,
  `release(ch)` or `pixel(i).release()`. `setVals(...)` covers color channels too
  (that is how a color is hard-coded); to set only a dimmer, use `setVal(0, 255)`.
  Calling `colorChannels(...)` hands its channels to the canvas, clearing earlier
  hand-set values on them. Unlinked channels keep their value.
- Each added fixture prints one line, e.g.
  `ArtNetMapper: added strip, 30 pixels x 3 channels, universe 1, DMX 9-98`, so swapped
  arguments are easy to spot; a strip or matrix with more channels per pixel than
  pixels also prints a warning with the likely intended call. Messages say "DMX" or
  "address" for 1-based numbers and "channel" only for 0-based channels in a fixture.
- `wiring()` constants have distinct values per group (0-1, 10-13, 20-21), so
  arguments given in the wrong order are rejected.
- `getVal(ch)` / `pixel(i).getVal(ch)` return the current value.
- Info: `channels()` (per pixel), `pixels()`, `totalChannels()`, `address()` (first
  DMX address), `lastAddress()`, `universe()`, `x()`/`y()` (par position) and
  `pixel(i).x()`/`pixel(i).y()`. `println(fixture)` prints a full description.
- Every setting can be read back with `get...`: `getGamma()`, `getSampleRadius()`,
  `getWhiteMode()` on fixtures; `getGamma()`, `getBrightness()`, `getMaxRate()` on the
  mapper. `dmx.getUniverse(u)` returns a universe's 512 values (index 0 = DMX 1).
- A fixture may not cross a universe boundary: past address 512 throws with a clear
  message. Overlaps print a warning only.
- Placement: `setPos(x, y)` (par), `line(x1, y1, x2, y2)` (strip; first to last
  pixel), `rect(x, y, w, h)` + `rotate(angle)` (matrix; pixel centers on a grid,
  rotated about the rect center), `wiring(...)` for matrices
  (`SERPENTINE`/`PROGRESSIVE`, start corner, `ROWS`/`COLUMNS`; default progressive,
  top-left, rows). A point samples the canvas pixel that contains it.
- Per-fixture settings: `sampleRadius(r)`, `gamma(g)`, `whiteMode(b)`.

## Color pipeline

Canvas-linked channels only: sample → white extraction (4 color channels) → gamma →
brightness. Values set by hand are sent exactly as written.

- Sample: 1 pixel by default; `.sampleRadius(r)` averages a square of radius r.
- Source: main canvas by default; `dmx.source(pg)` samples any `PGraphics`.
- White extraction (4 color channels): `w = min(r, g, b)`, subtracted from r, g, b.
  `.whiteMode(false)` disables it; w is then 0 unless set by hand.
- Sample points up to one pixel past the canvas edge read the edge pixel (so
  `line(0, y, width, y)` works); points further out read black. A fixture that is
  never placed prints a warning and samples (0, 0).
- Gamma: default 2.5 (matches Resolume Arena). `dmx.gamma(g)` globally,
  `.gamma(g)` per fixture; 1.0 disables.
- Brightness: `dmx.brightness(0..1)`, global.

## Sending

- Automatic at the end of `draw()` (registered `draw` hook, so sampling happens
  before the overlay is drawn), capped at 44 fps (`dmx.maxRate(fps)`; measured
  ~43.3 fps). Frames are only sent when a channel changed; otherwise the last frame
  is resent every 1 s.
- Manual mode: `dmx.autoSend(false)` + `dmx.send()`.
- Sends from an ephemeral UDP port (never binds 6454), so it coexists with
  Resolume and other Art-Net software.
- ArtDmx sequence 1..255 per universe; full 15-bit port address.
- Only universes that have fixtures are sent.
- Blackout on exit (a few zero frames); `dmx.blackoutOnExit(false)` keeps the
  last frame.
- `dmx.blackout(true)` / `blackout(false)` is a switch: while on, every packet is
  zeros, while fixture values keep updating; `isBlackout()` reads it.
- Overlay: a ring at each sample point in the sampled (pre-gamma) color (grey for
  manual fixtures), drawn after sampling. Rings shrink to fit between a fixture's own
  pixels; for very dense strips they become ticks beside the line, for dense
  matrices an outline. They never touch the fixture's own sample squares, so they
  don't feed back even if the sketch never clears the canvas.
  `dmx.showLocations(false)` hides it.

## Known limitations

- Markers can touch the sample points of a *different* fixture placed within a few
  pixels, in sketches that never clear the canvas.
- Fixture methods are meant for the animation thread (setup, draw, event handlers);
  calls from other threads may be half-applied for one frame.
- With `source(pg)`, markers are drawn on the main window in `pg` coordinates; hide
  them with `showLocations(false)` or draw `pg` at (0, 0).
- In P3D the overlay re-enables depth testing after drawing, overriding a sketch's
  `hint(DISABLE_DEPTH_TEST)`.
- `list()` and background scans bind UDP 6454 (shared, SO_REUSEPORT) for up to ~1.5 s
  to hear ArtPollReply. Another app that binds 6454 exclusively at that moment can
  fail to start. Sending never binds 6454.
- The interface scan (for nodes that ignore ArtPoll) runs on macOS and Linux only.

## Structure

- `ArtNetMapper`: public entry point; fixture registry and per-frame sampling.
- `Fixture` (+ `Fixture.Pixel`): channels, placement, canvas link, hand-set values.
- `Sender`: universe buffers, UDP socket, send thread (sleeps until the next frame
  is due: ~0.3% of a core when idle), sequence numbers, connection check, blackout.
- `Overlay`: location markers. `Discovery`: `list()`. `ArtNetPackets`, `ColorMath`,
  `MethodList`: helpers.
- Version: single source in `library.properties` (`prettyVersion`); a test checks
  `ArtNetMapper.VERSION` matches.

## Process

- Local git repo, one tag per version (`v0.1`, `v0.2`, ...). No remote push unless asked.
- `EVALUATION.md`: per version, what worked, what felt wrong, decisions for next.
- Quality control by a separate reviewer agent:
  1. Unit tests (packets, white extraction, gamma, wiring, address validation).
  2. Loopback test: a local Art-Net receiver captures packets sent by an example
     sketch run headless and checks channel values. Every change.
  3. Hardware test once per version: the two RGBW pars (u0 ch1, u0 ch5) light
     separate areas of a white wall; the FaceTime camera (via `ffmpeg`) reads the
     reflected color.
- Examples: `Discover`, `TwoPars`, `ParModes` (8-channel par), `MouseDimmer` (fully
  manual, no canvas), `Strip`, `Matrix`, `OffscreenSource`. The build appends the
  grouped method list to each example's installed copy; `examples/` itself holds
  the sketches only.
- v1 is done when tests and the webcam check pass, the user has tried the
  examples, and the `EVALUATION.md` entry is written together.
