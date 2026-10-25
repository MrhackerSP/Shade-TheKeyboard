Place optional on-device model files here:

neural/model.ort
neural/vocab.tsv

Expected ONNX contract:
- input: int64[1, 16]
- output: float32[1, vocab_size]

Recommended vocabulary format (TSV):
id<TAB>byte_length<TAB>token

If these files are absent, Shade continues using its existing dictionary and
bigram suggestion engine without neural predictions.
