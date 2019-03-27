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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;
import java.io.IOException;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Immutable file scanner for copyrights classifying the copyright matches it finds.
 *
 * <p>In general, configure the first-party (1p) and forbidden owners, and any generic owner matches
 * get classified as third-party (3p) automatically. Generally, only configure third-party (3p)
 * owners that the generic pattern will not match for some reason.
 *
 * <p>Licenses are different. Unknown licenses get identified as unknown and treated the same as
 * forbidden. Configure all of the known first-party (1p), third-party (3p) and forbidden licenes.
 *
 * <p>Configure the scanner using simplified regular expressions. The scanner will replace sequences
 * of whitespace with a regular sub-expression matching sequences of whitespace or comment
 * characters. Because the scanner makes this transformation, avoid including whitespace inside
 * character classes.
 *
 * <p>e.g. use "Android Open(?: |-)Source Project" not "Android Open[- ]Source Project"
 *
 * <p>When classifying matches as 1p, 3p or forbidden, the scanner looks for complete matches of
 * configured patterns. i.e. "re.match()" not "re.find()"
 *
 * <p>It's useful to include wildcards in configured patterns to match sub-sequences in generic
 * matches, but these can cause excessive backtracking leading to performance problems or even stack
 * exhaustion. The scanner replaces the wildcards '.*' and '.+' with expressions matching a more
 * limited set of characters for a shorter length that will generally match what is expected.
 *
 * <p>This allows simple configuration patterns like ".*Licen[cs]ed under the Apache Licen[cs]e,?"
 * without the risks normally caused by wildcard patterns.
 */
public final class CopyrightScanner {

  private final Pattern copyright; // Full regular expression for scanner to match.
  private final ImmutableList<Pattern> firstPartyLicenses; // Match 1p licenses.
  private final ImmutableList<Pattern> thirdPartyLicenses; // Match 3p licenses.
  private final ImmutableList<Pattern> forbiddenLicenses; // Match forbidden licences.
  private final ImmutableList<Pattern> firstPartyOwners; // Match 1p authors/matches.
  private final ImmutableList<Pattern> thirdPartyOwners; // Match 3p authors/matches.
  private final ImmutableList<Pattern> forbiddenOwners; // Match forbidden authors.
  private final ImmutableList<Pattern> contractWords; // Match license words.
  private final ImmutableList<Pattern> excludePatterns; // Exclude when found.

  // Most files that have a copyright or license declaration have 1 of them -- or at most 2 or 3.
  // NOTICE files can have thousands all derived from other files in the repository. No need to find
  // them all. Picked a small multiple of the expected number of licenses per file to catch any
  // long-tail files without wasting much effort on derivative NOTICE files etc.
  private static final int MATCH_THRESHOLD = 10;

  // Determined empirically by scanning millions of files on several hosts and looking at the offset
  // of the first matched copyright or license declaration. A couple .cpp files have copyright
  // declarations near the end of the file for some function or class copied from a third party.
  //
  // The only files where the first match appeared later than 230k or so were a few multi-gigabyte
  // build images derived entirely from other files in the repository. Picked a power of 2 large
  // enough to report all or virtually all of the source files with copyright declarations; even if
  // it doesn't report all of the declarations in the largest source files.
  //
  // There is an obvious trade-off for performance here. Increasing the maximum search length beyond
  // this threshold makes little or no difference for detecting problematic licenses, but does
  // increase scan durations at least linearly for larger files. Reducing the maximum search length
  // significantly below this threshold increases the risk a problematic license will go undetected.
  private static final int MAX_SEARCH_LENGTH = 256 * 1024;

  // All of the MAX parameters below have been chosen empirically similar to MATCH_SEARCH_LENGTH to
  // minimize computing cost while still catching virtually all of the important matches.

  /** Maximum length of consecutive text characters to match. */
  private static final int MAX_NAME_LENGTH = CopyrightPatterns.MAX_NAME_LENGTH;
  /** Maximum number of potential names to match. */
  private static final int MAX_NAME_REPETITION = CopyrightPatterns.MAX_NAME_REPETITION;
  /** Maximum length of consecutive space/comment characters to match. */
  private static final int MAX_SPACE_LENGTH = CopyrightPatterns.MAX_SPACE_LENGTH;
  /** Maximum repetition of potential dates to match. Might have to revisit this in future. */
  private static final int MAX_DATE_REPETITION = CopyrightPatterns.MAX_DATE_REPETITION;

  /** Regular expression matching whitespace or a comment character. */
  private static final String WS = CopyrightPatterns.WS;
  /** Regular expression matching whitespace, a comment character, or punctuation. */
  private static final String WSPCT = CopyrightPatterns.WSPCT;
  /** Regular experssion matching a web address. */
  private static final String URL = CopyrightPatterns.URL;
  /** Regular expression matching a text or email address. */
  public static final String NAME = CopyrightPatterns.NAME;
  /** Regular expression matching an UPPERCASE text. */
  public static final String UPPER_NAME = CopyrightPatterns.UPPER_NAME;
  /** Regular expression matching a Proper Case text. */
  public static final String PROPER_NAME = CopyrightPatterns.PROPER_NAME;
  /** Regular expression matching any text, email address, or quote character. */
  private static final String ANY_CHAR = CopyrightPatterns.ANY_CHAR;
  /** Regular expression matching any text, email address, or quoted string. */
  public static final String ANY_WORD = CopyrightPatterns.ANY_WORD;

