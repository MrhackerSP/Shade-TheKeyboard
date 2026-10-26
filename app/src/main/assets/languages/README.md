# Runtime language assets

Shade loads direct ONNX Runtime assets from:

```text
languages/<lang>/model_<lang>.ort
languages/<lang>/vocab_<lang>.tsv
```

Canonical dictionaries live beside model assets when available:

```text
languages/en/dictionary.txt
languages/hi/dictionary.txt
languages/hinglish/dictionary.txt
```
