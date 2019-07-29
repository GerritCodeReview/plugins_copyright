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

package com.googlesource.gerrit.plugins.copyright.lib;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import java.util.NoSuchElementException;
import org.apache.commons.lang.StringUtils;

/** Constants declaring match patterns for common copyright licenses and owners. */
public abstract class CopyrightPatterns {

  // No instances
  private CopyrightPatterns() {}

  // All of the MAX parameters below have been chosen empirically similar to MATCH_SEARCH_LENGTH to
  // minimize computing cost while still catching virtually all of the important matches.

  /** Maximum length of consecutive text characters to match. */
  public static final int MAX_NAME_LENGTH = 30;
  /** Maximum number of potential names to match. */
  public static final int MAX_NAME_REPETITION = 35;
  /** Maximum length of consecutive space/comment characters to match. */
  public static final int MAX_SPACE_LENGTH = 47;
  /** Maximum repetition of potential dates to match. Might have to revisit this in future. */
  public static final int MAX_DATE_REPETITION = 30;

  /** Regular expression matching whitespace or a comment character. */
  public static final String WS = "[\\p{Z}\\s*/#·§­¶ ]";
  /** Regular expression matching whitespace, a comment character, or punctuation. */
  public static final String WSPCT = "[-,;.\\p{Z}\\s*/#·§­¶ ]";
  /** Regular expression matching a text character. */
  public static final String NAME_CHAR = "[-\\p{L}\\p{N}]"; // \p{L}->letter, \p{N}->numeral
  /** Regular expression matching an UPPER CASE text character. */
  public static final String UPPER_CHAR = "\\p{Lu}";
  /** Regular expression matching a lower case text character. */
  public static final String LOWER_CHAR = "\\p{Ll}";
  /** Regular expression matching an email character. */
  public static final String EMAIL_CHAR = "[-.\\p{L}\\p{N}_]";
  /** Regular expression matching a URL character. */
  public static final String URL_CHAR = "[-.\\p{L}\\p{N}_=%+]";
  /** Regular experssion matching a web address. */
  public static final String URL =
      "https?[:]/(?:[/?&]" // http://example.com/path/?var=val&var=val
          + URL_CHAR
          + "{1,"
          + MAX_NAME_LENGTH
          + "}){1,25}"
          + "|www[.]" // www.domain or www.domain/path
          + URL_CHAR
          + "{1,"
          + MAX_NAME_LENGTH
          + "}(?:[.]com|[.]net|[.]org|[.]\\p{L}\\p{L})(?:[/?&#]"
          + URL_CHAR
          + "{0,"
          + MAX_NAME_LENGTH
          + "}){0,25}";
  /** Regular expression matching a text or email address. */
  public static final String NAME =
      "(?:(?:"
          + URL // web address
          + "|[\\p{L}\\p{N}]" // regular text
          + NAME_CHAR
          + "{0,mnl}\\b"
          + "|[<]?[\\p{L}\\p{N}]" // email@domain or <email@domain>
          + EMAIL_CHAR
          + "{1,mnl}[@][\\p{L}\\p{N}]"
          + EMAIL_CHAR
          + "{1,mnl}\\b[>]?"
          + "|\\b\\p{N}{1,2}(?:[.]\\p{N}{1,2}){1,5}\\b" // version number
          + "|\\p{Pi}[^\\p{Pf}]{0,65}\\p{Pf}" // quoted string
          + "|(?-i:\\p{Lu}[-.]){1,5}" // Initials A.G. or S.A. etc. \\p{Lu} -> uppercase letter
          + "|" // domain text
          + EMAIL_CHAR
          + "{1,mnl}(?:[.]com|[.]org|[.]net|[.]\\p{L}\\p{L})"
          + ")[,;:]?)"; // punctuation following text
  /** Regular expression matching an UPPERCASE text. */
  public static final String UPPER_NAME = "(?-i:\\b" + UPPER_CHAR + "{1," + MAX_NAME_LENGTH + "})";
  /** Regular expression matching a Proper Case text. */
  public static final String PROPER_NAME =
      "(?-i:\\b" + UPPER_CHAR + LOWER_CHAR + "{0," + MAX_NAME_LENGTH + "})";
  /** Regular expression matching any text, email address, or quote character. */
  public static final String ANY_CHAR = "[-.,\\p{L}\\p{N}]";
  /** Regular expression matching any text, email address, or quoted string. */
  public static final String ANY_WORD = "(?:" + ANY_CHAR + "{1," + MAX_NAME_LENGTH + "})";

  /** Academic Freedom License Version 2.1 */
  public static final Rule AFL2_1 =
      license(
          ImmutableList.of(
              ".*SPDX-License-Identifier: AFL-2[.]1",
              "<license> <name> ?AFL-2[.]1 ?</name>.*",
              "Academic Free License 2[.]1"));

  /** Academic Freedom License Version 3.0 */
  public static final Rule AFL3_0 =
      license(
          ImmutableList.of(
              ".*SPDX-License-Identifier: AFL-3[.]0",
              "<license> <name> ?AFL-3[.]0 ?</name>.*",
              "Academic Free Licen[cs]e 3[.]0"));

  /** Affero General Public License */
  public static final Rule AGPL =
      license(
          ImmutableList.of(
              // "Affero" appears thrice in GPL 3.0 -- match to exclude below.
              "Use with the GNU Affero General Public License[.]?",
              "link or combine any covered work with a work licensed under version 3 of the GNU"
                  + " Affero General Public License into a single combined work[,.;:]?",
              "but the special requirements of the GNU Affero General Public License,?",
              // "Affero" appears in Mozilla Public License (MPL) -- match to exclude below.
              "means either the GNU General Public License,?"
                  + " Version 2[.]0,? the GNU Lesser General Public License,?"
                  + " Version 2[.]1,? the GNU Affero General Public License,?",
              // Otherwise, any occurrence of "Affero" suggests AGPL.
              "Affero"),
          ImmutableList.of(
              // "Affero" appears thrice in GPL 3.0
              "Use with the GNU Affero General Public License[.]?",
              "link or combine any covered work with a work licensed under version 3 of the GNU"
                  + " Affero General Public License into a single combined work[,.;:]?",
              "but the special requirements of the GNU Affero General Public License,?",
              // "Affero" appears in Mozilla Public License (MPL)
              "means either the GNU General Public License,?"
                  + " Version 2[.]0,? the GNU Lesser General Public License,?"
                  + " Version 2[.]1,? the GNU Affero General Public License,?"));

