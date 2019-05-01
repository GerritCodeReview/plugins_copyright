@PLUGIN@ Configuration
======================

## Server Configuration

The server's `gerrit.config` plugin configuration has two parameters mostly to
protect the server against overly expensive scanner patterns.

```
  [plugin "@PLUGIN@"]
    enable = true
    timeTestMax = 8
```

plugin.@PLUGIN@.enable
:    Whether to run the scanner on the server

    When false, the server will not run the copyright scanner even in projects
    where the plugin has been enabled.

plugin.@PLUGIN@.timeTestMax
:    The maximum latency for a simulated heavy load in seconds

    When greater than 0, any time a new configuration changes the scanner
    pattern, the plugin will look for a specially constructed token in the
    commit message. The token is created by running a command-line tool that
    tests the scanner pattern against a simulated load that tries to trigger
    excessive backtracking.

    The token encodes the duration in a manner specific to the scanner pattern.

    If the token does not match the current pattern, the plugin will reject the
    configuration change.

    If the time it took to perform the scan exceeds the configured parameter,
    the plugin will reject the configuration change.

    The token is not secure. Anyone with access to the source code can fake the
    token with some effort so take care not to grant `All-Projects` access to
    potentially malicious actors.

    In general, folks with administrative access will find it easier to just run
    the command-line tool, to take heed of any warnings or errors it prints, and
    to copy+paste the token.

## Project Configuration

Each project's `project.config` has a configuration parameter to enable or to
disable the scanner. If the `All-Projects` `project.config` enables the scanner
by default, the project-level config can disable it and vice versa.

```
  [plugin "@PLUGIN@"]
    enable = true
```

plugin.@PLUGIN@.enable
:    Whether to run the scanner for files in the project

    When false, the server will not run the copyright scanner for revisions in
    the project even if enabled by default in the `All-Projects`
    `project.config`.

    When true, the server will run the copyright scanner for revisions in the
    project even if disabled by default in the `All-Projects` `project.config`.

## Plugin Configuration

The configuration of the @PLUGIN@ plugin is primarily done for the entire server
in the `project.config` file of the `All-Projects` default configuration.

```
  [plugin "@PLUGIN@"]
    fromAccountId = 31415926
    reviewLabel = Copyright-Review
    reviewer = copyright-expert
    reviewer = legal@example.com
    cc = needs-to-know@example.com
    cc = also-needs-to-know
    matchProjects = .*
    excludeProjects = ^private$
    excludeProjects = ^not-ours$
    thirdPartyAllowedProjects = ^external/
    thirdPartyAllowedProjects = ^third-party/
    alwaysReview = PATENT$
    exclude = EXAMPLES
    excludePattern = driver license
    excludePattern = 007 license to
    firstParty = ANDROID
    firstParty = APACHE2
    firstPartyPattern = owner special pattern for us
    firstPartyPattern = license special pattern for our license
    thirdParty = BSD
    thirdParty = MIT
    thirdParty = CC_BY_C
   thirdPartyPattern = owner special pattern for them
   thirdPartyPattern = license special pattern for their license
    forbidden = NOT_A_CONTRIBUTION
    forbidden = NON_COMMERCIAL
    forbidden = CC_BY_NC
    forbiddenPattern = owner we don't want them
    forbiddenPattern = license .*(?:Previously|formerly) licen[cs]ed under.*
    forbiddenPattern = license we don't like their terms
```

