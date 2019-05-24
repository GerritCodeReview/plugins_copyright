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
import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.MatchType.AUTHOR_OWNER;
import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.MatchType.LICENSE;
import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType.FIRST_PARTY;
import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType.FORBIDDEN;
import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType.THIRD_PARTY;
import static com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.PartyType.UNKNOWN;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;

import com.google.common.collect.ImmutableList;
import com.google.common.truth.Correspondence;
import com.google.gerrit.extensions.api.changes.AddReviewerInput;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewInput.CommentInput;
import com.google.gerrit.extensions.client.ReviewerState;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.PluginUser;
import com.google.gerrit.server.account.GroupMembership;
import com.google.gerrit.server.util.ThreadLocalRequestContext;
import com.googlesource.gerrit.plugins.copyright.lib.CopyrightScanner.Match;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CopyrightReviewApiTest {
  private static final PluginUser pluginUser = new FakePluginUser();
  private static final CurrentUser currentUser = new FakeCurrentUser();

  private IdentifiedUser.GenericFactory identifiedUserFactory =
      createMock(IdentifiedUser.GenericFactory.class);

  private ThreadLocalRequestContext requestContext = createMock(ThreadLocalRequestContext.class);

  private CopyrightReviewApi reviewApi;

  @Before
  public void setUp() throws Exception {
    reviewApi =
        new CopyrightReviewApi(
            null, () -> pluginUser, () -> currentUser, identifiedUserFactory, requestContext, null);
  }

  @Test
  public void testGetSendingUser_fromAccountIdConfigured() throws Exception {
    expect(
            identifiedUserFactory.runAs(
                eq(null), anyObject(Account.Id.class), anyObject(PluginUser.class)))
        .andReturn(null);
    replay(identifiedUserFactory);

    CurrentUser from = reviewApi.getSendingUser(808);

    verify(identifiedUserFactory);
  }

  @Test
  public void testGetSendingUser_noFromAccountIdConfigured() throws Exception {
    replay(identifiedUserFactory);

    CurrentUser from = reviewApi.getSendingUser(0);

    verify(identifiedUserFactory);
    assertThat(from).isSameAs(currentUser);
  }

  @Test
  public void testAddReviewers_addNone() throws Exception {
    ReviewInput ri = new ReviewInput();
    ri = reviewApi.addReviewers(ri, ImmutableList.of(), ReviewerState.CC);
    assertThat(ri.reviewers).isNull();
    ri = reviewApi.addReviewers(ri, ImmutableList.of(), ReviewerState.REVIEWER);
    assertThat(ri.reviewers).isNull();
  }

  @Test
  public void testAddReviewers_addCC() throws Exception {
    ReviewInput ri = new ReviewInput();
    ri = reviewApi.addReviewers(ri, ImmutableList.of("someone"), ReviewerState.CC);
    assertThat(ri.reviewers).comparingElementsUsing(addressedTo()).containsExactly("CC:someone");
  }

  @Test
  public void testAddReviewers_addReviewer() throws Exception {
    ReviewInput ri = new ReviewInput();
    ri = reviewApi.addReviewers(ri, ImmutableList.of("someone"), ReviewerState.REVIEWER);
    assertThat(ri.reviewers)
        .comparingElementsUsing(addressedTo())
        .containsExactly("REVIEWER:someone");
  }

  @Test
  public void testAddReviewers_addMultiple() throws Exception {
    ReviewInput ri = new ReviewInput();
    ri =
        reviewApi.addReviewers(
            ri, ImmutableList.of("someone", "someone else"), ReviewerState.REVIEWER);
    ri = reviewApi.addReviewers(ri, ImmutableList.of("another", "and another"), ReviewerState.CC);
    assertThat(ri.reviewers)
        .comparingElementsUsing(addressedTo())
        .containsExactly(
            "REVIEWER:someone", "REVIEWER:someone else", "CC:another", "CC:and another");
  }

  @Test
  public void testContainsComment_empty() throws Exception {
    assertThat(reviewApi.containsComment(ImmutableList.of(), CI("text", 1, 2))).isFalse();
  }

  @Test
  public void testContainsComment_identity() throws Exception {
    CommentInput ci = CI("test text", 2, 3);
    assertThat(reviewApi.containsComment(ImmutableList.of(ci), ci)).isTrue();
  }

  @Test
  public void testContainsComment_sameValues() throws Exception {
    CommentInput ci = CI("test text", 2, 3);
    CommentInput twin = CI("test text", 2, 3);
    assertThat(reviewApi.containsComment(ImmutableList.of(ci), twin)).isTrue();
  }

  @Test
  public void testContainsComment_singleDifferentRangeStart() throws Exception {
    CommentInput ci = CI("a comment", 4, 5);
    CommentInput otherRange = CI("a comment", 3, 5);
    assertThat(reviewApi.containsComment(ImmutableList.of(ci), otherRange)).isFalse();
  }

  @Test
  public void testContainsComment_singleDifferentRangeEnd() throws Exception {
    CommentInput ci = CI("a comment", 6, 7);
    CommentInput otherRange = CI("a comment", 6, 8);
    assertThat(reviewApi.containsComment(ImmutableList.of(ci), otherRange)).isFalse();
  }

  @Test
  public void testContainsComment_singleDifferentText() throws Exception {
    CommentInput ci = CI("a comment", 9, 9);
    CommentInput otherText = CI("another comment", 9, 9);
    assertThat(reviewApi.containsComment(ImmutableList.of(ci), otherText)).isFalse();
  }

  @Test
  public void testContainsComment_multipleDoContain() throws Exception {
    ImmutableList<CommentInput> comments =
        ImmutableList.of(CI("one", 1, 2), CI("two", 806, 808), CI("three", 3, 14));
    assertThat(reviewApi.containsComment(comments, CI("three", 3, 14))).isTrue();
    assertThat(reviewApi.containsComment(comments, CI("two", 806, 808))).isTrue();
    assertThat(reviewApi.containsComment(comments, CI("one", 1, 2))).isTrue();
  }

  @Test
  public void testContainsComment_multipleDoNotContain() throws Exception {
    ImmutableList<CommentInput> comments =
        ImmutableList.of(CI("one", 1, 2), CI("two", 806, 808), CI("three", 3, 14));
    assertThat(reviewApi.containsComment(comments, CI("four", 806, 808))).isFalse();
  }

  @Test
  public void testReviewComments_firstParty() throws Exception {
    assertThat(
            reviewApi.reviewComments(
                "project",
                FIRST_PARTY, // 1p license with 3p author is 1p license
                false,
                ImmutableList.of(
                    lic1p(2), auth1p(3), lic1p(4), lic1p(120), auth3p(121), auth1p(122))))
        .comparingElementsUsing(startsWithAndRangesMatch())
        .containsExactly(
            CI("First-party license :", 2, 4),
            CI("First-party license :", 120, 120),
            CI("Third-party author or owner :", 121, 121),
            CI("First-party author or owner :", 122, 122));
  }

  @Test
  public void testReviewComments_thirdPartyAllowed() throws Exception {
    assertThat(
            reviewApi.reviewComments(
                "project",
                THIRD_PARTY, // 3p license and 1p license or author is 3p
                true,
                ImmutableList.of(
                    lic3p(2), auth3p(3), lic3p(10), auth3p(200), auth3p(210), lic1p(211))))
        .comparingElementsUsing(startsWithAndRangesMatch())
        .containsExactly(
            CI("Third-party license allowed", 2, 10),
            CI("Third-party author or owner allowed", 200, 210),
            CI("First-party license :", 211, 211));
  }

  @Test
  public void testReviewComments_thirdPartyNotAllowed() throws Exception {
    assertThat(
            reviewApi.reviewComments(
                "project",
                THIRD_PARTY, // 3p license and 1p license or author is 3p
                false,
                ImmutableList.of(
                    lic3p(2), auth3p(3), lic3p(10), auth3p(200), auth3p(210), auth1p(211))))
        .comparingElementsUsing(startsWithAndRangesMatch())
        .containsExactly(
            CI("Third-party license disallowed", 2, 10),
            CI("Third-party author or owner disallowed", 200, 210),
            CI("First-party author or owner :", 211, 211));
  }

  @Test
  public void testReviewComments_forbiddenAuthor() throws Exception {
    assertThat(
            reviewApi.reviewComments(
                "project",
                FORBIDDEN, // forbidden author and anything else is still forbidden
                false,
                ImmutableList.of(lic1p(2), auth1p(3), lic1p(4), lic1p(120), authForbidden(121))))
        .comparingElementsUsing(startsWithAndRangesMatch())
        .containsExactly(
            CI("First-party license :", 2, 4),
            CI("First-party license :", 120, 120),
            CI("Disapproved author or owner :", 121, 121));
  }

  @Test
  public void testReviewComments_forbiddenLicense() throws Exception {
    assertThat(
            reviewApi.reviewComments(
                "project",
                FORBIDDEN, // forbidden license and anything else is still forbidden
                false,
                ImmutableList.of(lic1p(2), auth1p(3), lic1p(4), lic1p(120), licForbidden(121))))
        .comparingElementsUsing(startsWithAndRangesMatch())
        .containsExactly(
            CI("First-party license :", 2, 4),
            CI("First-party license :", 120, 120),
            CI("Disapproved license :", 121, 121));
  }

  @Test
  public void testReviewComments_unknownLicense() throws Exception {
    assertThat(
            reviewApi.reviewComments(
                "project",
                FORBIDDEN, // an unknown license could be forbidden so always requires review
                false,
                ImmutableList.of(lic1p(2), auth1p(3), lic1p(4), lic1p(120), licUnknown(121))))
        .comparingElementsUsing(startsWithAndRangesMatch())
        .containsExactly(
            CI("First-party license :", 2, 4),
            CI("First-party license :", 120, 120),
            CI("Unrecognized license :", 121, 121));
  }

  @Test
  public void testPartyType_firstPartyLicense() throws Exception {
    // 1p license with 3p author is 1p in open-source
    assertThat(reviewApi.partyType(ImmutableList.of(lic1p(1), auth3p(2), lic1p(3))))
        .isEqualTo(FIRST_PARTY);
  }

  @Test
  public void testPartyType_firstPartyOwner() throws Exception {
    assertThat(reviewApi.partyType(ImmutableList.of(auth1p(1), auth1p(2)))).isEqualTo(FIRST_PARTY);
  }

  @Test
  public void testPartyType_thirdPartyLicense() throws Exception {
    // 3p license with 1p license or author is 3p
    assertThat(reviewApi.partyType(ImmutableList.of(lic3p(1), lic1p(3), auth1p(4))))
        .isEqualTo(THIRD_PARTY);
  }

  @Test
  public void testPartyType_thirdPartyOwner() throws Exception {
    // 3p author and 1p author without any license is 3p
    assertThat(reviewApi.partyType(ImmutableList.of(auth3p(1), auth1p(2)))).isEqualTo(THIRD_PARTY);
  }

  @Test
  public void testPartyType_forbiddenLicense() throws Exception {
    // forbidden anything with anything else in any combination is forbidden
    assertThat(
            reviewApi.partyType(
                ImmutableList.of(
                    licForbidden(1), licUnknown(2), lic3p(3), auth3p(4), lic1p(5), auth1p(6))))
        .isEqualTo(FORBIDDEN);
  }

  @Test
  public void testPartyType_forbiddenOwner() throws Exception {
    // forbidden anything with anything else in any combination is forbidden
    assertThat(
            reviewApi.partyType(
                ImmutableList.of(
                    authForbidden(1), licUnknown(2), lic3p(3), auth3p(4), lic1p(5), auth1p(6))))
        .isEqualTo(FORBIDDEN);
  }

  @Test
  public void testPartyType_unknownLicense() throws Exception {
    // unknown license with anything but forbidden is unknown (possibly forbidden)
    assertThat(
            reviewApi.partyType(
                ImmutableList.of(licUnknown(2), lic3p(3), auth3p(4), lic1p(5), auth1p(6))))
        .isEqualTo(UNKNOWN);
  }

  private static class FakePluginUser extends PluginUser {
    FakePluginUser() {
      super("copyright-test");
    }
  }

  private static class FakeCurrentUser extends CurrentUser {
    @Override
    public Object getCacheKey() {
      return "31415966";
    }

    @Override
    public GroupMembership getEffectiveGroups() {
      return null;
    }
  }

  private static Correspondence<CommentInput, CommentInput> startsWithAndRangesMatch() {
    return new Correspondence<CommentInput, CommentInput>() {
      @Override
      public boolean compare(CommentInput actual, CommentInput expected) {
        return actual.range.startLine == expected.range.startLine
            && actual.range.endLine == expected.range.endLine
            && actual.message.startsWith(expected.message);
      }

      @Override
      public String toString() {
        return "starts with and ranges match";
      }
    };
  }

  private static Correspondence<AddReviewerInput, String> addressedTo() {
    return new Correspondence<AddReviewerInput, String>() {
      @Override
      public boolean compare(AddReviewerInput actual, String expected) {
        return expected.equals(actual.state().toString() + ":" + actual.reviewer);
      }

      @Override
      public String toString() {
        return "addressed to";
      }
    };
  }

  /** Comment input {@code text} from {@code start} line to {@code end} line. */
  private CommentInput CI(String text, int start, int end) {
    CommentInput.Range r = new CommentInput.Range();
    r.startLine = start;
    r.endLine = end;
    CommentInput ci = new CommentInput();
    ci.message = text;
    ci.range = r;
    return ci;
  }

  /** First-party license at {@code line} */
  private Match lic1p(int line) {
    return new Match(FIRST_PARTY, LICENSE, "1p license", line, line, line, line);
  }

  /** Third-party license at {@code line} */
  private Match lic3p(int line) {
    return new Match(THIRD_PARTY, LICENSE, "3p license", line, line, line, line);
  }

  /** Forbidden license at {@code line} */
  private Match licForbidden(int line) {
    return new Match(FORBIDDEN, LICENSE, "forbidden license", line, line, line, line);
  }

  /** Unknown license at {@code line} */
  private Match licUnknown(int line) {
    return new Match(UNKNOWN, LICENSE, "unknown license", line, line, line, line);
  }

  /** First-party author/owner at {@code line} */
  private Match auth1p(int line) {
    return new Match(FIRST_PARTY, AUTHOR_OWNER, "1p author", line, line, line, line);
  }

  /** Third-party author/owner at {@code line} */
  private Match auth3p(int line) {
    return new Match(THIRD_PARTY, AUTHOR_OWNER, "3p author", line, line, line, line);
  }

  /** Forbidden author/owner at {@code line} */
  private Match authForbidden(int line) {
    return new Match(FORBIDDEN, AUTHOR_OWNER, "forbidden author", line, line, line, line);
  }
}
