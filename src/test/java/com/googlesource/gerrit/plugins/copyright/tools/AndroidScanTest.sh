#!/bin/bash

# Copyright (C) 2019 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

readonly root=$(dirname "${TEST_BINARY}")
readonly scanner="${root}/android_scan"
readonly testdata="${root}/src/test/java/com/googlesource/gerrit/plugins/copyright/testdata"

function die() {
  echo -e "$@" >&2
  exit 1
}

echo "Testing APACHE2 (1p) license"
output=$(echo "${testdata}/licenses/APACHE2.txt" | "${scanner}" --f=- -v) \
    || die "Failed scanning APACHE2 license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "FIRST_PARTY" ]]; then
  die "Expected first party and only first party licenses in APACHE2 license but found ${output}"
fi

echo "Testing ANDROID (1p) owner"
output=$("${scanner}" -v "${testdata}/licenses/ANDROID.txt") || die "Failed scanning ANDROID owner."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "FIRST_PARTY" ]]; then
  die "Expected first party and only first party owners in ANDROID owner but found ${output}"
fi

echo "Testing first_party.zip deep"
output=$("${scanner}" --deep "${testdata}/archives/first_party.zip") \
    || die "Failed deep scanning first_party.zip"
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "FIRST_PARTY" ]]; then
  die "Expected first party and only first party owners in first_party.zip but found ${output}"
fi

echo "Testing AFL2_1 (3p) license"
output=$("${scanner}" "${testdata}/licenses/AFL2_1.txt") || die "Failed scanning AFL2_1 license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in AFL2_1 license but found ${output}"
fi

echo "Testing AFL3_0 (3p) license"
output=$("${scanner}" "${testdata}/licenses/AFL3_0.txt") || die "Failed scanning AFL3_0 license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in AFL3_0 license but found ${output}"
fi

echo "Testing APACHE1_1 (3p) license"
output=$("${scanner}" "${testdata}/licenses/APACHE1_1.txt") || die "Failed scanning APACHE1_1 license."
licenses=$(echo "${output}" | fgrep 'LICENSE' | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in APACHE1_1 license but found ${output}"
fi

echo "Testing ARTISTIC_LICENSE (3p) license"
output=$("${scanner}" "${testdata}/licenses/ARTISTIC_LICENSE.txt") || die "Failed scanning ARTISTIC_LICENSE license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in ARTISTIC_LICENSE license but found ${output}"
fi

echo "Testing BSL1_0 (3p) license"
output=$("${scanner}" "${testdata}/licenses/BSL1_0.txt") || die "Failed scanning BSL1_0 license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in BSL1_0 license but found ${output}"
fi

echo "Testing BSD2 (3p) license"
output=$("${scanner}" "${testdata}/licenses/BSD2.txt") || die "Failed scanning BSD2 license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in BSD2 license but found ${output}"
fi

echo "Testing CC0 (3p) license"
output=$("${scanner}" "${testdata}/licenses/CC0.txt") || die "Failed scanning CC0 license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in CC0 license but found ${output}"
fi

echo "Testing EDL (3p) license"
output=$("${scanner}" "${testdata}/licenses/EDL.txt") || die "Failed scanning EDL license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in EDL license but found ${output}"
fi

echo "Testing EPL (3p) license"
output=$("${scanner}" "${testdata}/licenses/EPL.txt") || die "Failed scanning EPL license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in EPL license but found ${output}"
fi

echo "Testing FTL (3p) license"
output=$("${scanner}" "${testdata}/licenses/FTL.txt") || die "Failed scanning FTL license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in FTL license but found ${output}"
fi

echo "Testing LIBTIFF (3p) license"
output=$("${scanner}" "${testdata}/licenses/LIBTIFF.txt") || die "Failed scanning LIBTIFF license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in LIBTIFF license but found ${output}"
fi

echo "Testing LPL1_02 (3p) license"
output=$("${scanner}" "${testdata}/licenses/LPL1_02.txt") || die "Failed scanning LPL1_02 license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in LPL1_02 license but found ${output}"
fi

echo "Testing MIT (3p) license"
output=$("${scanner}" "${testdata}/licenses/MIT.txt") || die "Failed scanning MIT license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in MIT license but found ${output}"
fi

echo "Testing NCSA (3p) license"
output=$("${scanner}" "${testdata}/licenses/NCSA.txt") || die "Failed scanning NCSA license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in NCSA license but found ${output}"
fi

echo "Testing OPENSSL (3p) license"
output=$("${scanner}" "${testdata}/licenses/OPENSSL.txt") || die "Failed scanning OPENSSL license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in OPENSSL license but found ${output}"
fi

echo "Testing PSFL (3p) license"
output=$("${scanner}" "${testdata}/licenses/PSFL.txt") || die "Failed scanning PSFL license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in PSFL license but found ${output}"
fi

echo "Testing UNICODE (3p) license"
output=$("${scanner}" "${testdata}/licenses/UNICODE.txt") || die "Failed scanning UNICODE license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in UNICODE license but found ${output}"
fi

echo "Testing UNLICENSE (3p) license"
output=$("${scanner}" "${testdata}/licenses/UNLICENSE.txt") || die "Failed scanning UNLICENSE license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in UNLICENSE license but found ${output}"
fi

echo "Testing W3C (3p) license"
output=$("${scanner}" "${testdata}/licenses/W3C.txt") || die "Failed scanning W3C license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in W3C license but found ${output}"
fi

echo "Testing ZLIB (3p) license"
output=$("${scanner}" "${testdata}/licenses/ZLIB.txt") || die "Failed scanning ZLIB license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in ZLIB license but found ${output}"
fi

echo "Testing ZPL (3p) license"
output=$("${scanner}" "${testdata}/licenses/ZPL.txt") || die "Failed scanning ZPL license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in ZPL license but found ${output}"
fi

echo "Testing UNKNOWN (3p) owner"
output=$("${scanner}" "${testdata}/licenses/UNKNOWN.txt") || die "Failed scanning UNKNOWN owner."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party owners in UNKNOWN owner but found ${output}"
fi

echo "Testing third_party.tgz deep"
output=$("${scanner}" --deep "${testdata}/archives/third_party.tgz") \
    || die "Failed deep scanning third_party.tgz"
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party licenses in third_party.tgz but found ${output}"
fi

echo "Testing AFFERO (forbidden) license"
output=$("${scanner}" "${testdata}/licenses/AFFERO.txt.gz") || die "Failed scanning AFFERO license."
licenses=$(echo "${output}" | fgrep -v 'OWNER' | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "FORBIDDEN" ]]; then
  die "Expected forbidden and only forbidden licenses in AFFERO license but found ${output}"
