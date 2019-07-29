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

package com.googlesource.gerrit.plugins.copyright.tools;

import com.googlesource.gerrit.plugins.copyright.lib.CopyrightPatterns;
import java.io.IOException;

/** Runs the scan tool with patterns reflecting Android Open Source Project (AOSP) policies. */
public class AndroidScan {

  public static void main(String[] args) throws IOException {
    ScanTool.toolName = "android_scan";
    ScanTool.rules =
        CopyrightPatterns.RuleSet.builder()
            .exclude("EXAMPLES")
            // 1p
            .addFirstParty("APACHE2")
            .addFirstParty("ANDROID")
            .addFirstParty("GOOGLE")
            .addFirstParty("EXAMPLES")
            // 3p
            .addThirdParty("AFL2.1")
            .addThirdParty("AFL3.0")
            .addThirdParty("APACHE1.1")
            .addThirdParty("ARTISTIC_LICENSE")
            .addThirdParty("BSD")
            .addThirdParty("BSL1.0")
            .addThirdParty("CC0")
            .addThirdParty("CLANG_LLVM")
            .addThirdParty("EDL")
            .addThirdParty("EPL")
            .addThirdParty("FTL")
            .addThirdParty("GPL2")
            .addThirdParty("GPL3")
            .addThirdParty("JSON")
            .addThirdParty("LIBTIFF")
            .addThirdParty("LPL1.02")
            .addThirdParty("MIT")
            .addThirdParty("MS-PL")
            .addThirdParty("NCSA")
            .addThirdParty("OPENSSL")
            .addThirdParty("PSFL")
            .addThirdParty("UNLICENSE")
            .addThirdParty("UNICODE")
            .addThirdParty("W3C")
            .addThirdParty("XNET")
            .addThirdParty("ZEND")
            .addThirdParty("ZPL")
            .addThirdParty("ZLIB")
            // Forbidden
            .addForbidden("AGPL")
            .addForbidden("BEER_WARE")
            .addForbidden("CC_BY_C")
            .addForbidden("CC_BY_NC")
            .addForbidden("COMMONS_CLAUSE")
            .addForbidden("CPAL")
            .addForbidden("EUPL")
            .addForbidden("GPL")
            .addForbidden("LGPL")
            .addForbidden("NON_COMMERCIAL")
            .addForbidden("NOT_A_CONTRIBUTION")
            .addForbiddenLicense(".*(?:Previously|formerly) licen[cs]ed under.*")
            .addForbidden("SISSL")
            .addForbidden("SSPL")
            .addForbidden("WATCOM")
            .addForbidden("WTFPL")
            .build();

    ScanTool.main(args);
  }
}
