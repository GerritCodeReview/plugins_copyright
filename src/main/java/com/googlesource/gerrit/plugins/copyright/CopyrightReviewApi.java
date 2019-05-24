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

import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.MatchType.AUTHOR_OWNER;
import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.MatchType.LICENSE;
import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType.FIRST_PARTY;
import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType.FORBIDDEN;
import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType.THIRD_PARTY;
import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType.UNKNOWN;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewInput.CommentInput;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.extensions.client.Comment;
import com.google.gerrit.extensions.client.ReviewerState;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.metrics.Timer0;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.PluginUser;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.Match;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.MatchType;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;

/** Utility to report revision findings on the review thread. */
@Singleton
public class CopyrightReviewApi {
  static final ImmutableList<Match> ALWAYS_REVIEW = ImmutableList.of();

  private final Metrics metrics;
  private final Provider<PluginUser> pluginUserProvider;
  private final Provider<CurrentUser> userProvider;
  private final IdentifiedUser.GenericFactory identifiedUserFactory;
  private final ThreadLocalRequestContext requestContext;
  private final GerritApi gApi;

  @Inject
  CopyrightReviewApi(
      Metrics metrics,
      Provider<PluginUser> pluginUserProvider,
      Provider<CurrentUser> userProvider,
      IdentifiedUser.GenericFactory identifiedUserFactory,
      ThreadLocalRequestContext requestContext,
      GerritApi gApi) {
    this.metrics = metrics;
    this.pluginUserProvider = pluginUserProvider;
    this.userProvider = userProvider;
    this.identifiedUserFactory = identifiedUserFactory;
    this.requestContext = requestContext;
    this.gApi = gApi;
  }

  /**
   * Reports failure findings from or about the check_new_config.sh tool on the review thread.
   *
   * <p>If no output found in the commit message, directs the submitter to run the tool and copy its
   * output into the commit message.
   *
   * <p>If the output was found for a different scanner pattern, directs the submitter to run again
   * for the current commit.
   *
   * <p>Otherwise, the duration of the timing run must exceed the configured limit. Describes
   * patterns known to cause problems and directs the submitter to change the pattern.
   *
   * @param pluginName as installed in gerrit
   * @param project identifies the All-Projects config for recording metrics
   * @param oldConfig the prior state of the plugin configuration
   * @param newConfig the new state of the plugin configuration
   * @param event describes the pushed revision with the new configuration
   * @param findings describes the line numbers, validity and timing run durations found
   * @param maxElapsedSeconds the largest allows timing run duration in seconds
   * @throws RestApiException if an error occurs updating the review thread
   */
  ReviewResult reportCommitMessageFindings(
      String pluginName,
      String project,
      ScannerConfig oldConfig,
      ScannerConfig newConfig,
      RevisionCreatedListener.Event event,
      ImmutableList<CommitMessageFinding> findings,
      long maxElapsedSeconds)
      throws RestApiException {
    Preconditions.checkNotNull(newConfig);
    Preconditions.checkNotNull(newConfig.scanner);
    Preconditions.checkArgument(
        oldConfig == null
            || oldConfig.scanner == null
            || !oldConfig.scanner.equals(newConfig.scanner));
    Preconditions.checkArgument(findings.size() != 1 || !findings.get(0).isValid());
    Preconditions.checkArgument(maxElapsedSeconds > 0);

    metrics.reviewCount.increment();
    metrics.reviewCountByProject.increment(project);

    int fromAccountId =
        oldConfig != null && oldConfig.fromAccountId > 0
            ? oldConfig.fromAccountId
            : newConfig.fromAccountId;
    try (ManualRequestContext ctx = getContext(fromAccountId)) {
      ChangeApi cApi = gApi.changes().id(event.getChange().id);
      Map<String, List<CommentInfo>> priorReviewComments = cApi.comments();
      if (priorReviewComments == null) {
        priorReviewComments = ImmutableMap.of();
      }
      HashSet<CommentInfo> priorComments = new HashSet<>();
      priorComments.addAll(priorReviewComments.get("/COMMIT_MSG"));

      ReviewInput ri = new ReviewInput();
      ri.message(getCommitMessageMessage(pluginName, findings, maxElapsedSeconds));
      ImmutableList<CommentInput> comments =
          ImmutableList.copyOf(
              getCommitMessageComments(pluginName, findings, maxElapsedSeconds).stream()
                  .filter(ci -> !priorComments.contains(ci))
                  .toArray(i -> new CommentInput[i]));
      if (!comments.isEmpty()) {
        ri.comments = ImmutableMap.of("/COMMIT_MSG", comments);
      }
      String label = "Code-Review";
      if (!Strings.isNullOrEmpty(oldConfig.reviewLabel)) {
        label = oldConfig.reviewLabel;
      } else if (!Strings.isNullOrEmpty(newConfig.reviewLabel)) {
        label = newConfig.reviewLabel;
      }
      int vote = (findings.size() != 1 || !findings.get(0).isValid()) ? -2 : 2;
      if (vote == 2) {
        long elapsedMicros = findings.get(0).elapsedMicros;
        if (elapsedMicros > maxElapsedSeconds * 1000000) {
          vote = -2;
        } else if (elapsedMicros > 2000000) {
          vote = 1;
        }
      }
      ri.label(label, vote);
      return cApi.current().review(ri);
    }
  }

