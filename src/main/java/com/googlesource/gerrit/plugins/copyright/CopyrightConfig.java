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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.api.changes.AddReviewerResult;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.RevisionCreatedListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.config.AllProjectsName;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.events.CommitReceivedEvent;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.validators.CommitValidationException;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.gerrit.server.git.validators.ValidationMessage;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectConfig;
import com.google.gerrit.server.project.ProjectState;
import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightPatterns;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightPatterns.UnknownPatternName;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.Match;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.MatchType;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType;
import com.googlesource.gerrit.plugins.copyright.lib.IndexedLineReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.inject.Inject;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/** Listener to manage configuration for enforcing review of copyright declarations and licenses. */
@Singleton
class CopyrightConfig {

  private static final String DEFAULT_REVIEW_LABEL = "Copyright-Review";

  private final Metrics metrics;
  private final AllProjectsName allProjectsName;
  private final String pluginName;
  private final GitRepositoryManager repoManager;
  private final ProjectCache projectCache;
  private final ProjectConfig.Factory projectConfigFactory;
  private final PluginConfigFactory pluginConfigFactory;
  private final CopyrightReviewApi reviewApi;

  private ScannerConfig scannerConfig;

  @Singleton
  private static class Metrics {
    @Inject
    Metrics(MetricMaker metricMaker) {}
  }

  @Inject
  CopyrightConfig(
      Metrics metrics,
      AllProjectsName allProjectsName,
      @PluginName String pluginName,
      GitRepositoryManager repoManager,
      ProjectCache projectCache,
      ProjectConfig.Factory projectConfigFactory,
      PluginConfigFactory pluginConfigFactory,
      CopyrightReviewApi reviewApi) throws IOException {
    this.metrics = metrics;
    this.allProjectsName = allProjectsName;
    this.pluginName = pluginName;
    this.repoManager = repoManager;
    this.projectCache = projectCache;
    this.projectConfigFactory = projectConfigFactory;
    this.pluginConfigFactory = pluginConfigFactory;
    this.reviewApi = reviewApi;
  }

  private CopyrightConfig(MetricMaker metricMaker, CopyrightReviewApi reviewApi) {
    metrics = new Metrics(metricMaker);
    allProjectsName = new AllProjectsName("All-Projects");
    pluginName = "copyright";
    repoManager = null;
    projectCache = null;
    projectConfigFactory = null;
    pluginConfigFactory = null;
    this.reviewApi = reviewApi;
    scannerConfig = this.new ScannerConfig();
  }

  @VisibleForTesting
  static CopyrightConfig createTestInstance(MetricMaker metricMaker, CopyrightReviewApi reviewApi) {
    return new CopyrightConfig(metricMaker, reviewApi);
  }

  @VisibleForTesting
  ScannerConfig getScannerConfig() {
    return scannerConfig;
  }

  /** Returns true if any pattern in {@code regexes} found in {@code text}. */
  private static boolean matchesAny(String text, Collection<Pattern> regexes) {
    requireNonNull(regexes);
    for (Pattern pattern : regexes) {
      if (pattern.matcher(text).find()) {
        return true;
      }
    }
    return false;
  }

  /** Configuration state for {@link CopyrightValidator}. */
  class ScannerConfig {
    CopyrightScanner scanner;
    final ArrayList<CommitValidationMessage> messages;
    final LinkedHashSet<Pattern> alwaysReviewPath;
    final LinkedHashSet<Pattern> matchProjects;
    final LinkedHashSet<Pattern> excludeProjects;
    final LinkedHashSet<Pattern> thirdPartyAllowedProjects;
    final LinkedHashSet<String> reviewers;
    final LinkedHashSet<String> ccs;
    String reviewLabel;
    boolean defaultEnable;
    int fromAccountId;

    ScannerConfig() {
      this.messages = new ArrayList<>();
      this.alwaysReviewPath = new LinkedHashSet<>();
      this.matchProjects = new LinkedHashSet<>();
      this.excludeProjects = new LinkedHashSet<>();
      this.thirdPartyAllowedProjects = new LinkedHashSet<>();
      this.reviewers = new LinkedHashSet<>();
      this.ccs = new LinkedHashSet<>();
      this.reviewLabel = DEFAULT_REVIEW_LABEL;
      this.defaultEnable = false;
      this.fromAccountId = 0;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (other == null) {
        return false;
      }
      if (other instanceof ScannerConfig) {
        ScannerConfig otherConfig = (ScannerConfig) other;
        return defaultEnable == otherConfig.defaultEnable
            && messages.equals(otherConfig.messages)
            && alwaysReviewPath.equals(otherConfig.alwaysReviewPath)
            && matchProjects.equals(otherConfig.matchProjects)
            && excludeProjects.equals(otherConfig.excludeProjects)
            && thirdPartyAllowedProjects.equals(otherConfig.thirdPartyAllowedProjects)
            && reviewers.equals(otherConfig.reviewers)
            && ccs.equals(otherConfig.ccs)
            && Objects.equals(reviewLabel, otherConfig.reviewLabel)
            && Objects.equals(scanner, otherConfig.scanner);
      }
      return false;
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          defaultEnable,
          messages,
          alwaysReviewPath,
          matchProjects,
          excludeProjects,
          thirdPartyAllowedProjects,
          reviewers,
          ccs,
          reviewLabel,
          scanner);
    }

    /** Returns true if {@code project} repository allows third-party code. */
    boolean isThirdPartyAllowed(String project) {
      return matchesAny(project, thirdPartyAllowedProjects);
    }
  }
}
