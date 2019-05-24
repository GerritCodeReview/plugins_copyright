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

package com.googlesource.gerrit.plugins.copyright;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.common.hash.Hashing;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.gerrit.server.git.validators.ValidationMessage;
import com.googlesource.gerrit.plugins.copyright.lib.IndexedLineReader;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;

/**
 * Utility for verifying copyright plugin configurations.
 *
 * <p>{@code main} implements a command-line tool for measuring performance against a large test
 * file constructed to trigger excessive backtracking.
 */
public class CheckConfig {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String toolName = "check_new_config";

  private static final String ACCESS = "access";
  private static final String LABEL = "label";
  private static final String PLUGIN = "plugin";
  private static final int BUFFER_SIZE = 2048;

  private String pluginName;
  /** All-Projects project.config contents. */
  private Config configProject;
  /** Plugin config from All-Projects project.config file. */
  ScannerConfig scannerConfig;

  public CheckConfig(String pluginName, String projectConfigContents)
      throws ConfigInvalidException {
    this.pluginName = pluginName;

    configProject = new Config();
    configProject.fromText(projectConfigContents);
    Config config = new Config();
    for (String name : configProject.getNames(PLUGIN, pluginName)) {
      config.setStringList(
          PLUGIN,
          pluginName,
          name,
          Arrays.asList(configProject.getStringList(PLUGIN, pluginName, name)));
    }
    PluginConfig pluginConfig = new PluginConfig(pluginName, config);
    this.scannerConfig = new ScannerConfig(pluginName);
    this.scannerConfig.readConfigFile(pluginConfig);
  }

