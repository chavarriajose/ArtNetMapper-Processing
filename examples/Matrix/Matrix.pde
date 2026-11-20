// Matrix: an 8x8 RGB matrix (3 channels per pixel) wired serpentine from the
// top-left corner, rotated 15 degrees, over a plasma pattern.
//
// Set your matrix to DMX address 1 (universe 0), or change the numbers below.
// Argument order: matrix(channelsPerPixel, address, cols, rows)
//             or  matrix(channelsPerPixel, universe, address, cols, rows)

import artnetmapper.*;

ArtNetMapper dmx;

void setup() {
  size(500, 500);
  pixelDensity(1);   // this sketch writes pixels[] directly, one entry per sketch pixel
  String[] nodes = ArtNetMapper.list();           // prints the nodes found
  dmx = new ArtNetMapper(this, nodes[0]);          // no node? use "127.0.0.1"

  Fixture m = dmx.matrix(3, 1, 8, 8);              // 3 channels per pixel, address 1, 8x8
  m.rect(130, 130, 240, 240);
  m.rotate(radians(15));
  m.wiring(ArtNetMapper.SERPENTINE, ArtNetMapper.TOP_LEFT, ArtNetMapper.ROWS);
  m.colorChannels(0, 1, 2);
  m.sampleRadius(3);                               // average a small area per pixel
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
