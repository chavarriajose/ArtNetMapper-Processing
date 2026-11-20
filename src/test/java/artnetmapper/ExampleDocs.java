package artnetmapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Build tool (run by `gradle exampleDocs`): copies every example sketch from args[0]
 * to args[1] and appends the controlP5-style method list to each copy.
 */
public final class ExampleDocs {

  static final String START = "/* ArtNetMapper methods";

  public static void main(String[] args) throws IOException {
    Path from = Path.of(args[0]);
    Path to = Path.of(args[1]);
    String block = block();
    try (Stream<Path> files = Files.walk(from)) {
      for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
        Path out = to.resolve(from.relativize(p));
        Files.createDirectories(out.getParent());
        if (!p.toString().endsWith(".pde")) {
          Files.copy(p, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
          continue;
        }
        String s = Files.readString(p, StandardCharsets.UTF_8);
        int i = s.indexOf(START);
        String body = (i >= 0 ? s.substring(0, i) : s).stripTrailing();
        Files.writeString(out, body + "\n\n" + block, StandardCharsets.UTF_8);
      }
    }
  }

  static String block() {
    StringBuilder b = new StringBuilder();
    b.append(START).append("\n\n");
    b.append("All methods you can call, with what they receive.\n");
    b.append("Print this list in the console with:\n");
    b.append("  ArtNetMapper.printPublicMethodsFor(ArtNetMapper.class);\n");
    b.append("  ArtNetMapper.printPublicMethodsFor(Fixture.class);\n");
    b.append("  ArtNetMapper.printPublicMethodsFor(Fixture.Pixel.class);\n");
    b.append("Details for each method: reference/index.html in the library folder.\n\n");
    b.append("Format:\nClassName : returnType methodName(parameter type and name)\n");
    b.append("Channels inside a fixture count from 0; DMX addresses count from 1.\n");
    for (Class<?> c : List.of(ArtNetMapper.class, Fixture.class, Fixture.Pixel.class)) {
      b.append("\n---------------- ").append(c == Fixture.Pixel.class ? "Fixture.Pixel" : c.getSimpleName())
          .append(" ----------------\n");
      for (String line : MethodList.lines(c)) b.append(line).append('\n');
    }
    b.append("\nArtNetMapper ").append(ArtNetMapper.VERSION).append("\n*/\n");
    return b.toString();
  }
}
