// OffscreenSource: the lights read an offscreen buffer instead of the window.
//
// The lighting content is drawn into a PGraphics; the window shows a preview and
// a control. Useful with keystone, shaders, or when the window is a UI.
// Fixture positions are in the buffer's pixels. Two RGBW pars at DMX 1 and 5.
// Mouse Y: global brightness of the canvas colors.

import artnetmapper.*;

ArtNetMapper dmx;
PGraphics lights;

void setup() {
  size(600, 400);
  lights = createGraphics(300, 200);

  String[] nodes = ArtNetMapper.list();           // prints the nodes found
  dmx = new ArtNetMapper(this, nodes[0]);          // no node? use "127.0.0.1"
  dmx.source(lights);                              // read the buffer, not the window
  dmx.fixture(4, 1).setPos(75, 100).colorChannels(0, 1, 2, 3);
  dmx.fixture(4, 5).setPos(225, 100).colorChannels(0, 1, 2, 3);
  dmx.showLocations(false);                        // markers would land on the window, not the buffer
}

void draw() {
  float level = map(mouseY, 0, height, 1, 0);
  dmx.brightness(level);

  lights.beginDraw();
  lights.background(0);
  lights.noStroke();
  lights.fill(255, 60, 0);
  lights.ellipse(150 + sin(frameCount * 0.05) * 120, 100, 160, 160);
  lights.endDraw();

  background(40);
  image(lights, 20, 20);                           // preview
  fill(255);
  text("brightness: " + nf(level, 1, 2) + " (move the mouse up and down)", 20, 260);
}
