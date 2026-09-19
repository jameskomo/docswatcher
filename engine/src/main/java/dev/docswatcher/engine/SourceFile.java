package dev.docswatcher.engine;

import java.nio.charset.StandardCharsets;

/** A text file under scan with precomputed line offsets, in char units. */
final class SourceFile {

  final String path;
  final String text;
  private final int[] lineStarts;
  private byte[] utf8;
  private int[] charStartByte;

  SourceFile(String path, String text) {
    this.path = path;
    this.text = text;
    int lines = 1;
    for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lines++;
    lineStarts = new int[lines];
    int n = 0;
    lineStarts[n++] = 0;
    for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') lineStarts[n++] = i + 1;
  }

  byte[] utf8() {
    if (utf8 == null) utf8 = text.getBytes(StandardCharsets.UTF_8);
    return utf8;
  }

  /** 1-based line for a char offset. */
  int lineAt(int charOffset) {
    int lo = 0;
    int hi = lineStarts.length - 1;
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      if (lineStarts[mid] <= charOffset) lo = mid;
      else hi = mid - 1;
    }
    return lo + 1;
  }

  /** 1-based column for a char offset. */
  int columnAt(int charOffset) {
    return charOffset - lineStarts[lineAt(charOffset) - 1] + 1;
  }

  /** The full line containing the offset, trimmed and capped at 200 chars. */
  String snippetAt(int charOffset) {
    int line = lineAt(charOffset) - 1;
    int start = lineStarts[line];
    int end = line + 1 < lineStarts.length ? lineStarts[line + 1] - 1 : text.length();
    String s = text.substring(start, end).strip();
    return s.length() > 200 ? s.substring(0, 200) : s;
  }

  /** Char offset for a UTF-8 byte offset. */
  int charOffsetForByte(int byteOffset) {
    byte[] b = utf8();
    if (b.length == text.length()) return byteOffset;
    if (charStartByte == null) {
      charStartByte = new int[text.length() + 1];
      int bytePos = 0;
      for (int i = 0; i < text.length(); i++) {
        charStartByte[i] = bytePos;
        char c = text.charAt(i);
        if (c < 0x80) bytePos += 1;
        else if (c < 0x800) bytePos += 2;
        else if (Character.isHighSurrogate(c)) {
          bytePos += 4;
          i++;
          if (i < text.length()) charStartByte[i] = bytePos;
        } else bytePos += 3;
      }
      charStartByte[text.length()] = bytePos;
    }
    int lo = 0;
    int hi = text.length();
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      if (charStartByte[mid] <= byteOffset) lo = mid;
      else hi = mid - 1;
    }
    return lo;
  }
}
