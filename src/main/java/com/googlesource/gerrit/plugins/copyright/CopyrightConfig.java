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

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.changes.AddReviewerResult;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiException;
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
import javax.inject.Inject;
import org.eclipse.jgit.errors.ConfigInvalidException;
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
    implements CommitValidationListener,
        RevisionCreatedListener,
        GitReferenceUpdatedListener,
        LifecycleListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  /** Default value of timeTestMax configuration parameter for avoiding excessive backtracking. */
  private final long DEFAULT_MAX_ELAPSED_SECONDS = 8;

  private final Metrics metrics;
  private final AllProjectsName allProjectsName;
  private final String pluginName;
  private final GitRepositoryManager repoManager;
  private final ProjectCache projectCache;
  private final PluginConfigFactory pluginConfigFactory;
  private final CopyrightReviewApi reviewApi;

  @Nullable private PluginConfig gerritConfig;
  @Nullable private CheckConfig checkConfig;

  static AbstractModule module() {
    return new AbstractModule() {
      @Override
      protected void configure() {
        DynamicSet.bind(binder(), CommitValidationListener.class).to(CopyrightConfig.class);
        DynamicSet.bind(binder(), LifecycleListener.class).to(CopyrightConfig.class);
        DynamicSet.bind(binder(), RevisionCreatedListener.class).to(CopyrightConfig.class);
        DynamicSet.bind(binder(), GitReferenceUpdatedListener.class).to(CopyrightConfig.class);
      }
    };
  }

  @Inject
  CopyrightConfig(
      Metrics metrics,
      AllProjectsName allProjectsName,
      @PluginName String pluginName,
      GitRepositoryManager repoManager,
      ProjectCache projectCache,
      PluginConfigFactory pluginConfigFactory,
      CopyrightReviewApi reviewApi) {
    this.metrics = metrics;
    this.allProjectsName = allProjectsName;
    this.pluginName = pluginName;
    this.repoManager = repoManager;
    this.projectCache = projectCache;
    this.pluginConfigFactory = pluginConfigFactory;
    this.reviewApi = reviewApi;
    this.checkConfig = null;
  }

  ScannerConfig getScannerConfig() {
    return checkConfig == null ? null : checkConfig.scannerConfig;
  }

  @Override
  public void start() {
    try (Timer0.Context ctx = metrics.readConfigTimer.start()) {
      checkConfig = readConfig(projectCache.getAllProjects().getProject().getConfigRefState());
    } catch (IOException | ConfigInvalidException e) {
      logger.atSevere().withCause(e).log("unable to load configuration");
      metrics.configurationErrors.increment(allProjectsName.get());
      metrics.errors.increment();
      return;
    }
  }

  @Override
  public void stop() {}

  /** Listens for merges to /refs/meta/config on All-Projects to reload plugin configuration. */
  @Override
  public void onGitReferenceUpdated(GitReferenceUpdatedListener.Event event) {
    if (!event.getRefName().equals(RefNames.REFS_CONFIG)) {
      return;
    }
    if (!event.getProjectName().equals(allProjectsName.get())) {
      return;
    }
    try (Timer0.Context ctx = metrics.readConfigTimer.start()) {
      checkConfig = readConfig(event.getNewObjectId());
    } catch (IOException | ConfigInvalidException e) {
      logger.atSevere().withCause(e).log("unable to load configuration");
      metrics.configurationErrors.increment(allProjectsName.get());
      metrics.errors.increment();
      return;
    }
  }

  /** Blocks upload of bad plugin configurations to /refs/meta/config on All-Projects. */
  @Override
  public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent event)
      throws CommitValidationException {
    if (!event.getRefName().equals(RefNames.REFS_CONFIG)) {
      return Collections.emptyList();
    }
    if (!event.getProjectNameKey().equals(allProjectsName)) {
      return Collections.emptyList();
    }
    CheckConfig trialConfig = null;
    try {
      try (Timer0.Context ctx = metrics.readConfigTimer.start()) {
        trialConfig = readConfig(event.commit.getName());
      }
      if (Objects.equals(
          trialConfig.scannerConfig, checkConfig == null ? null : checkConfig.scannerConfig)) {
        return Collections.emptyList();
      }
      try (Timer0.Context ctx = metrics.checkConfigTimer.start()) {
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
        List<CommitValidationMessage> messages =
            trialConfig == null || trialConfig.scannerConfig == null
                ? Collections.emptyList()
                : trialConfig.scannerConfig.messages;
        checkForConfigErrors(trialConfig);
        return messages;
      }
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("failed to read new project.config");
      throw new CommitValidationException(
          pluginName + "plugin failed to read new project.config", e);
    } catch (ConfigInvalidException e) {
      logger.atSevere().withCause(e).log("unable to parse plugin config");
      if (trialConfig != null && trialConfig.scannerConfig != null) {
        trialConfig.scannerConfig.messages.add(ScannerConfig.errorMessage(e.getMessage()));
        metrics.configurationErrors.increment(allProjectsName.get());
        metrics.errors.increment();
        checkForConfigErrors(trialConfig);
        return trialConfig.scannerConfig.messages;
      } else {
        throw new CommitValidationException(
            pluginName + "plugin unable to parse new project.config", e);
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
    CheckConfig trialConfig = null;
    try {
      try (Timer0.Context ctx = metrics.readConfigTimer.start()) {
        trialConfig = readConfig(event.getChange().currentRevision);
      }
      if (Objects.equals(
          trialConfig.scannerConfig, checkConfig == null ? null : checkConfig.scannerConfig)) {
        return;
      }

      try (Timer0.Context ctx = metrics.checkConfigTimer.start()) {
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
            ReviewResult result =
                reviewApi.reportCommitMessageFindings(
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
        boolean pluginEnabled =
            gerritConfig != null && gerritConfig.getBoolean(ScannerConfig.KEY_ENABLE, false);
        CheckConfig.checkProjectConfig(reviewApi, pluginEnabled, trialConfig);
        return;
      }
    } catch (RestApiException | ConfigInvalidException | IOException e) {
      logger.atSevere().withCause(e).log("unable to read new configuration");
      metrics.configurationErrors.increment(project);
      metrics.errors.increment();
      return;
    } finally {
      if (trialConfig != null
          && trialConfig.scannerConfig != null
          && !trialConfig.scannerConfig.messages.isEmpty()
          && !trialConfig.scannerConfig.hasErrors()) {
        try {
          ReviewResult result =
              reviewApi.reportConfigMessages(
                  pluginName,
                  project,
                  ProjectConfig.PROJECT_CONFIG,
                  checkConfig == null ? null : checkConfig.scannerConfig,
                  trialConfig.scannerConfig,
                  event);
          logReviewResultErrors(event, result);
        } catch (RestApiException e) {
          logger.atSevere().withCause(e).log("unable to report configuration findings");
          metrics.postReviewErrors.increment(project);
          metrics.errors.increment();
          return;
        }
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
      projectState = projectCache.checkedGet(Project.nameKey(project));
    } catch (IOException e) {
      logger.atSevere().withCause(e).log("error getting project state of %s", project);
      metrics.projectStateErrors.increment(project);
      metrics.errors.increment();
      return scannerConfig.defaultEnable;
    }
    if (projectState == null) {
      logger.atSevere().log("error getting project state of %s", project);
      metrics.projectStateErrors.increment(project);
      metrics.errors.increment();
      return scannerConfig.defaultEnable;
    }
    ProjectConfig projectConfig = projectState.getConfig();
    if (projectConfig == null) {
      logger.atWarning().log("error getting project config of %s", project);
      metrics.projectConfigErrors.increment(project);
      metrics.errors.increment();
      return scannerConfig.defaultEnable;
    }
    PluginConfig pluginConfig = projectConfig.getPluginConfig(pluginName);
    if (pluginConfig == null) {
      // no plugin config section in project so use default
      return scannerConfig.defaultEnable;
    }
    return pluginConfig.getBoolean(ScannerConfig.KEY_ENABLE, scannerConfig.defaultEnable);
  }

  private void checkForConfigErrors(CheckConfig trialConfig) throws CommitValidationException {
    if (trialConfig != null
        && trialConfig.scannerConfig != null
        && trialConfig.scannerConfig.hasErrors()) {
      StringBuilder sb = new StringBuilder();
      sb.append("\nerror in ");
      sb.append(pluginName);
      sb.append(" plugin configuration");
      trialConfig.scannerConfig.appendMessages(sb);
      throw new CommitValidationException(sb.toString());
    }
  }

  /**
   * Loads and compiles configured patterns from {@code ref/meta/All-Projects/project.config} and
   * {@code gerrit.config}.
   *
   * @param projectConfigObjectId identifies the version of project.config to load and to compile
   * @return the new scanner configuration to check
   * @throws IOException if accessing the repository fails
   */
  @Nullable
  private CheckConfig readConfig(String projectConfigObjectId)
      throws IOException, ConfigInvalidException {
    CheckConfig checkConfig = null;
    // new All-Projects project.config not yet in cache -- read from repository
    ObjectId id = ObjectId.fromString(projectConfigObjectId);
    if (ObjectId.zeroId().equals(id)) {
      return checkConfig;
    }
    try (Repository repo = repoManager.openRepository(allProjectsName)) {
      checkConfig =
          new CheckConfig(pluginName, readFileContents(repo, id, ProjectConfig.PROJECT_CONFIG));
    }
    gerritConfig = pluginConfigFactory.getFromGerritConfig(pluginName, true);
    if (gerritConfig == null) {
      checkConfig.scannerConfig.messages.add(
          ScannerConfig.hintMessage(
              "missing [plugin \"" + pluginName + "\"] section in gerrit.config"));
    } else {
      checkConfig.scannerConfig.defaultEnable =
          gerritConfig.getBoolean(ScannerConfig.KEY_ENABLE, false);
    }
    return checkConfig;
  }

  private void logReviewResultErrors(RevisionCreatedListener.Event event, ReviewResult result) {
    if (!Strings.isNullOrEmpty(result.error)) {
      logger.atSevere().log(
          "revision %s: error posting review: %s", event.getChange().currentRevision, result.error);
      metrics.postReviewErrors.increment(event.getChange().project);
      metrics.errors.increment();
    }
    if (result.reviewers != null) {
      for (Map.Entry<String, AddReviewerResult> entry : result.reviewers.entrySet()) {
        AddReviewerResult arr = entry.getValue();
        if (!Strings.isNullOrEmpty(arr.error)) {
          logger.atSevere().log(
              "revision %s: error adding reviewer %s: %s",
              event.getChange().currentRevision, entry.getKey(), arr.error);
          metrics.addReviewerErrors.increment(event.getChange().project);
          metrics.errors.increment();
        }
      }
    }
  }

  private String readFileContents(Repository repo, ObjectId objectId, String filename)
      throws IOException {
    try (RevWalk rw = new RevWalk(repo);
        TreeWalk tw = TreeWalk.forPath(rw.getObjectReader(), filename, rw.parseTree(objectId))) {
      ObjectLoader loader = repo.open(tw.getObjectId(0), Constants.OBJ_BLOB);
      return new String(loader.getCachedBytes(), UTF_8);
    }
  }
}
