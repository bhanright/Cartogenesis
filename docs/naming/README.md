# Naming lists

Word lists gathered ahead of the atlas release (5.0), when realm, settlement and feature names
move from the syllable assembler to curated material. Each file is plain text, one entry per
line, ASCII, no commentary, so a build step can read it. `world-names.txt` is the list already
in use for a world's own name (`WorldNames` in `:ui` carries the same three hundred names; the
two must agree, and a test will say so once the build reads this file instead).

`realm-affixes.tsv` holds 120 realm affixes, tab-separated: the affix (suffixes with a leading
hyphen, prefixes as words followed by a space), its position, and its flavour (norse, english,
latin, greek, romance, soft), twenty per flavour.

Planned files, added as they are written: realm stems per flavour, settlement
prefixes and suffixes, river and sea words, mountain words, and the affixes that turn a stem
into a place name.
