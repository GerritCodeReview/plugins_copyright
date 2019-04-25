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

import static java.util.Objects.requireNonNull;

import com.google.common.base.Strings;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.git.validators.CommitValidationMessage;
import com.google.gerrit.server.git.validators.ValidationMessage;
import com.google.gerrit.server.project.ProjectConfig;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightPatterns;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightPatterns.UnknownPatternName;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Configuration state for {@link CopyrightValidator}. */
class ScannerConfig {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  public static final String KEY_ENABLE = "enable";

  static final String KEY_TIME_TEST_MAX = "timeTestMax";
  static final String DEFAULT_REVIEW_LABEL = "Copyright-Review";
  static final String KEY_REVIEWER = "reviewer";
  static final String KEY_CC = "cc";
  static final String KEY_FROM = "fromAccountId";
  static final String KEY_REVIEW_LABEL = "reviewLabel";
  static final String KEY_EXCLUDE = "exclude";
  static final String KEY_FIRST_PARTY = "firstParty";
  static final String KEY_THIRD_PARTY = "thirdParty";
  static final String KEY_FORBIDDEN = "forbidden";
  static final String KEY_EXCLUDE_PATTERN = "excludePattern";
  static final String KEY_FIRST_PARTY_PATTERN = "firstPartyPattern";
  static final String KEY_THIRD_PARTY_PATTERN = "thirdPartyPattern";
  static final String KEY_FORBIDDEN_PATTERN = "forbiddenPattern";
  static final String KEY_ALWAYS_REVIEW_PATH = "alwaysReviewPath";
  static final String KEY_MATCH_PROJECTS = "matchProjects";
  static final String KEY_EXCLUDE_PROJECTS = "excludeProjects";
  static final String KEY_THIRD_PARTY_ALLOWED_PROJECTS = "thirdPartyAllowedProjects";
  private static final String OWNER = "owner ";
  private static final String LICENSE = "license ";

  String pluginName;
  CopyrightScanner scanner;
  String patternSignature;
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