  /**
   * Returns a {@link com.google.gerrit.server.git.validators.CommitValidationException} describing
   * failure findings from or about the check_new_config.sh tool.
   *
   * <p>If no output found in the commit message, directs the submitter to run the tool and copy its
   * output into the commit message.
   *
   * <p>If the output was found for a different scanner pattern, directs the submitter to run again
   * for the current commit.
   *
   * <p>Otherwise, the duration of the timing run must exceed the configured limit. Describes
   * patterns known to cause problems and directs the submitter to change the pattern.
   *
   * @param pluginName as installed in gerrit
   * @param findings describes the line numbers, validity and timing run durations found
   * @param maxElapsedSeconds the largest allows timing run duration in seconds
   */
  public CommitValidationException getCommitMessageException(
      String pluginName, ImmutableList<CommitMessageFinding> findings, long maxElapsedSeconds) {
    Preconditions.checkArgument(
        findings.size() != 1
            || !findings.get(0).isValid()
            || findings.get(0).elapsedMicros > maxElapsedSeconds * 1000000);
    StringBuilder sb = new StringBuilder();
    sb.append(getCommitMessageMessage(pluginName, findings, maxElapsedSeconds));
    sb.append("\n\n");
    ImmutableList<CommentInput> comments =
        getCommitMessageComments(pluginName, findings, maxElapsedSeconds);
    for (CommentInput ci : comments) {
      if (ci.line != 0) {
        sb.append("commit message line ");
        sb.append(Integer.toString(ci.range.startLine));
        sb.append(":\n");
      }
      sb.append(ci.message);
      sb.append("\n");
    }
    return new CommitValidationException(sb.toString());
  }