  /**
   * Regular expressions to match arbitrary contract words.
   *
   * <p>Purposefully pushed the definition of common contract words to the lowest levels of the
   * library to make it difficult--but not impossible--to customize the word list.
   *
   * <p>There are many words one can think of that are common to license contracts that do not
   * appear here. For example, "grant" and "permission" lead to many false positives due to their
   * use associated with ACLs and visibility etc. The word "contributed" appears so many times in
   * .xml files in the Android code base that it adds significant latency and had to be removed.
   *
   * <p>Most license declarations will have multiple of these words so if a particular word causes a
   * problem in a particular code base, it is probably okay to remove it for all code bases without
   * too large a reduction in true positives. But please, check first.
   *
   * <p>Take care adding new words to make sure they do increase the number of true positives
   * without causing other problems. Remember that the existing word list was arrived at empirically
   * by adding many candidates and then pruning.
   *
   * <p>If the word lists really must diverge among different code bases, make the 2nd constructor
   * public, and provide different word lists at a higher level.
   */
  private static final ImmutableList<String> CONTRACT_WORDS =
      ImmutableList.of(
          "agree(?:s|d|ment)?",
          "amendments?",
          "applicable laws?",
          "any manner",
          "auth?or(?:s|ed|ship)?:?(?-i: \\p{Lu}\\p{Ll}*){2,5}",
          "breach",
          "(?:(?:required|return|allocated|allowed|contributed|copyrighted|generated|provided"
              + "|raised|understandable|used|written) )?by:? @[-\\p{L}\\p{N}._]+",
          "(?:(?:required|return|allocated|allowed|contributed|copyrighted|generated|provided"
              + "|raised|understandable|used|written) )?by:? [-\\p{L}\\p{N}._]+@[-\\p{L}\\p{N}._]+",
          "(?:(?:required|return|allocated|allowed|contributed|copyrighted|generated|provided"
              + "|raised|understandable|used|written) )?by:?(?-i: \\p{Lu}\\p{Ll}*){2,5}",
          "charge for",
          "constitut(?:e|es|ed|ing)",
          "contract(?:s|ed|ing|ual|ually)?",
          // contributed removed -- frequent appearance in large .xml files increases latency
          "contribut(?:e|es|or|ors|ion|ions)",
          "copyleft",
          "\\p{L}+ copyright(?:able)? \\p{L}+",
          "damages",
          "derivative",
          "disclaim(?:s|ed|er)?",
          "endorsements?",
          " [(]?EUPL[)]? ",
          "exemplary",
          "expressly",
          "fitness",
          "govern(?:s|ed|ing)?",
          "here(?:by|under)",
          "herein(?:after)?",
          "however caused",
          "incidental",
          "infring(?:e|es|ed|ing)",
          "injury",
          "jurisdictions?",
          "lawful",
          "liable",
          "liabilit(?:ies|y)",
          "(?:re)?licen[cs](?:e(?![:])|es|ed|ing|or)",
          "litigation",
          "merchantability",
          "must agree",
          "negligen(?:ce|t)",
          "no event",
          "no provision",
          "(?:non|un)enforce(?:s|d|able|ability)?",
          "nonexclusive",
          "notwithstanding",
          "obligations?",
          "otherwise agreed",
          "perpetu(?:al|ity)",
          "phonorecords?",
          "prior written",
          "provisions",
          "public domain",
          "(?-i:(?:" + UPPER_NAME + " ){0,5}PUBLIC LICEN[CS]E)",
          "(?-i:(?:" + PROPER_NAME + " ){0,5}Public Licen[cs]e)",
          "punitive",
          "pursuant",
          "redistribut(?:e|ion)",
          "right to",
          "royalties",
          "set forth",
          " [(]?SISSL[)]? ",
          "SPDX-License-Identifier[:]?",
          "stoppage",
          "terms and conditions",
          "the laws of",
          "third party",
          "tort(?:s|ious)?",
          "trademark",
          "waive(?:s|d|r)?",
          "warrant(?:s|y|ee|ed|ing)?",
          "whatsoever");

  public CopyrightScanner(
      Iterable<String> firstPartyLicenses,
      Iterable<String> thirdPartyLicenses,
      Iterable<String> forbiddenLicenses,
      Iterable<String> firstPartyOwners,
      Iterable<String> thirdPartyOwners,
      Iterable<String> forbiddenOwners,
      Iterable<String> excludePatterns) {
    this(
        firstPartyLicenses,
        thirdPartyLicenses,
        forbiddenLicenses,
        firstPartyOwners,
        thirdPartyOwners,
        forbiddenOwners,
        excludePatterns,
        CONTRACT_WORDS);
  }

