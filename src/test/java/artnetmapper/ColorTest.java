package artnetmapper;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ColorTest {

  private static final int[] LINEAR = ColorMath.gammaTable(1f);

  @Test
  void gammaOneIsIdentity() {
    for (int i = 0; i < 256; i++) assertEquals(i, LINEAR[i]);
  }

  @Test
  void gammaKeepsEndpointsAndDarkensMidtones() {
    int[] t = ColorMath.gammaTable(2.5f);
    assertEquals(0, t[0]);
    assertEquals(255, t[255]);
    assertEquals(Math.round(255 * Math.pow(128 / 255.0, 2.5)), t[128]);
    assertTrue(t[128] < 128);
    for (int i = 1; i < 256; i++) assertTrue(t[i] >= t[i - 1], "monotonic at " + i);
  }

  @Test
  void whiteExtraction() {
    int[] out = new int[4];
    ColorMath.process(255, 255, 255, true, LINEAR, 1f, out);
    assertArrayEquals(new int[] {0, 0, 0, 255}, out);
    ColorMath.process(200, 100, 50, true, LINEAR, 1f, out);
    assertArrayEquals(new int[] {150, 50, 0, 50}, out);
    ColorMath.process(255, 0, 0, true, LINEAR, 1f, out);
    assertArrayEquals(new int[] {255, 0, 0, 0}, out);
  }

  @Test
  void whiteExtractionOff() {
    int[] out = new int[4];
    ColorMath.process(200, 100, 50, false, LINEAR, 1f, out);
    assertArrayEquals(new int[] {200, 100, 50, 0}, out);
  }

  @Test
  void orderIsWhiteThenGammaThenBrightness() {
    int[] lut = ColorMath.gammaTable(2.5f);
    int[] out = new int[4];
    ColorMath.process(200, 100, 50, true, lut, 0.5f, out);
    // extract: r150 g50 b0 w50 -> gamma -> *0.5
    assertEquals(Math.round(lut[150] * 0.5f), out[0]);
    assertEquals(Math.round(lut[50] * 0.5f), out[1]);
    assertEquals(0, out[2]);
    assertEquals(Math.round(lut[50] * 0.5f), out[3]);
  }

  @Test
  void brightnessZeroIsBlack() {
    int[] out = new int[4];
    ColorMath.process(255, 255, 255, false, LINEAR, 0f, out);
    assertArrayEquals(new int[] {0, 0, 0, 0}, out);
  }
}
