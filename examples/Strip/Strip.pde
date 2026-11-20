// A virtual 30-pixel RGB strip laid along a line.
// The markers show what each pixel samples. Without a node, replace nodes[0]
// with any IP (e.g. "2.0.0.1") to run it without hardware.
// Drag the mouse to move the strip's end point.

import artnetmapper.*;

ArtNetMapper dmx;
Fixture strip;

void setup() {
  size(600, 400);
  String[] nodes = ArtNetMapper.list();
  dmx = new ArtNetMapper(this, nodes[0]);          // no node? type an IP, e.g. "2.0.0.1"
  strip = dmx.strip(dmx.type("r, g, b"), 1, 9, 30)   // universe 1, channel 9, 30 pixels
             .line(50, 200, 550, 200);
}

void draw() {
  background(0);
  noStroke();
  // a white blob that bounces along the strip
  float x = width / 2 + sin(frameCount * 0.03) * 250;
  for (int r = 120; r > 0; r -= 4) {
    fill(255 - r * 2, 80 + r, 255, 40);
    ellipse(x, 200, r * 2, r);
  }
}

void mouseDragged() {
  strip.line(50, 200, mouseX, mouseY);
}
