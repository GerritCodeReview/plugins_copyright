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

import static com.google.common.truth.Truth.assertThat;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_ALWAYS_REVIEW_PATH;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_ENABLE;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_EXCLUDE;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_FIRST_PARTY;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_FORBIDDEN;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_FORBIDDEN_PATTERN;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_REVIEW_LABEL;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_THIRD_PARTY;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_THIRD_PARTY_ALLOWED_PROJECTS;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.common.data.GroupReference;
import com.google.gerrit.extensions.common.GroupInfo;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.project.GroupList;
import com.google.gerrit.server.project.ProjectConfig;
import java.util.Arrays;
import java.util.function.Consumer;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevBlob;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;

/** Test helper for copyright plugin All-Projects configurations. */
class TestConfig {
  static final String LOCAL_BRANCH = "config";

  private static final String ACCESS = "access";
  private static final String LABEL = "label";
  private static final String PLUGIN = "plugin";

  private Project.NameKey project;
  private String pluginName;
  private TestAccount owner;
  private TestRepository<InMemoryRepository> testRepo;
  private Config configProject;
  private Config configPlugin;
  private PluginConfig pluginConfig;
  private GroupList groupList;
  private String commitMsg;

  TestConfig(
      Project.NameKey project,
      String pluginName,
      TestAccount owner,
      TestRepository<InMemoryRepository> testRepo)
      throws Exception {
    this.project = project;
    this.pluginName = pluginName;
    this.owner = owner;
    this.testRepo = testRepo;
    readConfigProject();
    extractPlugin();
    this.commitMsg = "This is a commit.";
  }

  static final Consumer<PluginConfig> BASE_CONFIG =
      pCfg -> {
        pCfg.setStringList(KEY_ALWAYS_REVIEW_PATH, ImmutableList.of("PATENT$"));
        pCfg.setStringList(
            KEY_THIRD_PARTY_ALLOWED_PROJECTS, ImmutableList.of("ThirdParty(?:Owner)?Allowed"));
        pCfg.setString(KEY_REVIEW_LABEL, "Copyright-Review");
        pCfg.setStringList(KEY_EXCLUDE, ImmutableList.of("EXAMPLES"));
        pCfg.setStringList(
            KEY_FIRST_PARTY, ImmutableList.of("APACHE2", "ANDROID", "GOOGLE", "EXAMPLES"));
        pCfg.setStringList(
            KEY_THIRD_PARTY, ImmutableList.of("BSD", "MIT", "EPL", "GPL2", "GPL3", "PSFL"));
        pCfg.setStringList(
            KEY_FORBIDDEN,
            ImmutableList.of(
                "AGPL",
                "NOT_A_CONTRIBUTION",
                "WTFPL",
                "CC_BY_NC",
                "NON_COMMERCIAL",
                "COMMONS_CLAUSE"));
        pCfg.setStringList(
            KEY_FORBIDDEN_PATTERN,
            ImmutableList.of("license .*(?:Previously|formerly) licen[cs]ed under.*"));
      };

  static final Consumer<PluginConfig> ENABLE_CONFIG =
      pCfg -> {
        pCfg.setBoolean(KEY_ENABLE, true);
      };
  static final Consumer<PluginConfig> DISABLE_CONFIG =
      pCfg -> {
        pCfg.setBoolean(KEY_ENABLE, false);
      };

  /** Adds {@code groups} to the groups file. */
  void addGroups(GroupInfo... groups) throws Exception {
    if (groups == null) {
      return;
    }
    for (GroupInfo group : groups) {
      AccountGroup.UUID uuid = AccountGroup.uuid(group.id);
      groupList.put(uuid, new GroupReference(uuid, group.name));
    }
  }

  /**
   * Make a copy of an existing label configuration {@code fromLabel} under a different name, {@code
   * toLabel}.
   *
   * <p>Useful shortcut for configuring a Copyright-Review label based on the default Code-Review
   * label.
   */
  void copyLabel(String fromLabel, String toLabel) throws Exception {
    for (String name : configProject.getNames(LABEL, fromLabel)) {
      configProject.setStringList(
          LABEL, toLabel, name, Arrays.asList(configProject.getStringList(LABEL, fromLabel, name)));
    }
    for (String sub : configProject.getSubsections(ACCESS)) {
      for (String name : configProject.getNames(ACCESS, sub)) {
        if (!name.endsWith("-" + fromLabel)) {
          continue;
        }
        String[] values = configProject.getStringList(ACCESS, sub, name);
        if (values == null || values.length == 0) {
          continue;
        }
        configProject.setStringList(
            ACCESS,
            sub,
            name.substring(0, name.length() - fromLabel.length()) + toLabel,
            Arrays.asList(values));
      }
    }
  }