  private CopyrightScanner(
      Iterable<String> firstPartyLicenses,
      Iterable<String> thirdPartyLicenses,
      Iterable<String> forbiddenLicenses,
      Iterable<String> firstPartyOwners,
      Iterable<String> thirdPartyOwners,
      Iterable<String> forbiddenOwners,
      Iterable<String> excludePatterns,
      Iterable<String> contractWords) {
    ImmutableList.Builder<Pattern> b = ImmutableList.builder();
    if (firstPartyLicenses != null) {
      for (String license : firstPartyLicenses) {
        b.add(patternizeKnownMatch(license));
      }
    }
    this.firstPartyLicenses = b.build();
    b = ImmutableList.builder();
    if (thirdPartyLicenses != null) {
      for (String license : thirdPartyLicenses) {
        b.add(patternizeKnownMatch(license));
      }
    }
    this.thirdPartyLicenses = b.build();
    b = ImmutableList.builder();
    if (forbiddenLicenses != null) {
      for (String license : forbiddenLicenses) {
        b.add(patternizeKnownMatch(license));
      }
    }
    this.forbiddenLicenses = b.build();
    b = ImmutableList.builder();
    if (firstPartyOwners != null) {
      for (String owner : firstPartyOwners) {
        b.add(patternizeKnownMatch(owner));
      }
    }
    this.firstPartyOwners = b.build();
    b = ImmutableList.builder();
    if (thirdPartyOwners != null) {
      for (String owner : thirdPartyOwners) {
        b.add(patternizeKnownMatch(owner));
      }
    }
    this.thirdPartyOwners = b.build();
    b = ImmutableList.builder();
    if (forbiddenOwners != null) {
      for (String owner : forbiddenOwners) {
        b.add(patternizeKnownMatch(owner));
      }
    }
    this.forbiddenOwners = b.build();
    b = ImmutableList.builder();
    for (String word : contractWords) {
      b.add(patternizeKnownMatch(word));
    }
    this.contractWords = b.build();
    Preconditions.checkArgument(!this.contractWords.isEmpty());
    b = ImmutableList.builder();
    if (excludePatterns != null) {
      for (String pattern : excludePatterns) {
        b.add(Pattern.compile(pattern)); // not transformed because applies to normalized matches
      }
    }
    this.excludePatterns = b.build();
    this.copyright = buildPattern();
  }

