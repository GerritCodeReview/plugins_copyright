// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.copyright.lib;

import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.util.ArrayList;
import java.util.Collections;

/**
 * Class for reading character streams to scan for copyright declarations while indexing the
 * newlines for quick line number lookups.
 *
 * <p>Interprets the bytes of the input source as UTF-8 when it can. Reinterprets some non-UTF-8
 * bytes that empirically appear in or near copyrights. In many cases, these correspond to the
 * low-byte of a UTF-16 character stored as-is without requisite escaping for UTF-8. In other cases,
 * these are just characters from other arbitrary code pages.
 *
 * <p>Replaces all other non-UTF-8 (i.e. binary) bytes with '?' because it matches neither name,
 * whitespace, nor comment charactes and expresses appropriate uncertainty.
 */
public class IndexedLineReader implements Readable, Closeable {

  public static final int BUFFER_SIZE = 2048;
  private static final int INITIAL_LINES_CAPACITY = 1024;
  private static final int FALLBACK_BUFFER_SIZE = 16;

  private String name; // identifies input source
  private InputStream source; // raw data (bytes) to read
  private ByteBuffer bb; // io buffer

  private CharBuffer cb; // Decoded but unread characters.

  private int currChar; // Count of previously read chars.
  private int currLine; // Count of previously read newlines.

  private ArrayList<Integer> lineIndex; // Count of chars up to end of each line.

  private CharsetDecoder decoder; // Converts UTF-8 bytes to chars.
  private boolean atEof; // False until entire source is read.

  public int firstBinary;
  public int numBinary;

  /**
   * @param name Identifies the input source.
   * @param size Hints number of bytes in source. Use -1 if unknown.
   * @param source Input source of bytes (usually UTF-8 encoded) to scan.
   */
  public IndexedLineReader(String name, long size, InputStream source) {
    this.name = name;
    this.source = source;

    int bufferSize = size < 1 || size > BUFFER_SIZE ? BUFFER_SIZE : (int) size;
    bb = ByteBuffer.wrap(new byte[bufferSize > 8 ? bufferSize : 8]);
    bb.flip();

    cb = CharBuffer.allocate(FALLBACK_BUFFER_SIZE);
    cb.flip();

    currChar = 0;

    int initialLines =
        size < 30 || size > 30 * INITIAL_LINES_CAPACITY ? INITIAL_LINES_CAPACITY : (int) size / 30;
    lineIndex = new ArrayList<>(initialLines);
    lineIndex.add(0);

    firstBinary = -1;
    numBinary = 0;

    decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
  }

