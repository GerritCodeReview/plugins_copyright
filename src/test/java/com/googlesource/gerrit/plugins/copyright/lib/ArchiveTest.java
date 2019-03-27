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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class ArchiveTest {
  @Test
  public void testGetArchive_NotAnArchive() {
    String[] filenames = {"output.txt", "picture.jpg", "source.c", "header.h", "Class.java"};

    for (String filename : filenames) {
      assertThat(Archive.getArchive(filename)).isNull();
    }
  }

  @Test
  public void testGetArchive_Archive() {
    String[] filenames = {
      "compressed.zip",
      "ball.tar",
      "release.apk",
      "library.jar",
      "archive.ar",
      "archive.arj",
      "files.dump",
      "r2d2.cpio",
      "ms.docx",
      "open.odt"
    };

    for (String filename : filenames) {
      Archive archive = Archive.getArchive(filename);
      assertThat(archive).isNotNull();
      assertThat(archive.isArchive(filename)).isTrue();
      assertThat(archive.isArchive("output.txt")).isFalse();
    }
  }
}