  /** Android owned or licensed */
  public static final Rule ANDROID =
      new Rule(
          ImmutableList.of("Android(?:-x86)? Open(?: |-)Source Project", "LK Trusty Authors"),
          ImmutableList.of("Android Software Development Kit Licen[cs]e Agreement"));

  /** Apache Software License 1.1 */
  public static final Rule APACHE1_1 =
      license(
          ImmutableList.of(
              ".*SPDX-License-Identifier: Apache-1[.]1?",
              "<license> <name> ?Apache(?: Software)?(?: Public)?(?: Licen[cs]e)?,?"
                  + "(?: |-)1[.]1 ?</name>.*",
              "<license> <name> ?(?:The )?Apache(?: Software)?(?: Public)?"
                  + " Licen[cs]e,? Version 1[.]1 ?</name>.*",
              "http://www[.]apache[.]org/licenses/LICENSE-1[.]1",
              "(?:the )?Apache 1[.]1 Licen[cs]e",
              ".*Licen[cs]ed under (?:both )?(?:the )?Apache Licen[cs]e,? version 1[.]1",
              ".+Licen[cs]ed under (?:both )?(?:the )?Apache Licen[cs]e v1[.]1",
              ".+licen[cs]ed under (?:the )?Apache 1[.]1",
              "(?:the )?Apache(?: Software)?(?: Public)? Licen[cs]e,? Version 1[.]1",
              "(?:the )?Apache(?: Software)?(?: Public)? Licen[cs]e,? v1[.]1",
              "^terms of the Apache 1[.]1 licen[cs]e"));

  /** Apache 2 owned or licensed */
  public static final Rule APACHE2 =
      new Rule(
          ImmutableList.of(
              "(?:by )?(?:The )?Apache Software Foundation.?",
              "Apache Software Foundation.?"
                  + " This product includes software developed"
                  + " (?:by|at) The Apache Software Foundation"),
          ImmutableList.of(
              ".*SPDX-License-Identifier: Apache-2[.]?[0]?",
              "<license> <name> ?Apache(?: Software)?(?: Public)?(?: Licen[cs]e)?,?"
                  + "(?: |-)2[.]?[0]? ?</name>.*",
              "<license> <name> ?(?:The )?Apache(?: Software)?(?: Public)?"
                  + " Licen[cs]e,? Version 2[.]?[0]? ?</name>.*",
              "https?://www[.]apache[.]org/licenses/LICENSE-2[.]0",
              "Apache 2[.]?[0]? Licen[cs]e",
              ".*Licen[cs]ed under (?:both )?(?:the )?Apache Licen[cs]e,?(?: version 2[.]?0?)?",
              ".+Licen[cs]ed under (?:both )?(?:the )?Apache Licen[cs]e v2[.]?(?:[\\p{L}\\p{N}]+)?",
              ".+licen[cs]ed under (?:the )?Apache 2[.]?",
              ".+licen[cs]es this file to you under (?:the )?Apache Licen[cs]e,?",
              "Apache(?: Software)?(?: Public)? Licen[cs]e,? Version 2[.]?[0]?",
              "^apache2(?:-android)?",
              "^the apache licen[cs]e",
              "^terms of the Apache 2[.]?[0]? licen[cs]e",
              ".+under the terms of (?:either )the Apache Licen[cs]e[,.;]?"),
          ImmutableList.of("owner as \\p{Pi}?Not a Contribution[.,;:]{0,3}\\p{Pf}?"));

  /** The Artistic License version 1 or 2 */
  public static final Rule ARTISTIC_LICENSE =
      license(
          ImmutableList.of(
              ".*SPDX-License-Identifier: Artistic-[12][.]?[0]?",
              "<license> <name> ?Artistic-[12][.]?[0]? ?</name>.*",
              "(?:The )?\\p{Pi}?Artistic Licen[cs]e\\p{Pf}?(?: [12][.]?[0]?)?"));

  /** The BEER-WARE License */
  public static final Rule BEER_WARE = license(ImmutableList.of("\\bTHE BEER-WARE LICEN[CS]E"));

  /** BSD licensed */
  public static final Rule BSD =
      license(
          ImmutableList.of(
              "<license> <name> ?BSD \\p{N}-clause(?: License)? ?</name>.*",
              "<license> <name> ?BSD(?: |-)style(?: License)? ?</name>.*",
              "<license> <name> ?(?:New )?BSD(?: New)?(?: Licen[cs]e)? ?</name>.*",
              "<license> <name> ?Berkeley Software Distribution [(]BSD[)] Licen[cs]e ?</name>.*",
              ".*SPDX-License-Identifier: BSD-2-Clause",
              ".*SPDX-License-Identifier: BSD-2-Clause-FreeBSD",
              ".*SPDX-License-Identifier: BSD-3-Clause",
              ".*SPDX-License-Identifier: BSD-4-Clause",
              "^BSD(?:[.]|, see LICEN[CS]E for (?:more )details[.])?",
              ".*under the terms and conditions of the BSD Licen[cs]e.*",
              ".*(?:\\p{N}-clause |a )?BSD (?:\\p{N}-clause )?licen[cs]e.*",
              ".*Redistribution and use in source and binary forms,? with or without modification,?"
                  + " are permitted provided that the following conditions are met[:]?",
              ".*This header is BSD licen[cs]ed so anyone can use the definitions to implement"
                  + " compatible drivers servers[.:]?.*",
              ".*Redistribution and use is allowed according to the terms of the"
                  + " (?:\\p{N}-clause )?BSD licen[cs]e[.:]?.*",
              ".*(?:[-\\p{L}\\p{N}] )?redistributions (?:of|in) source code must retain the"
                  + " (?:(?:above|accompanying) )?copyright notice(?: unmodified)?[,.:;]? this list"
                  + " of conditions[,.;:]? and the following disclaimers?[,.;:]?"
                  + " (?:[-\\p{L}\\p{N}] )?redistributions (?:in|of) binary form must reproduce the"
                  + " (?:above|accompanying) copyright notice[,.;:]? this list of conditions[,.:;]?"
                  + " and the following disclaimer in the documentation[,.;:]? and(?: |[/])or other"
                  + " materials(?: provided with the distribution)?[,.:;]{0,3}"));

