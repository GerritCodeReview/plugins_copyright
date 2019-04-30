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
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_FROM;
import static com.googlesource.gerrit.plugins.copyright.ScannerConfig.KEY_REVIEWER;
import static com.googlesource.gerrit.plugins.copyright.TestConfig.LOCAL_BRANCH;

import com.google.common.collect.ImmutableList;
import com.google.common.truth.Correspondence;
import com.google.gerrit.acceptance.GerritConfig;
import com.google.gerrit.acceptance.GerritConfigs;
import com.google.gerrit.acceptance.GitUtil;
import com.google.gerrit.acceptance.LightweightPluginDaemonTest;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.TestAccount;
import com.google.gerrit.acceptance.TestPlugin;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.client.Change;
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
import org.eclipse.jgit.junit.TestRepository;
import org.junit.Before;
import org.junit.Test;

@TestPlugin(name = "copyright", sysModule = "com.googlesource.gerrit.plugins.copyright.Module")
public class CopyrightValidatorIT extends LightweightPluginDaemonTest {
  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightValidator_pushNoLicense() throws Exception {
    PushOneCommit.Result result =
        pushFactory
            .create(author.newIdent(), testRepo, "subject", "filename", "content")
            .to("refs/for/master");

    assertNoReviewerAdded(result);
    assertThat(result.getChange().notes().getComments()).isEmpty();
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightValidator_pushAlwaysReview() throws Exception {
    PushOneCommit.Result result =
        pushFactory
            .create(author.newIdent(), testRepo, "subject", "PATENT", "content")
            .to("refs/for/master");

    assertReviewerAdded(result);
    assertThat(result.getChange().notes().getComments().values())
        .comparingElementsUsing(commentContains())
        .containsExactly(unresolved("PATENT always requires"));
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightValidator_pushFirstPartyOwner() throws Exception {
    PushOneCommit.Result result =
        pushFactory
            .create(author.newIdent(), testRepo, "subject", "source.cpp", FIRST_PARTY_OWNER)
            .to("refs/for/master");

    assertNoReviewerAdded(result);
    assertThat(result.getChange().notes().getComments().values())
        .comparingElementsUsing(commentContains())
        .containsExactly(resolved("First-party author or owner"));
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightValidator_pushFirstPartyHeader() throws Exception {
    PushOneCommit.Result result =
        pushFactory
            .create(author.newIdent(), testRepo, "subject", "source.cpp", FIRST_PARTY_HEADER)
            .to("refs/for/master");

    assertNoReviewerAdded(result);
    assertThat(result.getChange().notes().getComments().values())
        .comparingElementsUsing(commentContains())
        .containsExactly(resolved("First-party license"));
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightValidator_pushFirstPartyOwnerAndHeader() throws Exception {
    PushOneCommit.Result result =
        pushFactory
            .create(
                author.newIdent(),
                testRepo,
                "subject",
                "source.cpp",
                FIRST_PARTY_OWNER + FIRST_PARTY_HEADER)
            .to("refs/for/master");

    assertNoReviewerAdded(result);
    assertThat(result.getChange().notes().getComments().values())
        .comparingElementsUsing(commentContains())
        .containsExactly(resolved("First-party license")); // owner folds into license
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightValidator_pushFirstPartyHeaderAndOwner() throws Exception {
    PushOneCommit.Result result =
        pushFactory
            .create(
                author.newIdent(),
                testRepo,
                "subject",
                "source.cpp",
                FIRST_PARTY_HEADER + FIRST_PARTY_OWNER)
            .to("refs/for/master");

    assertNoReviewerAdded(result);
    assertThat(result.getChange().notes().getComments().values())
        .comparingElementsUsing(commentContains())
        .containsExactly(resolved("First-party license")); // owner folds into license
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightValidator_pushNotAContribHeader() throws Exception {
    PushOneCommit.Result result =
        pushFactory
            .create(author.newIdent(), testRepo, "subject", "source.cpp", NOT_A_CONTRIB_HEADER)
            .to("refs/for/master");

    assertReviewerAdded(result);
    assertThat(result.getChange().notes().getComments().values())
        .comparingElementsUsing(commentContains())
        .containsExactly(resolved("First-party license"), unresolved("Disapproved license"));
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightValidator_pushThirdPartyAllowed() throws Exception {
    PushOneCommit.Result result =
        pushFactory
            .create(author.newIdent(), testRepo, "subject", "LICENSE", THIRD_PARTY_MIT)
            .to("refs/for/master");

    assertNoReviewerAdded(result);
    assertThat(result.getChange().notes().getComments().values())
        .comparingElementsUsing(commentContains())
        .containsExactly(resolved("Third-party license allowed"));
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightValidator_pushThirdPartyNotAllowed() throws Exception {
    PushOneCommit.Result result =
        pushFactory
            .create(author.newIdent(), testRepo, "subject", "LICENSE", THIRD_PARTY_MIT)
            .to("refs/for/master");

    assertReviewerAdded(result);
    assertThat(result.getChange().notes().getComments().values())
        .comparingElementsUsing(commentContains())
        .containsExactly(unresolved("Third-party license disallowed"));
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightValidator_pushThirdPartyOwnerAllowed() throws Exception {
    PushOneCommit.Result result =
        pushFactory
            .create(author.newIdent(), testRepo, "subject", "COPYING", THIRD_PARTY_OWNER)
            .to("refs/for/master");

    assertNoReviewerAdded(result);
    assertThat(result.getChange().notes().getComments().values())
        .comparingElementsUsing(commentContains())
        .containsExactly(resolved("Third-party author or owner allowed"));
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightValidator_pushThirdPartyOwnerNotAllowed() throws Exception {
    PushOneCommit.Result result =
        pushFactory
            .create(author.newIdent(), testRepo, "subject", "COPYING", THIRD_PARTY_OWNER)
            .to("refs/for/master");

    assertReviewerAdded(result);
    assertThat(result.getChange().notes().getComments().values())
        .comparingElementsUsing(commentContains())
        .containsExactly(unresolved("Third-party author or owner disallowed"));
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightValidator_pushFirstPartyLicenseThirdPartyOwner() throws Exception {
    PushOneCommit.Result result =
        pushFactory
            .create(
                author.newIdent(),
                testRepo,
                "subject",
                "COPYING",
                FIRST_PARTY_HEADER + THIRD_PARTY_OWNER)
            .to("refs/for/master");

    assertNoReviewerAdded(result);
    assertThat(result.getChange().notes().getComments().values())
        .comparingElementsUsing(commentContains())
        .containsExactly(
            resolved("First-party license"),
            resolved("Third-party author or owner")); // 1p license from 3p author is 1p
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightValidator_pushFirstPartyOwnerThirdPartyOwner() throws Exception {
    PushOneCommit.Result result =
        pushFactory
            .create(
                author.newIdent(),
                testRepo,
                "subject",
                "COPYING",
                FIRST_PARTY_OWNER + THIRD_PARTY_OWNER)
            .to("refs/for/master");

    assertReviewerAdded(result);
    assertThat(result.getChange().notes().getComments().values())
        .comparingElementsUsing(commentContains())
        .containsExactly(
            resolved("First-party author or owner"),
            unresolved("Third-party author or owner disallowed"));
  }

  @Test
  @GerritConfigs({
    @GerritConfig(name = "plugin.copyright.enable", value = "true"),
    @GerritConfig(name = "plugin.copyright.timeTestMax", value = "0")
  })
  public void testCopyrightValidator_pushThirdPartyLicenseFirstPartyOwner() throws Exception {
    PushOneCommit.Result result =
        pushFactory
            .create(
                author.newIdent(),
                testRepo,
                "subject",
                "COPYING",
                THIRD_PARTY_MIT + FIRST_PARTY_OWNER)
            .to("refs/for/master");

    assertReviewerAdded(result);
    assertThat(result.getChange().notes().getComments().values())
        .comparingElementsUsing(commentContains())
        .containsExactly(
            unresolved("Third-party license disallowed"), resolved("First-party author or owner"));
  }

  private static final String FIRST_PARTY_OWNER =
      "// Copyright (C) 2019 The Android Open Source Project\n";
  private static final String FIRST_PARTY_HEADER =
      "// Licensed under the Apache License, Version 2.0 (the \"License\");\n"
          + "// you may not use this file except in compliance with the License.\n"
          + "// You may obtain a copy of the License at\n//\n"
          + "// http://www.apache.org/licenses/LICENSE-2.0\n//\n"
          + "// Unless required by applicable law or agreed to in writing, software\n"
          + "// distributed under the License is distributed on an \"AS IS\" BASIS,\n"
          + "// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n"
          + "// See the License for the specific language governing permissions and\n"
          + "// limitations under the License.\n";
  private static final String NOT_A_CONTRIB_HEADER = FIRST_PARTY_HEADER + "Not a contribution.\n";
  private static final String THIRD_PARTY_MIT =
      "MIT License\n\nCopyright (c) Jane Doe\n\n"
          + "Permission is hereby granted, free of charge, to any person obtaining a copy\n"
          + "of this software and associated documentation files (the \"Software\"), to deal\n"
          + "in the Software without restriction, including without limitation the rights\n"
          + "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n"
          + "copies of the Software, and to permit persons to whom the Software is\n"
          + "furnished to do so, subject to the following conditions:\n\n"
          + "The above copyright notice and this permission notice shall be included in all\n"
          + "copies or substantial portions of the Software.\n\n"
          + "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n"
          + "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n"
          + "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n"
          + "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n"
          + "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n"
          + "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n"
          + "SOFTWARE.\n";
  private static final String THIRD_PARTY_OWNER = "Copyright (c) 2019 Acme Other Corp.\n";

  private static int nextId = 123;

  @Inject @ServerInitiated private Provider<GroupsUpdate> groupsUpdateProvider;

  private InternalGroup botGroup;
  private InternalGroup expertGroup;
  private TestAccount pluginAccount;
  private TestAccount reviewer;
  private TestAccount observer;
  private TestAccount author;
  private String projectConfigContent;

  @Before
  public void setUp() throws Exception {
    botGroup = testGroup("Non-Interactive Users");
    expertGroup = testGroup("Copyright Experts");
    pluginAccount =
        accountCreator.create(
            "copyright-scanner",
            "copyright-scanner@example.com",
            "Copyright Scanner",
            "Non-Interactive Users",
            expertGroup.getName());
    reviewer =
        accountCreator.create(
            "lawyercat", "legal@example.com", "J. Doe J.D. LL.M. Esq.", expertGroup.getName());
    observer = accountCreator.create("my-team", "my-team@example.com", "My Team");
    author = accountCreator.create("author", "author@example.com", "J. Doe");
    TestRepository<InMemoryRepository> testRepo = getTestRepo(allProjects);
    TestConfig testConfig = new TestConfig(allProjects, plugin.getName(), admin, testRepo);
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
        cfg -> {
          cfg.setStringList(KEY_REVIEWER, ImmutableList.of(reviewer.username()));
        },
        cfg -> {
          cfg.setStringList(KEY_CC, ImmutableList.of(observer.username()));
        },
        cfg -> {
          cfg.setInt(KEY_FROM, pluginAccount.id().get());
        });
    projectConfigContent = testConfig.getProjectConfigContents();
    PushOneCommit.Result result = testConfig.push(pushFactory);
    result.assertOkStatus();
    assertThat(result.getChange().publishedComments()).isEmpty();
    merge(result);
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
    InternalGroupCreation gc =
        InternalGroupCreation.builder()
            .setGroupUUID(new AccountGroup.UUID("users-" + name.replace(" ", "_")))
            .setNameKey(nameKey)
            .setId(nextGroupId())
            .build();
    InternalGroupUpdate gu = InternalGroupUpdate.builder().setName(nameKey).build();
    return groupsUpdate.createGroup(gc, gu);
  }

  private void assertReviewerAdded(PushOneCommit.Result result) throws Exception {
    result.assertOkStatus();
    result.assertChange(
        Change.Status.NEW,
        null,
        ImmutableList.of(author, reviewer),
        ImmutableList.of(pluginAccount, observer));
  }

  private void assertNoReviewerAdded(PushOneCommit.Result result) throws Exception {
    result.assertOkStatus();
    result.assertChange(
        Change.Status.NEW, null, ImmutableList.of(author), ImmutableList.of(pluginAccount));
  }

  private CommentMatch resolved(String content) {
    return new CommentMatch(true /* resolved */, content);
  }

  private CommentMatch unresolved(String content) {
    return new CommentMatch(false /* resolved */, content);
  }

  private static Correspondence<Comment, CommentMatch> commentContains() {
    return new Correspondence<Comment, CommentMatch>() {
      @Override
      public boolean compare(Comment actual, CommentMatch expected) {
        return actual.unresolved != expected.resolved && actual.message.contains(expected.content);
      }

      @Override
      public String toString() {
        return "comment resolution status and content matches";
      }
    };
  }

  private static class CommentMatch {
    boolean resolved;
    String content;

    CommentMatch(boolean resolved, String content) {
      this.resolved = resolved;
      this.content = content;
    }

    @Override
    public String toString() {
      return (resolved ? "" : "un") + "resolved(\"" + content + "\")";
    }
  }
}
