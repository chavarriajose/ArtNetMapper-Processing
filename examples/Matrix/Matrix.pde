// A virtual 8x8 RGB matrix, wired serpentine from the top-left corner,
// rotated 15 degrees. Plasma pattern underneath.

import artnetmapper.*;

ArtNetMapper dmx;

void setup() {
  size(500, 500);
  pixelDensity(1);   // this sketch writes pixels[] directly, one entry per sketch pixel
  String[] nodes = ArtNetMapper.list();
  dmx = new ArtNetMapper(this, nodes[0]);          // no node? type an IP, e.g. "2.0.0.1"
  dmx.matrix(dmx.type("r, g, b"), 2, 1, 8, 8)        // universe 2, channel 1
     .rect(130, 130, 240, 240)
     .rotate(radians(15))
     .wiring(ArtNetMapper.SERPENTINE, ArtNetMapper.TOP_LEFT, ArtNetMapper.ROWS)
     .sampleRadius(3);
  colorMode(HSB, 1);
}

void draw() {
  float t = millis() / 1000.0;
  loadPixels();
  for (int y = 0; y < height; y += 2) {
    for (int x = 0; x < width; x += 2) {
      float v = sin(x * 0.02 + t) + sin(y * 0.03 - t) + sin((x + y) * 0.015 + t * 0.5);
      int c = color((v * 0.15 + t * 0.05) % 1, 0.9, 1);
      pixels[y * width + x] = c;
      pixels[y * width + x + 1] = c;
      pixels[(y + 1) * width + x] = c;
      pixels[(y + 1) * width + x + 1] = c;
    }
  }
  updatePixels();
}