  /** The Boost Software License */
  public static final Rule BSL1_0 =
      license(
          ImmutableList.of(
              ".*SPDX-License-Identifier: BSL-1[.]?0?",
              "<license> <name> ?Boost(?:,? Version 1[.]?0?)? ?</name>.*",
              "<license> <name> ?Boost Software License(?:,? Version 1[.]?0?)? ?</name>.*",
              "<license> <name> ?BSL-1[.]?0? ?</name>.*",
              "https?://www[.]boost[.]org/LICENSE_1_0[.]txt(?: [(]?Boost license[)]?)?",
              "Boost Software License 1[.]?0?.*",
              ".*(?:The )?Boost Software License(?:,| ?-)?(?: Version 1[.]?0?)?"));

  /** Creative Commons Public Domain-like License */
  public static final Rule CC0 =
      license(
          ImmutableList.of(
              "<license> <name> ?CC0 ?</name>.*",
              "https?://[\\p{L}\\p{N}.]*creativecommons[.]org/publicdomain/zero(?:/[\\p{N}.]*)?",
              "To the extent possible under law[,;:]? the person who associated CC0 with this code"
                  + " has waived all copyright and related or neighboring rights to this code[.]?",
              "To the greatest extent permitted by,? but not in contravention of,? applicable law,?"
                  + " Affirmer hereby overtly,? fully,? permanently,? irrevocably and"
                  + " unconditionally waives,? abandons,? and surrenders all of Affirmer'?s"
                  + " Copyright and Related Rights and associated claims and causes of action,?"
                  + " whether (?:now )?known or unknown",
              "Affirmer hereby grants to each affected person a royalty-?free,?"
                  + " non(?: |-)?transferable,? non(?: |-)?sublicensable,? non(?: |-)?exclusive,?"
                  + " irrevocable,? and unconditional license to exercise Affirmer'?s Copyright"
                  + " and Related Rights in the Work"));

  /** Creative Commons Attribution -- allows commercial */
  public static final Rule CC_BY_C =
      license(
          ImmutableList.of(
              "\\bhttps?://[\\p{L}\\p{N}.]*creativecommons[.]org/licen[cs]es/by[-\\p{L}\\p{N}.]*",
              "(?-i:\\bAttribution(?:-(?:Share ?Alike|NoDerivs)){0,2} )"));

  /** Creative Commons Non-Commercial License */
  public static final Rule CC_BY_NC =
      license(
          ImmutableList.of(
              "\\bhttps?://[\\p{L}\\p{N}.]*creativecommons[.]org/licenses/by"
                  + "(?:-nd)?(?:-sa)?-nc[-/\\p{L}\\p{N}.]*",
              "\\bAttribution(?:-NoDerivs)?(?:-Share ?Alike)?"
                  + "-NonCommercial(?:-NoDerivs)?(?:-Share ?Alike)?"));

  /** Clang/LLVM License (NCSA version of MIT) */
  public static final Rule CLANG_LLVM =
      license(
          ImmutableList.of(
              "This file is dual licensed under the MIT and the University of Illinois Open"
                  + " Source Licenses[,.;:]?",
              "to deal with the Software without restriction,? including without limitation"));

  /** Commons Clause License */
  public static final Rule COMMONS_CLAUSE = license(ImmutableList.of("\\bCommons Clause"));

  /** Common Public Attribution License */
  public static final Rule CPAL =
      license(ImmutableList.of("\\bCommon Public Attribution Licen[cs]e"));

  /** Eclipse Distribution License (basically BSD 3-clause plus below) */
  public static final Rule EDL =
      license(
          ImmutableList.of(
              "https?://www[.]eclipse[.]org/org/documents/edl-v10[.]php",
              "Eclipse Distribution Licen[cs]e(?: -)? v? ?1[.]0"));

  /** Eclipse Public License */
  public static final Rule EPL =
      license(
          ImmutableList.of(
              "<license> <name> ?Eclipse Public Licen[cs]e"
                  + "(?: -)?(?: (?:v ?|version )?1[.]?[0]?)? ?</name>.*",
              "^Eclipse Public Licen[cs]e[.]?",
              ".*under (?:(?:the|this) )?(?:terms of )?(?:the )?eclipse"
                  + " (?:public )?licen[cs]e[,.;:]?.*",
              ".*terms of (?:(?:the|this) )?eclipse public licen[cs]e[,.;:]?.*"));

  /** European Union Public License */
  public static final Rule EUPL = license(ImmutableList.of(" [(]?EUPL[)]? "));

  /** Appears in tests similar to using example.com as a test domain */
  public static final Rule EXAMPLES = owner(ImmutableList.of("Your Company[.]?"));

  /** FreeType Project License */
  public static final Rule FTL =
      new Rule(
          ImmutableList.of("The FreeType ProjectI(?: [(]?www[.]freetype[.]org[)]?)[,;:.]?"),
          ImmutableList.of(
              ".*SPDX-License-Identifier: FTL",
              "<license> <name> ?(?:The )?Freetype Project ?</name>.*",
              "<license> <name> ?FTL ?</name>.*",
              "This license was inspired by the BSD,? Artistic,? and IJG [(]?Independent JPEG"
                  + " Group[)]? licenses,? which all encourage inclusion and use of free software"
                  + " in commercial and freeware products alike[,;:.]?",
              "you must acknowledge somewhere in your documentation that you have used the FreeType"
                  + " code[.;:.]?",
              "The FreeType Project LICENSE",
              "The FreeType Project is distributed in several archive packages[,;.:]?",
              "This file is part of the FreeType project,? and may only be used,? modified,? and"
                  + " distributed under the terms of the FreeType project license[,.;:]?"));

  /** Google owned */
  public static final Rule GOOGLE = owner(ImmutableList.of("Google,? Inc[.]?", "Google LLC"));

  /** Generic GNU General Public License */
  public static final Rule GPL =
      license(
          ImmutableList.of(
              "\\bIn addition to the permissions in the GNU General Public License[,.;:]?",
              "See the [\\[]?GNU[\\]]? General Public Licen[cs]e for more details[,.;:]?",
              "\\bGNU General Public Licen[cs]e",
              ".*gnu (?:library|lesser) general public licen[cs]e.*"),
          ImmutableList.of(
              "See the [\\[]?GNU[\\]]? General Public Licen[cs]e for more details[,.;:]?",
              "In addition to the permissions in the GNU General Public License[,.;:]?"));

