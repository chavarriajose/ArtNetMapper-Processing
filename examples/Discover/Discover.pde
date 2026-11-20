// Discover: lists the Art-Net nodes connected to this computer.
//
// The console shows a numbered list, like Serial.list():
//   [0] "2.0.0.1"  Electroconcept USB NODE  (answered ArtPoll, en6)
// Nodes that don't answer ArtPoll are found by the interface scan instead:
//   [0] "2.0.0.1"  (found by interface scan, no ArtPoll reply, en6)
// In your sketch, use nodes[0], or copy another IP from the list.
//
// Click the window to scan again.

import artnetmapper.*;

String[] nodes;

void setup() {
  size(480, 240);
  nodes = ArtNetMapper.list();        // waits ~1.5 s; ArtNetMapper.list(3000) waits longer
}

void draw() {
  background(20);
  fill(255);
  textSize(16);
  if (nodes.length == 0) {
    text("No Art-Net nodes found.\nClick to scan again.", 20, 40);
  } else {
    text("Art-Net nodes (click to scan again):", 20, 40);
    for (int i = 0; i < nodes.length; i++) {
      text("[" + i + "] " + nodes[i], 20, 75 + i * 24);
    }
  }
}

void mousePressed() {
  nodes = ArtNetMapper.list();
}
