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
readonly scanner="${root}/scan_tool"
readonly testdata="${root}/src/test/java/com/googlesource/gerrit/plugins/copyright/testdata"

function die() {
  echo -e "$@" >&2
  exit 1
}

echo "Testing APACHE2 (unknown) license"
output=$("${scanner}" "${testdata}/licenses/APACHE2.txt") || die "Failed scanning APACHE2 license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "UNKNOWN" ]]; then
  die "Expected unknown and only unknown licenses in APACHE2 license but found ${output}"
fi

echo "Testing ANDROID (3p) owner"
output=$("${scanner}" "${testdata}/licenses/ANDROID.txt") || die "Failed scanning ANDROID owner."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party owners in ANDROID owner but found ${output}"
fi

echo "Testing first_party.zip deep"
output=$("${scanner}" --deep "${testdata}/archives/first_party.zip") \
    || die "Failed deep scanning first_party.zip"
licenses=$(echo "${output}" | fgrep -v OWNER | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "UNKNOWN" ]]; then
  die "Expected unknown and only unknown licenses in first_party.zip but found ${output}"
fi
licenses=$(echo "${output}" | fgrep OWNER | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party owners in first_party.zip but found ${output}"
fi

echo "Testing BSD2 (unknown) license"
output=$("${scanner}" "${testdata}/licenses/BSD2.txt") || die "Failed scanning BSD2 license."
licenses=$(echo "${output}" | fgrep -v OWNER | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "UNKNOWN" ]]; then
  die "Expected unknown and only unknown licenses in BSD2 but found ${output}"
fi
licenses=$(echo "${output}" | fgrep OWNER | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party owners in BSD2 but found ${output}"
fi

echo "Testing MIT (unknown) license"
output=$("${scanner}" "${testdata}/licenses/MIT.txt") || die "Failed scanning MIT license."
licenses=$(echo "${output}" | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "UNKNOWN" ]]; then
  die "Expected unknown and only unknown licenses in MIT license but found ${output}"
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
licenses=$(echo "${output}" | fgrep -v OWNER | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "UNKNOWN" ]]; then
  die "Expected unknown and only unknown licenses in third_party.zip but found ${output}"
fi
licenses=$(echo "${output}" | fgrep OWNER | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party owners in third_party.zip but found ${output}"
fi

echo "Testing AFFERO (unknown) license"
output=$("${scanner}" "${testdata}/licenses/AFFERO.txt.gz") || die "Failed scanning AFFERO license."
licenses=$(echo "${output}" | fgrep -v 'OWNER' | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "UNKNOWN" ]]; then
  die "Expected unknown and only unknown licenses in AFFERO license but found ${output}"
fi
licenses=$(echo "${output}" | fgrep OWNER | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party owners in AFFERO license but found ${output}"
fi

echo "Testing forbidden.cpio deep"
output=$("${scanner}" --deep "${testdata}/archives/forbidden.cpio") \
    || die "Failed deep scanning forbidden.cpio"
licenses=$(echo "${output}" | fgrep -v 'OWNER' | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "UNKNOWN" ]]; then
  die "Expected unknown and only unknown licenses in forbidden.cpio but found ${output}"
fi
licenses=$(echo "${output}" | fgrep OWNER | cut -d ' ' -f1 | sort -u)
if [[ "${licenses}" != "THIRD_PARTY" ]]; then
  die "Expected third party and only third party owners in fobidden.cpio but found ${output}"
fi

echo "Testing scan of non-file error"
"${scanner}" "${TEST_SRCDIR}/google3/javatests/com/google/devtools/compliance" 2>/dev/null \
    && die "Expected directory to fail scan."

echo "Testing scan of no files error"
"${scanner}" 2>/dev/null && die "Expected scan of no files to fail."

exit 0