  /** Creates a commit for the current configuration and pushes the commit to trigger validation. */
  PushOneCommit.Result push(PushOneCommit.Factory pushFactory) throws Exception {
    savePlugin();
    String subject = "Change All-Projects project.config\n\n" + commitMsg;
    PersonIdent author = owner.newIdent();
    PushOneCommit pushCommit =
        pushFactory.create(
            author,
            testRepo,
            subject,
            ImmutableMap.<String, String>of(
                ProjectConfig.PROJECT_CONFIG,
                configProject.toText(),
                GroupList.FILE_NAME,
                groupList.asText()));
    return pushCommit.to("refs/for/" + RefNames.REFS_CONFIG);
  }

  /** Adds {@code message} to the /COMMIT_MSG text for the push. */
  void commitMessage(String message) throws Exception {
    commitMsg = commitMsg + message;
  }

  /** Deletes the label section and voter configurations for {@code label}. */
  void removeLabel(String label) throws Exception {
    for (String sub : configProject.getSubsections(ACCESS)) {
      configProject.setStringList(ACCESS, sub, "label-" + label, ImmutableList.of());
      configProject.setStringList(ACCESS, sub, "labelAs-" + label, ImmutableList.of());
    }
    configProject.unsetSection(LABEL, label);
  }

  /** Deletes the voter configurations for {@code label} from the access section for {@code ref}. */
  void removeVoters(String ref, String label) throws Exception {
    configProject.setStringList(ACCESS, ref, "label-" + label, ImmutableList.of());
  }

  /**
   * Replaces the voter configuration for {@code label} in the access section for {@code ref} with
   * {@code voters}.
   */
  void setVoters(String ref, String label, Voter... voters) throws Exception {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    for (Voter v : voters) {
      builder.add(vString(v.minVote) + ".." + vString(v.maxVote) + " group " + v.groupName);
    }
    configProject.setStringList(ACCESS, ref, "label-" + label, builder.build());
  }

  /** Apply {@code updates} to {@code this.pluginConfig}. */
  void updatePlugin(Consumer<PluginConfig>... updates) throws Exception {
    for (Consumer<PluginConfig> update : updates) {
      update.accept(pluginConfig);
    }
  }

  /** Extracts the plugin section from the project config and creates a PluginConfig view of it. */
  private void extractPlugin() throws Exception {
    configPlugin = new Config();
    for (String name : configProject.getNames(PLUGIN, pluginName)) {
      configPlugin.setStringList(
          PLUGIN,
          pluginName,
          name,
          Arrays.asList(configProject.getStringList(PLUGIN, pluginName, name)));
    }
    pluginConfig = new PluginConfig(pluginName, configPlugin);
  }

  /** Reads the project.config and groups files from {@code this.testRepo}. */
  private void readConfigProject() throws Exception {
    Ref ref = testRepo.getRepository().exactRef(LOCAL_BRANCH);
    configProject = new Config();
    configProject.fromText(readFileContents(ref, ProjectConfig.PROJECT_CONFIG));
    groupList = GroupList.parse(project, readFileContents(ref, GroupList.FILE_NAME), e -> {});
  }

  /** Reads the contents of {@code ref}/{@code path} from {@code this.restRepo}. */
  private String readFileContents(Ref ref, String path) throws Exception {
    RevWalk rw = testRepo.getRevWalk();
    RevObject obj = testRepo.get(rw.parseTree(ref.getObjectId()), path);
    assertThat(obj).isInstanceOf(RevBlob.class);
    ObjectLoader loader = rw.getObjectReader().open(obj);
    return new String(loader.getCachedBytes(), UTF_8);
  }

  /**
   * Replaces the plugin section in {@code this.configProject} with the contents of {@code
   * this.configPlugin}.
   */
  private void savePlugin() throws Exception {
    configProject.unsetSection(PLUGIN, pluginName);
    for (String name : configPlugin.getNames(PLUGIN, pluginName)) {
      configProject.setStringList(
          PLUGIN,
          pluginName,
          name,
          Arrays.asList(configPlugin.getStringList(PLUGIN, pluginName, name)));
    }
  }

  /** Formats integers with an explicit sign for both positive and negative values. */
  private String vString(int vote) throws Exception {
    return (vote > 0 ? "+" : "") + Integer.toString(vote);
  }

  /** A group name and allowed vote range. */
  static class Voter {
    String groupName;
    int minVote;
    int maxVote;

    Voter(String groupName, int minVote, int maxVote) {
      this.groupName = groupName;
      this.minVote = minVote;
      this.maxVote = maxVote;
    }
  }
}