  /** GNU General Public License v2 */
  public static final Rule GPL2 =
      license(
          ImmutableList.of(
              "<license> <name> ?GNU General Public License, Version 2 ?</name>.*",
              "<license> <name> ?The GPLv2 License ?</name>.*",
              "<license> <name> ?GPLv2 ?</name>.*",
              ".*SPDX-License-Identifier: GPL-2.0[+]?",
              ".*SPDX-License-Identifier: GPL-2.0-only",
              ".*SPDX-License-Identifier: GPL-2.0-or-later",
              ".*[\\[]?GNU[\\]]? GPL[,;]? version 2[,.;:]?.*",
              ".*[\\[]?GNU[\\]]? General Public Licen[cs]e[,;]? version 2[,.;:]?.*",
              "See the [\\[]?GNU[\\]]? General Public Licen[cs]e for more details[,.;:]?",
              "You should have received a copy of the [\\[]?GNU[\\]]? General Public Licen[cs]e",
              ".*[\\[]?GNU[\\]]? General Public Licen[cs]e as published by the Free Software"
                  + " Foundation?(?:[']s)?[,.;:]? (?:either )?version 2.*"),
          ImmutableList.of(
              "See the [\\[]?GNU[\\]]? General Public Licen[cs]e for more details[,.;:]?",
              "In addition to the permissions in the GNU General Public License[,.;:]?"));

  /** GNU General Public License v3 */
  public static final Rule GPL3 =
      license(
          ImmutableList.of(
              "<license> <name> ?GPLv3 ?</name>.*",
              ".*SPDX-License-Identifier: GPL-3.0[+]?",
              ".*[\\[]?GNU[\\]]? GPL[,;]? version 3[,.;:]?.*",
              ".*[\\[]?GNU[\\]]? General Public Licen[cs]e[,;]? version 3[,.;:]?.*",
              "See the [\\[]?GNU[\\]]? General Public Licen[cs]e for more details[,.;:]?",
              "You should have received a copy of the [\\[]?GNU[\\]]? General Public Licen[cs]e",
              ".*[\\[]?GNU[\\]]? General Public Licen[cs]e as published by the Free Software"
                  + " Foundation?(?:[']s)?[,.;:]? (?:either )?version 3.*"),
          ImmutableList.of(
              "See the [\\[]?GNU[\\]]? General Public Licen[cs]e for more details[,.;:]?",
              "In addition to the permissions in the GNU General Public License[,.;:]?"));

  /** The ISC License */
  public static final Rule ISC =
      license(
          ImmutableList.of(
              "<license> <name> ?(?:The )?ISC(?: Licen[cs]e)? ?</name>.*",
              ".*SPDX-License-Identifier: ISC",
              "https?://(?:www[.])?spdx[.]org/licenses/ISC",
              "(?:The )?ISC Licen[cs]e",
              "Files that are completely new have a Google copyright and an ISC license[,;:.]?",
              "ISC license used for completely new code in BoringSSL[,.;:]?",
              "this file is available under an ISC license[,;:.]?",
              "ISC"));

  /** The JSON License (MIT plus the sentence below) */
  public static final Rule JSON =
      license(ImmutableList.of("The Software shall be used for Good,? not Evil[.]?"));

  /** GNU Lessor or Library General Public License */
  public static final Rule LGPL =
      license(
          ImmutableList.of(
              "<license> <name> ?GNU (?:Lesser|Library) General Public License ?</name>.*",
              ".*SPDX-License-Identifier: LGPL.*",
              ".*LGPL.*",
              ".*gnu (?:library|lesser) general public licen[cs]e.*"));

  /** LibTIFF Library */
  public static final Rule LIBTIFF =
      license(
          ImmutableList.of(
              "<license> <name> ?libtiff(?: license)? ?</name>.*",
              ".*SPDX-License-Identifier: libtiff",
              "https?://(?:www[.])?fedoraproject[.]org/wiki/Licensing/libtiff",
              "Permission to use,? copy,? modify,? distribute,? and sell this software and its"
                  + " documentation for any purpose is hereby granted without fee,? provided that"
                  + " [(]i[)] the above copyright notices and this permission notice appear in all"
                  + " copies of the software and related documentation,? and [(]ii[)] the names of"
                  + " Sam Leffler and Silicon Graphics may not be used in any advertising"));

  /** Lucent Public License v 1.02 */
  public static final Rule LPL1_02 =
      license(
          ImmutableList.of(
              "<license> <name> ?LPL-1[.]02 ?</name>.*",
              ".*SPDX-License-Identifier: LPL-1[.]02",
              "https?://plan9[.]bell-labs[.]com/plan9dist/license[.]html",
              "https?://(?:www[.])?opensource[.]org/licenses/LPL-1[.]02",
              "This software is (?:also )?made available under the Lucent Public License,? version"
                  + " 1[.]02",
              "Lucent Public License,? version 1[.]02",
              "Lucent Public License v1[.]02"));

  /** MIT licensed */
  public static final Rule MIT =
      license(
          ImmutableList.of(
              "<license> <name> ?(?:The )?MIT Licen[cs]e(?: [(]MIT[)])? ?</name>.*",
              "<license> <name> ?MIT ?</name>.*",
              ".*SPDX-License-Identifier: MIT",
              "http://www.opensource.org/licenses/mit-license.php",
              "^the mit licen[cs]e(?:[:] http://www.opensource.org/licenses/mit-license.php)?",
              "^MIT licen[cs]e[,.;:]? http://www.ibiblio.org/pub/Linux/LICENSE",
              ".*under (?:(?:the|this) )?(?:terms of )?(?:the )?mit"
                  + " (?:open source )?licen[cs]e[,.;:]?.*",
              ".*MIT licen[cs]ed",
              ".*terms of (?:(?:the|this) )?mit licen[cs]e[,.;:]?.*",
              ".*this code is licen[cs]ed under the mit licen[cs]e[,;.:]?.*",
              ".*the mit or psf open source licen[cs]es[,.]?.*",
              ".*Dual licen[cs]ed under the MIT or.*",
              ".*Use of this software is governed by the MIT licen[cs]e[,.;:]?.*",
              ".*This library is free software[,.;:]? you can redistribute it and or modify it"
                  + " under the terms of the MIT licen[cs]e[,.;:]?.*",
              ".*may be distributed under the MIT or PSF open source licen[cs]es[,.;:]?.*",
              ".*permission is (?:hereby )?granted[,;]? free of charge[,;]? to any person.*",
              "(?:the mit licen[cs]e )?permission is (?:hereby )?granted[,;]? free of charge[,;]?"
                  + " to any person obtaining a copy of this software and associated documentation"
                  + " files [(]?the \\p{Pi}?software\\p{Pf}[)]?[,;]? to deal (?:in|with) the"
                  + " software without restriction[,;.:]? including without limitation the rights"
                  + " to use[,;]? copy[,;]? modify[,;]? merge[,;]? publish[,;]? distribute[,;]?"
                  + " sublicense[,;]? and(?: |[/])or sell copies of the software[,;]? and to permit"
                  + " persons to whom the software is furnished to do so[,;.:]? subject to the"
                  + " following conditions[,;.:]? the above copyright notice[,;]? and this"
                  + " permission notice shall be included in all copies[,;]? or substantial"
                  + " portions of the software[,;.:]?",
              ".*permission to use[,;]? copy[,;]? modify[,;]? (?:and )?distribute"
                  + " (?:and sell )?this software (?:and its documentation )?(?:for any purpose )?"
                  + "(?:(?:and|with or) without fee)?is (?:hereby )?granted[,;]?"
                  + " (?:without fee )?provided that the above copyright notice.*",
              ".*I hereby give permission[,;]? free of charge[,;]? to copy[,;]? modify[,;]? and"
                  + " redistribute this software[,;]? in source or binary form[,;]? provided that"
                  + " the above copyright notice and the following disclaimer are included.*"));

