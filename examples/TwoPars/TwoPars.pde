// Two RGBW pars on one Art-Net node:
// par 1 at DMX channel 1, par 2 at DMX channel 5 (universe 0).
// Each par takes its color from the point marked on the canvas.
// Move the mouse to change the gradient's speed; press W to toggle white extraction.

import artnetmapper.*;

ArtNetMapper dmx;
Fixture par1, par2;
boolean white = true;

void setup() {
  size(600, 300);
  String[] nodes = ArtNetMapper.list();           // prints the nodes found
  dmx = new ArtNetMapper(this, nodes[0]);          // or new ArtNetMapper(this, "2.0.0.1")

  FixtureType rgbw = dmx.type("r, g, b, w");
  par1 = dmx.fixture(rgbw, 1).at(150, 150);
  par2 = dmx.fixture(rgbw, 5).at(450, 150);
  colorMode(HSB, 360, 100, 100);
}

void draw() {
  float speed = map(mouseX, 0, width, 0.2, 3);
  float t = frameCount * speed;
  // horizontal hue gradient that scrolls over time
  for (int x = 0; x < width; x++) {
    stroke((t + x * 0.6) % 360, 100, 100);
    line(x, 0, x, height);
  }
  fill(0, 0, 100);
  noStroke();
  textSize(14);
  text("white extraction: " + (white ? "on" : "off") + " (W)   node: "
       + (dmx.isConnected() ? dmx.ip() : "none"), 10, height - 12);
}

void keyPressed() {
  if (key == 'w' || key == 'W') {
    white = !white;
    par1.whiteMode(white);
    par2.whiteMode(white);
  }
}
