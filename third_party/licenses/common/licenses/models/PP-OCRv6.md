# PP-OCRv6-small model coordinates

License: Apache License 2.0, as declared by each immutable Hugging Face model
card. The complete Apache License 2.0 text is packaged at
`assets/licenses/APACHE-2.0.txt`.

## Detection

- Repository: `PaddlePaddle/PP-OCRv6_small_det_onnx`
- Revision: `28fe5895c24fd108c19eb3e8479f4ab385fbfc62`
- File: `inference.onnx`
- SHA-256: `d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e`
- Model card: <https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/tree/28fe5895c24fd108c19eb3e8479f4ab385fbfc62>

## Recognition

- Repository: `PaddlePaddle/PP-OCRv6_small_rec_onnx`
- Revision: `b8f84f0b80c529de40b4fbb3544b84fa7233a513`
- File: `inference.onnx`
- SHA-256: `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634`
- Character source: `inference.yml`
- Source YAML SHA-256: `ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1`
- Generated `characters.txt` SHA-256:
  `b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d`
- Model card: <https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/tree/b8f84f0b80c529de40b4fbb3544b84fa7233a513>

The two ONNX files and generated character list are distributed inside every
APK/AAB. The build task verifies every source and generated digest before
packaging.