  /** Microsoft Public License */
  public static final Rule MSPL =
      license(
          ImmutableList.of(
              "<license> <name> ?(?:The )?Microsoft Public Licen[cs]e ?</name>.*",
              "<license> <name> ?MS-PL ?</name>.*",
              ".*SPDX-License-Identifier: MS-PL"));

  /** NCSA License */
  public static final Rule NCSA =
      license(
          ImmutableList.of(
              "<license> <name> ?NCSA ?</name>.*",
              ".*SPDX-License-Identifier: NCSA",
              "https?://otm[.]illinois[.]edu/uiuc_openSource",
              "https?://(?:www[.])?opensource[.]org/licenses/NCSA",
              "University of Illinois[/]NCSA Open Source Licen[cs]e",
              "University of Illinois NCSA Open Source Licen[cs]e",
              "Redistributions of source code must retain the above copyright notice,? this list of"
                  + " conditions and the following disclaimers[,;:.]?"));

  /** Generic non-commercial disclaimer. */
  public static final Rule NON_COMMERCIAL =
      license(ImmutableList.of("\\bNON-?COMMERCIAL LICEN[CS]E"));

  /** Rejects distribution under APACHE */
  public static final Rule NOT_A_CONTRIBUTION =
      license(
          ImmutableList.of(
              ".*(?:"
                  + ANY_WORD
                  + " ){2}\\p{Pi}?" // 2 words to exclude false +ves
                  + "Not a Contribution[.,;:]{0,3}\\p{Pf}?.*"), // explicitly disclaims license
          ImmutableList.of("owner as \\p{Pi}?Not a Contribution[.,;:]{0,3}\\p{Pf}?"));

  /** OpenSSL License */
  public static final Rule OPENSSL =
      new Rule(
          ImmutableList.of("The OpenSSL Project[,.;:]?"),
          ImmutableList.of(
              "<license> <name> ?OpenSSL ?</name>.*",
              ".*SPDX-License-Identifier: OpenSSL",
              "https?://(?:www[.])?openssl[.]org/source/license[.]html",
              "Licensed under the OpenSSL license(?: [(]?the \\p{Pi}?License\\p{Pf}?[)]?)?[,.;:]?",
              "\\p{Pi}?This product includes software developed by the OpenSSL Project for use in"
                  + " the OpenSSL Toolkit[,;:.]?"
                  + "(?: [(]?https?://(?:www[.])?openssl[.]org(?:/| )?[)]?\\p{Pf}?)?",
              "The names \\p{Pi}?OpenSSL Toolkit\\p{Pf}? and \\p{Pi}?OpenSSL Project\\p{Pf}? must"
                  + " not be used to endorse or promote",
              "Original SSLeay License",
              "OpenSSL License"));

  /** Python Software Foundation */
  public static final Rule PSF = owner(ImmutableList.of("Python Software Foundation"));

  /** Python Software Foundation License */
  public static final Rule PSFL =
      license(
          ImmutableList.of(
              "(?:The )?PSF Licen[cs]e",
              ".*Python Software Foundation license(?: version \\p{N})?[,.;:]?.*",
              ".*Permission to use[,;]? copy[,;]? modify[,;]? and distribute this Python software"
                  + " and its associated documentation for any purpose.*"));

  /** Sun Insdustry Standards Source License */
  public static final Rule SISSL =
      license(ImmutableList.of("\\bSun Industry Standards Source Licen[cs]e"));

  /** Server Side Public License */
  public static final Rule SSPL =
      license(
          ImmutableList.of(
              ".*Server Side Public Licen[cs]e.*",
              ".*SPDX-License-Identifier: SSPL-1[.]0",
              "\\p{Pi}?This License\\p{Pf}? refers to (?:the )?Server Side Public License[,;:.]?",
              "If you make the functionality of the Program (?:or a modified version )?available to"
                  + " third parties as a service,? you must make the Service Source Code available",
              "\\p{Pi}?Service Source Code\\p{Pf}? means the Corresponding Source for the Program"
                  + "(?: or the modified version),? and the Corresponding Source for all programs"
                  + " that you use to make the Program",
              "(?:https?://)?(?:www[.])?mongodb[.]com/licensing/server-side-public-license",
              "SSPL-\\p{N}[.]?\\p{N}?",
              "This program is free software[,.;:]? you can redistribute it and/or modify it under"
                  + " the terms of the Server Side Public License.*",
              "You must comply with the Server Side Public License in all respects"));

  /** Unicode Data Files and Software/Terms of Use */
  public static final Rule UNICODE =
      license(
          ImmutableList.of(
              ".*SPDX-License-Identifier: Unicode-DFS-\\p{N}{4}",
              ".*SPDX-License-Identifier: Unicode-TOU",
              "https?://(?:www[.])?unicode[.]org/copyright[.]html",
              "UNICODE,? INC[.]? LICENSE AGREEMENT(?: -)? DATA FILES AND SOFTWARE",
              "Permission is hereby granted,? free of charge,? to any person obtaining a copy of"
                  + " the Unicode data files and any associated documentation",
              "Certain documents and files on this website contain a legend indicating that"
                  + " \\p{Pi}?Modification is permitted[,;:.]?\\p{Pf}?",
              "the particular set of data files known as the \\p{Pi}?Unicode Character"
                  + " Database\\p{Pf}? can be found in",
              "Each version of the Unicode Standard has further specifications of rights and"
                  + " restrictions of use[,;:.]?",
              "Unicode License Agreement(?: -)? Data Files and Software(?: [(]?\\p{N}{4}[)]?)?",
              "Unicode Terms of Use"));