  /**
   * Reports validation findings for a proposed new plugin configuration to the review thread for
   * the newly pushed revision.
   *
   * @param pluginName as installed in gerrit
   * @param project identifies the All-Projects config for recording metrics
   * @param path identifies the file to attach messages to (@code gerrit.config or project.config}
   * @param oldConfig the prior state of the plugin configuration
   * @param newConfig the new state of the plugin configuration
   * @param event describes the pushed revision with the new configuration
   * @throws RestApiException if an error occurs updating the review thread
   */
  ReviewResult reportConfigMessages(
      String pluginName,
      String project,
      String path,
      ScannerConfig oldConfig,
      ScannerConfig newConfig,
      RevisionCreatedListener.Event event)
      throws RestApiException {
    Preconditions.checkNotNull(newConfig);
    Preconditions.checkNotNull(newConfig.messages);
    Preconditions.checkArgument(!newConfig.messages.isEmpty());

    metrics.reviewCount.increment();
    metrics.reviewCountByProject.increment(project);

    int fromAccountId =
        oldConfig != null && oldConfig.fromAccountId > 0
            ? oldConfig.fromAccountId
            : newConfig.fromAccountId;
    try (Timer0.Context t0 = metrics.reviewTimer.start();
        Timer1.Context t1 = metrics.reviewTimerByProject.start(project);
        ManualRequestContext ctx = getContext(fromAccountId)) {
      ChangeApi cApi = gApi.changes().id(event.getChange().id);
      StringBuilder message = new StringBuilder();
      message.append(pluginName);
      message.append(" plugin issues parsing new configuration");
      ReviewInput ri = new ReviewInput().message(message.toString());

      Map<String, List<CommentInfo>> priorComments = cApi.comments();
      if (priorComments == null) {
        priorComments = ImmutableMap.of();
      }

      int numComments = 0;
      ImmutableMap.Builder<String, List<CommentInput>> comments = ImmutableMap.builder();
      for (CommitValidationMessage m : newConfig.messages) {
        message.setLength(0);
        message.append(m.getType().toString());
        message.append(" ");
        message.append(m.getMessage());
        CommentInput ci = new CommentInput();
        ci.line = 0;
        ci.unresolved = true;
        ci.message = message.toString();
        if (containsComment(priorComments.get(path), ci)) {
          continue;
        }
        comments.put(path, ImmutableList.of(ci));
        numComments++;
      }
      if (numComments > 0) {
        ri.comments = comments.build();
      }
      metrics.commentCount.incrementBy((long) numComments);
      metrics.commentCountByProject.incrementBy(project, (long) numComments);
      return cApi.current().review(ri);
    }
  }

  /**
   * Reports {@link CopyrightValidator} findings from a scanned revision on its review thread.
   *
   * @param project identifies the project where change pushed for recording metrics
   * @param scannerConfig the state of the plugin configuration and scanner
   * @param event describes the pushed revision scanned by the plugin
   * @param findings maps each scanned file to the copyright matches found by the scanner
   * @throws RestApiException if an error occurs updating the review thread
   */
  ReviewResult reportScanFindings(
      String project,
      ScannerConfig scannerConfig,
      RevisionCreatedListener.Event event,
      Map<String, ImmutableList<Match>> findings)
      throws RestApiException {
    metrics.reviewCount.increment();
    metrics.reviewCountByProject.increment(project);

    try (Timer0.Context t0 = metrics.reviewTimer.start();
        Timer1.Context t1 = metrics.reviewTimerByProject.start(project);
        ManualRequestContext ctx = getContext(scannerConfig.fromAccountId)) {
      boolean tpAllowed = scannerConfig.isThirdPartyAllowed(project);
      boolean reviewRequired = false;
      for (Map.Entry<String, ImmutableList<Match>> entry : findings.entrySet()) {
        if (entry.getValue() == ALWAYS_REVIEW) {
          reviewRequired = true;
          break;
        }
        PartyType pt = partyType(entry.getValue());
        if (pt.compareTo(THIRD_PARTY) > 0) {
          reviewRequired = true;
          break;
        }
        if (pt == THIRD_PARTY && !tpAllowed) {
          reviewRequired = true;
          break;
        }
      }
      ChangeApi cApi = gApi.changes().id(event.getChange().id);
      ReviewInput ri =
          new ReviewInput()
              .message("Copyright scan")
              .label(scannerConfig.reviewLabel, reviewRequired ? -1 : +2);

      if (reviewRequired) {
        ri = addReviewers(ri, scannerConfig.ccs, ReviewerState.CC);
        ri = addReviewers(ri, scannerConfig.reviewers, ReviewerState.REVIEWER);
      }
      ImmutableMap.Builder<String, List<CommentInput>> comments = ImmutableMap.builder();
      if (reviewRequired) {
        ri = ri.message("This change requires copyright review.");
      } else {
        ri = ri.message("This change appears to comply with copyright requirements.");
      }
      Map<String, List<CommentInfo>> priorComments = cApi.comments();
      if (priorComments == null) {
        priorComments = ImmutableMap.of();
      }

      int numCommentsAdded = 0;
      for (Map.Entry<String, ImmutableList<Match>> entry : findings.entrySet()) {
        ImmutableList<CommentInput> newComments = null;
        if (entry.getValue() == ALWAYS_REVIEW) {
          CommentInput ci = new CommentInput();
          ci.line = 0;
          ci.unresolved = true;
          ci.message = entry.getKey() + " always requires copyright review";
          if (containsComment(priorComments.get(entry.getKey()), ci)) {
            continue;
          }
          newComments = ImmutableList.of(ci);
        } else {
          PartyType pt = partyType(entry.getValue());
          newComments = reviewComments(project, pt, tpAllowed, entry.getValue());
          List<CommentInfo> prior = priorComments.get(entry.getKey());
          newComments =
              ImmutableList.copyOf(
                  newComments.stream()
                      .filter(ci -> !containsComment(prior, ci))
                      .toArray(i -> new CommentInput[i]));
          if (newComments.isEmpty()) {
            continue;
          }
        }
        numCommentsAdded += newComments.size();
        comments.put(entry.getKey(), newComments);
      }

      if (numCommentsAdded > 0) {
        ri.comments = comments.build();
      }
      metrics.commentCount.incrementBy((long) numCommentsAdded);
      metrics.commentCountByProject.incrementBy(project, (long) numCommentsAdded);
      return cApi.current().review(ri);
    }
  }

