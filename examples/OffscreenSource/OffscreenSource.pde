// Draws the lighting content into an offscreen buffer and samples from there,
// while the window shows a small preview plus controls.
// Useful with keystone, shaders, or when the window is a UI.

import artnetmapper.*;

ArtNetMapper dmx;
PGraphics lights;

void setup() {
  size(600, 400);
  lights = createGraphics(300, 200);

  String[] nodes = ArtNetMapper.list();
  dmx = new ArtNetMapper(this, nodes[0]);          // no node? type an IP, e.g. "2.0.0.1"
  dmx.source(lights);                        // sample the buffer, not the window
  FixtureType rgbw = dmx.type("r, g, b, w");
  dmx.fixture(rgbw, 1).at(75, 100);          // coordinates are in the buffer
  dmx.fixture(rgbw, 5).at(225, 100);
  dmx.showLocations(false);                  // markers would land on the window, not the buffer
}

void draw() {
  lights.beginDraw();
  lights.background(0);
  lights.noStroke();
  lights.fill(255, 60, 0);
  lights.ellipse(150 + sin(frameCount * 0.05) * 120, 100, 160, 160);
  lights.endDraw();

  background(40);
  image(lights, 20, 20);                     // preview
  fill(255);
  text("brightness: " + nf(map(mouseY, 0, height, 1, 0), 1, 2) + " (move mouse up/down)", 20, 260);
  dmx.brightness(map(mouseY, 0, height, 1, 0));
}