  /** Unlicense (public domain) */
  public static final Rule UNLICENSE =
      license(
          ImmutableList.of(
              ".*SPDX-License-Identifier: Unlicen[cs]e",
              "<license> <name> ?Unlicen[cs]e ?</name>.*",
              "This is free and unencumbered software released into the public domain[,;:.]?",
              "the author (?:or authors )?of this software dedicate any and all copyright interest"
                  + " in the software to the public domain[,;:.]?",
              "(?:https?://)?(?:www[.])?unlicen[cs]e[.]org(?:/UNLICEN[CS]E)?"));

  /** Universal Permissive License */
  public static final Rule UPL =
      license(
          ImmutableList.of(
              "<license> <name> ?UPL(?:-1[.]0)? ?</name>.*",
              ".*SPDX-License-Identifier: UPL-1[.]0",
              "Universal Permissive License v1[.]0",
              "The above copyright notice and either this complete permission notice or at a"
                  + " minimum a reference to the UPL must be included in all copies",
              "The Universal Permissive License(?: [(]?UPL[)]?)?,?(?: Version 1[.]0)?"));

  /** W3C Software Notice and License */
  public static final Rule W3C =
      new Rule(
          ImmutableList.of(
              "World Wide Web Consortium[,.;:]?",
              "Massachusetts Institute of Technology,? European Research Consortium for Informatics"
                  + " and Mathematics, Keio University"),
          ImmutableList.of(
              ".*SPDX-License-Identifier: W3C(?:-(?:19980720|20021231|20150513))?",
              "<license> <name> ? W3C(?:-(?:19980720|20021231|20150513))? ?</name>.*",
              "https?://(?:www[.])?opensource[.]org/licenses/W3C",
              "https?://(?:www[.])?w3[.]org/Consortium/Legal/copyright-software-19980720[.]html",
              "https?://(?:www[.])?w3[.]org/Consortium/Legal/2002"
                  + "/copyright-software-20021231[.]html",
              "https?://(?:www[.])?w3[.]org/Consortium/Legal/2015/copyright-software-and-document",
              "If none exist,? a short notice of the following form",
              "If none exist,? the W3C Software Short Notice should be included",
              "If none exist,? the W3C Software and Document Short Notice should be included[.]?",
              "W3C(?:®|[(]R[)])? SOFTWARE (?:NOTICE AND )?LICEN[CS]E"
                  + "(?: [(]?(?:1998-07-20|2002-12-31|2015-05-13)[)]?)?"));

  /** Watcom-1.0 license */
  public static final Rule WATCOM =
      license(
          ImmutableList.of(
              ".*SPDX-License-Identifier: Watcom-1[.]0",
              ".*Sybase Open Watcom Public License.*",
              ".*automatically without notice if You[,;]? at any time during the term of this"
                  + " Licen[cs]e[,;]? commence an action for patent infringement [(]?including as a"
                  + " cross claim or counterclaim[)]?.*"));

  /** Do What The Fuck You Want To Public License */
  public static final Rule WTFPL =
      license(
          ImmutableList.of(
              "<license> <name> ?WTFPL ?</name>.*",
              ".*SPDX-License-Identifier: WTFPL",
              "\\bDo What The F.ck You Want To Public Licen[cs]e"));

  /** X.Net License */
  public static final Rule XNET =
      license(
          ImmutableList.of(
              "<license> <name> ?X[.]Net(?: Licen[cs]e)? ?</name>.*",
              "<license> <name> ?Xnet ?</name>.*",
              ".*SPDX-License-Identifier: Xnet",
              "https?://(?:www[.])?opensource[.]org/licenses/Xnet",
              "X.Net Licen[cs]e"));

  /** Zend Engine License version 2.00 */
  public static final Rule ZEND =
      license(
          ImmutableList.of(
              "<license> <name> ?Zend(?: Engine)? License(?: (?:v|version )2[.]00?)? ?</name>.*",
              "<license> <name> ?Zend(?:-2[.]00?)? ?</name>.*",
              ".*SPDX-License-Identifier: Zend-2[.]0",
              "https?://(?:www[.])?zend[.]com/license/2_00[.]txt",
              "Zend (?:Engine )?License,? v2[.]00?",
              "The Zend Engine License,? version 2[.]00?"));

  /** Zlib/libpng License */
  public static final Rule ZLIB =
      license(
          ImmutableList.of(
              "<license> <name> ?zlib Licen[cs]e ?</name>.*",
              "<license> <name> ?zlib/libpng Licen[cs]e(?: with Acknowledgement)? ?</name>.*",
              ".*SPDX-License-Identifier: Zlib",
              ".*SPDX-License-Identifier: zlib-acknowledgement",
              "https?://(?:www[.])?opensource[.]org/licenses/zlib-license[.]php",
              "https?://(?:www[.])?zlib[.]net/zlib_license[.]html",
              "https?://(?:www[.])?opensource[.]org/licenses/Zlib",
              "https?://(?:www[.])?fedoraproject[.]org/wiki/Licensing/ZlibWithAcknowledgement",
              "Permission is granted to anyone to use this software for any purpose,? including"
                  + " commercial applications,? and to alter it and redistribute it freely,?"
                  + " subject to the following restrictions[,.;:]?",
              "(?:The )?zlib/libpng License(?: with Acknowledgement)?",
              "(?:The )?zlib/libpng License [(]Zlib[)]",
              "zlib licen[cs]e"));

  /** Zope Public License */
  public static final Rule ZPL =
      license(
          ImmutableList.of(
              "<license> <name> ?Zope Public License,?(?: version \\p{N}[.]?\\p{N}?)? ?</name>.*",
              "<license> <name> ?ZPL-\\p{N}[.]?\\p{N}? ?</name>.*",
              ".*SPDX-License-Identifier: ZPL-\\p{N}[.]?\\p{N}?",
              "https?://(?:www[.])?opensource[.]org/licenses/ZPL-\\p{N}[.]?\\p{N}?",
              "This product includes software developed by Zope Corporation",
              "Names associated with Zope or Zope Corporation must not be used",
              "The name Zope Corporation ?[(]tm[)] must not be used",
              "Names of the copyright holders must not be used to endorse or promote products"
                  + " derived from this software without prior written permission from the"
                  + " copyright holders[,;:.]?",
              "Zope Public License(?: [(]?ZPL[)]?)?(?: Version)?(?: \\p{N}[.]?\\p{N}?)?"));

