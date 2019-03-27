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

import com.google.common.collect.ImmutableList;
import java.io.BufferedInputStream;
import java.io.IOException;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.arj.ArjArchiveInputStream;
import org.apache.commons.compress.archivers.cpio.CpioArchiveEntry;
import org.apache.commons.compress.archivers.cpio.CpioArchiveInputStream;
import org.apache.commons.compress.archivers.dump.DumpArchiveEntry;
import org.apache.commons.compress.archivers.dump.DumpArchiveInputStream;
import org.apache.commons.compress.archivers.jar.JarArchiveEntry;
import org.apache.commons.compress.archivers.jar.JarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;

/** Encapsulates the differences among the known ArchiveInputStream/ArchiveEntry pairs. */
public abstract class Archive {

  /** The know archive file types. */
  private static final ImmutableList<Archive> archives =
      ImmutableList.of(
          new ArFile(),
          new ArjFile(),
          new CpioFile(),
          new DumpFile(),
          new JarFile(),
          new TarFile(),
          new ZipFile());

  /** Returns the Archive to use based on `fileName` or null if not a known archive file format. */
  public static Archive getArchive(String fileName) {
    for (Archive archive : archives) {
      if (archive.isArchive(fileName)) {
        return archive;
      }
    }
    return null;
  }

  /** Returns true if `fileName` identifies an instance of the archive file type. */
  protected abstract boolean isArchive(String fileName);

  /** Wraps `source` with the `ArchiveInputStream` type for the archive file type. */
  public abstract ArchiveInputStream newStream(BufferedInputStream source) throws ArchiveException;

  /** Returns the next `ArchiveEntry` in `archive` for the archive file type. */
  public abstract ArchiveEntry getNext(ArchiveInputStream archive)
      throws ArchiveException, IOException;

  /** Returns true if `entry` describes a regular file in the archive. */
  public abstract boolean isRegularFile(ArchiveEntry entry);

  /** Archives created with the `ar` command or equivalent. */
  private static final class ArFile extends Archive {
    @Override
    protected boolean isArchive(String fileName) {
      return fileName.endsWith(".a")
          || fileName.endsWith(".deb")
          || fileName.endsWith(".ar")
          || fileName.endsWith("-ar")
          || fileName.endsWith("-deb")
          || fileName.endsWith("-a");
    }

    @Override
    public ArchiveInputStream newStream(BufferedInputStream source) throws ArchiveException {
      return new ArArchiveInputStream(source);
    }

    @Override
    public ArchiveEntry getNext(ArchiveInputStream archive) throws ArchiveException, IOException {
      return ((ArArchiveInputStream) archive).getNextArEntry();
    }

    @Override
    public boolean isRegularFile(ArchiveEntry entry) {
      return !entry.isDirectory();
    }
  }

  /** Archives created with the `arj` command. */
  private static final class ArjFile extends Archive {
    @Override
    protected boolean isArchive(String fileName) {
      return fileName.endsWith(".arj");
    }

    @Override
    public ArchiveInputStream newStream(BufferedInputStream source) throws ArchiveException {
      return new ArjArchiveInputStream(source);
    }

    @Override
    public ArchiveEntry getNext(ArchiveInputStream archive) throws ArchiveException, IOException {
      return ((ArjArchiveInputStream) archive).getNextEntry();
    }

    @Override
    public boolean isRegularFile(ArchiveEntry entry) {
      return !entry.isDirectory();
    }
  }

  /** Archives created with the `cpio` command. */
  private static final class CpioFile extends Archive {
    @Override
    protected boolean isArchive(String fileName) {
      return fileName.endsWith(".cpio");
    }

    @Override
    public ArchiveInputStream newStream(BufferedInputStream source) throws ArchiveException {
      return new CpioArchiveInputStream(source);
    }

    @Override
    public ArchiveEntry getNext(ArchiveInputStream archive) throws ArchiveException, IOException {
      return ((CpioArchiveInputStream) archive).getNextCPIOEntry();
    }

    @Override
    public boolean isRegularFile(ArchiveEntry entry) {
      return ((CpioArchiveEntry) entry).isRegularFile();
    }
  }

