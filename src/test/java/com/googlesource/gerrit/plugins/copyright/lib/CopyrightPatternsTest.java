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

package com.googlesource.gerrit.plugins.copyright.lib;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CopyrightPatternsTest {

  @Test
  public void testBuildEmpty() {
    CopyrightPatterns.RuleSet.Builder builder = CopyrightPatterns.RuleSet.builder();

    CopyrightPatterns.RuleSet rules = builder.build();

    assertThat(rules.excludePatterns).isEmpty();
    assertThat(rules.firstPartyLicenses).isEmpty();
    assertThat(rules.firstPartyOwners).isEmpty();
    assertThat(rules.thirdPartyLicenses).isEmpty();
    assertThat(rules.thirdPartyOwners).isEmpty();
    assertThat(rules.forbiddenLicenses).isEmpty();
    assertThat(rules.forbiddenOwners).isEmpty();
  }

  @Test
  public void testBuildExcludePattern() {
    CopyrightPatterns.RuleSet.Builder builder = CopyrightPatterns.RuleSet.builder()
        .excludePattern("pattern");

    CopyrightPatterns.RuleSet rules = builder.build();

    assertThat(rules.excludePatterns).containsExactly("pattern");
    assertThat(rules.firstPartyLicenses).isEmpty();
    assertThat(rules.firstPartyOwners).isEmpty();
    assertThat(rules.thirdPartyLicenses).isEmpty();
    assertThat(rules.thirdPartyOwners).isEmpty();
    assertThat(rules.forbiddenLicenses).isEmpty();
    assertThat(rules.forbiddenOwners).isEmpty();
  }

  @Test
  public void testBuildFirstPartyLicense() {
    CopyrightPatterns.RuleSet.Builder builder = CopyrightPatterns.RuleSet.builder()
        .addFirstPartyLicense("pattern");

    CopyrightPatterns.RuleSet rules = builder.build();

    assertThat(rules.excludePatterns).isEmpty();
    assertThat(rules.firstPartyLicenses).containsExactly("pattern");
    assertThat(rules.firstPartyOwners).isEmpty();
    assertThat(rules.thirdPartyLicenses).isEmpty();
    assertThat(rules.thirdPartyOwners).isEmpty();
    assertThat(rules.forbiddenLicenses).isEmpty();
    assertThat(rules.forbiddenOwners).isEmpty();
  }

  @Test
  public void testBuildFirstPartyOwner() {
    CopyrightPatterns.RuleSet.Builder builder = CopyrightPatterns.RuleSet.builder()
        .addFirstPartyOwner("pattern");

    CopyrightPatterns.RuleSet rules = builder.build();

    assertThat(rules.excludePatterns).isEmpty();
    assertThat(rules.firstPartyLicenses).isEmpty();
    assertThat(rules.firstPartyOwners).containsExactly("pattern");
    assertThat(rules.thirdPartyLicenses).isEmpty();
    assertThat(rules.thirdPartyOwners).isEmpty();
    assertThat(rules.forbiddenLicenses).isEmpty();
    assertThat(rules.forbiddenOwners).isEmpty();
  }

  @Test
  public void testBuildThirdPartyLicense() {
    CopyrightPatterns.RuleSet.Builder builder = CopyrightPatterns.RuleSet.builder()
        .addThirdPartyLicense("pattern");

    CopyrightPatterns.RuleSet rules = builder.build();

    assertThat(rules.excludePatterns).isEmpty();
    assertThat(rules.firstPartyLicenses).isEmpty();
    assertThat(rules.firstPartyOwners).isEmpty();
    assertThat(rules.thirdPartyLicenses).containsExactly("pattern");
    assertThat(rules.thirdPartyOwners).isEmpty();
    assertThat(rules.forbiddenLicenses).isEmpty();
    assertThat(rules.forbiddenOwners).isEmpty();
  }

  @Test
  public void testBuildThirdPartyOwner() {
    CopyrightPatterns.RuleSet.Builder builder = CopyrightPatterns.RuleSet.builder()
        .addThirdPartyOwner("pattern");

    CopyrightPatterns.RuleSet rules = builder.build();

    assertThat(rules.excludePatterns).isEmpty();
    assertThat(rules.firstPartyLicenses).isEmpty();
    assertThat(rules.firstPartyOwners).isEmpty();
    assertThat(rules.thirdPartyLicenses).isEmpty();
    assertThat(rules.thirdPartyOwners).containsExactly("pattern");
    assertThat(rules.forbiddenLicenses).isEmpty();
    assertThat(rules.forbiddenOwners).isEmpty();
  }

  @Test
  public void testBuildForbiddenLicense() {
    CopyrightPatterns.RuleSet.Builder builder = CopyrightPatterns.RuleSet.builder()
        .addForbiddenLicense("pattern");

    CopyrightPatterns.RuleSet rules = builder.build();

    assertThat(rules.excludePatterns).isEmpty();
    assertThat(rules.firstPartyLicenses).isEmpty();
    assertThat(rules.firstPartyOwners).isEmpty();
    assertThat(rules.thirdPartyLicenses).isEmpty();
    assertThat(rules.thirdPartyOwners).isEmpty();
    assertThat(rules.forbiddenLicenses).containsExactly("pattern");
    assertThat(rules.forbiddenOwners).isEmpty();
  }

  @Test
  public void testBuildForbiddenOwner() {
    CopyrightPatterns.RuleSet.Builder builder = CopyrightPatterns.RuleSet.builder()
        .addForbiddenOwner("pattern");

    CopyrightPatterns.RuleSet rules = builder.build();

    assertThat(rules.excludePatterns).isEmpty();
    assertThat(rules.firstPartyLicenses).isEmpty();
    assertThat(rules.firstPartyOwners).isEmpty();
    assertThat(rules.thirdPartyLicenses).isEmpty();
    assertThat(rules.thirdPartyOwners).isEmpty();
    assertThat(rules.forbiddenLicenses).isEmpty();
    assertThat(rules.forbiddenOwners).containsExactly("pattern");
  }

  @Test
  public void testBuildEachNamedRuleExclusion() {
    for (String ruleName : CopyrightPatterns.lookup.keySet()) {
      CopyrightPatterns.RuleSet.Builder builder = CopyrightPatterns.RuleSet.builder()
          .exclude(ruleName);

      CopyrightPatterns.RuleSet rules = builder.build();

      CopyrightPatterns.Rule rule = CopyrightPatterns.lookup.get(ruleName);

      if (rule.exclusions != null) {
        assertThat(rules.excludePatterns).containsAllIn(rule.exclusions);
      }
      if (rule.licenses != null) {
        assertThat(rules.excludePatterns).containsAllIn(rule.licenses);
      }
      if (rule.owners != null) {
        assertThat(rules.excludePatterns).containsAllIn(rule.owners);
      }
    }
  }

  @Test
  public void testBuildEachNamedRuleFirstParty() {
    for (String ruleName : CopyrightPatterns.lookup.keySet()) {
      CopyrightPatterns.RuleSet.Builder builder = CopyrightPatterns.RuleSet.builder()
          .addFirstParty(ruleName);

      CopyrightPatterns.RuleSet rules = builder.build();

      CopyrightPatterns.Rule rule = CopyrightPatterns.lookup.get(ruleName);

      if (rule.exclusions != null) {
        assertThat(rules.excludePatterns).containsExactlyElementsIn(rule.exclusions);
      }
      if (rule.licenses != null) {
        assertThat(rules.firstPartyLicenses).containsExactlyElementsIn(rule.licenses);
      }
      if (rule.owners != null) {
        assertThat(rules.firstPartyOwners).containsExactlyElementsIn(rule.owners);
      }
    }
  }

  @Test
  public void testBuildEachNamedRuleThirdParty() {
    for (String ruleName : CopyrightPatterns.lookup.keySet()) {
      CopyrightPatterns.RuleSet.Builder builder = CopyrightPatterns.RuleSet.builder()
          .addThirdParty(ruleName);

      CopyrightPatterns.RuleSet rules = builder.build();

      CopyrightPatterns.Rule rule = CopyrightPatterns.lookup.get(ruleName);

      if (rule.exclusions != null) {
        assertThat(rules.excludePatterns).containsExactlyElementsIn(rule.exclusions);
      }
      if (rule.licenses != null) {
        assertThat(rules.thirdPartyLicenses).containsExactlyElementsIn(rule.licenses);
      }
      if (rule.owners != null) {
        assertThat(rules.thirdPartyOwners).containsExactlyElementsIn(rule.owners);
      }
    }
  }

  @Test
  public void testBuildEachNamedRuleForbidden() {
    for (String ruleName : CopyrightPatterns.lookup.keySet()) {
      CopyrightPatterns.RuleSet.Builder builder = CopyrightPatterns.RuleSet.builder()
          .addForbidden(ruleName);

      CopyrightPatterns.RuleSet rules = builder.build();

      CopyrightPatterns.Rule rule = CopyrightPatterns.lookup.get(ruleName);

      if (rule.exclusions != null) {
        assertThat(rules.excludePatterns).containsExactlyElementsIn(rule.exclusions);
      }
      if (rule.licenses != null) {
        assertThat(rules.forbiddenLicenses).containsExactlyElementsIn(rule.licenses);
      }
      if (rule.owners != null) {
        assertThat(rules.forbiddenOwners).containsExactlyElementsIn(rule.owners);
      }
    }
  }
}