plugin.@PLUGIN@.alwaysReview
:    List of Path Patterns to Always Review

    The plugin first joins the project name to the file path within the project
    to create a full path. If any of this list of [regular
    expressions](https://docs.oracle.com/javase/7/docs/api/java/util/regex/Pattern.html)
    is found within the full path, the plugin will require review of the entire
    file without scanning the content.

    e.g. The example above would match all PATENT files, which could contain
    important legal terms requiring review without being licenses or copyrights
    per se.

plugin.@PLUGIN@.cc
:    List of Accounts or Email Addresses to CC on the Review

    When the plugin determines that review is required, it will add these
    accounts or addresses as CC reviewers.

plugin.@PLUGIN@.exclude
:    List of [Known Patterns](known-patterns.md) to Ignore when Found

    When found in a revision, the scanner will skip these patterns as if never
    found. They do not get reported as findings in review comments.

    This is a noise-reduction parameter.

    e.g. The example above would skip made-up examples of copyrights that can
    appear in tests or documentation but are not actual copyright declarations.

plugin.@PLUGIN@.excludePattern
:    List of [Modified Regular Expressions](modified-regex.md) to Ignore

    When found in a revision, the scanner will skip these patterns as if never
    found. They do not get reported as findings in review comments.

    This is a noise-reduction parameter.

    Unlike first-party, third-party or forbidden, it doesn't matter whether the
    match is for an owner or for a license. Do not use the `owner` or `license`
    keyword for this parameter.

plugin.@PLUGIN@.excludeProjects
:    List of Project Patterns to Skip

    When any of these [regular
    expressions](https://docs.oracle.com/javase/7/docs/api/java/util/regex/Pattern.html)
    are found in the project name, the plugin will skip the revision regardless
    whether the `project.config` enables scanning.

    Defaults to skipping no projects.

plugin.@PLUGIN@.firstParty
:    List of [Known Patterns](known-patterns.md) to Treat as First-Party

    When found in a revision, the scanner will treat these patterns as
    first-party. The plugin will not require review, and it will report the
    finding in a resolved comment.

plugin.@PLUGIN@.firstPartyPattern
:    List of Owner or License Patterns to Treat as First-Party

    When found in a revision, the scanner will treat these patterns as
    first-party. Each of these patterns must start with the `owner` or `license`
    keyword followed by the [Modified Regular Expression](modified-regex.md) to
    find.

    When combined with other findings in the same file, the `owner` or `license`
    affects whether the plugin will require review:

    A first-party license will take precedence over a third-party author
    because multiple contributors to an open-source project is the norm. It
    does not take precedence over other licenses.

    A first-party owner will combine with a first-party license, but will not
    take precedence over other licenses or owners.

    Regardless whether the file needs review, the plugin will report first-party
    findings in resolved comments.

plugin.@PLUGIN@.forbidden
:    List of [Known Patterns](known-patterns.md) to Treat as Forbidden

    When found in a revision, the scanner will treat these patterns as
    forbidden. The plugin will always require review, and it will report the
    finding in an unresolved comment.

    e.g. The example given above requires review for non-commercial licenses and
    the phrase "not a contribution" as documented in the Apache 2.0 license for
    identifying modified Apache 2.0 code to explicitly exclude the modifications
    from the license.

plugin.@PLUGIN@.forbiddenPattern
:    List of Owner or License Patterns to Treat as Forbidden

    When found in a revision, the scanner will treat these patterns as
    forbidden. Each of these patterns must start with the `owner` or `license`
    keyword followed by the [Modified Regular Expression](modified-regex.md) to
    find.

    The plugin uses the `owner` or `license` to describe the finding.

    Any file containing a forbidden match will always require review, and the
    plugin will report the finding as an unresolved comment.

    e.g. One of the examples given above requires review for phrases sometimes
    used like "not a contribution" to disclaim the prior license.

plugin.@PLUGIN@.fromAccountId
:    Numeric Account Id Impersonated by the Plugin

    Review comments will appear to come from the given account. Recommend
    creating a non-interactive user with a descriptive name like
    "Copyright Scanner".

plugin.@PLUGIN@.matchProjects
:    List of Project Patterns to Scan

    When any of these [regular
    expressions](https://docs.oracle.com/javase/7/docs/api/java/util/regex/Pattern.html)
    are found in the project name, the plugin will look to the `project.config`
    and `All-Projects` default to determine whether the scanner is enabled.

    If enabled, the plugin will scan the files in the revision.

    Defaults to scanning all enabled projects.

plugin.@PLUGIN@.reviewLabel
:    Single Review Label for Copyrights

    When the plugin determines a revision requires review, it will vote -1 on
    the configured label and add reviewers. When it determines the revision
    requires no special review, it will vote +2 on the configured label and make
    no changes to the reviewers.

plugin.@PLUGIN@.reviewer
:    List of Accounts or Email Address to Add as Reviewers

    When the plugin determines the revision requires review, it will add these
    accounts as reviewers. At least one reviewer must be able to vote +2 on
    the review label and have adequate knowledge of copyright licenses to
    review their texts.

plugin.@PLUGIN@.thirdParty
:    List of [Known Patterns](known-patterns.md) to Treat as Third-Party

    When found in a revision, the scanner will treat these patterns as
    third-party. The plugin will require review unless the revision is in a
    project configured to allow third-party code. When the third-party finding
    requires review, the plugin will report the finding in an unresolved
    comment. When the configuration allows the third-party finding--in a
    project configured to allow third-party licenses or a third-party author of
    first-party-licensed code--the plugin will report the finding in a resolved
    comment.

    e.g. The example above shows the legacy MIT and BSD open-source licenses as
    well as the pseudo-license CC_BY_C, which matches all of the Creative
    Commons Attribution license variants allowing commercial use.

plugin.@PLUGIN@.thirdPartyAllowedProjects
:    List of Project Patterns Where Third-Party Licenses Allowed

    When any of these [regular
    expressions](https://docs.oracle.com/javase/7/docs/api/java/util/regex/Pattern.html)
    are found in the project name, the plugin will accept third-party licenses
    and owners without requiring review.

plugin.@PLUGIN@.thirdPartyPattern
:    List of Owner or License Patterns to Treat as Third-Party

    When found in a revision, the scanner will treat these patterns as
    third-party. Each of these patterns must start with the `owner` or `license`
    keyword followed by the [Modified Regular Expression](modified-regex.md) to
    find.

    When combined with other findings in the same file, the `owner` or `license`
    affects whether the plugin will require review:

    A third-party license will take precedence over any first-party findings.

    A third-party owner be reported as third-party but accepted as part of a
    first-party license. In open-source projects, multiple contributors are the
    norm.

    When a review is required for the third-party finding--not in a project
    configured to accept third-party code and not a third-party author of
    first-party licensed code--the plugin will report the finding in an
    unresolved comment.

    When found in a location that allows third-party or as part of a first-party
    license, the plugin will report the finding in a resolved comment.