  /**
   * Attempts to read characters into the specified character buffer. The buffer is used as a
   * repository of characters as-is: the only changes made are the results of a put operation. No
   * flipping or rewinding of the buffer is performed.
   *
   * @param dest The buffer into which the read characters are put.
   * @return The number of {@code char} values added to the buffer, or -1 if this source of
   *     characters is at its end.
   * @throws IOException if an I/O error occurs
   * @throws NullPointerException if dest is null
   * @throws java.nio.ReadOnlyBufferException if dest is a read only buffer
   */
  @Override
  @SuppressWarnings("ReferenceEquality")
  public int read(CharBuffer dest) throws IOException {
    Preconditions.checkNotNull(dest);
    Preconditions.checkArgument(dest.remaining() >= 2);
    try {
      int nPrev = 0;
      if (atEof && !this.cb.hasRemaining() && !bb.hasRemaining()) {
        // At end with nothing left in the buffers -- time to indicate EOF.
        return -1;
      }
      if (!dest.hasRemaining()) {
        throw new BufferOverflowException();
      }
      int nRead = 0;
      if (this.cb.hasRemaining() && dest != this.cb) {
        // Copy the previously decoded characters (either all of them or enough to fill dest) into
        // dest.
        nPrev = Math.min(dest.remaining(), this.cb.remaining());
        dest.put(this.cb);
      }
      while (dest.hasRemaining()) {
        int oldCharOffset = dest.position() - nPrev;
        nPrev = 0;
        CoderResult cr = decoder.decode(bb, dest, atEof);
        nRead += dest.position() - oldCharOffset;
        // Scan decoded characters to index the line endings.
        for (int i = oldCharOffset; i < dest.position(); i++) {
          char c = dest.array()[dest.arrayOffset() + i];
          currChar++;
          if (c == '\n') {
            lineIndex.set(currLine, currChar);
            currLine++;
            lineIndex.add(currChar);
          } else if (c == '&') {
            if (!replaceAt(dest, i, "&quot;", '"')) {
              nRead -= cutAt(dest, i);
              return nRead;
            }
            if (!replaceAt(dest, i, "&#34;", '"')) {
              nRead -= cutAt(dest, i);
              return nRead;
            }
          } else if (c == '<') {
            if (!replaceAt(dest, i, "<var>", '"')) {
              nRead -= cutAt(dest, i);
              return nRead;
            }
            if (!replaceAt(dest, i, "</var>", '"')) {
              nRead -= cutAt(dest, i);
              return nRead;
            }
          }
          lineIndex.set(currLine, currChar);
        }
        if (cr.isUnderflow()) {  // all bytes decoded -- read more if possible.
          if (atEof) {
            break;
          }
          bb.compact();
          int n =
              (numBinary > currLine)
                  ? -1
                  : source.read(bb.array(), bb.arrayOffset() + bb.position(), bb.remaining());
          if (n > 0) {
            bb.position(bb.position() + n);
          }
          bb.flip();
          if (n < 0) {
            atEof = true;
          }
          decoder.reset();
          continue;
        } else if (cr.isOverflow()) {
          // dest filled or dest has space for 1 character, but next byte sequence to decode is a
          // surrogate pair requiring 2 characters to represent.
          if (nRead == 0) {
            // Presumably a surrogate pair -- need to buffer the un-read 2nd character of the pair.
            this.cb.clear();
            int oldPosition = bb.position();
            decoder.reset();
            cr = decoder.decode(bb, this.cb, false);
            int n = bb.position() - oldPosition;
            this.cb.flip();
            if (n == 0 || !this.cb.hasRemaining()) {
              // cr must be an error i.e. next byte not part of valid UTF-8 character.
              dest.put('?');
              bb.position(bb.position() + 1);
            } else {
              dest.put(this.cb.get());
            }
            nRead++;
          }
          break;
        } else if (cr.isError()) {
          // not valid utf-8 sequence -- binary file or other code page...
          if (firstBinary < 0) {
            firstBinary = currChar;
          }
          numBinary += cr.length();
          nRead += cr.length();
          if (!dest.hasRemaining()) {
            break;
          }
          byte b = bb.array()[bb.arrayOffset() + bb.position()];
          char c = '?'; // By default, replace binary data with '?'

          // There is no need to try to translate all binary data -- some is just binary.
          //
          // Empirically the non-UTF-8 characters below sometimes appear in or near copyrights.
          // In some cases, the file may be encoded with a different code page, or a UTF
          // character above 128 may have been stored without proper escaping. Making these
          // substitutions improves readability of extracted matches and licenses.
          //
          // The range U+00c0 to U+00ff are mostly accented characters, which require escaping in
          // UTF-8. The low-order byte sometimes appears without escaping -- perhaps this
          // corresponds to a different code page? In any case, just interpreting as chars works in
          // files that include them in copyrights, and doesn't matter when they appear in other
          // binary sequences...
          if (b >= (byte) 0xc0 && b <= (byte) 0xff) {
            c = (char) ('\u0000' | (b & 0xff));
          }
          switch (b) {
            case (byte) 0: // preserve nul character
              c = '\000';
              break;
            case (byte) 0x87: // sometimes appears where one might expect bullet
            case (byte) 0xb7: // middle-dot could be bullet -- unescaped U+00b7
              c = '*'; // treat bullets the same as comment character '*' -- ignored as whitespace
              break;
            case (byte) 0x85: // sometimes appears where one might expect (TM)
            case (byte) 0x99: // sometimes appears where one might expect (TM)
              c = '™';
              break;
            case (byte) 0xa0: // non-breaking space -- unescaped U+00a0
            case (byte) 0xa7: // section symbol -- unescapd U+00a7
            case (byte) 0xad: // soft hyphen -- unescaped U+00ad
            case (byte) 0xb6: // pilcrow or paragraph symbol -- unescaped U+00b6
              // treat as white space
              c = ' ';
              break;
            case (byte) 0xa9: // copright -- unescaped U+00a9
              c = '©';
              break;
            case (byte) 0xae: // registered -- unescaped U+00ae
              c = '®';
              break;
            case (byte) 0x94: // sometimes appears in place of ö in Björn
              c = 'ö';
              break;
          }
          dest.put(c);
          bb.position(bb.position() + 1);
          decoder.reset();
          continue;
        }
        assert false : "Unexpected CoderResult state: " + cr.toString();
      }
      return nRead;
    } catch (CharacterCodingException e) {
      throw binaryFile(e);
    } catch (IOException e) {
      throw ioException(e);
    }
  }

  /**
   * Reads a string from the file up to the next delimiter `delim` (or until eof if no delimiter)
   * appending the string to buffer `sb`.
   *
   * <p>Resulting string does not include the delimiter.
   *
   * @param delim The string delimiter. e.g. '\n' or '\000'
   * @param sb A string builder into which the string is read without the delimiter.
   * @return The number of characters read from the stream including the delimiter.
   */
  public int readString(char delim, StringBuilder sb) throws IOException {
    char[] buf = new char[FALLBACK_BUFFER_SIZE];
    CharBuffer cb = CharBuffer.wrap(buf);
    if (this.cb.hasRemaining()) {
      cb.put(this.cb);
    }
    cb.flip();
    int nRead = 0;
    int tries = 3;
    while (true) {
      while (cb.hasRemaining()) {
        char c = cb.get();
        nRead++;
        if (c == delim) {
          unput(cb);
          return nRead;
        }
        sb.append(c);
      }
      cb.clear();
      int n = read(cb);
      cb.flip();
      if (n < 0) {
        if (nRead == 0) {
          return -1;
        }
        break;
      } else if (n == 0) {
        tries--;
        if (tries < 1) {
          if (nRead == 0) {
            return -1;
          }
          break;
        }
      }
    }
    return nRead;
  }

