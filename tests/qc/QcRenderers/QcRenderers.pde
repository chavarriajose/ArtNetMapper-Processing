// QC: P2D main canvas + createGraphics source in a density-2 sketch.
import artnetmapper.*;
ArtNetMapper dmx, dmx2;
PGraphics pg;
void setup() {
  size(200, 200, P2D);
  dmx = new ArtNetMapper(this, "127.0.0.1");
  dmx.gamma(1);
  dmx.fixture(3, 1).colorChannels(0, 1, 2).setPos(50, 50);
  dmx.fixture(3, 4).colorChannels(0, 1, 2).setPos(150, 150);
  pg = createGraphics(100, 100);
  dmx2 = new ArtNetMapper(this, "127.0.0.1");
  dmx2.gamma(1).source(pg).showLocations(false);
  dmx2.fixture(3, 1).colorChannels(0, 1, 2).setPos(25, 50);
  dmx2.fixture(3, 4).colorChannels(0, 1, 2).setPos(75, 50);
  dmx2.fixture(3, 7).colorChannels(0, 1, 2).setPos(99.6, 50);
  println("QC main density=" + pixelDensity + " pg density=" + pg.pixelDensity + " pg pixelWidth=" + pg.pixelWidth);
}
void draw() {
  noStroke();
  fill(0, 255, 0); rect(0, 0, 100, 200);
  fill(0, 0, 255); rect(100, 0, 100, 200);
  pg.beginDraw(); pg.noStroke();
  pg.fill(255, 0, 0); pg.rect(0, 0, 50, 100);
  pg.fill(255, 255, 0); pg.rect(50, 0, 50, 100);
  pg.endDraw();
  if (frameCount == 20) {
    int[] a = dmx.getUniverse(0), b = dmx2.getUniverse(0);
    println("QC P2D main: " + a[0] + "," + a[1] + "," + a[2] + " | " + a[3] + "," + a[4] + "," + a[5] + "  (expect 0,255,0 | 0,0,255)");
    println("QC pg: " + b[0] + "," + b[1] + "," + b[2] + " | " + b[3] + "," + b[4] + "," + b[5] + " | " + b[6] + "," + b[7] + "," + b[8] + "  (expect 255,0,0 | 255,255,0 | 255,255,0)");
    exit();
  }
}
