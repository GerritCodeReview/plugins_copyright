load("//tools/bzl:junit.bzl", "junit_tests")
load("//tools/bzl:plugin.bzl", "PLUGIN_DEPS", "PLUGIN_TEST_DEPS", "gerrit_plugin")

filegroup(
    name = "testdata",
    srcs = glob(["src/test/java/com/googlesource/gerrit/plugins/copyright/testdata/**"]),
)

java_library(
    name = "copyright_scanner",
    srcs = glob(["src/main/java/**/*.java"]),
    deps = PLUGIN_DEPS,
)

java_binary(
    name = "scan_tool",
    srcs = ["src/main/java/com/googlesource/gerrit/plugins/copyright/tools/ScanTool.java"],
    main_class = "com.googlesource.gerrit.plugins.copyright.tools.ScanTool",
    deps = [
        ":copyright_scanner",
        "@commons-compress//jar",
        "@guava//jar",
    ],
)

java_binary(
    name = "android_scan",
    srcs = ["src/main/java/com/googlesource/gerrit/plugins/copyright/tools/AndroidScan.java"],
    main_class = "com.googlesource.gerrit.plugins.copyright.tools.AndroidScan",
    deps = [":copyright_scanner"],
)

TEST_SRCS = "src/test/java/**/*Test.java"

TEST_DEPS = PLUGIN_DEPS + PLUGIN_TEST_DEPS + [
    ":copyright_scanner",
    "//lib/mockito",
    "@guava//jar",
    "@mockito//jar",
]

junit_tests(
    name = "copyright_scanner_tests",
    testonly = 1,
    srcs = glob([TEST_SRCS]),
    tags = ["copyright"],
    deps = TEST_DEPS,
)

sh_test(
    name = "AndroidScanTest",
    size = "small",
    srcs = ["src/test/java/com/googlesource/gerrit/plugins/copyright/tools/AndroidScanTest.sh"],
    data = [
        ":android_scan",
        ":testdata",
    ],
)

sh_test(
    name = "ScanToolTest",
    size = "small",
    srcs = ["src/test/java/com/googlesource/gerrit/plugins/copyright/tools/ScanToolTest.sh"],
    data = [
        ":scan_tool",
        ":testdata",
    ],
)
