# Bundled language assets

The organized SwiftKey language packs are bundled inside the app assets:

```text
app/src/main/assets/languages/
├── en_US/
├── hi_IN/
└── hg_IN/
```

Each language folder contains:

- language/model container files,
- emoji-search assets where available,
- punctuation / charactermap / IME configs,
- decoded vocabulary TSV files,
- Shade-ready files where available.

Runtime neural assets currently used by Shade remain in:

```text
app/src/main/assets/neural/
```

The bundled `languages/` folder is kept as the organized source-of-truth for
language-specific assets and future expansion.