  /**
   * Validates the final state of {@code trialConfig}.
   *
   * <p>Uses {@code reviewApi}, when given, to verify account information. When {@code
   * pluginEnabled} is false, treats errors as warnings.
   */
  public static void checkProjectConfig(
      CopyrightReviewApi reviewApi, boolean pluginEnabled, CheckConfig trialConfig) {
    // Warn without blocking project.config pushes when plugin disabled across entire server.
    ValidationMessage.Type errorWhenActive =
        pluginEnabled ? ValidationMessage.Type.ERROR : ValidationType.WARNING;
    CurrentUser fromUser =
        reviewApi == null
            ? null
            : reviewApi.getSendingUser(trialConfig.scannerConfig.fromAccountId);
    if (Strings.nullToEmpty(trialConfig.scannerConfig.reviewLabel).trim().isEmpty()) {
      trialConfig.scannerConfig.messages.add(
          new CommitValidationMessage(
              trialConfig.scannerConfig.pluginKeyRequired(
                  ScannerConfig.KEY_REVIEW_LABEL,
                  "please use \""
                      + ScannerConfig.KEY_REVIEW_LABEL
                      + " = <label name>\" to identify the label "
                      + (trialConfig.scannerConfig.fromAccountId < 1 || fromUser == null
                          ? "the plugin"
                          : fromUser.getLoggableName())
                      + " will vote on"),
              errorWhenActive));
    } else {
      String labelName = trialConfig.scannerConfig.reviewLabel.trim();
      if (!trialConfig.configProject.getSubsections(LABEL).contains(labelName)) {
        trialConfig.scannerConfig.messages.add(
            new CommitValidationMessage(
                trialConfig.scannerConfig.pluginKeyValueMessage(
                    ScannerConfig.KEY_REVIEW_LABEL,
                    labelName,
                    "no [" + LABEL + " \"" + labelName + "\"] section configured."),
                errorWhenActive));
      }

      // Enforce at least 1 approver exists for the copyright review label for content changes.
      String[] voters =
          trialConfig.configProject.getStringList(
              ACCESS, RefNames.REFS_HEADS + "*", "label-" + labelName);
      boolean foundApprover = false;
      for (String voter : voters) {
        if (voter.trim().split("\\s", 2)[0].endsWith("+2")) {
          foundApprover = true;
          break;
        }
      }
      if (!foundApprover) {
        trialConfig.scannerConfig.messages.add(
            new CommitValidationMessage(
                trialConfig.scannerConfig.pluginKeyValueMessage(
                    ScannerConfig.KEY_REVIEW_LABEL,
                    labelName,
                    "no configured approvers for "
                        + labelName
                        + " on "
                        + RefNames.REFS_HEADS
                        + "*"),
                errorWhenActive));
      }

      // Enforce an approver exists for the copyright review label for configuration changes.
      voters =
          trialConfig.configProject.getStringList(
              ACCESS, RefNames.REFS_CONFIG, "label-" + labelName);
      foundApprover = false;
      for (String voter : voters) {
        if (voter.trim().split("\\s", 2)[0].endsWith("+2")) {
          foundApprover = true;
          break;
        }
      }
      if (!foundApprover) {
        trialConfig.scannerConfig.messages.add(
            new CommitValidationMessage(
                trialConfig.scannerConfig.pluginKeyValueMessage(
                    ScannerConfig.KEY_REVIEW_LABEL,
                    labelName,
                    "no configured approvers for " + labelName + " on " + RefNames.REFS_CONFIG),
                errorWhenActive));
      }
    }
    if (trialConfig.scannerConfig.reviewers.isEmpty()) {
      trialConfig.scannerConfig.messages.add(
          ScannerConfig.warningMessage(
              trialConfig.scannerConfig.pluginKeyRequired(
                  ScannerConfig.KEY_REVIEWER, "adding no qualified reviewer may cause confusion")));
    }
    if (trialConfig.scannerConfig.fromAccountId < 1) {
      trialConfig.scannerConfig.messages.add(
          new CommitValidationMessage(
              trialConfig.scannerConfig.pluginKeyRequired(
                  ScannerConfig.KEY_FROM,
                  "please use \""
                      + ScannerConfig.KEY_FROM
                      + " = <account id>\" to identify a"
                      + " non-interactive user with full voting permissions for the review label '"
                      + trialConfig.scannerConfig.reviewLabel
                      + "'"),
              errorWhenActive));
      // TODO: inject ReviewerAdder into reviewApi, and use ReviewerAdder.prepare
      //       a la
      // https://gerrit.googlesource.com/gerrit/+/refs/heads/master/java/com/google/gerrit/server/restapi/change/PostReview.java#265
      //       to verify the reviewers and ccs are valid.
    }
    if (fromUser != null && fromUser instanceof IdentifiedUser) {
      IdentifiedUser sendingUser = (IdentifiedUser) fromUser;
      Account account = sendingUser.getAccount();
      if (Strings.isNullOrEmpty(account.getFullName())
          && Strings.isNullOrEmpty(account.getPreferredEmail())) {
        trialConfig.scannerConfig.messages.add(
            ScannerConfig.warningMessage(
                trialConfig.scannerConfig.pluginKeyValueMessage(
                    ScannerConfig.KEY_FROM,
                    Long.toString(trialConfig.scannerConfig.fromAccountId),
                    fromUser.getLoggableName()
                        + " (account id "
                        + trialConfig.scannerConfig.fromAccountId
                        + ") has no full name or preferred email")));
      }
      if (!account.isActive()) {
        trialConfig.scannerConfig.messages.add(
            ScannerConfig.warningMessage(
                trialConfig.scannerConfig.pluginKeyValueMessage(
                    ScannerConfig.KEY_FROM,
                    Long.toString(trialConfig.scannerConfig.fromAccountId),
                    fromUser.getLoggableName()
                        + " (account id "
                        + trialConfig.scannerConfig.fromAccountId
                        + ") account is no longer active")));
      }
    } else if (fromUser != null && !fromUser.getUserName().isPresent()) {
      trialConfig.scannerConfig.messages.add(
          ScannerConfig.warningMessage(
              trialConfig.scannerConfig.pluginKeyValueMessage(
                  ScannerConfig.KEY_FROM,
                  Long.toString(trialConfig.scannerConfig.fromAccountId),
                  fromUser.getLoggableName()
                      + " (account id "
                      + trialConfig.scannerConfig.fromAccountId
                      + ") has no user name")));
    }
  }