  /**
   * Scans `source` for copyright notices returning found license/author/owner information.
   *
   * @param name Arbitrary string identifying the source. Usually a filename.
   * @param size Hint regarding the expected size of the input source. Use -1 if unknown.
   * @param source The source input stream with line endings indexed for lookup.
   * @return the list of matches found in the input stream -- never null.
   */
  public ImmutableList<Match> findMatches(String name, long size, IndexedLineReader source)
      throws IOException {
    Preconditions.checkNotNull(name);
    Preconditions.checkNotNull(source);

    ImmutableList.Builder<Match> builder = ImmutableList.builder();

    // Accumulates unknown licenses in case no known matches found.
    ArrayList<Match> unknowns = new ArrayList<>();

    // Allocate a character buffer using the size hint.
    int searchLength = size < 1 || size > MAX_SEARCH_LENGTH ? MAX_SEARCH_LENGTH : (int) size;
    char[] content = new char[searchLength > 2 ? searchLength : 2]; // minimum 2 chars required
    CharBuffer cb = CharBuffer.wrap(content);

    // Read the input into the character buffer.
    source.read(cb);
    cb.flip(); // Switch from tracking available space to read into to tracking amount read.

    int numUnknown = 0; // track number of contract words from unknown licenses found
    int numLicenses = 0; // track number of licenses versus owners added to the builder
    int numLicenseGroups = // First 2 or 3 captured groups are licenses. Rest are author/owner.
        firstPartyLicenses.isEmpty() && thirdPartyLicenses.isEmpty() && forbiddenLicenses.isEmpty()
            ? 2
            : 3;

    Matcher matcher = copyright.matcher(cb);
    while (matcher.find()) {
      MatchResult mr = matcher.toMatchResult();
      int numBuilt = 0; // track number of matches added to the builder
      for (int i = 1; i <= mr.groupCount(); i++) { // group 0 is entire match not a specific group
        String license = normalizeLicense(mr.group(i));
        if (license == null || license.trim().isEmpty() || isExcluded(license)) {
          continue;
        }
        String owner = normalizeOwner(license);
        if (isForbiddenLicense(license)) {
          builder.add(
              new Match(
                  PartyType.FORBIDDEN,
                  MatchType.LICENSE,
                  normalizeLicense(mr.group()),
                  source.getLineNumber(mr.start(i)),
                  source.getLineNumber(mr.end(i)),
                  mr.start(i),
                  mr.end(i)));
          numLicenses++;
        } else if (isThirdPartyLicense(license)) {
          builder.add(
              new Match(
                  PartyType.THIRD_PARTY,
                  MatchType.LICENSE,
                  normalizeLicense(mr.group()),
                  source.getLineNumber(mr.start(i)),
                  source.getLineNumber(mr.end(i)),
                  mr.start(i),
                  mr.end(i)));
          numLicenses++;
        } else if (isFirstPartyLicense(license)) {
          builder.add(
              new Match(
                  PartyType.FIRST_PARTY,
                  MatchType.LICENSE,
                  normalizeLicense(mr.group()),
                  source.getLineNumber(mr.start(i)),
                  source.getLineNumber(mr.end(i)),
                  mr.start(i),
                  mr.end(i)));
          numLicenses++;
        } else if (i <= numLicenseGroups) { // first 2 or 3 groups are licenses
          builder.add(
              new Match(
                  PartyType.UNKNOWN, // unknown licenses classified as unknown
                  MatchType.LICENSE,
                  normalizeLicense(mr.group()),
                  source.getLineNumber(mr.start(i)),
                  source.getLineNumber(mr.end(i)),
                  mr.start(i),
                  mr.end(i)));
          numLicenses++;
        } else if (license.toLowerCase().contains("license")
            || license.toLowerCase().contains("licence")) {
          builder.add(
              new Match(
                  PartyType.UNKNOWN, // unknown licenses classified as unknown
                  MatchType.LICENSE,
                  normalizeLicense(mr.group()),
                  source.getLineNumber(mr.start(i)),
                  source.getLineNumber(mr.end(i)),
                  mr.start(i),
                  mr.end(i)));
          numLicenses++;
        } else if (isForbiddenOwner(owner)) {
          builder.add(
              new Match(
                  PartyType.FORBIDDEN,
                  normalizeLicense(mr.group()),
                  source.getLineNumber(mr.start(i)),
                  source.getLineNumber(mr.end(i)),
                  mr.start(i),
                  mr.end(i)));
        } else if (isThirdPartyOwner(owner)) {
          builder.add(
              new Match(
                  PartyType.THIRD_PARTY,
                  normalizeLicense(mr.group()),
                  source.getLineNumber(mr.start(i)),
                  source.getLineNumber(mr.end(i)),
                  mr.start(i),
                  mr.end(i)));
        } else if (isFirstPartyOwner(owner)) {
          builder.add(
              new Match(
                  PartyType.FIRST_PARTY,
                  normalizeLicense(mr.group()),
                  source.getLineNumber(mr.start(i)),
                  source.getLineNumber(mr.end(i)),
                  mr.start(i),
                  mr.end(i)));
        } else { // remainder of groups are owner/author copyrights
          builder.add(
              new Match(
                  PartyType.THIRD_PARTY, // unknown authors classified as third party.
                  normalizeLicense(mr.group()),
                  source.getLineNumber(mr.start(i)),
                  source.getLineNumber(mr.end(i)),
                  mr.start(i),
                  mr.end(i)));
        }
        numBuilt++;
      }
      // If no capture group has content, the entire match is a word from an unknown contract.
      // Don't bother accumulating unknown contract matches after known patterns detected.
      if (numLicenses == 0 && numBuilt == 0 && numUnknown <= MATCH_THRESHOLD) {
        String license = normalizeLicense(mr.group());
        if (license.matches("(?i)no copyright(?:able)?.*")) { // exclude negated match
          continue;
        }
        if (isExcluded(license)) {
          continue;
        }
        if (license.matches( // exclude common implementation comments using the word `by`
            "(?i:required|return|allocated|allowed|generated|provided|raised|understandable"
                + "|used) by .*")) {}
        int startLine = source.getLineNumber(mr.start());
        int endLine = source.getLineNumber(mr.end());
        String owner = normalizeOwner(license);
        if (isForbiddenLicense(license)) {
          builder.add(
              new Match(
                  PartyType.FORBIDDEN,
                  MatchType.LICENSE,
                  license,
                  startLine,
                  endLine,
                  mr.start(),
                  mr.end()));
          numBuilt++;
          continue;
        } else if (isThirdPartyLicense(license)) {
          builder.add(
              new Match(
                  PartyType.THIRD_PARTY,
                  MatchType.LICENSE,
                  license,
                  startLine,
                  endLine,
                  mr.start(),
                  mr.end()));
          numBuilt++;
          continue;
        } else if (isFirstPartyLicense(license)) {
          builder.add(
              new Match(
                  PartyType.FIRST_PARTY,
                  MatchType.LICENSE,
                  license,
                  startLine,
                  endLine,
                  mr.start(),
                  mr.end()));
          numBuilt++;
          continue;
        } else if (isForbiddenOwner(owner)) {
          builder.add(
              new Match(PartyType.FORBIDDEN, license, startLine, endLine, mr.start(), mr.end()));
          numBuilt++;
          continue;
        } else if (isThirdPartyOwner(owner)) {
          builder.add(
              new Match(PartyType.THIRD_PARTY, license, startLine, endLine, mr.start(), mr.end()));
          numBuilt++;
          continue;
        } else if (isFirstPartyOwner(owner)) {
          builder.add(
              new Match(PartyType.FIRST_PARTY, license, startLine, endLine, mr.start(), mr.end()));
          numBuilt++;
          continue;
        }
        Match priorMatch = !unknowns.isEmpty() ? Iterables.getLast(unknowns) : null;
        // If close to an earlier match (within 6 lines or 300 chars), extend the match to include
        // the new word.
        if (priorMatch != null
            && (startLine - priorMatch.endLine < 6 || mr.start() - priorMatch.end < 300)) {
          priorMatch.text = priorMatch.text + "..." + license;
          priorMatch.endLine = endLine;
          priorMatch.end = mr.end();
        } else {
          // Otherwise, create a new match.
          if (numUnknown < MATCH_THRESHOLD) {
            unknowns.add(
                new Match(
                    PartyType.UNKNOWN,
                    MatchType.LICENSE,
                    license,
                    startLine,
                    endLine,
                    mr.start(),
                    mr.end()));
          }
          numUnknown++;
        }
      }
      // Stop the search early if enough known patterns already matched.
      if (numBuilt >= MATCH_THRESHOLD) {
        break;
      }
    }
    // Return unknown contracts only when found and no known patterns matched.
    if (numLicenses == 0) {
      builder.addAll(unknowns);
    }
    return builder.build();
  }

