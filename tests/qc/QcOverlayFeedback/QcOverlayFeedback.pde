// QC: sketch that paints once (no background() in draw) + sampleRadius >= 5.
// Expect channels to stay 255,0,0. Overlay ring drawn on the canvas feeds back if they drift.
import artnetmapper.*;
ArtNetMapper dmx;
void setup() {
  size(200, 200);
  background(255, 0, 0);
  dmx = new ArtNetMapper(this, "127.0.0.1");
  dmx.gamma(1);
  dmx.fixture(dmx.type("r, g, b"), 1).at(100, 100).sampleRadius(6);
  println("QC density=" + pixelDensity + " pixelWidth=" + pixelWidth);
}
void draw() {
  int[] c = dmx.channels(0);
  if (frameCount == 2 || frameCount == 10 || frameCount == 30) println("QC frame " + frameCount + ": " + c[0] + "," + c[1] + "," + c[2]);
  if (frameCount == 30) exit();
}
