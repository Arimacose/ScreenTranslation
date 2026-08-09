# Translation regression fixture rights and provenance

Corpus release: `2026.08-public-v1`

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