  @Override
  public void close() throws IOException {
    source.close();
  }

  /** Returns the line number containing the given character position, `charPosn`. */
  public int getLineNumber(int charPosn) {
    int index = Collections.binarySearch(lineIndex, charPosn);
    if (index < 0) { // binarySearch returns inexact matches as negative indexes.
      index = -index - 1;
    }
    return index + 1;
  }

  /** Wrap a CharacterCodingException with a BinaryFileException describing file, line, etc. */
  private BinaryFileException binaryFile(CharacterCodingException cause) {
    int lineNumber = getLineNumber(currChar);
    int index = lineNumber - 1;
    int column = (index == 0 ? currChar : currChar - lineIndex.get(index - 1)) + 1;
    int length = 0;
    if (cause instanceof MalformedInputException) {
      MalformedInputException me = (MalformedInputException) cause;
      length = me.getInputLength();
    } else if (cause instanceof UnmappableCharacterException) {
      UnmappableCharacterException ue = (UnmappableCharacterException) cause;
      length = ue.getInputLength();
    }
    StringBuffer sb = new StringBuffer();
    sb.append(name);
    for (int i = 0; i < length; i++) {
      sb.append(String.format(" %02x", bb.array()[bb.arrayOffset() + bb.position() + i]));
    }
    return new BinaryFileException(sb.toString(), currChar, lineNumber, column, cause);
  }

  /** Wrap an IOException with a description of the current file, line number and column number. */
  private LineReaderIOException ioException(IOException cause) {
    int lineNumber = getLineNumber(currChar);
    int index = lineNumber - 1;
    int column = (index == 0 ? currChar : currChar - lineIndex.get(index)) + 1;
    return new LineReaderIOException(
        "IndexedLineReaderIOException " + cause.getMessage() + " " + name,
        currChar,
        lineNumber,
        column,
        cause);
  }


  /** Cut the current buffer `cb` at `position` putting the rest in `this.cb`. */
  @SuppressWarnings("ReferenceEquality")
  private int cutAt(CharBuffer cb, int position) {
    if (cb == this.cb) {
      throw new BufferOverflowException();
    }
    int nCut = cb.position() - position;
    this.cb.clear();
    this.cb.put(cb.array(), cb.arrayOffset() + position, nCut);
    cb.position(position);
    this.cb.flip();
    return nCut;
  }

  /** Save the remaining characters from `cb` onto `this.cb` for later. */
  private void unput(CharBuffer cb) {
    if (!this.cb.hasRemaining()) {
      this.cb.clear();
      this.cb.put(cb);
      this.cb.flip();
      return;
    }
    // Shift `this.cb` and prepend `cb`
    int len = cb.remaining();
    if (this.cb.limit() + len > this.cb.capacity()) {
      throw new BufferOverflowException();
    }
    this.cb.limit(this.cb.limit() + len);
    for (int i = this.cb.limit() - len - 1; i >= this.cb.position(); i--) {
      this.cb.put(i + len, this.cb.get(i));
    }
    for (int i = 0; i < len; i++) {
      this.cb.put(this.cb.position() + i, cb.get());
    }
  }

  /** Conditionally replaces `prefix` when found at `position` in `cb` with `replacement` char. */
  private static boolean replaceAt(CharBuffer cb, int position, String prefix, char replacement) {
    for (int i = 0; i < prefix.length(); i++) {
      if (position + i >= cb.position()) {
        return false;
      }
      if (cb.get(position + i) != prefix.charAt(i)) {
        return true;
      }
    }
    cb.put(position, replacement);
    int dst = position + 1;
    int src = position + prefix.length();
    while (src < cb.position()) {
      cb.put(dst, cb.get(src));
      src++;
      dst++;
    }
    cb.position(dst);
    return true;
  }

  /** Describes an IO error at a specific location in a file. */
  public static class LineReaderIOException extends IOException {
    private int charPosn;
    private int lineNumber;
    private int column;

    LineReaderIOException(
        String message,
        int charPosn,
        int lineNumber,
        int column,
        Throwable cause) {
      super(message, cause);
      this.charPosn = charPosn;
      this.lineNumber = lineNumber;
      this.column = column;
    }

    @Override
    public String getMessage() {
      StringBuffer m = new StringBuffer();
      m.append(super.getMessage())
          .append(" line ")
          .append(lineNumber)
          .append(" col ")
          .append(column)
          .append(" offset ")
          .append(charPosn);
      return m.toString();
    }
  }

  /** Thrown when a binary file is detected. */
  public static class BinaryFileException extends LineReaderIOException {
    BinaryFileException(
        String fileName,
        int charPosn,
        int lineNumber,
        int column,
        Throwable cause) {
      super("Binary file: " + fileName, charPosn, lineNumber, column, cause);
    }
  }
}