  /** Archives created with the `dump` command. */
  private static final class DumpFile extends Archive {
    @Override
    protected boolean isArchive(String fileName) {
      return fileName.endsWith(".dump") || fileName.endsWith(".dmp");
    }

    @Override
    public ArchiveInputStream newStream(BufferedInputStream source) throws ArchiveException {
      return new DumpArchiveInputStream(source);
    }

    @Override
    public ArchiveEntry getNext(ArchiveInputStream archive) throws ArchiveException, IOException {
      return ((DumpArchiveInputStream) archive).getNextDumpEntry();
    }

    @Override
    public boolean isRegularFile(ArchiveEntry entry) {
      return ((DumpArchiveEntry) entry).isFile();
    }
  }

  /** Java archives and equivalents. Internally structured as special cases of zip files. */
  private static final class JarFile extends Archive {
    @Override
    protected boolean isArchive(String fileName) {
      return fileName.endsWith(".jar")
          || fileName.endsWith(".aar")
          || fileName.endsWith(".apk")
          || fileName.endsWith(".apex")
          || fileName.endsWith(".war")
          || fileName.endsWith(".rar")
          || fileName.endsWith(".ear")
          || fileName.endsWith(".sar")
          || fileName.endsWith(".par")
          || fileName.endsWith(".kar")
          || fileName.endsWith("-jar");
    }

    @Override
    public ArchiveInputStream newStream(BufferedInputStream source) throws ArchiveException {
      return new JarArchiveInputStream(source);
    }

    @Override
    public ArchiveEntry getNext(ArchiveInputStream archive) throws ArchiveException, IOException {
      return ((JarArchiveInputStream) archive).getNextJarEntry();
    }

    @Override
    public boolean isRegularFile(ArchiveEntry entry) {
      JarArchiveEntry e = (JarArchiveEntry) entry;
      return !e.isDirectory() && !e.isUnixSymlink();
    }
  }

  /** Archives created with the `tar` command or equivalent. */
  private static final class TarFile extends Archive {
    @Override
    protected boolean isArchive(String fileName) {
      return fileName.endsWith(".tar") || fileName.endsWith("-tar") || fileName.endsWith(".pax");
    }

    @Override
    public ArchiveInputStream newStream(BufferedInputStream source) throws ArchiveException {
      return new TarArchiveInputStream(source);
    }

    @Override
    public ArchiveEntry getNext(ArchiveInputStream archive) throws ArchiveException, IOException {
      return ((TarArchiveInputStream) archive).getNextTarEntry();
    }

    @Override
    public boolean isRegularFile(ArchiveEntry entry) {
      return ((TarArchiveEntry) entry).isFile();
    }
  }

  /**
   * Archives created with the `zip` command or equivalent.
   *
   * <p>Many standard file types are internally structured as zip files.
   */
  private static final class ZipFile extends Archive {
    @Override
    protected boolean isArchive(String fileName) {
      return fileName.endsWith(".zip")
          || fileName.endsWith(".ZIP")
          || fileName.endsWith("-zip")
          || fileName.endsWith("-ZIP")
          || fileName.endsWith(".sfx")
          || fileName.endsWith(".docx")
          || fileName.endsWith(".docm")
          || fileName.endsWith(".xlsx")
          || fileName.endsWith(".xlsm")
          || fileName.endsWith(".pptx")
          || fileName.endsWith(".pptm")
          || fileName.endsWith(".odf")
          || fileName.endsWith(".odt")
          || fileName.endsWith(".odp")
          || fileName.endsWith(".ods")
          || fileName.endsWith(".odg");
    }

    @Override
    public ArchiveInputStream newStream(BufferedInputStream source) throws ArchiveException {
      return new ZipArchiveInputStream(source);
    }

    @Override
    public ArchiveEntry getNext(ArchiveInputStream archive) throws ArchiveException, IOException {
      return ((ZipArchiveInputStream) archive).getNextZipEntry();
    }

    @Override
    public boolean isRegularFile(ArchiveEntry entry) {
      ZipArchiveEntry e = (ZipArchiveEntry) entry;
      return !e.isDirectory() && !e.isUnixSymlink();
    }
  }
}
