// QC: dense strip (6 px spacing), canvas painted once (no background() in draw).
// DESIGN says the overlay never feeds back into sampling. Neighbor rings (radius 4.5 and 6)
// cross the neighbors' sample points; expect all pixels to stay 255,255,255 if no feedback.
import artnetmapper.*;
ArtNetMapper dmx;
Fixture s;
void setup() {
  size(200, 200);
  background(255);
  dmx = new ArtNetMapper(this, "127.0.0.1");
  dmx.gamma(1);
  s = dmx.strip(3, 1, 21).line(40, 100, 160, 100).colorChannels(0, 1, 2);
}
void draw() {
  if (frameCount == 2 || frameCount == 10) {
    int bad = 0; String v = "";
    for (int i = 0; i < 21; i++) { int r = s.pixel(i).getVal(0); if (r != 255) bad++; if (i < 6) v += r + " "; }
    println("QC frame " + frameCount + ": pixels not white = " + bad + "/21, first r values: " + v);
  }
  if (frameCount == 10) exit();
}
