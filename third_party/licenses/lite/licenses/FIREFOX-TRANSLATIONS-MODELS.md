# Firefox Translations model coordinates

License: Mozilla Public License 2.0. The verbatim license from the archived
upstream model repository at revision
`e7957fc407441a5e3e35bbcbf9d60d9b35764618` is packaged beside this file as
`FIREFOX-TRANSLATIONS-MPL-2.0.txt`.

The application downloads these files directly from Mozilla's production model
storage. They are not stored in the APK/AAB.

## English to Chinese

Base URL:

`https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/models/en-zh/llmaat_finetune10M_qe8_f2_ByQcSxGXQRqGi-UTxYE43g/exported/`

| Compressed file | Bytes | SHA-256 | Expanded file | Bytes | SHA-256 |
|---|---:|---|---|---:|---|
| `model.enzh.intgemm.alphas.bin.gz` | 33,375,922 | `7f255403b3bb2502f08ac4d5ca397a8a5a13f899d2f2e987a4934e089d241d16` | `model.enzh.intgemm.alphas.bin` | 43,849,787 | `4e5accc141373565ddc8fa1565bceaa8d0c3482a82cab8131c719ebcc6c2157c` |
| `srcvocab.enzh.spm.gz` | 407,784 | `7846e3c236388390f4e5d321f8413d67f34c1bab5f066165eeb673bfd07607cc` | `srcvocab.enzh.spm` | 806,952 | `bd9b65504acc6d9726dd281f7defc2adb7c2c22d0688fe2f84697de25197c8c5` |
| `trgvocab.enzh.spm.gz` | 425,748 | `4d641ce165b1f8478ee2ffb5149d2d46fab3779dc8fa1e9b97f9af1d2206c091` | `trgvocab.enzh.spm` | 772,004 | `aded6993c36e440284d11cec3f6b8aef9c0e43188a772d80be342a713adf223d` |
| `lex.50.50.enzh.s2t.bin.gz` | 2,536,039 | `806f75821c0b838f4a8f4afe5bab3db8289cb7e5187753ba04c3bceadd75687a` | `lex.50.50.enzh.s2t.bin` | 4,485,184 | `8575d8daa10e2dbff316dcdf8e1ce475357bcc2c92bdc63b736a2d5add22f681` |

## Japanese to English

Base URL:

`https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/models/ja-en/cjk_retrain_base-memory_NLRJLD_pQFyrvgKtbie2nA/exported/`

| Compressed file | Bytes | SHA-256 | Expanded file | Bytes | SHA-256 |
|---|---:|---|---|---:|---|
| `model.jaen.intgemm.alphas.bin.gz` | 32,577,435 | `ae56ffbb5556d8e4240b2f208a7c7a2449a4b627ac9d673981ed29eaadaab79d` | `model.jaen.intgemm.alphas.bin` | 43,977,787 | `3a603e20bfe1be86071913f9e23ab5129075bc0a8490151020ac4821e4f17302` |
| `vocab.jaen.spm.gz` | 746,616 | `12d693f5055525d5cc1e133c8c1b8ed787c77b9bb797400d9a14382ac69c1236` | `vocab.jaen.spm` | 1,443,222 | `5cb217758bae05877bb3f0c2f612e4e7c1e4cb03c10db11f4a47098d7ae62919` |
| `lex.50.50.jaen.s2t.bin.gz` | 4,819,610 | `438152f5ccd982edb43e88ef51305e3ae7c7b66ee5c20a8fa425e9f1822f9b9b` | `lex.50.50.jaen.s2t.bin` | 9,348,172 | `525f412f0d210536c2933c78ae395fa0bf2b5ee6cc5dda61ebc2e79410ebaee4` |

Japanese-to-Chinese translation uses the Japanese-to-English model followed by
the English-to-Chinese model.