  /**
   * Constructs the search pattern incorporating the known matches into the generic regular
   * expression.
   *
   * <p>The first 2 or 3 match groups correspond to license matches. If the configuration specifies
   * known license patterns (1p, 3p or forbidden), the 1st match group will include these matches.
   *
   * <p>If the configuration specifies no known license patterns, the 1st and 2nd match groups will
   * include matches to the generic license pattern. Otherwise, the 2nd and 3rd match groups will
   * include these.
   *
   * <p>Subsequent match groups are all copyright author/owner matches.
   *
   * <p>The arbitrary contract words expression uses a non-capturing group. If none of the other
   * match groups contain any content, the entire match is treated as an unknown license word.
   */
  private Pattern buildPattern() {
    StringBuilder words = new StringBuilder();
    for (Pattern word : contractWords) {
      if (words.length() > 0) {
        words.append('|');
      }
      words.append(word);
    }

    StringBuilder owners = new StringBuilder();
    owners.append("(?:by");
    owners.append(WS);
    owners.append("{1,msl})?(?:the");
    owners.append(WS);
    owners.append("{1,msl})?("); // owner expression always captured here
    for (Pattern owner : thirdPartyOwners) {
      String s = owner.toString();
      int start = s.startsWith(".*") || s.startsWith(".+") ? 2 : 0;
      int end = s.endsWith(".*") || s.endsWith(".+") ? s.length() - 2 : s.length();
      owners.append(owner.toString().substring(start, end));
      owners.append('|');
    }
    for (Pattern owner : firstPartyOwners) {
      String s = owner.toString();
      int start = s.startsWith(".*") || s.startsWith(".+") ? 2 : 0;
      int end = s.endsWith(".*") || s.endsWith(".+") ? s.length() - 2 : s.length();
      owners.append(owner.toString().substring(start, end));
      owners.append('|');
    }
    for (Pattern owner : forbiddenOwners) {
      String s = owner.toString();
      int start = s.startsWith(".*") || s.startsWith(".+") ? 2 : 0;
      int end = s.endsWith(".*") || s.endsWith(".+") ? s.length() - 2 : s.length();
      owners.append(owner.toString().substring(start, end));
      owners.append('|');
    }
    owners.append("(?:");
    owners.append(NAME);
    owners.append("(?:");
    owners.append(WS);
    owners.append("{1,msl}");
    owners.append(NAME);
    owners.append("){0,mnr}))"); // end of owner capture

    // One of the frequent objections to regular expressions is the objection that long or complex
    // expressions are difficult to read, and they are. Avoid changes to the expressions below. If
    // given a choice between making a change below or adding a few "known owner"/"known license"
    // patterns to the configuration, bias toward configuration.
    //
    // If that is not possible, one of the most difficult tasks when maintaining these expressions
    // is balancing the parentheses and braces at the appropriate parts. The author of the below
    // expression added a System.err.println() statement to output:
    //   pattern.toString().replaceall("([(](?:[?][:])?)", "$1\n").replaceall("[)]", "\n$1")
    // inserting newlines after opening parentheses and before closing parentheses. The output
    // was then fed through an awk script to indent the nested expressions:

    /* awk '
         BEGIN {
           p="";
         }
         $0 ~ /^[)].*$/ {
           p=substr(p,1, length(p)-2);
         }
         {
           print p $0;
         }
         $0 ~ /[(]([?][:])?$/ {
           p=p "  ";
         }
       '
    */
    // From that output, it was possible to see where parentheses balanced and what changes to make
    // to edit the expression correctly. Not for the fainthearted.
    StringBuilder sb = new StringBuilder();

    // Optional known licence capture.
    if (!firstPartyLicenses.isEmpty()
        || !thirdPartyLicenses.isEmpty()
        || !forbiddenLicenses.isEmpty()) {
      sb.append("("); // start of optional 1st captured match group
      sb.append(
          Streams.concat(
                  thirdPartyLicenses.stream(),
                  firstPartyLicenses.stream(),
                  forbiddenLicenses.stream())
              .map(
                  input -> {
                    if (input == null) {
                      return "";
                    }
                    String s = input.toString();
                    int start = s.startsWith(".*") || s.startsWith(".+") ? 2 : 0;
                    int end = s.endsWith(".*") || s.endsWith(".+") ? s.length() - 2 : s.length();
                    return input.toString().substring(start, end);
                  })
              .collect(Collectors.joining("|")));
      sb.append(")|"); // end of optional 1st captured group and | to introduce 2nd captured group.
    }

    // Other license captures. -- ends with License
    sb.append("(?:is"); // not captured -- helps confirm license but interferes with matching 1p,3p
    sb.append(WS);
    sb.append("{1,msl}(?:distributed|provided)");
    sb.append(WS);
    sb.append("{1,msl}under(?:");
    sb.append(WS);
    sb.append("{1,msl}(?:the|this))?");
    sb.append(WS);
    sb.append("{1,msl}((?:"); // start of 1st or 2nd captured match group
    sb.append(NAME);
    sb.append(WS);
    sb.append(
        "{1,msl}){2,mnr}?licen[cs]e))[,.;]{0,3}(?![:])"); // end of 1st or 2nd captured match group

    // Other license captures. -- Line starting with License:
    sb.append("|(?-ms:licen[cs]e:\\s{1,msl}("); // start of the 2nd or 3rd captured match group
    sb.append(NAME);
    sb.append("(?:\\s{1,msl}");
    sb.append(NAME);
    sb.append("){0,mnr})\\n)"); // end of 2nd or 3rd captured match group

    // "Author is" copyright capture.
    sb.append("|\\b(?:(?:the"); // not captured--helps confirm but interferes with 1p, 3p, forbidden
    sb.append(WS);
    sb.append("{1,msl}author");
    sb.append(WS);
    sb.append("{1,msl}of");
    sb.append(WS);
    sb.append("{1,msl}this");
    sb.append(WS);
    sb.append("{1,msl}software");
    sb.append(WS);
    sb.append("{1,msl}is|\\b(?:(?:principal");
    sb.append(WS);
    sb.append("{1,msl})?author:?))");
    sb.append(WS);
    sb.append("{1,msl}");
    sb.append(owners.toString()); // owner pattern includes capture group
    sb.append(")");

    // Copyright+year(s)+owner copyright capture.
    sb.append("|(?:"); // not captureed -- helps confirm but interferes with 1p, 3p, forbidden
    sb.append(WS);
    sb.append("{0,msl}(?:[(]c[)]|&copy;|©)");
    sb.append(WS);
    sb.append("{0,msl})?(?:(?:copy(?:right|left)(?:");
    sb.append(WS);
    sb.append("{1,msl}notice)?(?:");
    sb.append(WS);
    sb.append("{0,msl}(?:[(]c[)]|&copy;|©))?)|(?:[(]c[)]|&copy;|©))");
    sb.append(WS);
    sb.append("{1,msl}(?:");
    sb.append("[\\p{N}]{2,4}(?:"); // year(s)+owner
    sb.append(WSPCT);
    sb.append("{1,msl}(?:and");
    sb.append(WSPCT);
    sb.append("{1,msl})?[\\p{N}]{2,4}){0,mdr}(?:"); // allows pre-y2k 2-digit years
    sb.append(WSPCT);
    sb.append("{1,msl}(?:present|now))?");
    sb.append(WSPCT);
    sb.append("{1,msl}");
    sb.append(owners.toString()); // owner pattern includes capture group
    sb.append("|"); // owner+year(s)
    sb.append(owners.toString()); // owner pattern includes capture group
    sb.append(WS);
    sb.append("{1,msl}[\\p{N}]{2,4}(?:"); // allows pre-y2k 2-digit years
    sb.append(WSPCT);
    sb.append("{1,msl}(?:and");
    sb.append(WSPCT);
    sb.append("{1,msl})?[\\p{N}]{2,4}){0,mdr}(?:"); // allows pre-y2k 2-digit years
    sb.append(WSPCT);
    sb.append("{1,msl}(?:present|now))?");
    sb.append(")(?:(?:portions)?");
    sb.append(WS);
    sb.append("{0,msl}(?:[(]c[)]|&copy;|©)?");
    sb.append(WS);
    sb.append("{1,msl}copy(?:right|left)(?:");
    sb.append(WS);
    sb.append("{0,msl}(?:[(]c[)]|&copy;|©))?");
    sb.append(WS);
    sb.append("{1,msl}(?:");
    sb.append("[\\p{N}]{2,4}(?:"); // year(s)+owner
    sb.append(WSPCT);
    sb.append("{1,msl}(?:and");
    sb.append(WSPCT);
    sb.append("{1,msl})?[\\p{N}]{2,4}){0,mdr}(?:"); // allows pre-y2k 2-digit years
    sb.append(WSPCT);
    sb.append("{1,msl}(?:present|now))?");
    sb.append(WSPCT);
    sb.append("{1,msl}");
    sb.append(owners.toString()); // owner pattern (repeated) includes capture group
    sb.append("|"); // owner+year(s)
    sb.append(owners.toString()); // owner pattern (repeated) includes capture group
    sb.append(WS);
    sb.append("{1,msl}[\\p{N}]{2,4}(?:"); // allows pre-y2k 2-digit years
    sb.append(WSPCT);
    sb.append("{1,msl}(?:and");
    sb.append(WSPCT);
    sb.append("{1,msl})?[\\p{N}]{2,4}){0,mdr}(?:"); // allows pre-y2k 2-digit years
    sb.append(WSPCT);
    sb.append("{1,msl}(?:present|now))?");
    sb.append(")){0,5}"); // captures 0 to 5 additional author/owner declarations

    // Detect contract words to detect unknown licenses.
    sb.append("|(?:(?:\\b|\\p{Pi})(?:"); // unknown licenses use non-capturing group
    sb.append(words);
    sb.append(")(?:");
    sb.append(WS);
    sb.append("(?:");
    sb.append(words);
    sb.append(")){0,mnr}(?:\\b|[,.;:\\p{Pf}]))");

    return Pattern.compile(
        sb.toString()
            .replaceAll("[,]mnl[}]", "," + MAX_NAME_LENGTH + "}")
            .replaceAll("[,]msl[}]", "," + MAX_SPACE_LENGTH + "}")
            .replaceAll("[,]mnr[}]", "," + MAX_NAME_REPETITION + "}")
            .replaceAll("[,]mdr[}]", "," + MAX_DATE_REPETITION + "}"),
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.UNICODE_CASE | Pattern.DOTALL);
  }