  @VisibleForTesting
  static ImmutableMap<String, Rule> lookup =
      ImmutableMap.<String, Rule>builder()
          .put("AFL2.1", AFL2_1)
          .put("AFL3.0", AFL3_0)
          .put("AGPL", AGPL)
          .put("ANDROID", ANDROID)
          .put("APACHE1.1", APACHE1_1)
          .put("APACHE2", APACHE2)
          .put("ARTISTIC_LICENSE", ARTISTIC_LICENSE)
          .put("BEER_WARE", BEER_WARE)
          .put("BOOST", BSL1_0)
          .put("BSD", BSD)
          .put("BSL1.0", BSL1_0)
          .put("CC0", CC0)
          .put("CC_BY_C", CC_BY_C)
          .put("CC_BY_NC", CC_BY_NC)
          .put("CLANG_LLVM", CLANG_LLVM)
          .put("COMMONS_CLAUSE", COMMONS_CLAUSE)
          .put("CPAL", CPAL)
          .put("EDL", EDL)
          .put("EPL", EPL)
          .put("EUPL", EUPL)
          .put("EXAMPLES", EXAMPLES)
          .put("FTL", FTL)
          .put("GOOGLE", GOOGLE)
          .put("GPL", GPL)
          .put("GPL2", GPL2)
          .put("GPL3", GPL3)
          .put("JSON", JSON)
          .put("LGPL", LGPL)
          .put("LIBTIFF", LIBTIFF)
          .put("LPL1.02", LPL1_02)
          .put("MIT", MIT)
          .put("MS-PL", MSPL)
          .put("NCSA", NCSA)
          .put("NON_COMMERCIAL", NON_COMMERCIAL)
          .put("NOT_A_CONTRIBUTION", NOT_A_CONTRIBUTION)
          .put("OPENSSL", OPENSSL)
          .put("PSF", PSF)
          .put("PSFL", PSFL)
          .put("SISSL", SISSL)
          .put("SSPL", SSPL)
          .put("UNLICENSE", UNLICENSE)
          .put("UNICODE", UNICODE)
          .put("UPL", UPL)
          .put("W3C", W3C)
          .put("WATCOM", WATCOM)
          .put("WTFPL", WTFPL)
          .put("XNET", XNET)
          .put("ZEND", ZEND)
          .put("ZLIB", ZLIB)
          .put("ZPL", ZPL)
          .build();

  /** Immutable set of copyright rules described as lists of regular expression strings. */
  public static class RuleSet {
    public final ImmutableList<String> firstPartyLicenses;
    public final ImmutableList<String> thirdPartyLicenses;
    public final ImmutableList<String> forbiddenLicenses;
    public final ImmutableList<String> firstPartyOwners;
    public final ImmutableList<String> thirdPartyOwners;
    public final ImmutableList<String> forbiddenOwners;
    public final ImmutableList<String> excludePatterns;

    /** Returns a Builder object for the RuleSet class. */
    public static Builder builder() {
      return new Builder();
    }

    public String signature() {
      StringBuilder sb = new StringBuilder();
      sb.append("1pl:\n");
      sb.append(Joiner.on("\n").join(firstPartyLicenses));
      sb.append("\n1po:\n");
      sb.append(Joiner.on("\n").join(firstPartyOwners));
      sb.append("\n3pl:\n");
      sb.append(Joiner.on("\n").join(thirdPartyLicenses));
      sb.append("\n3po:\n");
      sb.append(Joiner.on("\n").join(thirdPartyOwners));
      sb.append("\n!!l:\n");
      sb.append(Joiner.on("\n").join(forbiddenLicenses));
      sb.append("\n!!o:\n");
      sb.append(Joiner.on("\n").join(forbiddenOwners));
      sb.append("\nxx:\n");
      sb.append(Joiner.on("\n").join(excludePatterns));
      return Hashing.farmHashFingerprint64().hashBytes(sb.toString().getBytes(UTF_8)).toString();
    }

    /** Implements the Builder pattern for CopyrightPatterns.RuleSet. */
    public static class Builder {
      private final ImmutableList.Builder<String> firstPartyLicenses =
          ImmutableList.<String>builder();
      private final ImmutableList.Builder<String> thirdPartyLicenses =
          ImmutableList.<String>builder();
      private final ImmutableList.Builder<String> forbiddenLicenses =
          ImmutableList.<String>builder();
      private final ImmutableList.Builder<String> firstPartyOwners =
          ImmutableList.<String>builder();
      private final ImmutableList.Builder<String> thirdPartyOwners =
          ImmutableList.<String>builder();
      private final ImmutableList.Builder<String> forbiddenOwners = ImmutableList.<String>builder();
      private final ImmutableList.Builder<String> excludePatterns = ImmutableList.<String>builder();

      private Builder() {}

      /** Create a RuleSet reflecting the current state of this Builder. */
      public RuleSet build() {
        return new RuleSet(
            firstPartyLicenses.build(),
            thirdPartyLicenses.build(),
            forbiddenLicenses.build(),
            firstPartyOwners.build(),
            thirdPartyOwners.build(),
            forbiddenOwners.build(),
            excludePatterns.build());
      }

      /** Look up `ruleName` and add it as a 1p rule type. */
      public Builder addFirstParty(String ruleName) {
        Rule pattern = lookup.get(ruleName);
        if (pattern == null) {
          throw unknownPatternName(ruleName);
        }
        if (pattern.licenses != null) {
          firstPartyLicenses.addAll(pattern.licenses);
        }
        if (pattern.owners != null) {
          firstPartyOwners.addAll(pattern.owners);
        }
        if (pattern.exclusions != null) {
          excludePatterns.addAll(pattern.exclusions);
        }
        return this;
      }

      /** Add the regular expression `pattern` as a 1p owner. */
      public Builder addFirstPartyOwner(String pattern) {
        firstPartyOwners.add(pattern);
        return this;
      }

      /** Add the regular expression `pattern` as a 1p license */
      public Builder addFirstPartyLicense(String pattern) {
        firstPartyLicenses.add(pattern);
        return this;
      }