  /**
   * Returns the {@link com.google.gerrit.server.CurrentUser} seeming to send the review comments.
   *
   * <p>Impersonates {@code fromAccountId} if configured by {@code fromAccountId =} in plugin
   * configuration -- falling back to the identity of the user pushing the revision.
   */
  CurrentUser getSendingUser(int fromAccountId) {
    PluginUser pluginUser = pluginUserProvider.get();
    return fromAccountId <= 0
        ? userProvider.get()
        : identifiedUserFactory.runAs(null, Account.id(fromAccountId), pluginUser);
  }

  /**
   * Returns 1 of 3 review messages depending on the check_new_config.sh tool output.
   *
   * @param pluginName as installed in gerrit
   * @param findings describes the line numbers, validity and timing run durations found
   */
  private String getCommitMessageMessage(
      String pluginName, ImmutableList<CommitMessageFinding> findings, long maxElapsedSeconds) {
    StringBuilder sb = new StringBuilder();
    if (findings.isEmpty()) {
      sb.append(pluginName);
      sb.append(" plugin: match patterns have changed; please run check_new_config tool");
    } else if (findings.size() == 1 && findings.get(0).isValid()) {
      long elapsedMicros = findings.get(0).elapsedMicros;
      if (elapsedMicros > maxElapsedSeconds * 1000000) {
        sb.append("check_new_config: problem pattern detected");
      } else if (elapsedMicros > 2000000) {
        sb.append("check_new_config: possible problem pattern detected");
      } else if (elapsedMicros > 1000000) {
        sb.append("check_new_config: possibly okay");
      } else {
        sb.append("check_new_config: okay");
      }
    } else {
      sb.append("check_new_config: results for wrong commit; please run again for current");
    }
    return sb.toString();
  }

