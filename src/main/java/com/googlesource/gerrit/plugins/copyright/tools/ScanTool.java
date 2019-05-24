// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//  http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.copyright.tools;

import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.googlesource.gerrit.plugins.copyright.lib.Archive;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightPatterns;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.Match;
import com.googlesource.gerrit.plugins.copyright.lib.IndexedLineReader;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2Utils;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipUtils;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;
import org.apache.commons.compress.compressors.lzma.LZMAUtils;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZUtils;

/** Command-line tool to scan files for copyright or license notices. */
public class ScanTool {

  public static String toolName = "scan_tool";

  public static CopyrightPatterns.RuleSet rules =
      CopyrightPatterns.RuleSet.builder().addFirstParty("EXAMPLES").build();

  // Flag -f=<inputFile>
  private static String inputFile = "";

  // Flag --deep
  private static boolean deepScan = false;

  // Flag --skip=<pathPattern> (multiple allowed)
  private static List<String> skipFiles = ImmutableList.of("[/][.]git[/]");

  // Flag -0
  private static boolean nulDelim = false;

  // Flag -v or --verbose
  private static boolean verbose = false;

  private static final Pattern flagsPattern =
      Pattern.compile("^[-][-]*(deep|skip(?:=.*)?|0|v(?:erbose)?|f(?:[-]|=.*))$");

  private static final int CAPACITY = 32768; // how much memory to set aside for pending results
  private static final int NUM_THREADS = 32; // amount of concurrency
  private static final int MAX_DEPTH = 10; // how deep to go into archives containing archives etc.

  private static WaitGroup wg;
  private static long maxLatencyUs;
  private static String maxLatencyName;

  public static void usage() {
    System.err.printf(
        "%s <flags> {file-to-scan...}\n  where flags are:\n"
            + "    -f=<filename>      file named `filename` contains the list of files to scan\n"
            + "                       use - as filename for list of filse from stdin\n"
            + "    --deep             scan files contained in archives (.zip, .jar etc.)\n"
            + "    --skip=<pattern>   ignore file with names matching `pattern`\n"
            + "                       defaults to \"[/][.]git[/]\"\n"
            + "                       --skip flag may appear multiple times\n"
            + "    -v (or --verbose)  output additional progress and status to err\n"
            + "    -0                 with -f to use nul instead of newline to separate files\n",
        toolName);
    System.exit(1);
  }

