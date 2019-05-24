Modified Regular Expressions
============================

The copyright scanner has to meet several competing requirements: 1) it must
be fast to keep server load and latency reasonable, 2) it must find copyrights
and licenses appearing in and interrupted by any number of comment formats,
and 3) it must be configurable by mere mortals.

Configured patterns are basically [regular
expressions](https://docs.oracle.com/javase/7/docs/api/java/util/regex/Pattern.html)
with modifications to assist the 3 requirements above.


## Unlimited Wildcards

If you run the ScanTool.java command-line tool against the files in your
repository, it will already find a lot of the copyright and license declarations
that interest. Often it will pick up a couple extra junk words before or after
the text you want. In these cases, the natural inclination to use wildcards:
e.g. something like `.*My License.*` in a first-party pattern. However, when
scanning a large file, those wildcards can pick up a lot more junk words and
slow the scan to a crawl.

To allow the easy and obvious choice, the plugin allows you to use the .* and .+
patterns, and it modifies your pattern before scanning. When they appear at the
start or the end of the pattern, the plugin removes them before scanning the
file and puts them back when classifying the matches.

The scanner will still find all of the matches containing the pattern that it
found before plus it might find a few exact matches without increasing the work
to scan the file. The matches are limited in size. When it puts the wildcards
back to analyze the matches, the wildcards in your first-party pattern cause
it to match despite the junk words so it correctly identifies the first-party
license without costing much cpu time.

When they appear in the middle of your pattern, the plugin replaces them with a
pattern that matches a limited number of words separated by a limited number of
whitespace characters. The limits are large enough generally to match anything
of interest, but small enough to keep the scan down to a reasonable time.

If the limits cause the plugin to miss too many desired hits, it's always
possible to write a more complex pattern with different limits.

## Comment Characters and Whitespace

Whitespace does not affect the meaning of a license. The same text may be
formatted all manner of different ways using spaces, tabs, newlines, etc. to
align margins, center text, word-wrap at different columns etc. without changing
the meaning. Many times the same text will be incorporated into comments of
different languages. Even in the same language, sometimes the author will use
multi-line comments /*...*/ and sometimes the author will use single-line
comments //. Some languages use a completely different comment character #.

You could insert a complex expression anywhere you expect whitespace.
e.g. `[\\s/*#]+` But the configuration will already be unreadable, and if a file
has a lot of whitespace, that pattern could match large blocks while slowing
the scan.

To keep configurations readable, the plugin substitutes any embedded spaces with
a regular expression to match a limited number of whitespace or comment
characters where the limit is long enough to match almost all of the potential
hits without slowing the scan too much.

Mostly you don't need to worry about the details. Just use a space between words
even if they might appear on different lines etc.

However, if you use a space inside a character class [ a-z0-9], the plugin will
reject the pattern. You need to break that out into a more complicated pattern:
`(?: |[a-z0-9])`

## Capture Groups

The plugin uses capture groups to keep track of licenses versus owners. To group
parts of your patterns, use a non-capturing group `(?:pattern)` instead of
`(pattern)`. The plugin will reject your configuration if you try to use a
pattern that looks like it contains a capture group.

This might also force you to use a different order in character classes:
e.g.  `[)a-d(]` instead of `[(a-d)]`
