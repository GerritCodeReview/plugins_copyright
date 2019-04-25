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

import static com.google.common.truth.Truth.assertThat;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_CC;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_FIRST_PARTY;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_FIRST_PARTY_PATTERN;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_FROM;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_REVIEWER;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_REVIEW_LABEL;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_THIRD_PARTY_PATTERN;
import static com.googlesource.gerrit.plugins.copyright.TestConfig.LOCAL_BRANCH;

import com.google.common.collect.ImmutableList;
import com.google.common.truth.Correspondence;
import com.google.gerrit.acceptance.AccountCreator;
import com.google.gerrit.acceptance.GerritConfig;
import com.google.gerrit.acceptance.GerritConfigs;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.client.Comment;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.ServerInitiated;
import com.google.gerrit.server.group.InternalGroup;
import com.google.gerrit.server.group.db.GroupsUpdate;
import com.google.gerrit.server.group.db.InternalGroupCreation;
import com.google.gerrit.server.group.db.InternalGroupUpdate;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.util.Optional;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.util.RawParseUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.Description;

@TestPlugin(
    name = "copyright",
    sysModule = "com.googlesource.gerrit.plugins.copyright.Module")
public class CopyrightConfigIT extends LightweightPluginDaemonTest {
  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightConfig_pushGoodConfig() throws Exception {
    PushOneCommit.Result actual = testConfig.push(pushFactory);
    actual.assertOkStatus();
    assertThat(actual.getChange().publishedComments()).isEmpty();
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightConfig_pushWithoutSender() throws Exception {
    testConfig.updatePlugin(cfg -> { cfg.setString(KEY_FROM, ""); });

    testConfig.push(pushFactory).assertErrorStatus("no \"" + KEY_FROM + " =\" key was found");
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "false"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightConfig_pushDisabledWithoutSender() throws Exception {
    testConfig.updatePlugin(cfg -> { cfg.setString(KEY_FROM, ""); });

    PushOneCommit.Result actual = testConfig.push(pushFactory);
    String expected = "no \"" + KEY_FROM + " =\" key was found";
    actual.assertMessage(expected);
    actual.assertOkStatus();
    assertThat(actual.getChange().publishedComments())
        .comparingElementsUsing(warningContains())
        .containsExactly(expected);
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightConfig_pushWithSenderNoName() throws Exception {
    TestAccount anonymous = accountCreator.create(
        "copyright-scan", "", "", "Non-Interactive Users", expertGroup.getName());
    testConfig.updatePlugin(cfg -> { cfg.setInt(KEY_FROM, anonymous.id().get()); });

    PushOneCommit.Result actual = testConfig.push(pushFactory);
    String expected = "has no full name";
    actual.assertMessage(expected);
    actual.assertOkStatus();
    assertThat(actual.getChange().publishedComments())
        .comparingElementsUsing(warningContains())
        .containsExactly(expected);
  }

  @Test
  @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  public void testCopyrightConfig_pushWithNonNumericSender() throws Exception {
    testConfig.updatePlugin(cfg -> { cfg.setString(KEY_FROM, "some random string"); });

    testConfig.push(pushFactory).assertErrorStatus();
  }

  @Test
  @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  public void testCopyrightConfig_pushWithoutReviewers() throws Exception {
    testConfig.updatePlugin(cfg -> { cfg.setStringList(KEY_REVIEWER, ImmutableList.of()); });

    PushOneCommit.Result actual = testConfig.push(pushFactory);
    String expected = "no \"" + KEY_REVIEWER + " =\" key was found";
    actual.assertMessage(expected);
    actual.assertOkStatus();
    assertThat(actual.getChange().publishedComments())
        .comparingElementsUsing(warningContains())
        .containsExactly(expected);
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightConfig_pushWithoutLabelConfig() throws Exception {
    testConfig.removeLabel("Copyright-Review");

    testConfig.push(pushFactory)
        .assertErrorStatus("no [label \"Copyright-Review\"] section configured");
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightConfig_pushWithoutVoters() throws Exception {
    testConfig.removeVoters(RefNames.REFS_HEADS + "*", "Copyright-Review");

    testConfig.push(pushFactory).assertErrorStatus("no configured approvers");
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightConfig_pushWithoutConfigVoters() throws Exception {
    testConfig.removeVoters(RefNames.REFS_CONFIG, "Copyright-Review");

    testConfig.push(pushFactory).assertErrorStatus("no configured approvers");
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightConfig_pushWithoutReviewLabel() throws Exception {
    testConfig.updatePlugin(cfg -> { cfg.setString(KEY_REVIEW_LABEL, " "); });

    testConfig.push(pushFactory)
        .assertErrorStatus("no \"" + KEY_REVIEW_LABEL + " =\" key was found");
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "false"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightConfig_pushDisabledWithoutReviewLabel() throws Exception {
    testConfig.updatePlugin(cfg -> { cfg.setString(KEY_REVIEW_LABEL, " "); });

    PushOneCommit.Result actual = testConfig.push(pushFactory);
    String expected = "no \"" + KEY_REVIEW_LABEL + " =\" key was found";
    actual.assertMessage(expected);
    actual.assertOkStatus();
    assertThat(actual.getChange().publishedComments())
        .comparingElementsUsing(warningContains())
        .containsExactly(expected);
  }

  @Test
  @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  public void testCopyrightConfig_pushPatternWithCapture() throws Exception {
    testConfig.updatePlugin(cfg -> { cfg.setString(KEY_FIRST_PARTY_PATTERN, "owner (capture group)"); });

    testConfig.push(pushFactory).assertErrorStatus();
  }

  @Test
  @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  public void testCopyrightConfig_pushPatternWithNonCapture() throws Exception {
    testConfig.updatePlugin(
        cfg -> { cfg.setString(KEY_THIRD_PARTY_PATTERN, "owner (?:non-capture group)"); });

    PushOneCommit.Result actual = testConfig.push(pushFactory);
    actual.assertOkStatus();
    assertThat(actual.getChange().publishedComments()).isEmpty();
  }

  @Test
  @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  public void testCopyrightConfig_pushPatternWithOpenNonCapture() throws Exception {
    testConfig.updatePlugin(
        cfg -> { cfg.setString(KEY_THIRD_PARTY_PATTERN, "owner (?:non-capture group"); });

    testConfig.push(pushFactory).assertErrorStatus();
  }

  @Test
  @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  public void testCopyrightConfig_pushPatternWithUnknownRuleName() throws Exception {
    testConfig.updatePlugin(cfg -> { cfg.setString(KEY_FIRST_PARTY, "not a valid rule name"); });

    testConfig.push(pushFactory).assertErrorStatus("Unknown license or copyright owner name");
  }

  @Test
  @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  public void testCopyrightConfig_pushPatternWithOpenCharacterClass() throws Exception {
    testConfig.updatePlugin(
        cfg -> { cfg.setString(KEY_FIRST_PARTY_PATTERN, "license non-terminated ["); });

    testConfig.push(pushFactory).assertErrorStatus();
  }

  @Test
  @GerritConfig(name = "plugin.copyright.timeTestMax", value = "1")
  public void testCopyrightConfig_pushPatternWithoutTimeSignature() throws Exception {
    testConfig.push(pushFactory).assertErrorStatus("please run check_new_config tool");
  }

  @Test
  @GerritConfig(name = "plugin.copyright.timeTestMax", value = "1")
  public void testCopyrightConfig_pushPatternWithWrongTimeSignature() throws Exception {
    testConfig.commitMessage("Copyright-check: b0bb4.3141596634344624");
    testConfig.push(pushFactory).assertErrorStatus("results for wrong commit");
  }

  @Test
  @GerritConfig(name = "plugin.copyright.timeTestMax", value = "8")
  public void testCopyrightConfig_pushPatternWithRightTimeSignature() throws Exception {
    testConfig.commitMessage("Copyright-check: b0bb4.40bd43852e4bcf12");
    testConfig.push(pushFactory).assertOkStatus();
  }

  @Test
  @GerritConfig(name = "plugin.copyright.timeTestMax", value = "8")
  public void testCopyrightConfig_pushPatternWithTooLongTimeSignature() throws Exception {
    testConfig.commitMessage("Copyright-check: 7a1201.677dcd085b30b4e1");
    testConfig.push(pushFactory).assertErrorStatus("took longer than");
  }

  private static int nextId = 123;

  @Inject @ServerInitiated private Provider<GroupsUpdate> groupsUpdateProvider;

  private TestAccount sender;
  private TestAccount reviewer;
  private TestAccount observer;
  private InternalGroup botGroup;
  private InternalGroup expertGroup;
  private TestConfig testConfig;

  @Before
  public void setUp() throws Exception {
    botGroup = testGroup("Non-Interactive Users");
    expertGroup = testGroup("Copyright Experts");
    sender = accountCreator.create(
        "copyright-scanner",
        "copyright-scanner@example.com",
        "Copyright Scanner",
        "Non-Interactive Users",
        expertGroup.getName());
    reviewer = accountCreator.create(
        "lawyercat",
        "legal@example.com",
        "J. Doe J.D. LL.M. Esq.",
        expertGroup.getName());
    observer = accountCreator.create(
        "my-team",
        "my-team@example.com",
        "My Team");
    testRepo = getTestRepo(allProjects);
    testConfig = new TestConfig(allProjects, plugin.getName(), admin, testRepo);
    testConfig.copyLabel("Code-Review", "Copyright-Review");
    testConfig.setVoters(
        RefNames.REFS_HEADS + "*",
        "Copyright-Review",
         new TestConfig.Voter("Administrators", -2, +2),
         new TestConfig.Voter(expertGroup.getNameKey().get(), -2, +2),
         new TestConfig.Voter("Registered Users", -2, 0));
    testConfig.addGroups(botGroup, expertGroup);
    testConfig.updatePlugin(
        TestConfig.BASE_CONFIG,
        TestConfig.ENABLE_CONFIG,
        cfg -> { cfg.setStringList(KEY_REVIEWER, ImmutableList.of(reviewer.username())); },
        cfg -> { cfg.setStringList(KEY_CC, ImmutableList.of(observer.username())); },
        cfg -> { cfg.setInt(KEY_FROM, sender.id().get()); });
  }

  private AccountGroup.Id nextGroupId() {
    return new AccountGroup.Id(nextId++);
  }

  private TestRepository<InMemoryRepository> getTestRepo(Project.NameKey projectName)
      throws Exception {
    TestRepository<InMemoryRepository> testRepo = cloneProject(projectName, admin);
    GitUtil.fetch(testRepo, RefNames.REFS_CONFIG + ":" + LOCAL_BRANCH);
    testRepo.reset(LOCAL_BRANCH);
    return testRepo;
  }

  private InternalGroup testGroup(String name) throws Exception {
    AccountGroup.NameKey nameKey = new AccountGroup.NameKey(name);
    Optional<InternalGroup> g = groupCache.get(nameKey);
    if (g.isPresent()) {
      return g.get();
    }
    GroupsUpdate groupsUpdate = groupsUpdateProvider.get();
    InternalGroupCreation gc = InternalGroupCreation.builder()
        .setGroupUUID(new AccountGroup.UUID("users-" + name.replace(" ", "_")))
        .setNameKey(nameKey)
        .setId(nextGroupId())
        .build();
    InternalGroupUpdate gu = InternalGroupUpdate.builder()
        .setName(nameKey)
        .build();
    return groupsUpdate.createGroup(gc, gu);
  }

  private static Correspondence<Comment, String> warningContains() {
    return new Correspondence<Comment, String>() {
        @Override
        public boolean compare(Comment actual, String expected) {
          return actual.message.startsWith("WARNING ") && actual.message.contains(expected);
        }

        @Override
        public String toString() {
          return "matches regex";
        }
      };
  }
}
