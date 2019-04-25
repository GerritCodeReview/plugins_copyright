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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.changes.AddReviewerResult;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.metrics.Description;
import com.google.gerrit.metrics.Description.Units;
import com.google.gerrit.metrics.Field;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.metrics.Timer0;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidationListener;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectConfig;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

/** Listener to manage configuration for enforcing review of copyright declarations and licenses. */
@Singleton
class CopyrightConfig
    implements CommitValidationListener, RevisionCreatedListener, GitReferenceUpdatedListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final long DEFAULT_MAX_ELAPSED_SECONDS = 8;

  private final Metrics metrics;
  private final AllProjectsName allProjectsName;
  private final String pluginName;
  private final GitRepositoryManager repoManager;
  private final ProjectCache projectCache;
  private final PluginConfigFactory pluginConfigFactory;
  private final CopyrightReviewApi reviewApi;

  private PluginConfig gerritConfig;
  private CheckConfig checkConfig;

  static AbstractModule module() {
    return new AbstractModule() {
      @Override
      protected void configure() {
        DynamicSet.bind(binder(), CommitValidationListener.class).to(CopyrightConfig.class);
        DynamicSet.bind(binder(), RevisionCreatedListener.class).to(CopyrightConfig.class);
        DynamicSet.bind(binder(), GitReferenceUpdatedListener.class).to(CopyrightConfig.class);
      }
    };
  }

  @Singleton
  private static class Metrics {
    final Timer0 readConfigTimer;
    final Timer0 checkConfigTimer;
    final Timer0 testConfigTimer;

    @Inject
    Metrics(MetricMaker metricMaker) {
      readConfigTimer =
          metricMaker.newTimer(
              "plugins/copyright/read_config_latency",
              new Description("Time spent reading and parsing plugin configurations")
                  .setCumulative()
                  .setUnit(Units.MICROSECONDS));
      checkConfigTimer =
          metricMaker.newTimer(
              "plugins/copyright/check_config_latency",
              new Description("Time spent testing proposed plugin configurations")
                  .setCumulative()
                  .setUnit(Units.MICROSECONDS));
      testConfigTimer =
          metricMaker.newTimer(
              "plugins/copyright/test_config_latency",
              new Description("Time spent testing configurations against difficult file pattern")
                  .setCumulative()
                  .setUnit(Units.MICROSECONDS));
    }
  }

  @Inject
  CopyrightConfig(
      Metrics metrics,
      AllProjectsName allProjectsName,
      @PluginName String pluginName,
      GitRepositoryManager repoManager,
      ProjectCache projectCache,
      PluginConfigFactory pluginConfigFactory,
      CopyrightReviewApi reviewApi) throws IOException, ConfigInvalidException {
    this.metrics = metrics;
    this.allProjectsName = allProjectsName;
    this.pluginName = pluginName;
    this.repoManager = repoManager;
    this.projectCache = projectCache;
    this.pluginConfigFactory = pluginConfigFactory;
    this.reviewApi = reviewApi;

    long nanoStart = System.nanoTime();
    try {
      checkConfig = readConfig(projectCache.getAllProjects().getProject().getConfigRefState());
    } finally {
      long elapsedMicros = (System.nanoTime() - nanoStart) / 1000;
      metrics.readConfigTimer.record(elapsedMicros, TimeUnit.MICROSECONDS);
    }
  }

  private CopyrightConfig(
      MetricMaker metricMaker, CopyrightReviewApi reviewApi, String projectConfigContents)
      throws ConfigInvalidException {
    metrics = new Metrics(metricMaker);
    allProjectsName = new AllProjectsName("All-Projects");
    pluginName = "copyright";
    repoManager = null;
    projectCache = null;
    pluginConfigFactory = null;
    this.reviewApi = reviewApi;
    checkConfig = new CheckConfig(pluginName, projectConfigContents);
  }

  @VisibleForTesting
  static CopyrightConfig createTestInstance(
      MetricMaker metricMaker, CopyrightReviewApi reviewApi, String projectConfigContents)
      throws ConfigInvalidException {
    return new CopyrightConfig(metricMaker, reviewApi, projectConfigContents);
  }

  ScannerConfig getScannerConfig() {
    return checkConfig.scannerConfig;
  }

  /** Listens for merges to /refs/meta/config on All-Projects to reload plugin configuration. */
  @Override
  public void onGitReferenceUpdated(GitReferenceUpdatedListener.Event event) {
    if (!event.getRefName().equals(RefNames.REFS_CONFIG)) {
      return;
    }
    if (!event.getProjectName().equals(allProjectsName.get())) {
      return;
    }
    long nanoStart = System.nanoTime();
    try {
      clearConfig();
      checkConfig = readConfig(event.getNewObjectId());
    } catch (IOException | ConfigInvalidException e) {
      logger.atSevere().withCause(e).log("%s plugin unable to load configuration", pluginName);
      checkConfig = null;
      return;
    } finally {
      long elapsedMicros = (System.nanoTime() - nanoStart) / 1000;
      metrics.readConfigTimer.record(elapsedMicros, TimeUnit.MICROSECONDS);
    }
  }

  /** Blocks upload of bad plugin configurations to /refs/meta/config on All-Projects. */
  @Override
  public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent event)
      throws CommitValidationException {
    if (!event.getBranchNameKey().get().equals(RefNames.REFS_CONFIG)) {
      return Collections.emptyList();
    }
    if (!event.getProjectNameKey().equals(allProjectsName)) {
      return Collections.emptyList();
    }
    long readStart = System.nanoTime();
    long checkStart = -1;
    long elapsedMicros = -1;
    CheckConfig trialConfig = null;
    try {
      trialConfig = readConfig(event.commit.getName());
      elapsedMicros = (System.nanoTime() - readStart) / 1000;
      metrics.readConfigTimer.record(elapsedMicros, TimeUnit.MICROSECONDS);
      if (Objects.equals(trialConfig.scannerConfig, checkConfig.scannerConfig)) {
        return Collections.emptyList();
      }
      checkStart = System.nanoTime();
      long maxElapsedSeconds =
          gerritConfig == null
              ? DEFAULT_MAX_ELAPSED_SECONDS
                  : gerritConfig.getLong(
                      ScannerConfig.KEY_TIME_TEST_MAX, DEFAULT_MAX_ELAPSED_SECONDS);
      if (maxElapsedSeconds > 0
          && CheckConfig.hasScanner(trialConfig)
          && !CheckConfig.scannersEqual(trialConfig, checkConfig)) {
        String commitMessage = event.commit.getFullMessage();
        ImmutableList<CopyrightReviewApi.CommitMessageFinding> findings =
            trialConfig.checkCommitMessage(commitMessage);
        if (CheckConfig.mustReportFindings(findings, maxElapsedSeconds)) {
          throw reviewApi.getCommitMessageException(pluginName, findings, maxElapsedSeconds);
        }
      }
      boolean pluginEnabled =
          gerritConfig != null && gerritConfig.getBoolean(ScannerConfig.KEY_ENABLE, false);
      CheckConfig.checkProjectConfig(reviewApi, pluginEnabled, trialConfig);
      return trialConfig == null || trialConfig.scannerConfig == null
          ? Collections.emptyList() : trialConfig.scannerConfig.messages;
    } catch (IOException e) {
      logger.atSevere().withCause(e).log(
          "failed to read new project.config for %s plugin", pluginName);
      throw new CommitValidationException("failed to read new project.config", e);
    } catch (ConfigInvalidException e) {
      logger.atSevere().withCause(e).log("unable to parse %s plugin config", pluginName);
      if (trialConfig != null && trialConfig.scannerConfig != null) {
        trialConfig.scannerConfig.messages.add(ScannerConfig.errorMessage(e.getMessage()));
        return trialConfig.scannerConfig.messages;
      } else {
        throw new CommitValidationException("unable to parse new project.config", e);
      }
    } finally {
      if (elapsedMicros < 0) {
        elapsedMicros = (System.nanoTime() - readStart) / 1000;
        metrics.readConfigTimer.record(elapsedMicros, TimeUnit.MICROSECONDS);
      } else if (checkStart >= 0) {
        long elapsedNanos = System.nanoTime() - checkStart;
        metrics.checkConfigTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
      }
      if (trialConfig != null && trialConfig.scannerConfig != null
          && trialConfig.scannerConfig.hasErrors()) {
        StringBuilder sb = new StringBuilder();
        sb.append("\nerror in ");
        sb.append(pluginName);
        sb.append(" plugin configuration");
        trialConfig.scannerConfig.appendMessages(sb);
        throw new CommitValidationException(sb.toString());
      }
    }
  }

  /** Warns on review thread about suspect plugin configurations. */
  @Override
  public void onRevisionCreated(RevisionCreatedListener.Event event) {
    String project = event.getChange().project;
    String branch = event.getChange().branch;

    if (!branch.equals(RefNames.REFS_CONFIG)) {
      return;
    }
    if (!project.equals(allProjectsName.get())) {
      return;
    }
    if (!event.getRevision().files.keySet().contains(ProjectConfig.PROJECT_CONFIG)) {
      return;
    }
    // passed onCommitReceived so expect at worst only warnings here
    long readStart = System.nanoTime();
    long checkStart = -1;
    long elapsedMicros = -1;
    CheckConfig trialConfig = null;
    try {
      trialConfig = readConfig(event.getChange().currentRevision);
      elapsedMicros = (System.nanoTime() - readStart) / 1000;
      metrics.readConfigTimer.record(elapsedMicros, TimeUnit.MICROSECONDS);
      if (Objects.equals(trialConfig, checkConfig.scannerConfig)) {
        return;
      }

      checkStart = System.nanoTime();
      if (CheckConfig.hasScanner(trialConfig)
          && !CheckConfig.scannersEqual(trialConfig, checkConfig)) {
        long maxElapsedSeconds =
            gerritConfig == null
                ? DEFAULT_MAX_ELAPSED_SECONDS
                    : gerritConfig.getLong(
                        ScannerConfig.KEY_TIME_TEST_MAX, DEFAULT_MAX_ELAPSED_SECONDS);
        if (maxElapsedSeconds > 0) {
          String commitMessage = event.getRevision().commitWithFooters;
          ImmutableList<CopyrightReviewApi.CommitMessageFinding> findings =
              trialConfig.checkCommitMessage(commitMessage);
          if (CheckConfig.mustReportFindings(findings, maxElapsedSeconds)) {
            ReviewResult result = reviewApi.reportCommitMessageFindings(
              pluginName,
              allProjectsName.get(),
              checkConfig == null ? null : checkConfig.scannerConfig,
              trialConfig.scannerConfig,
              event,
              findings,
              maxElapsedSeconds);
            logReviewResultErrors(event, result);
          }
        }
      }
      boolean pluginEnabled =
          gerritConfig != null && gerritConfig.getBoolean(ScannerConfig.KEY_ENABLE, false);
      CheckConfig.checkProjectConfig(reviewApi, pluginEnabled, trialConfig);
      return;
    } catch (RestApiException | ConfigInvalidException | IOException e) {
      logger.atSevere().withCause(e)
          .log("%s plugin unable to read new configuration", pluginName);
      // throw IllegalStateException? RestApiException?
      return;
    } finally {
      if (trialConfig != null && trialConfig.scannerConfig != null
          && !trialConfig.scannerConfig.hasErrors()) {
        try {
          ReviewResult result = reviewApi.reportConfigMessages(
              pluginName,
              project,
              ProjectConfig.PROJECT_CONFIG,
              checkConfig.scannerConfig,
              trialConfig.scannerConfig,
              event);
          logReviewResultErrors(event, result);
        } catch (RestApiException e) {
          logger.atSevere().withCause(e)
              .log("%s plugin unable to read new configuration", pluginName);
          // throw IllegalStateException? RestApiException?
          return;
        }
      }
      if (elapsedMicros < 0) {
        elapsedMicros = (System.nanoTime() - readStart) / 1000;
        metrics.readConfigTimer.record(elapsedMicros, TimeUnit.MICROSECONDS);
      } else if (checkStart >= 0) {
        long elapsedNanos = System.nanoTime() - checkStart;
        metrics.checkConfigTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
      }
    }
  }

  /** Returns true if copyright validation enabled for {@code project}. */
  boolean isProjectEnabled(ScannerConfig scannerConfig, String project) {
    // scan all projects when missing
    if (!scannerConfig.matchProjects.isEmpty()
        && !ScannerConfig.matchesAny(project, scannerConfig.matchProjects)) {
      // doesn't match == isn't checked
      return false;
    }
    // exclude no projects when missing
    if (!scannerConfig.excludeProjects.isEmpty()
        && ScannerConfig.matchesAny(project, scannerConfig.excludeProjects)) {
      // does match == isn't checked
      return false;
    }
    ProjectState projectState;
    try {
      projectState = projectCache.checkedGet(new Project.NameKey(project));
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("error getting project state of %s", project);
      // throw IllegalStateException? RestApiException?
      return scannerConfig.defaultEnable;
    }
    if (projectState == null) {
      logger.atSevere().log("error getting project state of %s", project);
      // throw IllegalStateException? RestApiException?
      return scannerConfig.defaultEnable;
    }
    ProjectConfig projectConfig = projectState.getConfig();
    if (projectConfig == null) {
      logger.atWarning().log("error getting project config of %s", project);
      // throw IllegalStateException? RestApiException? return?
      return scannerConfig.defaultEnable;
    }
    PluginConfig pluginConfig = projectConfig.getPluginConfig(pluginName);
    if (pluginConfig == null) {
      // no plugin config section in project so use default
      return scannerConfig.defaultEnable;
    }
    return pluginConfig.getBoolean(ScannerConfig.KEY_ENABLE, scannerConfig.defaultEnable);
  }

  /**
   * Loads and compiles configured patterns from {@code ref/meta/All-Projects/project.config} and
   * {@code gerrit.config}.
   *
   * @param projectConfigObjectId identifies the version of project.config to load and to compile
   * @return the new scanner configuration to check
   * @throws IOException if accessing the repository fails
   */
  private CheckConfig readConfig(String projectConfigObjectId)
      throws IOException, ConfigInvalidException {
    CheckConfig checkConfig = null;
    // new All-Projects project.config not yet in cache -- read from repository
    ObjectId id = ObjectId.fromString(projectConfigObjectId);
    if (ObjectId.zeroId().equals(id)) {
      return checkConfig;
    }
    try (Repository repo = repoManager.openRepository(allProjectsName)) {
      checkConfig = new CheckConfig(
          pluginName, readFileContents(repo, id, ProjectConfig.PROJECT_CONFIG));
    }
    gerritConfig = pluginConfigFactory.getFromGerritConfig(pluginName, true);
    if (gerritConfig == null) {
      // throw IllegalStateException? RestApiException?
      checkConfig.scannerConfig.messages.add(
        ScannerConfig.hintMessage(
            "missing [plugin \"" + pluginName + "\"] section in gerrit.config"));
    } else {
      checkConfig.scannerConfig.defaultEnable =
          gerritConfig.getBoolean(ScannerConfig.KEY_ENABLE, false);
    }
    return checkConfig;
  }

  /** Erases any prior configuration state. */
  private void clearConfig() {
    checkConfig = null;
  }

  private void logReviewResultErrors(RevisionCreatedListener.Event event, ReviewResult result) {
    if (!Strings.isNullOrEmpty(result.error)) {
      logger.atSevere().log(
          "%s plugin revision %s: error posting review: %s",
          pluginName,
          event.getChange().currentRevision,
          result.error);
    }
    for (Map.Entry<String, AddReviewerResult> entry : result.reviewers.entrySet()) {
      AddReviewerResult arr = entry.getValue();
      if (!Strings.isNullOrEmpty(arr.error)) {
        logger.atSevere().log(
            "%s plugin revision %s: error adding reviewer %s: %s",
            pluginName,
            event.getChange().currentRevision,
            entry.getKey(),
            arr.error);
      }
    }
  }

  private String readFileContents(Repository repo, ObjectId objectId, String filename)
      throws IOException {
    RevWalk rw = new RevWalk(repo);
    RevTree tree = rw.parseTree(objectId);
    try (TreeWalk tw = TreeWalk.forPath(rw.getObjectReader(), filename, tree)) {
      ObjectLoader loader = repo.open(tw.getObjectId(0), Constants.OBJ_BLOB);
      return new String(loader.getCachedBytes(), UTF_8);
    }
  }
}