  public static void main(String[] args) throws IOException {
    Stopwatch entireSw = Stopwatch.createStarted();

    ArrayList<String> skips = new ArrayList<>();
    boolean hasSkips = false;
    ArrayList<String> targets = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      Matcher flagMatcher = flagsPattern.matcher(arg);
      if (flagMatcher.matches()) {
        String flag = flagMatcher.group(1);
        if ("deep".equals(flag)) {
          if (deepScan) {
            usage();
          }
          deepScan = true;
        } else if ("0".equals(flag)) {
          if (nulDelim) {
            usage();
          }
          nulDelim = true;
        } else if ("v".equals(flag) || "verbose".equals(flag)) {
          if (verbose) {
            usage();
          }
          verbose = true;
        } else if ("skip".equals(flag)) {
          if (++i >= args.length) {
            usage();
          }
          skips.add(args[i]);
          hasSkips = true;
        } else if (flag.startsWith("skip=")) {
          hasSkips = true;
          flag = flag.substring(5);
          if (!flag.isEmpty()) {
            skips.add(flag);
          }
        } else if ("f".equals(flag)) {
          if (++i >= args.length || !inputFile.equals("")) {
            usage();
          }
          inputFile = args[i];
        } else if ("f-".equals(flag)) {
          if (!inputFile.equals("")) {
            usage();
          }
          inputFile = "-";
        } else if (flag.startsWith("f=")) {
          if (!inputFile.equals("")) {
            usage();
          }
          inputFile = flag.substring(2);
        } else {
          usage();
        }
        continue;
      }
      targets.add(args[i]);
    }
    if (hasSkips) {
      skipFiles = ImmutableList.copyOf(skips);
    }
    long numFiles = 0;
    CopyrightScanner s =
        new CopyrightScanner(
            rules.firstPartyLicenses,
            rules.thirdPartyLicenses,
            rules.forbiddenLicenses,
            rules.firstPartyOwners,
            rules.thirdPartyOwners,
            rules.forbiddenOwners,
            rules.excludePatterns);
    ExecutorService pool = Executors.newFixedThreadPool(NUM_THREADS);
    wg = new WaitGroup();
    if (inputFile.isEmpty()) { // each command-line argument is a file to scan
      if (targets.isEmpty()) {
        usage();
        System.exit(1);
      }
      for (String target : targets) {
        wg.startTask(target);
        pool.execute(new ScanFile(pool, s, target));
        numFiles++;
      }
    } else { // inputFile lists files to scan -- 1 per line. (use stdin if "-")
      ArrayList<Pattern> skipPatterns = new ArrayList<>(skipFiles.size());
      for (String pattern : skipFiles) {
        skipPatterns.add(Pattern.compile(pattern));
      }
      if (verbose) {
        for (Pattern p : skipPatterns) {
          System.err.printf("Skip=%s\n", p.pattern());
        }
      }
      IndexedLineReader ifr =
          inputFile.trim().equals("-")
              ? new IndexedLineReader("-", -1, System.in)
              : new IndexedLineReader(
                  inputFile.trim(),
                  Paths.get(inputFile.trim()).toFile().length(),
                  new FileInputStream(inputFile.trim()));
      char delim = nulDelim ? '\000' : '\n';
      StringBuilder sb = new StringBuilder();
      while (true) {
        int nRead = ifr.readString(delim, sb);
        if (nRead < 0) {
          break;
        }
        String line = sb.toString();
        sb.setLength(0);
        boolean skip = false;
        for (Pattern p : skipPatterns) {
          if (p.matcher(line).find()) {
            skip = true;
            break;
          }
        }
        if (skip) {
          continue;
        }
        wg.startTask(line);
        pool.execute(new ScanFile(pool, s, line));
        numFiles++;
        if ((numFiles & 0xffL) == 0) { // lots of files -- at least 256
          // Poll and drain any accumulating results.
          wg.processResultsAndReturnRemaining();
        }
      }
    }
    // Poll the results until done.
    while (wg.processResultsAndReturnRemaining() > 0) {
      try {
        Thread.sleep(60); // Faster than the blink of an eye -- or a screen refresh.
      } catch (InterruptedException e) {
        pool.shutdownNow();
        Thread.currentThread().interrupt();
        break;
      }
    }
    entireSw.stop();
    if (verbose) {
      long elapsedS = entireSw.elapsed(TimeUnit.SECONDS);
      System.err.printf(
          "High water results: %d\nHigh water errors: %d\n", wg.highResults, wg.highErrors);
      if (elapsedS > 1) {
        System.err.printf(
            "%d files in %ds -- %d files per second\n", numFiles, elapsedS, numFiles / elapsedS);
      } else {
        System.err.printf(
            "%d files in %dms -- %d files per s\n",
            numFiles,
            entireSw.elapsed(TimeUnit.MILLISECONDS),
            (numFiles * 1000) / entireSw.elapsed(TimeUnit.MILLISECONDS));
      }
      System.err.printf("Max latency: %dus %s\n", maxLatencyUs, maxLatencyName);
    }
    System.exit(wg.highErrors == 0 ? 0 : 2);
  }

  /* Runnable task that scans a file looking for copyright, authorship or license declarations. */
  private static class ScanFile implements Runnable {
    ExecutorService pool;
    CopyrightScanner s;
    ArrayList<String> fileNames;

    int firstBinary = -1;
    int numBinary = 0;

    private ScanFile(ExecutorService pool, CopyrightScanner s, String fileName) {
      this.pool = pool;
      this.s = s;
      this.fileNames = deepScan ? new ArrayList<>(MAX_DEPTH) : new ArrayList<>(1);
      this.fileNames.add(fileName);
    }

    @Override
    public void run() {
      Stopwatch sw = Stopwatch.createStarted();
      long size = 0;
      try {
        Path p = Paths.get(fileNames.get(0));
        size = p.toFile().length();
        try (InputStream source = Files.newInputStream(Paths.get(fileNames.get(0)))) {
          scan(s, fileNames.get(0), size, source);
        }
      } catch (Exception e) {
        wg.addError(new ScanError(fileNames.toArray(new String[0]), e));
      } finally {
        wg.finishTask(fileNames.get(0));
        if (sw.isRunning()) {
          sw.stop();
        }
        if (sw.elapsed(TimeUnit.MICROSECONDS) > maxLatencyUs) {
          maxLatencyName = fileNames.get(0);
          maxLatencyUs = sw.elapsed(TimeUnit.MICROSECONDS);
        }
        while (fileNames.size() > 1) {
          popName();
        }
        if (verbose) {
          System.err.printf(
              "%d %d %d %d %s\n",
              sw.elapsed(TimeUnit.MICROSECONDS), size, firstBinary, numBinary, formatFn());
        }
      }
    }

    /* Scan a possibly compressed, possibly embedded file. */
    private void scan(CopyrightScanner s, String fileName, long size, InputStream source)
        throws IOException, ArchiveException {
      String rawFileName = fileName;
      boolean isArchive = deepScan && this.fileNames.size() < MAX_DEPTH;
      InputStream newSource = null;
      BufferedInputStream bufferedSource = null;
      try {
        try {
          if (BZip2Utils.isCompressedFilename(fileName)) {
            newSource = new BZip2CompressorInputStream(source);
            rawFileName = BZip2Utils.getUncompressedFilename(fileName);
          } else if (GzipUtils.isCompressedFilename(fileName)) {
            newSource = new GzipCompressorInputStream(source);
            rawFileName = GzipUtils.getUncompressedFilename(fileName);
          } else if (LZMAUtils.isLZMACompressionAvailable()
              && LZMAUtils.isCompressedFilename(fileName)) {
            newSource = new LZMACompressorInputStream(source);
            rawFileName = LZMAUtils.getUncompressedFilename(fileName);
          } else if (XZUtils.isXZCompressionAvailable() && XZUtils.isCompressedFilename(fileName)) {
            newSource = new XZCompressorInputStream(source);
            rawFileName = XZUtils.getUncompressedFilename(fileName);
          }
        } catch (Exception ignored) {
          newSource = null;
          rawFileName = fileName;
        }
        bufferedSource = new BufferedInputStream(newSource == null ? source : newSource);
        bufferedSource.mark(IndexedLineReader.BUFFER_SIZE);
        if (!isArchive) {
          scanText(s, rawFileName, size, bufferedSource);
        } else {
          Archive archive = Archive.getArchive(rawFileName);
          if (archive == null) {
            isArchive = false;
            scanText(s, rawFileName, size, bufferedSource);
          } else {
            scanArchive(s, archive, rawFileName, bufferedSource);
          }
        }
      } catch (IOException e) {
        if (isArchive && bufferedSource.markSupported()) {
          bufferedSource.reset();
          scanText(s, rawFileName, size, bufferedSource);
        } else if (newSource != null) {
          scanText(s, fileName, size, source);
        } else {
          throw e;
        }
      } catch (Exception e) {
        if (isArchive && bufferedSource.markSupported()) {
          bufferedSource.reset();
          scanText(s, rawFileName, size, bufferedSource);
        } else if (newSource != null) {
          scanText(s, fileName, size, source);
        } else {
          throw e;
        }
      }
    }

    /* Scan a file as a regular, non-archive file. */
    private void scanText(CopyrightScanner s, String fileName, long size, InputStream source)
        throws IOException {
      Stopwatch sw = Stopwatch.createStarted();
      IndexedLineReader lr = new IndexedLineReader(fileName, size, source);
      try {
        ImmutableList<Match> matches = s.findMatches(fileName, size, lr);
        sw.stop();
        if (!matches.isEmpty()) {
          wg.addResult(
              new Result(
                  this.fileNames.toArray(new String[0]),
                  size,
                  matches,
                  sw.elapsed(TimeUnit.MICROSECONDS)));
        }
      } finally {
        if (sw.isRunning()) {
          sw.stop();
        }
        if (lr.firstBinary >= 0 && (firstBinary < 0 || lr.firstBinary < firstBinary)) {
          firstBinary = lr.firstBinary;
        }
        numBinary += lr.numBinary;
      }
    }

    /* Scan the files contained in an archive file. e.g. .zip, .tar, .jar etc. */
    private void scanArchive(
        CopyrightScanner s, Archive archive, String fileName, BufferedInputStream source)
        throws IOException, ArchiveException {
      assert deepScan : "Must be deep scan to look inside archive file " + fileName;
      int originalDepth = this.fileNames.size();
      try {
        ArchiveInputStream af = archive.newStream(source);
        ArchiveEntry entry = archive.getNext(af);
        int numTries = 3;
        while (entry != null) {
          if (archive.isRegularFile(entry)) {
            String name = cleanName(entry.getName());
            pushName(name);
            scan(s, name, entry.getSize(), (InputStream) af);
            popName();
          }
          // After at least 1 entry scans without error, ignore a limited number of bad entries.
          try {
            entry = archive.getNext(af);
          } catch (IOException e) {
            numTries--;
            if (numTries < 1) {
              throw e;
            }
          }
        }
      } finally {
        while (this.fileNames.size() > originalDepth) {
          this.fileNames.remove(this.fileNames.size() - 1);
        }
      }
    }

    /** Add another embedded filename to the stack. */
    private void pushName(String name) {
      fileNames.add(name);
    }

    /** Remove the deepest nested filename from the stack. */
    private void popName() {
      fileNames.remove(fileNames.size() - 1);
    }

    /** Remove unexpected characters from embedded filenames. */
    private String cleanName(String name) {
      return Pattern.compile(
              "[^\\p{L}\\p{N}\\p{P}\\p{S}\\s].*[^\\p{L}\\p{N}\\p{P}\\p{S}\\s]",
              Pattern.MULTILINE | Pattern.UNICODE_CASE | Pattern.DOTALL)
          .matcher(name.replaceAll("^[^\\p{L}\\p{N}\\p{P}\\p{S}\\s]+", "_BINARY_"))
          .replaceAll("_BINARY_");
    }

    /** Format filenames urlencoding whitespace and appending containing file in &lt;&gt; */
    private String formatFn() {
      StringBuffer sb = new StringBuffer();
      for (int i = fileNames.size() - 1; i > 0; i--) {
        sb.append(fileNames.get(i)).append('<');
      }
      sb.append(fileNames.get(0));
      if (fileNames.size() > 1) {
        sb.append(Strings.repeat(">", fileNames.size() - 1));
      }
      return sb.toString()
          .replaceAll("[%]", "%37")
          .replaceAll("[ ]", "%20")
          .replaceAll("[\\r]", "%0D")
          .replaceAll("[\\n]", "%0A")
          .replaceAll("[\\t]", "%09");
    }
  }

  /** Format filenames urlencoding whitespace and appending containing file in &lt;&gt; */
  private static String formatFilenames(String[] fileNames) {
    assert fileNames.length > 0 : "Root file required.";
    StringBuffer sb = new StringBuffer();
    for (int i = fileNames.length - 1; i > 0; i--) {
      sb.append(fileNames[i]).append('<');
    }
    sb.append(fileNames[0]);
    if (fileNames.length > 1) {
      sb.append(Strings.repeat(">", fileNames.length - 1));
    }
    return sb.toString()
        .replaceAll("[%]", "%37")
        .replaceAll("[ ]", "%20")
        .replaceAll("[\\r]", "%0D")
        .replaceAll("[\\n]", "%0A")
        .replaceAll("[\\t]", "%09");
  }

  private static class Result {
    String[] fileName;
    long size;
    ImmutableList<Match> matches;
    long elapsedUs;

    private Result(String[] fileName, long size, ImmutableList<Match> matches, long elapsedUs) {
      this.fileName = fileName;
      this.size = size;
      this.matches = matches;
      this.elapsedUs = elapsedUs;
    }
  }

  private static class ScanError {
    String[] fileName;
    Throwable e;

    private ScanError(String[] fileName, Throwable e) {
      this.fileName = fileName;
      this.e = e;
    }
  }

  /** Synchronizes scanning (i.e. child) and reading (i.e. main) tasks. */
  private static class WaitGroup {
    public HashMap<String, Integer> tasks; // finished when becomes empty again.
    public ArrayList<Result> results; // accumulates results to be read; guarded by this
    public ArrayList<ScanError> errors; // accumulates errors to be read; guarded by this

    // To keep the critical section short, the main reader thread keeps 2 copies of results and
    // errors. In the critical section, it swaps the output references with `next` references while
    // it drains the prior output outside of the critical section. The members below are manipulated
    // by a single thread (the main reader thread) and do not require inter-thread synchronization.
    private ArrayList<Result> nextResults; // referenced only by main thread -- swaps with results
    private ArrayList<ScanError> nextErrors; // referenced only by main thread -- swaps with errors
    private int highResults; // Maximum observed size of the results list.
    private int highErrors; // Maximum observed size of the errors list.

    private WaitGroup() {
      tasks = new HashMap<>();
      results = new ArrayList<>(CAPACITY);
      nextResults = new ArrayList<>(CAPACITY);
      errors = new ArrayList<>(16);
      nextErrors = new ArrayList<>(16);
      highResults = 0;
      highErrors = 0;
    }

    // Call once for every new scan task created. */
    private synchronized void startTask(String name) {
      if (tasks.containsKey(name)) {
        tasks.put(name, tasks.get(name) + 1);
      } else {
        tasks.put(name, 1);
      }
    }

    // Call once per scan task after task completed. */
    private synchronized void finishTask(String name) {
      if (tasks.get(name) == 1) {
        tasks.remove(name);
      } else {
        tasks.put(name, tasks.get(name) - 1);
      }
    }

    // Append a Result to `results`.
    private synchronized void addResult(Result result) {
      results.add(result);
    }

    // Append a ScanError to `errors`.
    private synchronized void addError(ScanError e) {
      errors.add(e);
    }

    /* Process all available results and return the count of unfinished tasks. */
    private int processResultsAndReturnRemaining() {
      assert nextResults != null && nextResults.isEmpty();
      assert nextErrors != null && nextErrors.isEmpty();
      ArrayList<Result> currentResults = null;
      ArrayList<ScanError> currentErrors = null;
      int numRunning = 0;
      synchronized (this) {
        if (!this.results.isEmpty()) {
          currentResults = results;
          results = nextResults;
          nextResults = null;
        }
        if (!this.errors.isEmpty()) {
          currentErrors = errors;
          errors = nextErrors;
          nextErrors = null;
        }
        numRunning = tasks.size();
      }
      if (currentResults != null) {
        assert nextResults == null;
        if (currentResults.size() > highResults) {
          highResults = currentResults.size();
        }
        for (Result result : currentResults) {
          for (Match match : result.matches) {
            System.out.printf(
                "%s %s [%d,%d) [%d,%d) %d %dus %d %s %s\n",
                match.partyType.name(),
                match.matchType.name(),
                match.startLine,
                match.endLine,
                match.start,
                match.end,
                match.end - match.start,
                result.elapsedUs,
                result.size,
                formatFilenames(result.fileName),
                match.text);
          }
        }
        currentResults.clear();
        nextResults = currentResults;
        currentResults = null;
      }
      if (currentErrors != null) {
        assert nextErrors == null;
        if (currentErrors.size() > highErrors) {
          highErrors = currentErrors.size();
        }
        for (ScanError error : currentErrors) {
          System.err.printf(
              "Error scanning %s: %s\n", formatFilenames(error.fileName), error.e.getMessage());
          error.e.printStackTrace(System.err);
        }
        currentErrors.clear();
        nextErrors = currentErrors;
        currentErrors = null;
      }
      assert nextResults != null && nextResults.isEmpty();
      assert nextErrors != null && nextErrors.isEmpty();
      return numRunning;
    }
  }
}
