// Lists the Art-Net nodes connected to this computer.
// Nodes that answer ArtPoll are labelled (ArtPoll).
// USB nodes that don't answer are found by the interface scan and labelled (guessed).
// Use nodes[0] in your sketch, or copy another IP from the console.

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
