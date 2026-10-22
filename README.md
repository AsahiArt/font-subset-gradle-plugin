# AsahiArt Font Subset Gradle Plugin

[![Pre Merge Checks](https://github.com/AsahiArt/font-subset-gradle-plugin/actions/workflows/pre-merge.yaml/badge.svg)](https://github.com/AsahiArt/font-subset-gradle-plugin/actions/workflows/pre-merge.yaml)
[![Publish Plugin to Portal](https://github.com/AsahiArt/font-subset-gradle-plugin/actions/workflows/publish-plugin.yaml/badge.svg)](https://github.com/AsahiArt/font-subset-gradle-plugin/actions/workflows/publish-plugin.yaml)

`io.github.asahiart.fontsubset` is a Gradle plugin for subsetting TTF and OTF font files down to the Unicode code points your app actually needs.

It supports:

- TrueType (`glyf`/`loca`) and OpenType CFF/CFF2 fonts
- `cmap` format 4 and 12
- Inline character lists and external character files
- Optional `dropGlyphNames`, writing `post` as version 3.0 without glyph names
- JSON-based batch configuration
- Composite builds for local plugin development

## Usage

```kotlin
plugins {
    id("io.github.asahiart.fontsubset") version "1.0.1"
}

fontSubset {
    font {
        source = "src/main/assets/fonts/SourceHanSansSC-Regular.otf"
        output = "src/main/assets/fonts/SourceHanSansSC-Regular-subset.otf"
        characters = "你好世界Hello"
        // Omit glyph names: `post` becomes version 3.0 (32-byte header).
        // Same idea as pyftsubset `--no-glyph-names`. Default false keeps 1.0.0 behavior.
        dropGlyphNames = true
    }

    font {
        source = "src/main/assets/fonts/Inter-Regular.ttf"
        output = "src/main/assets/fonts/Inter-Regular-subset.ttf"
        charactersFile = "font-chars.txt"
    }

    font {
        // Copy the source font to output as-is, without stripping glyphs.
        source = "src/main/assets/fonts/Noto-Full.ttf"
        output = "src/main/assets/fonts/Noto-Full.ttf"
        subset = false
    }

    // Optional batch config file relative to the project directory.
    configFile = "font-subset.json"
}
```

The plugin registers a `subsetFonts` task and automatically wires it into common Android resource-generation tasks when those tasks exist.

## JSON Config

```json
[
  {
    "source": "src/main/assets/fonts/SourceHanSansSC-Regular.otf",
    "output": "src/main/assets/fonts/SourceHanSansSC-Regular-subset.otf",
    "characters": "你好世界Hello",
    "dropGlyphNames": true
  },
  {
    "source": "src/main/assets/fonts/Inter-Regular.ttf",
    "output": "src/main/assets/fonts/Inter-Regular-subset.ttf",
    "charactersFile": "font-chars.txt"
  },
  {
    "source": "src/main/assets/fonts/Noto-Full.ttf",
    "output": "src/main/assets/fonts/Noto-Full.ttf",
    "subset": false
  }
]
```

## Per-font fields

| Field            | Type    | Default | Description                                                                                      |
| ---------------- | ------- | ------- | ------------------------------------------------------------------------------------------------ |
| `source`         | String  | —       | Path to the source font file, relative to the project directory.                                 |
| `output`         | String  | —       | Path to the output font file, relative to the project directory.                                 |
| `characters`     | String  | `""`    | Inline characters to keep. All unique Unicode code points in the string are included.            |
| `charactersFile` | String  | `""`    | Path to a plain-text file whose content supplies additional characters to keep.                  |
| `subset`         | Boolean | `true`  | When `false`, the source font is copied to `output` as-is, without stripping glyphs.             |
| `dropGlyphNames` | Boolean | `false` | When `true`, write `post` as version 3.0 (32 bytes, no glyph names). Header fields such as `italicAngle`, `underlinePosition`, `underlineThickness`, and `isFixedPitch` are copied from the source table. `false` leaves `post` unchanged, matching 1.0.0. Ignored when `subset` is `false`. Glyph selection, `cmap`, `maxp`, and `hmtx` do not use `post` glyph names. |

## Development

Run the full verification suite with:

```bash
./gradlew preMerge
```

Run only the plugin build checks with:

```bash
./gradlew -p plugin-build :plugin:check
```

## Publishing

The repository is configured to publish to the Gradle Plugin Portal via:

```bash
./gradlew --project-dir plugin-build setupPluginUploadFromEnvironment publishPlugins
```

You need `GRADLE_PUBLISH_KEY` and `GRADLE_PUBLISH_SECRET` in the environment or GitHub Actions secrets.