  /** Returns true when `owner` matches any known first party owner. */
  private boolean isExcluded(String match) {
    for (Pattern p : excludePatterns) {
      if (p.matcher(match).find()) {
        return true;
      }
    }
    return false;
  }

  /** Returns true when `owner` matches any known first party owner. */
  private boolean isFirstPartyOwner(String owner) {
    if (owner == null || owner.isEmpty()) {
      return false;
    }
    for (Pattern p : firstPartyOwners) {
      if (p.matcher(owner).matches()) {
        return true;
      }
    }
    return false;
  }

  /** Returns true when `owner` matches any known forbidden owner. */
  private boolean isForbiddenOwner(String owner) {
    if (owner == null || owner.isEmpty()) {
      return false;
    }
    for (Pattern p : forbiddenOwners) {
      if (p.matcher(owner).matches()) {
        return true;
      }
    }
    return false;
  }

  /** Returns true when `owner` matches any known third party owner. */
  private boolean isThirdPartyOwner(String owner) {
    if (owner == null || owner.isEmpty()) {
      return false;
    }
    for (Pattern p : thirdPartyOwners) {
      if (p.matcher(owner).matches()) {
        return true;
      }
    }
    return false;
  }

  /** Returns true when `license` matches any known first party license. */
  private boolean isFirstPartyLicense(String license) {
    for (Pattern p : firstPartyLicenses) {
      if (p.matcher(license).matches()) {
        return true;
      }
    }
    return false;
  }

