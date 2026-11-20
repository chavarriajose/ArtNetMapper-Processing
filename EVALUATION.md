# ArtNetMapper — Evaluation log

One entry per version: what worked, what felt wrong, and what was decided for the next version.

## v0.1 — 2026-11-20

**Tested:** `TwoPars` on two RGBW pars (u0 ch1, u0 ch5) through the Electroconcept USB
Node at 2.0.0.1. 41 unit tests and the loopback test pass. Webcam hardware check not
run yet (camera not set up).

**Worked**
- The lights followed the sketch.
- `ArtNetMapper.list()` found the node, via ArtPoll, as `Electroconcept USB NODE`.
- Reviewer-agent QC found 2 high / 5 medium issues; all fixed before testing
  (Matrix example on Retina screens, overlay feeding back into sampling, background
  rescans holding UDP 6454, 40 fps instead of 44, no change detection, and others).

**Felt wrong**
- `new ArtNetMapper(this, ArtNetMapper.FIRST)`: unclear. Fixed in v0.1 before tagging:
  now `String[] nodes = ArtNetMapper.list(); new ArtNetMapper(this, nodes[0]);`, like
  `Serial.list()[0]`. An empty list makes `nodes[0]` fail, as in Processing's own APIs.
- `dmx.type("r, g, b, w")`: describing channels with a string felt illogical. Real
  fixtures have several channel modes (4, 6, 8 channels) and their manuals define
  each channel; the user wants to decide per channel.
- Method names: prefers `setPos(x, y)` over `at(x, y)`.

**Decided for v0.2**
- Fixtures are a number of channels: `dmx.fixture(channels, [universe,] address)`,
  `dmx.strip(channelsPerPixel, [universe,] address, count)`,
  `dmx.matrix(channelsPerPixel, [universe,] address, cols, rows)`. `FixtureType` and
  the type string are removed.
- Fixture channels are numbered from 0; the DMX address from 1.
- Fixtures start fully manual (all channels 0). `colorChannels(r, g, b[, w])` optionally
  links internal channels to the canvas color at the fixture's position.
- `setVal(ch, v)` (int or float, clamped 0-255) and `setVals(v0, v1, ...)` set channels
  by hand on every pixel; `pixel(i).setVal(...)` / `pixel(i).setVals(...)` for one pixel.
  A manual value holds until `release()` / `release(ch)` / `pixel(i).release()`.
- Color correction (white extraction, gamma, brightness) applies only to canvas
  colors. Manual values are sent exactly as written.
- Names: `setPos(x, y)`, `line(...)`, `rect(...)`, `rotate(a)`, `setVal`, `setVals`,
  `release`, `colorChannels`, `sampleRadius`, `gamma`, `whiteMode`, `wiring`.
- All of these may be called in `setup()` or `draw()` (e.g. moving a fixture live).
