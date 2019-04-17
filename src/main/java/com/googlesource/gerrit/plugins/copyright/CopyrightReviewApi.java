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

package com.googlesource.gerrit.plugins.copyright;

import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.MatchType.AUTHOR_OWNER;
import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.MatchType.LICENSE;
import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType.FIRST_PARTY;
import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType.THIRD_PARTY;
import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType.FORBIDDEN;
import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType.UNKNOWN;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewInput.CommentInput;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.extensions.client.Comment;
import com.google.gerrit.extensions.client.ReviewerState;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.extensions.restapi.IdString;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.metrics.Counter0;
import com.google.gerrit.metrics.Counter1;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Description.Units;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer0;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.PatchSetUtil;
import com.google.gerrit.server.PluginUser;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.restapi.change.ListChangeComments;
import com.google.gerrit.server.restapi.change.PostReview;
import com.google.inject.Singleton;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.Match;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.MatchType;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

/** Utility to report revision findings on the review thread. */
@Singleton
public class CopyrightReviewApi {
  static final ImmutableList<Match> ALWAYS_REVIEW = ImmutableList.of();

  private final Metrics metrics;
  private final Provider<PluginUser> pluginUserProvider;
  private final Provider<CurrentUser> userProvider;
  private final IdentifiedUser.GenericFactory identifiedUserFactory;
  private final ChangeNotes.Factory changeNotesFactory;
  private final ChangeResource.Factory changeResourceFactory;
  private final PatchSetUtil psUtil;
  private final PostReview postReview;
  private final ListChangeComments listChangeComments;

  @Singleton
  static class Metrics {
    final Counter0 reviewCount;
    final Counter0 commentCount;
    final Timer0 reviewTimer;
    final Counter1 reviewCountByProject;
    final Counter1 commentCountByProject;
    final Timer1 reviewTimerByProject;

    @Inject
    Metrics(MetricMaker metricMaker) {
      Field<String> project = Field.ofString("project", "project name");
      reviewCount =
          metricMaker.newCounter(
              "plugin/copyright/review_count",
              new Description("Total number of posted reviews").setRate().setUnit("reviews"));
      commentCount =
          metricMaker.newCounter(
              "plugin/copyright/comment_count",
              new Description("Total number of posted review comments")
                  .setRate()
                  .setUnit("comments"));
      reviewTimer =
          metricMaker.newTimer(
              "plugin/copyright/review_latency",
              new Description("Time spent posting reviews to revisions")
                  .setCumulative()
                  .setUnit(Units.MICROSECONDS));
      reviewCountByProject =
          metricMaker.newCounter(
              "plugin/copyright/review_count_by_project",
              new Description("Total number of posted reviews").setRate().setUnit("reviews"),
              project);
      commentCountByProject =
          metricMaker.newCounter(
              "plugin/copyright/comment_count_by_project",
              new Description("Total number of posted review comments")
                  .setRate()
                  .setUnit("comments"),
              project);
      reviewTimerByProject =
          metricMaker.newTimer(
              "plugin/copyright/review_latency_by_project",
              new Description("Time spent posting reviews to revisions")
                  .setCumulative()
                  .setUnit(Units.MICROSECONDS),
              project);
    }
  }