  /**
   * Returns one or more of 3 review comment versions based on the check_new_config.sh tool output.
   *
   * @param pluginName as installed in gerrit
   * @param findings describes the line numbers, validity and timing run durations found
   * @param maxElapsedSeconds the largest allows timing run duration in seconds
   */
  private ImmutableList<CommentInput> getCommitMessageComments(
      String pluginName, ImmutableList<CommitMessageFinding> findings, long maxElapsedSeconds) {
    ImmutableList.Builder<CommentInput> comments = ImmutableList.builder();
    StringBuilder sb = new StringBuilder();
    CommentInput ci = new CommentInput();
    if (findings.isEmpty()) {
      sb.append("While most patterns are fine, some patterns can force your gerrit server\n");
      sb.append("to work too hard. To protect your server, there is a tool that can\n");
      sb.append("detect these patterns before the configuration gets submitted.\n\n");
      sb.append("Please use git to download the tool from:\n");
      sb.append("https://gerrit.googlesource.com/plugins/copyright/+/refs/heads/master\n\n");
      sb.append("After downloading, run tools/check_new_config.sh (requires bazel):\n");
      sb.append("<path>/tools/check_new_config.sh '");
      sb.append(pluginName);
      sb.append("' '<path>/project.config'\n");
      sb.append("and copy it's output to your commit message.\n\n");
      sb.append("e.g. if your local All-Projects is at workspace/All-Projects and if you\n");
      sb.append("downloaded plugins/copyright to workspace/copyright, you might run:\n");
      sb.append("../copyright/tools/check_new_config.sh '");
      sb.append(pluginName);
      sb.append("' project.config\n");
      sb.append("from the workspace/All-Projects directory.\n");
      ci.line = 0;
      ci.unresolved = true;
      ci.message = sb.toString();
      comments.add(ci);
    } else if (findings.size() == 1 && findings.get(0).isValid()) {
      CommitMessageFinding finding = findings.get(0);
      if (finding.elapsedMicros > maxElapsedSeconds * 1000000) {
        sb.append("Scanning the test file took longer than ");
        sb.append(Long.toString(maxElapsedSeconds));
        sb.append(" seconds.");
        if (finding.elapsedMicros - (maxElapsedSeconds * 1000000) > 1000000) {
          sb.append(" (");
          sb.append(Long.toString(finding.elapsedMicros / 1000000));
          sb.append(" seconds)");
        }
        sb.append("\n\nThis is much longer than usual even on a slower, modern computer.\n\n");
        sb.append("The result suggests a pattern that causes excessive backtracking.\n");
        sb.append(typicalBacktrackingCauses());
        sb.append("\nPlease fix any problematic patterns and try again.\n");
      } else if (finding.elapsedMicros > 2000000) {
        sb.append("Scanning the test file took longer than 2 seconda. (");
        if (finding.elapsedMicros > 3000000) {
          sb.append(Long.toString(finding.elapsedMicros / 1000000));
          sb.append(" seconds)");
        } else {
          sb.append(Long.toString(finding.elapsedMicros / 1000));
          sb.append(" ms)");
        }
        sb.append("\n\nThe result suggests a pattern that might cause excessive backtracking.\n");
        sb.append(typicalBacktrackingCauses());
        sb.append("\nPlease try to fix any problematic patterns before proceeding.\n");
      } else if (finding.elapsedMicros > 1000000) {
        sb.append("Scanning the test file took just longer than a second. (");
        sb.append(Long.toString(finding.elapsedMicros / 1000));
        sb.append("ms)");
        sb.append("\n\nThe result is a little longer than ideal.\n\n");
        sb.append(typicalBacktrackingCauses());
        sb.append("\n\nCompare with the current config, and if this config is signigicantly\n");
        sb.append("slower, consider changing whatever pattern causes the problem.\n");
      } else if (finding.elapsedMicros > 1000) {
        sb.append("Scanning the test file took ");
        sb.append(Long.toString(finding.elapsedMicros / 1000));
        sb.append("ms.");
      } else {
        sb.append("Scanning the test file took ");
        sb.append(Long.toString(finding.elapsedMicros));
        sb.append(" microsends.");
      }
      ci.line = finding.endLine;
      ci.range = new CommentInput.Range();
      ci.range.startLine = finding.startLine;
      ci.range.endLine = finding.endLine;
      ci.range.startCharacter = finding.startCol;
      ci.range.endCharacter = finding.endCol;
      ci.unresolved = finding.elapsedMicros > 1000000;
      ci.message = sb.toString();
      comments.add(ci);
    } else {
      for (CommitMessageFinding finding : findings) {
        sb.setLength(0);
        sb.append("'");
        sb.append(finding.text.trim());
        sb.append("'\nis not a result for the patterns in the current revision");
        ci.line = finding.endLine;
        ci.range = new CommentInput.Range();
        ci.range.startLine = finding.startLine;
        ci.range.endLine = finding.endLine;
        ci.range.startCharacter = finding.startCol;
        ci.range.endCharacter = finding.endCol;
        ci.unresolved = true;
        ci.message = sb.toString();
        comments.add(ci);
        ci = new CommentInput();
      }
    }
    return comments.build();
  }

