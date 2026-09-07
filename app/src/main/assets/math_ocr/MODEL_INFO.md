# Bundled math OCR models

All files are bundled for offline inference. The app never uploads the source image during the exam OCR flow.

## Pix2Text MFD 1.5

- Source: `https://huggingface.co/breezedeus/pix2text-mfd-1.5`
- Revision: `f470a885e0fca1d3d2bfa2a54991db7ae01f1861`
- License: MIT
- File: `pix2text-mfd-1.5.onnx`
- SHA-256: `40D4FC852D99BCBF25A9478897D2F49FBBB8F7FDD6569C088CD1C31386293BD7`

## Pix2Text MFR

- Source: `https://huggingface.co/breezedeus/pix2text-mfr`
- Revision: `bea257edb2653f2ae413b084f2ac0e8299d08df0`
- License: MIT
- Encoder SHA-256: `BD8D5C322792E9EC45793AF5569E9748F82A3D728A9E00213DBFC56C1486F37D`
- Decoder SHA-256: `FD0F92D7A012F3DAE41E1AC79421AEA0EA888B5A66CB3F9A004E424F82F3DAED`
- Tokenizer SHA-256: `3E2AB757277D22639BEC28C9D7972E352D3D1DBA223051FA674002DC5AB64DF3`

The remaining configuration-file hashes are enforced by `verifyMathOcrModels` in `app/build.gradle.kts`.