  /** Returns true when `license` matches any known forbidden license. */
  private boolean isForbiddenLicense(String license) {
    for (Pattern p : forbiddenLicenses) {
      if (p.matcher(license).matches()) {
        return true;
      }
    }
    return false;
  }

  /** Returns true when `license` matches any known third party license. */
  private boolean isThirdPartyLicense(String license) {
    for (Pattern p : thirdPartyLicenses) {
      if (p.matcher(license).matches()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Converts a known matching pattern written in a simplified regular expression language into a
   * regular expression treating comment characters as whitespace and replacing unlimited wildcard
   * expressions with expressions using a limited set of characters and a limited quantifier.
   */
  private static Pattern patternizeKnownMatch(String match) {
    Preconditions.checkNotNull(match);
    Preconditions.checkArgument(!match.isEmpty(), "Non-empty pattern required.");
    // Disallow capture groups which will interfere with 1p, 3p, or forbidden classification.
    Preconditions.checkArgument(
        !match.matches("(?:^|.*[^\\[])[(][^?](?:[^:].*|$)"),
        "Capturing group found in /" + match + "/. Use non-capturing (?:...) instead of (...).");
    // Disallow spaces inside character classes because they will get replaced.
    Preconditions.checkArgument(
        !match.matches(".*\\[[^]]*\\s[]].*"),
        "Character class with space in /" + match + "/. Use (?: |...) instead of space in [...].");
    // Replace unlimited "any char" wildcards that can cost too much backtracking with patterns that
    // match a smaller subset of characters with more limited quantifiers.
    //
    // Replace any sequence of whitespace with a regular expression to match any non-empty sequence
    // of whitespace or comment characters.
    String prefix = "";
    if (match.startsWith(".*")) {
      prefix = ".*";
    } else if (match.startsWith(".+")) {
      prefix = ".*";
    }
    String suffix = "";
    if (match.endsWith(".*")) {
      suffix = ".*";
    } else if (match.endsWith(".+")) {
      suffix = ".*";
    }
    return Pattern.compile(
        prefix
            + match
                .substring(prefix.length(), match.length() - suffix.length())
                .replaceAll(
                    "[.][*]",
                    ("(?: "
                            + ANY_CHAR
                            + "{1,"
                            + MAX_NAME_LENGTH
                            + "}){0,"
                            + MAX_NAME_REPETITION
                            + "}")
                        .replace("\\", "\\\\"))
                .replaceAll(
                    "[.][+]",
                    ("(?: "
                            + ANY_CHAR
                            + "{1,"
                            + MAX_NAME_LENGTH
                            + "}){1,"
                            + MAX_NAME_REPETITION
                            + "}")
                        .replace("\\", "\\\\"))
                .replaceAll("\\s+[?]", WS.replace("\\", "\\\\") + "{0," + MAX_SPACE_LENGTH + "}")
                .replaceAll("\\s+", WS.replace("\\", "\\\\") + "{1," + MAX_SPACE_LENGTH + "}")
            + suffix,
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.UNICODE_CASE | Pattern.DOTALL);
  }

  /**
   * Replaces sequences of whitespace and comment characters with a single space preserving URLs,
   * which often contain `/` or `#` as non-comment characters.
   */
  private static String normalizeLicense(String match) {
    if (match == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    Matcher m = Pattern.compile(URL).matcher(match);
    int nextIndex = 0;
    while (m.find()) {
      int start = m.start();
      if (nextIndex < start) {
        sb.append(match.substring(nextIndex, start).replaceAll(WS + "+", " "));
      }
      sb.append(m.group());
      nextIndex = m.end();
    }
    if (nextIndex < match.length()) {
      sb.append(match.substring(nextIndex).replaceAll(WS + "+", " "));
    }
    return sb.toString().trim();
  }

  /**
   * Strips common non-author/owner suffixes that get picked up unintentionally from previously
   * normalized license with sequences of whitespace and comment characters replaced with a single
   * space preserving URLS, which often contain `/` or `#` as non-comment characters.
   *
   * <p>The generic license pattern always ends by matching the word `license` or stops at the end
   * of the line so it does not pick up spurious additional text. The generic owner pattern does not
   * end in a specific word so it often includes spurious additional words like #ifdef or #ifndef,
   * which interfere when comparing the match against known author/owner patterns. This method
   * strips the most common non-author/owner words from the end of the match.
   */
  private static String normalizeOwner(String license) {
    if (license == null) {
      return null;
    }
    return license
        .split(
            "(?i)[ ](?:all rights|(?:the|this) [^ ]+(?: [^ ]+){0,2} (?:is|assumes|may)"
                + "|permission|copyright|version \\p{N}|for conditions|include |include$"
                + "|modification|however|open source license|please (?:use|read)|libname"
                + "|if defined|usage|this is free|added|generic|redistribution|ifdef|ifndef"
                + "|for (?:more|terms)|copying and|you (?:may|can)|released under|see the"
                + "|full source|freedom to use|this program and|distributed|https?|unit ?test"
                + "|import|static|by obtaining|by using|by copying|example|namespace|config\\b"
                + "|public (?:static|final|class)|package (?:org|com)|[^ ]+ is hereby)")[0];
  }

  /** Identifies the relevant party as 1p, 3p, forbidden, or unknown. */
  public enum PartyType {
    FIRST_PARTY,
    THIRD_PARTY,
    FORBIDDEN,
    UNKNOWN,
  }

  /** Identifies whether text matched by author/owner pattern or by license pattern. */
  public enum MatchType {
    AUTHOR_OWNER,
    LICENSE,
  }

  /**
   * Describes a copyright author/owner or license `text` match found in the input stream.
   *
   * <p>Identifies the relevant party as `FIRST_PARTY`, `THIRD_PARTY`, `FORBIDDEN`, or `UNKNOWN`.
   *
   * <p>Identifies the match as `AUTHOR_OWNER` or `LICENSE`.
   *
   * <p>Includes a normalized version of the matched text including where it was found in the file.
   */
  public static class Match {
    /** Classifies relevant party as 1p, 3p, forbidden, or unknown. */
    public PartyType partyType;
    /** Classifies match as author/owner or as license. */
    public MatchType matchType;
    /** Matched text with spaces and comment characters replaced by a single space. */
    public String text;
    /** The line number in the file where the match starts. */
    public int startLine;
    /** The line number in the file where the match ends. */
    public int endLine;
    /** The character offset into the file where the match starts. */
    public int start;
    /** The character offset into the file where the match ends. */
    public int end;

    Match(PartyType partyType, String text, int startLine, int endLine, int start, int end) {
      this(partyType, MatchType.AUTHOR_OWNER, text, startLine, endLine, start, end);
    }

    Match(
        PartyType partyType,
        MatchType matchType,
        String text,
        int startLine,
        int endLine,
        int start,
        int end) {
      this.partyType = partyType;
      this.matchType = matchType;
      this.text = text;
      this.startLine = startLine;
      this.endLine = endLine;
      this.start = start;
      this.end = end;
    }
  }
}