  public static String typicalBacktrackingCauses() {
    StringBuilder sb = new StringBuilder();
    sb.append("Typical causes of excessive backtracking include:\n");
    sb.append("  1. unbounded repetitions of wildcards or\n");
    sb.append("  2. zero-length look-ahead/look-behind patterns\n\n");
    sb.append("Wildcards:\n");
    sb.append("  The scanner automatically handles .* and .+ patterns, but it's\n");
    sb.append("  possible to accidentally compose equivalents or near-equivalents:\n");
    sb.append("  e.g. (?:[a]|[^a])* or [\\\\s\\\\p{N}\\\\p{L}\\\\p{P}]+ match nearly everything\n");
    sb.append("  If your new pattern contains something similar, consider using .* for\n");
    sb.append("  automatic handling instead, or use a smaller character class.\n\n");
    sb.append("Unbounded repetitions:\n");
    sb.append("  If your new pattern uses * or + for unlimited repetitions, consider\n");
    sb.append("  using a more limited repetition like {0,10} or {1,50} that is long\n");
    sb.append("  enough to match what you need but short enough to scan quickly.\n\n");
    sb.append("Zero-length look-ahead or look-behind:\n");
    sb.append("  Patterns like (?!word), (?=word), (?<!word). (?<=word) etc. can cause\n");
    sb.append("  excessive backtracking too. Sometimes, it is faster to match a little\n");
    sb.append("  more than needed and use an excludePattern to eliminate unwanted hits.\n");
    sb.append("  e.g. forbiddenPattern = owner some pattern \\p{L}*\n");
    sb.append("       excludePattern = some pattern word\n");
    return sb.toString();
  }

  private ManualRequestContext getContext(int fromAccountId) {
    PluginUser pluginUser = pluginUserProvider.get();
    CurrentUser user =
        fromAccountId <= 0
            ? userProvider.get()
            : identifiedUserFactory.runAs(null, Account.id(fromAccountId), pluginUser);
    return new ManualRequestContext(user, requestContext);
  }

  /**
   * Returns a modified {@link com.google.gerrit.extensions.api.changes.ReviewInput} after adding
   * {@code reviewers} as {@code type} CC or REVIEWER.
   */
  @VisibleForTesting
  ReviewInput addReviewers(ReviewInput ri, Iterable<String> reviewers, ReviewerState type) {
    for (String reviewer : reviewers) {
      ri = ri.reviewer(reviewer, type, true);
    }
    return ri;
  }

