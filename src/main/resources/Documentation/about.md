This plugin allows to configure required copyright reviews.

The plugin looks for text patterns that might be license terms or copyright
ownership declarations in newly uploaded revisions. It broadly groups findings
into first-party, third-party, forbidden or unknown.

The plugin requires special review when third-party code appears outside of
areas designated for third-party code, or when forbidden or unknown licenses
appear anywhere.

Note: The plugin does not make definitive assertions about the licenses or
owners it finds. It leaves that up to a copyright-knowledgeable human reviewer.

It will have false-positives--especially in the unknown category. It may also
have false negatives.

## First-party

Licensed or owned by the owner of the gerrit repository.

e.g. The Android Open Source Project (AOSP) uses the Apache 2.0 License and
accepts any contributions released under that license as first-party.

## Third-party

Licensed or owned by someone other than the user or the owner of the gerrit
repository, but released under license terms compatible with the first-party
license and accepted by the owners of the repository.

e.g. AOSP accepts a handful of legacy open-source licenses like MIT or BSD.

## Forbidden

Licensed under terms incompatible with the first-party license or considered
onerous by the owner of the host.

e.g. AOSP cannot accept restrictions prohibiting "commercial use" because
vendors generally sell Android devices for profit.

## Unknown

In addition to common copyright and license patterns, the scanner searches for
a set of words and patterns that often appear in or near copyright/license
declarations, but do not often appear elsewhere in source code.

These apparent license terms do not match any of the known licenses. Because 
such licenses might contain any number of incompatible or onerous terms, they
are treated the same as forbidden. i.e. always require review.

Unrecognized authors or owners are considered third-party rather than unknown.