  @Inject
  CopyrightReviewApi(
      Metrics metrics,
      Provider<PluginUser> pluginUserProvider,
      Provider<CurrentUser> userProvider,
      IdentifiedUser.GenericFactory identifiedUserFactory,
      ChangeNotes.Factory changeNotesFactory,
      ChangeResource.Factory changeResourceFactory,
      PatchSetUtil psUtil,
      PostReview postReview,
      ListChangeComments listChangeComments) {
    this.metrics = metrics;
    this.pluginUserProvider = pluginUserProvider;
    this.userProvider = userProvider;
    this.identifiedUserFactory = identifiedUserFactory;
    this.changeNotesFactory = changeNotesFactory;
    this.changeResourceFactory = changeResourceFactory;
    this.psUtil = psUtil;
    this.postReview = postReview;
    this.listChangeComments = listChangeComments;
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
      CopyrightConfig.ScannerConfig oldConfig,
      CopyrightConfig.ScannerConfig newConfig,
      RevisionCreatedListener.Event event) throws RestApiException {
    Preconditions.checkNotNull(newConfig);
    Preconditions.checkNotNull(newConfig.messages);
    Preconditions.checkArgument(!newConfig.messages.isEmpty());

    long startNanos = System.nanoTime();
    metrics.reviewCount.increment();
    metrics.reviewCountByProject.increment(project);

    try {
      int fromAccountId =
          oldConfig != null && oldConfig.fromAccountId > 0
              ? oldConfig.fromAccountId
                  : newConfig.fromAccountId;
      ChangeResource change = getChange(event, fromAccountId);
      StringBuilder message = new StringBuilder();
      message.append(pluginName);
      message.append(" plugin issues parsing new configuration");
      ReviewInput ri = new ReviewInput()
          .message(message.toString());

      Map<String, List<CommentInfo>> priorComments = getComments(change);
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
      return review(change, ri);
    } finally {
      long elapsedMicros = (System.nanoTime() - startNanos) / 1000;
      metrics.reviewTimer.record(elapsedMicros, TimeUnit.MICROSECONDS);
      metrics.reviewTimerByProject.record(project, elapsedMicros, TimeUnit.MICROSECONDS);
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
      CopyrightConfig.ScannerConfig scannerConfig,
      RevisionCreatedListener.Event event,
      Map<String, ImmutableList<Match>> findings) throws RestApiException {
    long startNanos = System.nanoTime();
    metrics.reviewCount.increment();
    metrics.reviewCountByProject.increment(project);

    try {
      boolean tpAllowed = scannerConfig.isThirdPartyAllowed(project);
      boolean reviewRequired = false;
      boolean hasAlwaysReview = false;
      for (Map.Entry<String, ImmutableList<Match>> entry : findings.entrySet()) {
        if (entry.getValue() == ALWAYS_REVIEW) {
          reviewRequired = true;
          hasAlwaysReview = true;
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
      ChangeResource change = getChange(event, scannerConfig.fromAccountId);
      ReviewInput ri = new ReviewInput()
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
      Map<String, List<CommentInfo>> priorComments = getComments(change);
      if (priorComments == null) {
        priorComments = ImmutableMap.of();
      }

      int numCommentsAdded = 0;
      if (hasAlwaysReview) {
        for (Map.Entry<String, ImmutableList<Match>> entry : findings.entrySet()) {
          if (entry.getValue() != ALWAYS_REVIEW) {
            continue;
          }
          CommentInput ci = new CommentInput();
          ci.line = 0;
          ci.unresolved = true;
          ci.message = entry.getKey() + " always requires copyright review";
          if (containsComment(priorComments.get(entry.getKey()), ci)) {
            continue;
          }
          comments.put(entry.getKey(), ImmutableList.of(ci));
          numCommentsAdded++;
        }
      }
      for (Map.Entry<String, ImmutableList<Match>> entry : findings.entrySet()) {
        if (entry.getValue() == ALWAYS_REVIEW) {
          continue;
        }
        PartyType pt = partyType(entry.getValue());
        ImmutableList<CommentInput> newComments = reviewComments(
            project, pt, tpAllowed, entry.getValue());
        List<CommentInfo> prior = priorComments.get(entry.getKey());
        newComments = ImmutableList.copyOf(
            newComments.stream().filter(ci -> !containsComment(prior, ci))
                .toArray(i -> new CommentInput[i]));
        if (newComments.isEmpty()) {
          continue;
        }
        numCommentsAdded += newComments.size();
        comments.put(entry.getKey(), newComments);
      }

      if (numCommentsAdded > 0) {
        ri.comments = comments.build();
      }
      metrics.commentCount.incrementBy((long) numCommentsAdded);
      metrics.commentCountByProject.incrementBy(project, (long) numCommentsAdded);
      return review(change, ri);
    } finally {
      long elapsedMicros = (System.nanoTime() - startNanos) / 1000;
      metrics.reviewTimer.record(elapsedMicros, TimeUnit.MICROSECONDS);
      metrics.reviewTimerByProject.record(project, elapsedMicros, TimeUnit.MICROSECONDS);
    }
  }

  /**
   * Returns the {@link com.google.gerrit.server.CurrentUser} seeming to send the review comments.
   *
   * Impersonates {@code fromAccountId} if configured by {@code fromAccountId =} in plugin
   * configuration -- falling back to the identity of the user pushing the revision.
   */
  CurrentUser getSendingUser(int fromAccountId) {
      PluginUser pluginUser = pluginUserProvider.get();
      return fromAccountId <= 0 ? userProvider.get() :
          identifiedUserFactory.runAs(null, new Account.Id(fromAccountId), pluginUser);
  }

  /**
   * Constructs a {@link com.google.gerrit.server.change.ChangeResource} from the notes log for
   * the change onto which a new revision was pushed.
   *
   * @param event describes the newly pushed revision
   * @param fromAccountId identifies the configured user to impersonate when sending review comments
   * @throws RestApiException if an error occurs looking up the notes log for the change
   */
  private ChangeResource getChange(RevisionCreatedListener.Event event, int fromAccountId)
      throws RestApiException {
    try {
      CurrentUser fromUser = getSendingUser(fromAccountId);
      ChangeNotes notes =
          changeNotesFactory.createChecked(new Change.Id(event.getChange()._number));
      return changeResourceFactory.create(notes, fromUser);
    } catch (Exception e) {
      Throwables.throwIfUnchecked(e);
      throw e instanceof RestApiException
          ? (RestApiException) e : new RestApiException("Cannot load change", e);
    }
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

  /**
   * Retrieves all of the prior review comments already attached to {@code change}.
   *
   * @throws RestApiException if an error occurs retrieving the comments
   */
  private Map<String, List<CommentInfo>> getComments(ChangeResource change)
      throws RestApiException {
    try {
      return listChangeComments.apply(change);
    } catch (Exception e) {
      Throwables.throwIfUnchecked(e);
      throw e instanceof RestApiException
          ? (RestApiException) e : new RestApiException("Cannot list comments", e);
    }
  }

  /**
   * Adds the code review described by {@code ri} to the review thread of {@code change}.
   *
   * @throws RestApiException if an error occurs updating the review thread
   */
  private ReviewResult review(ChangeResource change, ReviewInput ri) throws RestApiException {
    try {
      PatchSet ps = psUtil.current(change.getNotes());
      if (ps == null) {
        throw new ResourceNotFoundException(IdString.fromDecoded("current"));
      }
      RevisionResource revision = RevisionResource.createNonCacheable(change, ps);
      return postReview.apply(revision, ri).value();
    } catch (Exception e) {
      Throwables.throwIfUnchecked(e);
      throw e instanceof RestApiException
          ? (RestApiException) e : new RestApiException("Cannot post review", e);
    }
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
   * @param overallPt identifies the calculated
   *     {@link com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType} for all of
   *     the findings in the file. e.g. 1p license + 3p owner == 1p, no license + 3p owner == 3p
   * @param pt identifies the {@code PartyType} of the current finding
   * @param mt identifies the
   *     {@link com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.MatchType} of the
   *     current finding. i.e. AUTHOR_OWNER or LICENSE
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
   * @param pt the calculated overall
   *     {@link com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType} for the
   *     findings e.g. 1p license + 3p owner = 1p, no license + 3p owner = 3p
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
        boolean allowed = m.partyType == FIRST_PARTY
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
   * Calculates and returns the overall
   * {@link com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType} for the
   * copyright scanner findings in {@code matches}.
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
}
