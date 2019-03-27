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

import static com.google.common.truth.Truth.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class IndexedLineReaderTest {

  private static final int BUFSIZE = 1024;
  private static final String I18N_STRING =
      "2Îñţļ3国際化4\uD84F\uDCFE\uD843\uDE6D\uD84D\uDF3F8\uD83D\uDC69\uD83C\uDFFD";

  private static final String AMPERSAND_QUOT_STRING = "\n&quot;I think therefore I am.&quot;\n";
  private static final String HEX_QUOTE_STRING = "\n&#34;I think therefore I am.&#34;\n";
  private static final String VAR_QUOTE_STRING = "\n<var>I think therefore I am.</var>\n";
  private static final String ESCAPED_QUOTE = "\"I think therefore I am.\"";

  private final char[] buf = new char[BUFSIZE];
  private final CharBuffer cb = CharBuffer.wrap(buf);
  private final StringBuilder sb = new StringBuilder();

  private IndexedLineReader reader;

  @Before
  public void setUp() throws Exception {
    cb.clear();
    sb.setLength(0);
  }

  @After
  public void tearDown() throws Exception {
    if (reader != null) {
      reader.close();
    }
  }

  @Test
  public void testEmptyStream_read() throws Exception {
    reader = readerFromString("");
    assertThat(reader.read(cb)).isEqualTo(0);
    cb.flip();
    assertThat(cb.toString()).isEmpty();
  }

  @Test
  public void testEmptyStream_readString() throws Exception {
    reader = readerFromString("");

    assertThat(reader.readString('\n', sb)).isAtMost(0);
    assertThat(sb.toString()).isEmpty();
  }

  @Test
  public void testSimpleStream_read() throws Exception {
    reader = readerFromString("Hello there!");
    assertThat(reader.read(cb)).isEqualTo(12);
    cb.flip();
    assertThat(cb.toString()).isEqualTo("Hello there!");
  }

  @Test
  public void testSimpleStream_readString() throws Exception {
    reader = readerFromString("Hello there!");

    assertThat(reader.readString('\n', sb)).isEqualTo(12);
    assertThat(sb.toString()).isEqualTo("Hello there!");
    sb.setLength(0);
    assertThat(reader.readString('\n', sb)).isLessThan(0);
    assertThat(sb.toString()).isEmpty();
  }

  @Test
  public void testNulDelimitedStream_readString() throws Exception {
    reader = readerFromString("line1\000line");

    assertThat(reader.readString('\000', sb)).isEqualTo(6);
    assertThat(sb.toString()).isEqualTo("line1");
    sb.setLength(0);
    assertThat(reader.readString('\000', sb)).isEqualTo(4);
    assertThat(sb.toString()).isEqualTo("line");
    sb.setLength(0);
    assertThat(reader.readString('\000', sb)).isLessThan(0);
    assertThat(sb.toString()).isEmpty();
  }

  @Test
  public void testI18nStream_read() throws Exception {
    reader = readerFromString(I18N_STRING);
    assertThat(reader.read(cb)).isEqualTo(I18N_STRING.length());
    cb.flip();
    assertThat(cb.toString()).isEqualTo(I18N_STRING);
  }

  @Test
  public void testI18nStream_readString() throws Exception {
    reader = readerFromString(I18N_STRING);

    assertThat(reader.readString('\n', sb)).isEqualTo(I18N_STRING.length());
    assertThat(sb.toString()).isEqualTo(I18N_STRING);
    sb.setLength(0);
    assertThat(reader.readString('\n', sb)).isLessThan(0);
    assertThat(sb.toString()).isEmpty();
  }

  @Test
  public void testAmpersandQuotStream_read() throws Exception {
    reader = readerFromString(AMPERSAND_QUOT_STRING);
    assertThat(reader.read(cb)).isEqualTo(AMPERSAND_QUOT_STRING.length());
    cb.flip();
    assertThat(cb.toString()).isEqualTo("\n" + ESCAPED_QUOTE + "\n");
  }

  @Test
  public void testAmpersandQuotStream_readString() throws Exception {
    reader = readerFromString(AMPERSAND_QUOT_STRING);

    assertThat(reader.readString('\n', sb)).isEqualTo(1);
    assertThat(sb.toString()).isEmpty();
    sb.setLength(0);
    reader.readString('\n', sb);
    assertThat(sb.toString()).isEqualTo(ESCAPED_QUOTE);
    sb.setLength(0);
    assertThat(reader.readString('\n', sb)).isLessThan(0);
    assertThat(sb.toString()).isEmpty();
  }

  @Test
  public void testHexQuoteStream_read() throws Exception {
    reader = readerFromString(HEX_QUOTE_STRING);
    assertThat(reader.read(cb)).isEqualTo(HEX_QUOTE_STRING.length());
    cb.flip();
    assertThat(cb.toString()).isEqualTo("\n" + ESCAPED_QUOTE + "\n");
  }

  @Test
  public void testHexQuoteStream_readString() throws Exception {
    reader = readerFromString(HEX_QUOTE_STRING);

    assertThat(reader.readString('\n', sb)).isEqualTo(1);
    assertThat(sb.toString()).isEmpty();
    sb.setLength(0);
    reader.readString('\n', sb);
    assertThat(sb.toString()).isEqualTo(ESCAPED_QUOTE);
    sb.setLength(0);
    assertThat(reader.readString('\n', sb)).isLessThan(0);
    assertThat(sb.toString()).isEmpty();
  }

  @Test
  public void testVarQuoteStream_read() throws Exception {
    reader = readerFromString(VAR_QUOTE_STRING);
    assertThat(reader.read(cb)).isEqualTo(VAR_QUOTE_STRING.length());
    cb.flip();
    assertThat(cb.toString()).isEqualTo("\n" + ESCAPED_QUOTE + "\n");
  }

  @Test
  public void testVarQuoteStream_readString() throws Exception {
    reader = readerFromString(VAR_QUOTE_STRING);

    assertThat(reader.readString('\n', sb)).isEqualTo(1);
    assertThat(sb.toString()).isEmpty();
    sb.setLength(0);
    reader.readString('\n', sb);
    assertThat(sb.toString()).isEqualTo(ESCAPED_QUOTE);
    sb.setLength(0);
    assertThat(reader.readString('\n', sb)).isLessThan(0);
    assertThat(sb.toString()).isEmpty();
  }

  @Test
  public void testBytes_read() throws Exception {
    byte[] bytes = new byte[128];
    for (int i = 0; i <= 127; i++) {
      bytes[i] = (byte) (i + 0x80);
    }

    reader = readerFromByteArray(bytes);
    assertThat(reader.read(cb)).isEqualTo(128);
    cb.flip();

    // Select malformed chars mapped to spaces, symbols or accented chars. Rest mapped to '?'.
    assertThat(cb.toString()).isEqualTo(
        "?????™?*????????????ö????™?????? ?????? ?©??? ®??????? *????????ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏ"
            + "ÐÑÒÓÔÕÖ×ØÙÚÛÜÝÞßàáâãäåæçèéêëìíîïðñòóôõö÷øùúûüýþÿ");
  }

  @Test
  public void testBytes_readString() throws Exception {
    byte[] bytes = new byte[128];
    for (int i = 0; i <= 127; i++) {
      bytes[i] = (byte) (i + 0x80);
    }

    reader = readerFromByteArray(bytes);
    assertThat(reader.readString('\n', sb)).isEqualTo(128);

    // Select malformed chars mapped to spaces, symbols or accented chars. Rest mapped to '?'.
    assertThat(sb.toString()).isEqualTo(
        "?????™?*????????????ö????™?????? ?????? ?©??? ®??????? *????????ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏ"
            + "ÐÑÒÓÔÕÖ×ØÙÚÛÜÝÞßàáâãäåæçèéêëìíîïðñòóôõö÷øùúûüýþÿ");
    sb.setLength(0);
    assertThat(reader.readString('\n', sb)).isLessThan(0);
  }

  private IndexedLineReader readerFromString(String text) {
    return new IndexedLineReader("test", -1, newInputStream(text));
  }

  private IndexedLineReader readerFromByteArray(byte[] bytes) {
    return new IndexedLineReader("test", -1, new ByteArrayInputStream(bytes));
  }

  private InputStream newInputStream(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }
}
