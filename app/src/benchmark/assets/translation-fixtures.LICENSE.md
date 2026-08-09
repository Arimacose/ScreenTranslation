# Translation regression fixture rights and provenance

Corpus release: `2026.08-public-v2-original-references`

This file applies to `translation-fixtures.json`.

## Project-authored material

The following material was written specifically for ScreenTranslation and is
provided under the repository's Apache License 2.0:

- every fixture whose `provenance_id` is
  `project-authored-apache-2.0`;
- every Chinese `reference_translations` entry, including references paired
  with a public-domain source excerpt;
- categories, tags, critical semantic checks, and fixture metadata.

The repository license is available at `../../../../LICENSE` from this
asset's source location and at the repository root after checkout.

## Public-domain source excerpts

Three fixtures quote short, attributed passages from original works identified
by the linked source records as public-domain material. The Project Gutenberg
records explicitly state "Public domain in the USA"; the Aozora record lists
the 1905 publication and Natsume Soseki's 1916 death. These source excerpts are
not relicensed as Apache-2.0 text:

| `provenance_id` | Work and author | Source record |
|---|---|---|
| `jane-austen-pride-prejudice-1813-public-domain` | *Pride and Prejudice* (1813), Jane Austen | <https://www.gutenberg.org/ebooks/1342> |
| `charles-dickens-tale-two-cities-1859-public-domain` | *A Tale of Two Cities* (1859), Charles Dickens | <https://www.gutenberg.org/ebooks/98> |
| `natsume-soseki-wagahai-1905-public-domain` | *吾輩は猫である* (1905), 夏目漱石 | <https://www.aozora.gr.jp/cards/000148/card789.html> |

Only the original source excerpt has that status. The accompanying Chinese
reference translations and annotations are project-authored Apache-2.0
material. Downstream redistributors remain responsible for checking the
public-domain status that applies in their jurisdiction.

## Stability contract

The corpus byte hash is pinned in `translation-fixtures.sha256`. A fixture edit
must deliberately create a new `corpus_release`, update the pinned hash, and
document why historical candidate scores are no longer directly comparable.
Adding or replacing a model result never changes the corpus file.

## Original-reference audit for public-domain excerpts

The three public-domain **source** excerpts remain unchanged, but all six
Chinese references were independently rewritten for this release. In
particular, the Austen references no longer reuse the familiar “universal
truth” wording, the Dickens references no longer reproduce the conventional
parallel translation, and the Soseki references no longer reproduce either of
the short translations retained in public v1.

The following checks were completed on 2026-08-09 before the v2 hash was
pinned:

1. Exact SHA-256 comparison against all six retired v1 references: `0/6`
   matches. Their hashes remain in `translation_regression.py` as a regression
   deny-list, without redistributing the retired text.
2. Salt-free SHA-256 fingerprints of sampled 12-character windows from the
   retired references: no v2 reference shares two windows. Only the hashes are
   retained; no retired expressive fragment is reintroduced. The validator
   rejects a future near-duplicate that matches two or more windows.
3. Exact web searches for one distinctive sentence from each newly authored
   group (Austen, Dickens, and both Soseki alternatives) returned no exact
   quoted match in the indexed results inspected on 2026-08-09. This search is
   an additional collision check, not a claim that a search engine indexes all
   published text.
4. Corpus-wide normalized source IDs, source texts, and reference alternatives
   are checked for exact duplicates during `validate`; candidate files never
   carry a reference, category, tag, source text, or semantic check that could
   override the canonical join.

Search phrases used for the external collision check:

- `大家似乎都认定：一个家境殷实而尚未成婚的男人`
- `那个年代一面极为美好，一面又最为糟糕`
- `说来，我是一只猫，到现在仍没人给我取名`
- `我这家伙是只猫，至今还没有一个名字`