      /** Look up `ruleName` and add it as a 3p rule type. */
      public Builder addThirdParty(String ruleName) {
        Rule pattern = lookup.get(ruleName);
        if (pattern == null) {
          throw unknownPatternName(ruleName);
        }
        if (pattern.licenses != null) {
          thirdPartyLicenses.addAll(pattern.licenses);
        }
        if (pattern.owners != null) {
          thirdPartyOwners.addAll(pattern.owners);
        }
        if (pattern.exclusions != null) {
          excludePatterns.addAll(pattern.exclusions);
        }
        return this;
      }

      /** Add the regular expression `pattern` as a 3p owner. */
      public Builder addThirdPartyOwner(String pattern) {
        thirdPartyOwners.add(pattern);
        return this;
      }

      /** Add the regular expression `pattern` as a 3p license. */
      public Builder addThirdPartyLicense(String pattern) {
        thirdPartyLicenses.add(pattern);
        return this;
      }

      /** Look up `ruleName` and add it as a forbidden rule type. */
      public Builder addForbidden(String ruleName) {
        Rule pattern = lookup.get(ruleName);
        if (pattern == null) {
          throw unknownPatternName(ruleName);
        }
        if (pattern.licenses != null) {
          forbiddenLicenses.addAll(pattern.licenses);
        }
        if (pattern.owners != null) {
          forbiddenOwners.addAll(pattern.owners);
        }
        if (pattern.exclusions != null) {
          excludePatterns.addAll(pattern.exclusions);
        }
        return this;
      }

      /** Add the regular expression `pattern` as a forbidden owner. */
      public Builder addForbiddenOwner(String pattern) {
        forbiddenOwners.add(pattern);
        return this;
      }

      /** Add the regular expression `pattern` as a forbidden license. */
      public Builder addForbiddenLicense(String pattern) {
        forbiddenLicenses.add(pattern);
        return this;
      }

      /** Look up `ruleName` and add it as a rule type to ignore completely. */
      public Builder exclude(String ruleName) {
        Rule pattern = lookup.get(ruleName);
        if (pattern == null) {
          throw unknownPatternName(ruleName);
        }
        if (pattern.licenses != null) {
          excludePatterns.addAll(pattern.licenses);
        }
        if (pattern.owners != null) {
          excludePatterns.addAll(pattern.owners);
        }
        if (pattern.exclusions != null) {
          excludePatterns.addAll(pattern.exclusions);
        }
        return this;
      }

      /** Add the regular expression `pattern` to the list of patterns to ignore when found. */
      public Builder excludePattern(String pattern) {
        excludePatterns.add(pattern);
        return this;
      }
    }

    private RuleSet(
        ImmutableList<String> firstPartyLicenses,
        ImmutableList<String> thirdPartyLicenses,
        ImmutableList<String> forbiddenLicenses,
        ImmutableList<String> firstPartyOwners,
        ImmutableList<String> thirdPartyOwners,
        ImmutableList<String> forbiddenOwners,
        ImmutableList<String> excludePatterns) {
      this.firstPartyLicenses = firstPartyLicenses;
      this.thirdPartyLicenses = thirdPartyLicenses;
      this.forbiddenLicenses = forbiddenLicenses;
      this.firstPartyOwners = firstPartyOwners;
      this.thirdPartyOwners = thirdPartyOwners;
      this.forbiddenOwners = forbiddenOwners;
      this.excludePatterns = excludePatterns;
    }

    private static UnknownPatternName unknownPatternName(String ruleName) {
      int minDist = -1;
      for (String key : lookup.keySet()) {
        int dist = StringUtils.getLevenshteinDistance(key, ruleName);
        if (minDist < 0 || dist < minDist) {
          minDist = dist;
        }
      }
      ImmutableList.Builder<String> closeMatches = ImmutableList.builder();
      int numClose = 0;
      if (minDist < 3) {
        for (String key : lookup.keySet()) {
          int dist = StringUtils.getLevenshteinDistance(key, ruleName);
          if (dist == minDist) {
            closeMatches.add(key);
            numClose++;
          }
        }
      }
      String matches = Joiner.on(", ").join(numClose > 0 ? closeMatches.build() : lookup.keySet());
      int lastIndex = matches.lastIndexOf(", ");
      if (lastIndex > 0) {
        matches = matches.substring(0, lastIndex + 2) + "or " + matches.substring(lastIndex + 2);
      }
      String message =
          "Unknown license or copyright owner name: "
              + ruleName
              + "\n\n"
              + (numClose > 0
                  ? "Did you mean " + matches + "?"
                  : "Known names are: " + matches + ".");

      return new UnknownPatternName(message);
    }
  }

  /** Initialize a pattern consisting of only a list of owner patterns. */
  private static Rule owner(ImmutableList<String> owners) {
    return new Rule(owners, null, null);
  }

  /** Initialize a pattern consisting of only a list of license patterns. */
  private static Rule license(ImmutableList<String> licenses) {
    return new Rule(null, licenses, null);
  }

  /** Initialize a pattern consisting of lists of license and exclusion patterns. */
  private static Rule license(ImmutableList<String> licenses, ImmutableList<String> exclusions) {
    return new Rule(null, licenses, exclusions);
  }

  /**
   * A matching rule described by lists of regular expressions matching relevant licenses and
   * owners, and a list of regular expressions matching hits to ignore when found.
   *
   * <p>e.g. The text "not a contribution" is important for Apache2 licensed code because it
   * disclaims the terms of the otherwise described Apache2 license. However, this very text exists
   * inside the Apache2 license to allow such disclaimers. An effective rule for /not a
   * contribution/ will have to match /not a contribution/ but ignore /owner as "not a
   * contribution"/ like it appears in the license itself.
   */
  @VisibleForTesting
  static class Rule {
    public final ImmutableList<String> exclusions;
    public final ImmutableList<String> owners;
    public final ImmutableList<String> licenses;

    private Rule(ImmutableList<String> owners, ImmutableList<String> licenses) {
      this(owners, licenses, null);
    }

    private Rule(
        ImmutableList<String> owners,
        ImmutableList<String> licenses,
        ImmutableList<String> exclusions) {
      this.owners = owners;
      this.licenses = licenses;
      this.exclusions = exclusions;
    }
  }

  /** Thrown when requesting a pattern by a name that does not appear among the known patterns. */
  public static class UnknownPatternName extends NoSuchElementException {
    UnknownPatternName(String message) {
      super(message);
    }
  }
}