  ScannerConfig(String pluginName) {
    this.pluginName = pluginName;
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

  /** Formats {@code message} into a message about the plugin configuration. */
  String pluginMessage(String message) {
    return " in "
        + ProjectConfig.PROJECT_CONFIG
        + "\n[plugin \"" + pluginName + "\"]\n"
        + message;
  }

  /** Formats {@code message} into a message about the {@code key = value} line of the config. */
  String pluginKeyValueMessage(String key, String value, String message) {
    StringBuilder sb = new StringBuilder();
    sb.append("  ");
    sb.append(key);
    sb.append(" = ");
    sb.append(value.trim());
    sb.append("\n");
    sb.append("                                            ".substring(0, key.length() + 5));
    sb.append("^\n");  // ^ aligned under start of value in message
    sb.append(message);
    return pluginMessage(sb.toString());
  }

  static CommitValidationMessage errorMessage(String message) {
    return new CommitValidationMessage(message, ValidationMessage.Type.ERROR);
  }

  static CommitValidationMessage warningMessage(String message) {
    return new CommitValidationMessage(message, ValidationMessage.Type.WARNING);
  }

  static CommitValidationMessage hintMessage(String message) {
    return new CommitValidationMessage(message, ValidationMessage.Type.HINT);
  }

  /** Formats {@code message} into a message about a required {@code key} not found in config. */
  String pluginKeyRequired(String key, String message) {
    return pluginMessage("\nno \"" + key + " =\" key was found.\n\n" + message);
  }

  /**
   * Adjusts {@code scannerConfig} state per {@code cfg}.
   *
   * @param builder accumulates the scanner pattern rules used for constructing the scanner
   * @param scannerConfig records the configuration parameter values
   * @param cfg is the source of the configuration
   */
  void readConfigFile(PluginConfig cfg) {
    CopyrightPatterns.RuleSet.Builder builder = CopyrightPatterns.RuleSet.builder();
    // can be disabled in All-Projects and then enabled project-by-project
    defaultEnable = cfg.getBoolean(KEY_ENABLE, defaultEnable);
    fromAccountId = cfg.getInt(KEY_FROM, 0);
    addStringList(cfg, reviewers, KEY_REVIEWER, "reviewer email address");
    addStringList(cfg, ccs, KEY_CC, "CC email address");
    String key = cfg.getString(KEY_REVIEW_LABEL);
    if (!Strings.isNullOrEmpty(key)) {
      reviewLabel = key;
    }
    addPatternList(cfg, alwaysReviewPath, KEY_ALWAYS_REVIEW_PATH, "path pattern");
    addPatternList(cfg, matchProjects, KEY_MATCH_PROJECTS, "project pattern");
    addPatternList(cfg, excludeProjects, KEY_EXCLUDE_PROJECTS, "project pattern");
    addPatternList(
        cfg, thirdPartyAllowedProjects, KEY_THIRD_PARTY_ALLOWED_PROJECTS, "project pattern");
    // exclusions
    addRule(cfg, rule -> { builder.exclude(rule); }, KEY_EXCLUDE);
    addRule(cfg, rule -> { builder.excludePattern(rule); }, KEY_EXCLUDE_PATTERN);
    // first-party
    addRule(cfg, rule -> { builder.addFirstParty(rule); }, KEY_FIRST_PARTY);
    addRulePattern(
        cfg,
        pattern -> { builder.addFirstPartyOwner(pattern); },
        pattern -> { builder.addFirstPartyLicense(pattern); },
        KEY_FIRST_PARTY_PATTERN);
    // third-party
    addRule(cfg, rule -> { builder.addThirdParty(rule); }, KEY_THIRD_PARTY);
    addRulePattern(
        cfg,
        pattern -> { builder.addThirdPartyOwner(pattern); },
        pattern -> { builder.addThirdPartyLicense(pattern); },
        KEY_THIRD_PARTY_PATTERN);
    // forbidden
    addRule(cfg, rule -> { builder.addForbidden(rule); }, KEY_FORBIDDEN);
    addRulePattern(
        cfg,
        pattern -> { builder.addForbiddenOwner(pattern); },
        pattern -> { builder.addForbiddenLicense(pattern); },
        KEY_FORBIDDEN_PATTERN);
    if (hasErrors()) {
      // don't try to compile scanner if errors in configuration
      return;
    }
    CopyrightPatterns.RuleSet rules = builder.build();
    patternSignature = rules.signature();
    scanner = new CopyrightScanner(
        rules.firstPartyLicenses,
        rules.thirdPartyLicenses,
        rules.forbiddenLicenses,
        rules.firstPartyOwners,
        rules.thirdPartyOwners,
        rules.forbiddenOwners,
        rules.excludePatterns);
  }

  /** Returns true if {@code project} repository allows third-party code. */
  boolean isThirdPartyAllowed(String project) {
    return matchesAny(project, thirdPartyAllowedProjects);
  }

  /** Returns true if {@code fullPath} matches pattern always requiring review. e.g. PATENT */
  boolean isAlwaysReviewPath(String fullPath) {
    return matchesAny(fullPath, alwaysReviewPath);
  }

  /** Returns true if the configuration triggers any error messages. */
  boolean hasErrors() {
    return messages.stream().anyMatch(m -> m.getType().equals(ValidationMessage.Type.ERROR));
  }

  /** Formats and appends config validation messages to {@code sb}. */
  void appendMessages(StringBuilder sb) {
    for (CommitValidationMessage msg : messages) {
      sb.append("\n\n");
      sb.append(msg.getType().toString());
      sb.append(" ");
      sb.append(msg.getMessage());
    }
  }

  /** Returns true if any pattern in {@code regexes} found in {@code text}. */
  static boolean matchesAny(String text, Collection<Pattern> regexes) {
    requireNonNull(regexes);
    for (Pattern pattern : regexes) {
      if (pattern.matcher(text).find()) {
        return true;
      }
    }
    return false;
  }

  /** Looks up {@code key} in {@code cfg} adding values to {@code dest} as rule names. */
  private void addRule(
      PluginConfig cfg,
      Consumer<String> dest,
      String key) {
    for (String rule : cfg.getStringList(key)) {
      rule = Strings.nullToEmpty(rule).trim();
      if (rule.isEmpty()) {
        messages.add(
            errorMessage(pluginKeyValueMessage(key, rule, "missing license or owner name")));
        continue;
      }
      try {
        dest.accept(rule);
      } catch (UnknownPatternName e) {
        messages.add(errorMessage(pluginKeyValueMessage(key, rule, e.getMessage())));
      }
    }
  }

  /**
   * Looks up {@code key} in {@code cfg} adding {@code owner} patterns to {@code ownerDest} and
   * {@code license} patterns to {@code licenseDest}.
   */
  private void addRulePattern(
      PluginConfig cfg,
      Consumer<String> ownerDest,
      Consumer<String> licenseDest,
      String key) {
    for (String cfgValue : cfg.getStringList(key)) {
      cfgValue = Strings.nullToEmpty(cfgValue).trim();
      if (cfgValue.isEmpty()) {
        messages.add(
            errorMessage(
                pluginKeyValueMessage(key, cfgValue, "missing owner or license pattern")));
        continue;
      }
      if (cfgValue.toLowerCase().startsWith(OWNER)) {
        String pattern = cfgValue.substring(OWNER.length()).trim();
        ownerDest.accept(pattern);
      } else if (cfgValue.toLowerCase().startsWith(LICENSE)) {
        String pattern = cfgValue.substring(LICENSE.length()).trim();
        licenseDest.accept(pattern);
      } else {
        messages.add(
            errorMessage(
                pluginKeyValueMessage(
                    key,
                    cfgValue,
                    "missing 'owner' or 'license' keyword in '" + cfgValue + "'")));
      }
    }
  }

  /** Looks up {@code key} in {@code cfg} adding values to {@code dest} as strings. */
  private void addStringList(
      PluginConfig cfg,
      Collection<String> dest,
      String key,
      String shortDesc) {
    for (String s : cfg.getStringList(key)) {
      s = Strings.nullToEmpty(s).trim();
      if (s.isEmpty()) {
        messages.add(errorMessage(pluginKeyValueMessage(key, s, "missing " + shortDesc)));
        continue;
      }
      dest.add(s);
    }
  }

  /** Looks up {@code key} in {@code cfg} adding values to {@code dest} as regex patterns. */
  private void addPatternList(
      PluginConfig cfg,
      Collection<Pattern> dest,
      String key,
      String shortDesc) {
    for (String pattern : cfg.getStringList(key)) {
      pattern = Strings.nullToEmpty(pattern).trim();
      if (pattern.isEmpty()) {
        messages.add(errorMessage(pluginKeyValueMessage(key, pattern, "missing " + shortDesc)));
        continue;
      }
      try {
        dest.add(Pattern.compile(pattern));
      } catch (PatternSyntaxException e) {
        messages.add(errorMessage(pluginKeyValueMessage(key, pattern, e.getMessage())));
      }
    }
  }
}
