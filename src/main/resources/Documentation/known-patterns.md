Known Patterns
==============

To simplify @PLUGIN@ plugin configurations, the `firstParty`, `thirdParty`,
`forbidden`, and `exclude` configuration parameters reference sets of named
known [modified regular expressions](modified-regex.md).

Initially seeded with the patterns needed for the Android Open Source Project
(AOSP), the definitions are released open-source as part of the plugin, and
can be extended with contributions likewise released under Apache 2.0

To see the list of known names, earch for `lookup` in
[CopyrightPatterns.java](https://gerrit.googlesource.com/plugins/copyright/+/refs/heads/master/src/main/java/com/googlesource/gerrit/plugins/copyright/lib/CopyrightPatterns.java)

The names do not have to match an exact license or entity per se. For example,
the Creative Commons folks do not define a CC_BY_C license. The named pattern
matches any of several Creative Commons licenses that allow commercial use.