  /** Returns true if {@code priorComments} already includes a comment identical to {@code ci}. */
  @VisibleForTesting
  boolean containsComment(Iterable<? extends Comment> priorComments, CommentInput ci) {
    if (priorComments == null) {
      return false;
    }
    for (Comment prior : priorComments) {
      if (Objects.equals(prior.line, ci.line)
          && Objects.equals(prior.range, ci.range)
          && Objects.equals(prior.message, ci.message)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Puts the pieces together from a scanner finding to construct a coherent human-reable message.
   *
   * @param project describes the project or repository where the revision was pushed
   * @param overallPt identifies the calculated {@link
   *     com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType} for all of the
   *     findings in the file. e.g. 1p license + 3p owner == 1p, no license + 3p owner == 3p
   * @param pt identifies the {@code PartyType} of the current finding
   * @param mt identifies the {@link
   *     com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.MatchType} of the current
   *     finding. i.e. AUTHOR_OWNER or LICENSE
   * @param tpAllowed is true if {@code project} allows third-party code
   * @param text of the message for the finding
   */
  private String buildMessage(
      String project,
      PartyType overallPt,
      PartyType pt,
      MatchType mt,
      boolean tpAllowed,
      StringBuilder text) {
    StringBuilder message = new StringBuilder();
    switch (pt) {
      case FIRST_PARTY:
        message.append("First-party ");
        break;
      case THIRD_PARTY:
        message.append("Third-party ");
        break;
      case FORBIDDEN:
        message.append("Disapproved ");
        break;
      default:
        message.append("Unrecognized ");
        break;
    }
    message.append(mt == AUTHOR_OWNER ? "author or owner " : "license ");
    if (pt == THIRD_PARTY && overallPt != FIRST_PARTY) {
      if (!tpAllowed) {
        message.append("dis");
      }
      message.append("allowed in repository ");
      message.append(project);
    }
    message.append(":\n\n");
    message.append(text);
    return message.toString();
  }

  /**
   * Converts the scanner findings in {@code matches} into human-readable review comments.
   *
   * @param project the project or repository to which the revision was pushed
   * @param pt the calculated overall {@link
   *     com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType} for the findings
   *     e.g. 1p license + 3p owner = 1p, no license + 3p owner = 3p
   * @param tpAllowed is true if {@code project} allows third-party code
   * @param matches describes the location and types of matches found in a file
   * @return a list of {@link com.google.gerrit.extensions.api.changes.ReviewInput.CommentInput} to
   *     add to the {@link com.google.gerrit.extensions.api.changes.ReviewInput} to add to the
   *     review thread for the current patch set.
   */
  @VisibleForTesting
  ImmutableList<CommentInput> reviewComments(
      String project, PartyType pt, boolean tpAllowed, Iterable<Match> matches) {
    ImmutableList.Builder<CommentInput> builder = ImmutableList.builder();
    CommentInput ci = null;
    PartyType previousPt = UNKNOWN;
    MatchType previousMt = LICENSE;
    StringBuilder text = new StringBuilder();
    for (Match m : matches) {
      if (ci == null
          || previousPt != m.partyType
          || m.endLine > ci.range.startLine + 60
          || m.startLine > ci.range.endLine + 40) {
        if (ci != null) {
          ci.message = buildMessage(project, pt, previousPt, previousMt, tpAllowed, text);
          builder.add(ci);
        }
        ci = new CommentInput();
        boolean allowed =
            m.partyType == FIRST_PARTY
                || (m.partyType == THIRD_PARTY && tpAllowed)
                || (m.partyType == THIRD_PARTY && m.matchType == AUTHOR_OWNER && pt == FIRST_PARTY);
        ci.unresolved = !allowed;
        ci.range = new Comment.Range();
        ci.line = m.endLine;
        ci.range.startLine = m.startLine;
        ci.range.endLine = m.endLine;
        ci.range.startCharacter = 0;
        ci.range.endCharacter = 0;
        previousPt = m.partyType;
        previousMt = m.matchType;
        text.setLength(0);
        text.append(m.text);
        continue;
      }
      text.append("...");
      text.append(m.text);
      // an author or owner inside a license declaration is a normal part of a license declaration
      if (m.matchType == LICENSE) {
        previousMt = LICENSE;
      }
      if (m.startLine < ci.range.startLine) {
        ci.range.startLine = m.startLine;
      }
      if (m.endLine > ci.range.endLine) {
        ci.range.endLine = m.endLine;
        ci.line = m.endLine;
      }
    }
    if (ci != null) {
      ci.message = buildMessage(project, pt, previousPt, previousMt, tpAllowed, text);
      builder.add(ci);
    }
    return builder.build();
  }

  /**
   * Calculates and returns the overall {@link
   * com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType} for the copyright
   * scanner findings in {@code matches}.
   */
  @VisibleForTesting
  PartyType partyType(Iterable<Match> matches) {
    PartyType pt = PartyType.FIRST_PARTY;
    boolean hasThirdPartyOwner = false;
    boolean hasFirstPartyLicense = false;
    for (Match match : matches) {
      if (match.partyType == PartyType.THIRD_PARTY && match.matchType == MatchType.AUTHOR_OWNER) {
        hasThirdPartyOwner = true;
      } else if (match.partyType == PartyType.FIRST_PARTY && match.matchType == MatchType.LICENSE) {
        hasFirstPartyLicense = true;
      } else if (match.partyType.compareTo(pt) > 0) {
        pt = match.partyType;
      }
    }
    if (pt == PartyType.FIRST_PARTY && hasThirdPartyOwner && !hasFirstPartyLicense) {
      return PartyType.THIRD_PARTY;
    }
    return pt;
  }

  /**
   * Found {@code main} output in the commit message.
   *
   * <p>Each finding identifies the position in the commit message, the validity for the current
   * scanner pattern, and the large file scan duration if valid.
   */
  public static class CommitMessageFinding {
    private static final Pattern NL = Pattern.compile("\n", Pattern.MULTILINE | Pattern.DOTALL);

    /** The character offset into the commit message where the finding starts. */
    public final int start;
    /** The character offset into the commit message where the finding ends. */
    public final int end;
    /** The found text apparently matching {@code main} output. */
    public final String text;
    /** How long in microseconds it took to scan a large file, or -1 if scan with other pattern. */
    public final long elapsedMicros;

    /** The line number of the start of the finding in the commit message. */
    public final int startLine;
    /** The column (0-based) of the start of the finding in the commit message. */
    public final int startCol;
    /** The line number of the end of the finding in the commit message. */
    public final int endLine;
    /** The column (0-based) of the end of the finding in the commit message. */
    public final int endCol;

    /** Returns true when {@code elapsedMicros} reflects the current scanner pattern. */
    public boolean isValid() {
      return elapsedMicros >= 0;
    }

    /** A finding for the current scanner pattern with relevant {@code elapsedMicros}. */
    CommitMessageFinding(String commitMsg, String text, String elapsedMicros, int start, int end) {
      this.start = start;
      this.end = end;
      this.text = text;
      this.elapsedMicros = Long.parseLong(elapsedMicros, 16);

      Matcher m = NL.matcher(commitMsg);
      int line = 1;
      int lineStart = 0;
      int startLine = 0;
      int startCol = -1;
      int endLine = 0;
      int endCol = -1;
      while (m.find()) {
        if (m.start() > start) {
          startLine = line;
          startCol = start - lineStart;
        }
        if (m.start() > end) {
          endLine = line;
          endCol = end - lineStart;
          break;
        }
        line++;
        lineStart = m.end();
      }
      if (startLine == 0) {
        startLine = line;
        startCol = start - lineStart;
      }
      if (endLine == 0) {
        endLine = line;
        endCol = end - lineStart;
      }
      this.startLine = startLine;
      this.startCol = startCol;
      this.endLine = endLine;
      this.endCol = endCol;
    }

    /** A finding for a different scanner pattern -- {@code elapsedMicros} not relevant. */
    CommitMessageFinding(String commitMsg, String text, int start, int end) {
      this(commitMsg, text, "-1", start, end);
    }
  }
}
