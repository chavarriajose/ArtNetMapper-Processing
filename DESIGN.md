# ArtNetMapper — Design (v1)

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

## Fixtures

```java
FixtureType par = dmx.type("dim=255, r, g, b, w, strobe=0");
Fixture p1 = dmx.fixture(par, 1).at(100, 200);     // universe 0, channel 1
dmx.fixture(par, 1, 5).at(300, 200);               // universe 1, channel 5
p1.set("strobe", 128);

FixtureType px = dmx.type("r, g, b");
dmx.strip(px, 9, 30).line(50, 400, 550, 400);      // 30 pixels from ch 9
dmx.matrix(px, 1, 1, 8, 8).rect(400, 50, 160, 160).rotate(radians(15))
   .wiring(ArtNetMapper.SERPENTINE, ArtNetMapper.TOP_LEFT, ArtNetMapper.ROWS);
```

- Type string: comma-separated channel names in DMX order. `r`, `g`, `b`, `w` are
  sampled from the canvas and may appear once each. `name=value` channels are
  constants, changeable with `set(name, value)`. Other names without a value default
  to 0. Non-color names may repeat (e.g. two `unused` channels); `set()` sets all.
- Addressing: universe optional (default 0), counted from 0 (Art-Net port address,
  15-bit). Channels counted from 1 to 512.
- A fixture may not cross a universe boundary: exceeding channel 512 throws in
  `setup()` with a clear message. Overlapping fixtures print a warning only.
- Placement: `.at(x, y)` (single element), `.line(x1, y1, x2, y2)` (elements evenly
  spaced from first to last point), `.rect(x, y, w, h)` + optional `.rotate(angle)`
  (matrix, element centers on a grid; rotation about the rect center).
- Matrix wiring: `SERPENTINE`/`PROGRESSIVE`, start corner `TOP_LEFT`/`TOP_RIGHT`/
  `BOTTOM_LEFT`/`BOTTOM_RIGHT`, `ROWS`/`COLUMNS`. Default progressive, top-left, rows.

## Color pipeline

Per element: sample → white extraction → gamma → brightness.

- Sample: 1 pixel by default; `.sampleRadius(r)` averages a square of radius r.
- Source: main canvas by default; `dmx.source(pg)` samples any `PGraphics`.
- White extraction (fixtures with a `w` channel): `w = min(r, g, b)`, subtracted
  from r, g, b. `.whiteMode(false)` disables it; w is then 0 unless `set("w", v)`.
- Sample points up to one pixel past the canvas edge read the edge pixel (so
  `line(0, y, width, y)` works); points further out read black. A fixture that is
  never placed prints a warning and samples (0, 0).
- Gamma: default 2.5 (matches Resolume Arena). `dmx.gamma(g)` globally,
  `.gamma(g)` per fixture; 1.0 disables.
- Brightness: `dmx.brightness(0..1)`, global.
- Constant channels are not affected by gamma or brightness.

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
- Overlay: a ring at each sample point in the sampled (pre-gamma) color, drawn
  after sampling. The ring sits outside the sample square, so it never feeds back
  into sampling even if the sketch never clears the canvas.
  `dmx.showLocations(false)` hides it.

## Known limitations (v0.1)

- With `source(pg)`, markers are drawn on the main window in `pg` coordinates; hide
  them with `showLocations(false)` or draw `pg` at (0, 0).
- In P3D the overlay re-enables depth testing after drawing, overriding a sketch's
  `hint(DISABLE_DEPTH_TEST)`.
- `list()` and background scans bind UDP 6454 (shared, SO_REUSEPORT) for up to ~1.5 s
  to hear ArtPollReply. Another app that binds 6454 exclusively at that moment can
  fail to start. Sending never binds 6454.
- The interface scan (for nodes that ignore ArtPoll) runs on macOS and Linux only.

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
- v1 examples: `Discover`, `TwoPars`, `Strip`, `Matrix`, `OffscreenSource`.
- v1 is done when tests and the webcam check pass, the user has tried the
  examples, and the `EVALUATION.md` entry is written together.
