// MouseDimmer: a light set completely by hand, without the canvas.
//
// No setPos() and no colorChannels(): every channel is set with setVal().
// The light here is a 4-channel RGBW par at DMX address 1 (universe 0).
// Mouse X: brightness of the white channel.  Mouse Y: red.
//
// fixture(channels, address)       channels count from 0 inside the fixture

import artnetmapper.*;

ArtNetMapper dmx;
Fixture par;

void setup() {
  size(400, 300);
  String[] nodes = ArtNetMapper.list();           // prints the nodes found
  dmx = new ArtNetMapper(this, nodes[0]);          // no node? use "127.0.0.1"
  par = dmx.fixture(4, 1);                         // 4 channels at DMX address 1
}

void draw() {
  float white = map(mouseX, 0, width, 0, 255);
  float red = map(mouseY, height, 0, 0, 255);
  par.setVal(3, white);                            // channel 3 = white
  par.setVal(0, red);                              // channel 0 = red

  background(red, white * 0.3, white * 0.3);
  fill(255);
  text("white (ch3): " + par.getVal(3) + "\nred (ch0): " + par.getVal(0), 20, 30);
}
