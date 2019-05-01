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

import static com.googlesource.gerrit.plugins.copyright.CopyrightReviewApi.ALWAYS_REVIEW;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_ENABLE;

import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.changes.AddReviewerResult;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.metrics.Counter0;
import com.google.gerrit.metrics.Counter1;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Description.Units;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer0;
import com.google.gerrit.metrics.Timer1;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.Match;
import com.googlesource.gerrit.plugins.copyright.lib.IndexedLineReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.ObjectStream;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

/** Listener to enforce review of copyright declarations and licenses. */
public class CopyrightValidator implements RevisionCreatedListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final String pluginName; // name of plugin as installed in gerrit
  private final Metrics metrics;
  private final GitRepositoryManager repoManager;
  private final PluginConfigFactory pluginConfigFactory;
  private final CopyrightReviewApi reviewApi;

  private CopyrightConfig copyrightConfig;
  private ScannerConfig scannerConfig; // configuration state for plugin

  static AbstractModule module() {
    return new AbstractModule() {
      @Override
      protected void configure() {
        DynamicSet.bind(binder(), RevisionCreatedListener.class).to(CopyrightValidator.class);
      }
    };
  }

  @Singleton
  private static class Metrics {
    final Counter0 scanCount;
    final Timer0 scanRevisionTimer;
    final Timer0 scanFileTimer;
    final Counter1 scanCountByProject;
    final Timer1 scanRevisionTimerByProject;
    final Timer1 scanFileTimerByProject;
    final Counter1 scanCountByBranch;
    final Timer1 scanRevisionTimerByBranch;
    final Timer1 scanFileTimerByBranch;

    @Inject
    Metrics(MetricMaker metricMaker) {
      Field<String> project = Field.ofString("project", "project name");
      Field<String> branch = Field.ofString("branch", "branch name");
      scanCount =
          metricMaker.newCounter(
              "plugin/copyright/scan_count",
              new Description("Total number of copyright scans").setRate().setUnit("scans"));
      scanRevisionTimer =
          metricMaker.newTimer(
              "plugin/copyright/scan_revision_latency",
              new Description("Time spent scanning entire revisions")
                  .setCumulative()
                  .setUnit(Units.MILLISECONDS));
      scanFileTimer =
          metricMaker.newTimer(
              "plugin/copyright/scan_file_latency",
              new Description("Time spent scanning each file")
                  .setCumulative()
                  .setUnit(Units.MICROSECONDS));
      scanCountByProject =
          metricMaker.newCounter(
              "plugin/copyright/scan_count_by_project",
              new Description("Total number of copyright scans").setRate().setUnit("scans"),
              project);
      scanRevisionTimerByProject =
          metricMaker.newTimer(
              "plugin/copyright/scan_revision_latency_by_project",
              new Description("Time spent scanning entire revisions")
                  .setCumulative()
                  .setUnit(Units.MILLISECONDS),
              project);
      scanFileTimerByProject =
          metricMaker.newTimer(
              "plugin/copyright/scan_file_latency_by_project",
              new Description("Time spent scanning each file")
                  .setCumulative()
                  .setUnit(Units.MICROSECONDS),
              project);
      scanCountByBranch =
          metricMaker.newCounter(
              "plugin/copyright/scan_count_by_branch",
              new Description("Total number of copyright scans").setRate().setUnit("scans"),
              branch);
      scanRevisionTimerByBranch =
          metricMaker.newTimer(
              "plugin/copyright/scan_revision_latency_by_branch",
              new Description("Time spent scanning entire revisions")
                  .setCumulative()
                  .setUnit(Units.MILLISECONDS),
              branch);
      scanFileTimerByBranch =
          metricMaker.newTimer(
              "plugin/copyright/scan_file_latency_by_branch",
              new Description("Time spent scanning each file")
                  .setCumulative()
                  .setUnit(Units.MICROSECONDS),
              branch);
    }
  }

  @Inject
  CopyrightValidator(
      Metrics metrics,
      @PluginName String pluginName,
      GitRepositoryManager repoManager,
      CopyrightConfig copyrightConfig,
      @Nullable ScannerConfig scannerConfig,
      PluginConfigFactory pluginConfigFactory,
      CopyrightReviewApi reviewApi) {
    this.metrics = metrics;
    this.pluginName = pluginName;
    this.repoManager = repoManager;
    this.copyrightConfig = copyrightConfig;
    this.scannerConfig = scannerConfig;
    this.pluginConfigFactory = pluginConfigFactory;
    this.reviewApi = reviewApi;
  }

  @Override
  public void onRevisionCreated(RevisionCreatedListener.Event event) {
    String project = event.getChange().project;
    String branch = event.getChange().branch;

    if (branch.startsWith(RefNames.REFS)) {
      // do not scan refs
      return;
    }
    PluginConfig gerritConfig = pluginConfigFactory.getFromGerritConfig(pluginName, true);
    if (gerritConfig == null || !gerritConfig.getBoolean(KEY_ENABLE, false)) {
      logger.atFine().log("copyright plugin not enbled");
      return;
    }
    if (scannerConfig == null) {
      // error already reported during configuration load
      logger.atWarning().log(
          "%s plugin enabled with no configuration -- not scanning revision %s",
          pluginName, event.getChange().currentRevision);
      return;
    }
    if (scannerConfig.scanner == null) {
      if (scannerConfig.hasErrors()) {
        // error already reported during configuration load
        logger.atWarning().log(
            "%s plugin enabled with errors in configuration -- not scanning revision %s",
            pluginName, event.getChange().currentRevision);
      } // else plugin not enabled
      return;
    }
    // allow project override of All-Projects to enable or disable
    if (!copyrightConfig.isProjectEnabled(scannerConfig, project)) {
      return;
    }
    try {
      scanRevision(project, branch, event);
    } catch (IOException | RestApiException e) {
      logger.atSevere().withCause(e).log(
          "%s plugin cannot scan revision %s", pluginName, event.getChange().currentRevision);
      return;
    }
  }

  /**
   * Scans a pushed revision reporting all findings on its review thread.
   *
   * @param project the project or repository to which the change was pushed
   * @param branch the branch the change updates
   * @param event describes the newly created revision triggering the scan
   * @throws IOException if an error occurred reading the repository
   * @throws RestApiException if an error occured reporting findings to the review thread
   */
  private void scanRevision(String project, String branch, RevisionCreatedListener.Event event)
      throws IOException, RestApiException {
    Map<String, ImmutableList<Match>> findings = new HashMap<>();
    ArrayList<String> containedPaths = new ArrayList<>();
    long scanStart = System.nanoTime();
    metrics.scanCount.increment();
    metrics.scanCountByProject.increment(project);
    metrics.scanCountByBranch.increment(branch);

    try (Repository repo = repoManager.openRepository(Project.nameKey(project));
        RevWalk revWalk = new RevWalk(repo);
        TreeWalk tw = new TreeWalk(revWalk.getObjectReader())) {
      RevCommit commit = repo.parseCommit(ObjectId.fromString(event.getRevision().commit.commit));
      tw.setRecursive(true);
      tw.setFilter(TreeFilter.ANY_DIFF);
      tw.addTree(commit.getTree());
      if (commit.getParentCount() > 0) {
        for (RevCommit p : commit.getParents()) {
          if (p.getTree() == null) {
            revWalk.parseHeaders(p);
          }
          tw.addTree(p.getTree());
        }
      }
      while (tw.next()) {
        containedPaths.add(tw.getPathString());
        String fullPath = project + "/" + tw.getPathString();
        if (scannerConfig.isAlwaysReviewPath(fullPath)) {
          findings.put(tw.getPathString(), ALWAYS_REVIEW);
          continue;
        }
        if (!FileMode.EXECUTABLE_FILE.equals(tw.getFileMode())
            && !FileMode.REGULAR_FILE.equals(tw.getFileMode())) {
          continue;
        }
        long fileStart = System.nanoTime();
        try (ObjectReader reader = tw.getObjectReader();
            ObjectStream stream = reader.open(tw.getObjectId(0)).openStream()) {
          IndexedLineReader lineReader = new IndexedLineReader(fullPath, -1, stream);
          ImmutableList<Match> matches =
              scannerConfig.scanner.findMatches(fullPath, -1, lineReader);
          if (!matches.isEmpty()) {
            findings.put(tw.getPathString(), matches);
          }
          long fileTime = (System.nanoTime() - fileStart) / 1000;
          metrics.scanFileTimer.record(fileTime, TimeUnit.MICROSECONDS);
          metrics.scanFileTimerByProject.record(project, fileTime, TimeUnit.MICROSECONDS);
          metrics.scanFileTimerByBranch.record(branch, fileTime, TimeUnit.MICROSECONDS);
        }
      }
    }
    long scanTime = (System.nanoTime() - scanStart) / 1000000;
    metrics.scanRevisionTimer.record(scanTime, TimeUnit.MILLISECONDS);
    metrics.scanRevisionTimerByProject.record(project, scanTime, TimeUnit.MILLISECONDS);
    metrics.scanRevisionTimerByBranch.record(branch, scanTime, TimeUnit.MILLISECONDS);

    ReviewResult result = reviewApi.reportScanFindings(project, scannerConfig, event, findings);
    if (result.error != null && !result.error.equals("")) {
      logger.atSevere().log(
          "%s plugin revision %s: error posting review: %s",
          pluginName, event.getChange().currentRevision, result.error);
    }
    for (Map.Entry<String, AddReviewerResult> entry : result.reviewers.entrySet()) {
      AddReviewerResult arr = entry.getValue();
      if (arr.error != null && !arr.error.equals("")) {
        logger.atSevere().log(
            "%s plugin revision %s: error adding reviewer %s: %s",
            pluginName, event.getChange().currentRevision, entry.getKey(), arr.error);
      }
    }
  }
}
