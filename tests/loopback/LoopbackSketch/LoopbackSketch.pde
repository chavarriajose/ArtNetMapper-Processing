// Loopback test sketch: draws four known color quadrants, maps fixtures onto them,
// sends to 127.0.0.1 for ~2 s, then exits (which triggers the blackout).
// Checked by tests/loopback/LoopbackCheck.java. Do not change values without updating it.

import artnetmapper.*;

ArtNetMapper dmx;

void setup() {
  size(200, 100);
  frameRate(60);
  dmx = new ArtNetMapper(this, "127.0.0.1");
  dmx.gamma(1);                                   // exact values for checking

  FixtureType rgbw = dmx.type("r, g, b, w");
  FixtureType full = dmx.type("dim=255, r, g, b, w, strobe=0");
  FixtureType rgb  = dmx.type("r, g, b");

  dmx.fixture(rgbw, 1).at(50, 25);                // TL white -> 0,0,0,255
  dmx.fixture(full, 5).at(150, 25).set("strobe", 77); // TR -> 255,150,50,0,50,77
  dmx.strip(rgb, 1, 1, 3).line(10, 25, 190, 25);  // u1: white, TR, TR
  dmx.matrix(rgb, 2, 1, 2, 2).rect(0, 0, 200, 100)
     .wiring(ArtNetMapper.SERPENTINE, ArtNetMapper.TOP_LEFT, ArtNetMapper.ROWS); // u2
  dmx.fixture(rgb, 3, 1).at(195, 95);             // u3: animated, drives the send rate
}

void draw() {
  noStroke();
  fill(255, 255, 255); rect(0, 0, 100, 50);       // TL white
  fill(200, 100, 50);  rect(100, 0, 100, 50);     // TR
  fill(0, 255, 0);     rect(0, 50, 100, 50);      // BL green
  fill(0, 0, 255);     rect(100, 50, 100, 50);    // BR blue
  fill(1 + frameCount % 200, 0, 0); rect(190, 90, 10, 10); // changes every frame
  if (frameCount == 120) exit();
}