  /**
   * Confirms whether submitter ran {@code main} and copied the output to the commit message.
   *
   * <p>When a new commit alters the configured scanner patterns, the push will fail with a message
   * to download the plugin source, to run a shell script that runs {@code main} below, and to copy
   * the output on success into the commit message.
   *
   * <p>This method scans the commit message to find the copied text. If the text was created for
   * the same pattern signature, this method returns a single valid finding with the number of
   * microseconds it took to scan a large file, which can be used to block patterns that cause
   * excessive backtracking.
   *
   * <p>If the commit message contains one or more copied texts for other pattern signatures, this
   * method retuns an invalid finding for each.
   *
   * <p>If the commit message contains no copied texts, this method returns an empty list of
   * findings, which {@link com.googlesource.gerrit.plugins.copyright.CopyrightConfig} uses as a
   * signal to instruct the submitter to run the shell script in the first place.
   *
   * @param commitMessage from the pushed change
   */
  public ImmutableList<CopyrightReviewApi.CommitMessageFinding> checkCommitMessage(
      String commitMessage) {
    Preconditions.checkArgument(hasScanner(this));

    Pattern pattern =
        Pattern.compile(
            "Copyright-check:\\s*([\\p{N}a-fA-F]{1,9})[.]([\\p{N}a-fA-F]{16})\\s*(?:\n|$)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    Matcher m = pattern.matcher(commitMessage);
    ImmutableList.Builder<CopyrightReviewApi.CommitMessageFinding> builder =
        ImmutableList.builder();
    StringBuilder sb = new StringBuilder();
    while (m.find()) {
      sb.setLength(0);
      sb.append(m.group(1));
      sb.append("us");
      sb.append(scannerConfig.patternSignature);
      String signature =
          Hashing.farmHashFingerprint64().hashBytes(sb.toString().getBytes(UTF_8)).toString();
      if (signature.equals(m.group(2))) {
        return ImmutableList.of(
            new CopyrightReviewApi.CommitMessageFinding(
                commitMessage, m.group(), m.group(1), m.start(), m.end()));
      }
      builder.add(
          new CopyrightReviewApi.CommitMessageFinding(
              commitMessage, m.group(), m.start(), m.end()));
    }
    return builder.build();
  }

  /**
   * Returns true when {@code findings} indicate a problem to correct.
   *
   * <p>Problems include finding no time signature, finding a time signature for a different commit,
   * or finding a valid time signature for a test that took longer than {@code maxElapsedSeconds}.
   */
  public static boolean mustReportFindings(
      ImmutableList<CopyrightReviewApi.CommitMessageFinding> findings, long maxElapsedSeconds) {
    if (findings.size() == 1 && findings.get(0).isValid()) {
      return findings.get(0).elapsedMicros > maxElapsedSeconds * 1000000;
    }
    return true;
  }

  /** Returns true when {@code trialConfig} is non-null and has a non-null {@code scanner}. */
  public static boolean hasScanner(CheckConfig trialConfig) {
    if (trialConfig == null || trialConfig.scannerConfig == null) {
      return false;
    }
    return trialConfig.scannerConfig.scanner != null;
  }

  /** Returns true when both configs, {@code a} and {@code b}, have the same scanner pattern. */
  public static boolean scannersEqual(CheckConfig a, CheckConfig b) {
    if (!hasScanner(a)) {
      return !hasScanner(b);
    }
    return Objects.equals(a.scannerConfig.scanner, b.scannerConfig.scanner);
  }

  /** Checks whether {@code trialConfig} might cause excessive backtracking. */
  private long timeLargeFileInMicros() throws IOException {
    Stopwatch sw = Stopwatch.createStarted();
    try {
      IndexedLineReader file = largeFile();
      scannerConfig.scanner.findMatches("file", -1, file);
    } finally {
      sw.stop();
      logger.atFine().log("timeLargeFile %dms", sw.elapsed(TimeUnit.MILLISECONDS));
      return sw.elapsed(TimeUnit.MICROSECONDS);
    }
  }

  /** Returns {@code IndexedLineReader} for in-memory pattern that can trigger backtracking. */
  private IndexedLineReader largeFile() {
    StringBuilder sb = new StringBuilder();
    sb.append("                                                                "); // 64 spaces
    sb.append(sb); // 128
    sb.append(sb); // 256
    sb.append(sb); // 512
    sb.append(sb); // 1024
    sb.append('\n');
    String space1k = sb.toString();

    sb.setLength(0);
    for (int i = 0; i < 256; i++) {
      sb.append(String.format(" x%2x", i));
    }
    sb.append('\n');
    String mixed1k = sb.toString();

    sb.setLength(0);
    for (int i = 255; i >= 0; i--) {
      sb.append(String.format(", %2x", i));
    }
    sb.append('\n');
    String comma1k = sb.toString();

    sb.setLength(0);
    for (int i = 255; i >= 0; i--) {
      sb.append(String.format("%2x%2x", 255 - i, i));
    }
    sb.append('\n');
    String alnum1k = sb.toString();

    sb.setLength(0);
    sb.append("AbcdefGhijkLmnopQrstuVwxyzaBCDEFgHIJKlMNOPqRSTUvWXYZaeiouyAEIUOY"); // 64
    sb.append(sb); // 128
    sb.append(sb); // 256
    sb.append(sb); // 512
    sb.append(sb); // 1024
    sb.append('\n');
    String alpha1k = sb.toString();

    for (int i = 0; i < 16; i++) { // 16k + 48k = 64k
      sb.append(space1k);
    }
    for (int i = 0; i < 48; i++) {
      sb.append(space1k, 0, 512 - 10 * i);
      sb.append(mixed1k, 512 + 10 * i, mixed1k.length());
    }

    for (int i = 0; i < 16; i++) { // 64k + 16k + 48k = 128k
      sb.append(alnum1k);
    }
    for (int i = 0; i < 48; i++) {
      sb.append(comma1k);
    }

    for (int i = 0; i < 16; i++) { // 128k + 16k + 48k = 192k
      sb.append(mixed1k);
    }
    for (int i = 0; i < 48; i++) {
      sb.append(alpha1k);
    }

    for (int i = 0; i < 16; i++) { // 192k + 16k + 48k = 256k
      sb.append(alpha1k);
    }
    for (int i = 0; i < 48; i++) {
      sb.append(space1k, 0, 512 - 10 * i);
      sb.append(comma1k, 512 + 10 * i, comma1k.length());
    }

    return new IndexedLineReader(
        "big_file", -1, new ByteArrayInputStream(sb.toString().getBytes(UTF_8)));
  }

  /** Output validation messages on the error console. */
  private void printErrors() {
    for (CommitValidationMessage message : scannerConfig.messages) {
      System.err.printf("%s: %s\n", message.getType(), message.getMessage());
    }
  }

  /** Output a usage message on the error console. */
  private static void usage() {
    System.err.printf(
        "%s <plugin-name> <project.config>\n  where:\n"
            + "    <plugin-name> is the name of the plugin. e.g. 'copyright'\n"
            + "    <project.config> is the path to the project.config file\n",
        toolName);
    System.exit(1);
  }

  /** Read the contents of a project.config file from {@code ilr}. */
  private static String readProjectConfigFile(IndexedLineReader ilr) throws IOException {
    StringBuilder sb = new StringBuilder();
    CharBuffer cb = CharBuffer.wrap(new char[BUFFER_SIZE]);
    while (ilr.read(cb) >= 0) {
      cb.flip();
      sb.append(cb);
      cb.clear();
    }
    String contents = sb.toString();
    sb.setLength(0);
    sb = null;
    return contents;
  }

  /** Calculates the time signature for {@code elapsedMicros} and the current scanner pattern. */
  @VisibleForTesting
  String timeSignature(long elapsedMicros) {
    StringBuilder sb = new StringBuilder();
    sb.append(Long.toString(elapsedMicros));
    sb.append("us");
    sb.append(scannerConfig.patternSignature);

    return Hashing.farmHashFingerprint64().hashBytes(sb.toString().getBytes(UTF_8)).toString();
  }

  // TODO: move check from command-line tool to background thread with timeout.
  /** Entry point for command-line tool to check for excessive backtracking. */
  public static void main(String[] args) {
    if (args.length != 2) {
      usage();
    }
    String pluginName = args[0];
    String fileName = args[1].trim();
    try {
      IndexedLineReader ilr =
          new IndexedLineReader(
              fileName, Paths.get(fileName).toFile().length(), new FileInputStream(fileName));
      CheckConfig myConfig = new CheckConfig(pluginName, readProjectConfigFile(ilr));
      checkProjectConfig(null, false, myConfig);
      if (myConfig.scannerConfig.hasErrors()) {
        myConfig.printErrors();
        System.exit(1);
      }
      Preconditions.checkNotNull(myConfig.scannerConfig.scanner);

      long elapsedMicros = myConfig.timeLargeFileInMicros();
      Preconditions.checkArgument(elapsedMicros >= 0);

      if (elapsedMicros > 1000000) { // longer than a second migth be a problem...
        System.err.println(
            "\n\nThis tool used your copyright plugin config against a large, hard-");
        System.err.println("to-scan file.");
        System.err.println(
            "\nEven for large, difficult to scan files, it's best to keep scan times");
        System.err.println("below 1 second, due to load on the server.\n");
        System.err.println(CopyrightReviewApi.typicalBacktrackingCauses());
        if (elapsedMicros > 60000000) { // minutes is way too long on any server...
          System.err.printf(
              "\nAt %ds, the scan took longer than %d minutes! Please change\n",
              elapsedMicros / 1000000, elapsedMicros / 60000000);
          System.err.println("whatever pattern causes the problem befure submitting.");
        } else if (elapsedMicros > 10000000) { // tens of seconds is too long on any server...
          System.err.printf(
              "\nThe scan took longer than %d seconds! It's very likely this\n",
              elapsedMicros / 1000000);
          System.err.println(
              "configuration will cause problems on your server. Please try to change");
          System.err.println("whatever pattern causes the problem.");
        } else if (elapsedMicros > 2000000) { // multiple seconds is likely a problem...
          System.err.printf(
              "\nThe scan took longer than %d seconds. It's possible this configuration\n",
              elapsedMicros / 1000000);
          System.err.println(
              "might cause problems on your server. Please compare wtih the current");
          System.err.println("configuration, and if this configuration is significantly slower,");
          System.err.println("consider changing whatever pattern might cause the problem.");
        } else { // between 1s and 2s might be needed but could at least try to do better
          System.err.printf(
              "\nAt %dms, the scan took just longer than 1 second. This\n", elapsedMicros / 1000);
          System.err.println("configuration might work okay, but takes longer than ideal. Please");
          System.err.println("investigate whether an added pattern is more costly than needed.");
        }
      } else if (elapsedMicros > 1000) {
        System.err.printf("\nScanned the test load in %dms.\n", elapsedMicros / 1000);
      } else {
        System.err.printf("\nScanned the test load in %d microseconds.\n", elapsedMicros);
      }

      String signature = myConfig.timeSignature(elapsedMicros);
      System.out.println("\n\nCopy the line below into your commit message:");
      System.out.printf("\nCopyright-check: %x.%s\n\n\n", elapsedMicros, signature);
      System.exit(0);
    } catch (IOException e) {
      System.err.printf("Could not read %s\n%s\n", fileName, e.getMessage());
      System.exit(1);
    } catch (ConfigInvalidException e) {
      System.err.printf("Could not parse %s\n%s\n", fileName, e.getMessage());
      System.exit(1);
    }
  }
}