fi
licenses=$(echo "${output}" | fgrep OWNER | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party owners in AFFERO license but found ${output}"
fi

echo "Testing forbidden.cpio deep"
output=$("${scanner}" --deep "${testdata}/archives/forbidden.cpio") \
    || die "Failed deep scanning forbidden.cpio"
licenses=$(echo "${output}" | fgrep -v 'OWNER' | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "FORBIDDEN" ]]; then
  die "Expected forbidden and only forbidden licenses in forbidden.cpio (deep) but found ${output}"
fi
licenses=$(echo "${output}" | fgrep OWNER | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party owners in fobidden.cpio but found ${output}"
fi

echo "Testing NOT_A_CONTRIBUTION (forbidden) license"
output=$("${scanner}" "${testdata}/licenses/NOT_A_CONTRIBUTION.txt") || die "Failed scanning NOT_A_CONTRIBUTION license."
licenses=$(echo "${output}" | fgrep -v 'OWNER' | fgrep -v 'FIRST_PARTY' | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "FORBIDDEN" ]]; then
  die "Expected forbidden licenses in NOT_A_CONTRIBUTION license but found ${output}"
fi

echo "Testing SSPL (forbidden) license"
output=$("${scanner}" "${testdata}/licenses/SSPL.txt") || die "Failed scanning SSPL license."
licenses=$(echo "${output}" | fgrep -v 'OWNER' | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "FORBIDDEN" ]]; then
  die "Expected forbidden and only forbidden licenses in SSPL license but found ${output}"
fi
licenses=$(echo "${output}" | fgrep OWNER | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party owners in SSPL license but found ${output}"
fi

echo "Testing WTFPL (forbidden) license"
output=$("${scanner}" "${testdata}/licenses/WTFPL.txt") || die "Failed scanning WTFPL license."
licenses=$(echo "${output}" | fgrep -v 'OWNER' | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "FORBIDDEN" ]]; then
  die "Expected forbidden and only forbidden licenses in WTFPL license but found ${output}"
fi

echo "Testing scan of non-file error"
"${scanner}" -v "${TEST_SRCDIR}/google3/javatests/com/google/devtools/compliance" 2>/dev/null \
    && die "Expected directory to fail scan."

echo "Testing scan of no files error"
"${scanner}" 2>/dev/null && die "Expected scan of no files to fail."

exit 0
