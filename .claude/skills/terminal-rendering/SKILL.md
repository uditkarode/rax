---
name: terminal-rendering
description: How the terminal is drawn and how it looks. Covers how `:ghostty` sizes cells and draws glyphs, the font fallback chain and how to check glyph coverage, bundled and imported fonts, Rex themes and how app colours come from them, the UI font, and why Rex's snapshots can stop restoring. Use when glyphs show as emoji, boxes or gaps, when changing fonts or themes, or when snapshots fail to restore.
---

# Terminal rendering

## How `:ghostty` draws

- `AndroidGlyphPainter` (`ghostty/.../compose/Platform.kt`) draws each cell with
  `Canvas.drawText` and an Android `Paint` that uses the chosen `Typeface`, which Rax passes as a
  Compose `FontFamily`. Desktop Ghostty draws box drawing and block elements itself, but
  `:ghostty` does not: every glyph comes from a font.
- Cell width is `measureText("M")`, and cell height is `ceil(descent - ascent)` of the base font.
  `Paint.getFontMetrics` uses only the first font of a fallback typeface.
- Because of that, a glyph from a fallback font draws at that font's size, and block elements such
  as `█` fill the cell only if the font's glyph spans its whole line height. In several fonts it
  does not, so stacked blocks (Claude Code's logo, for example) show thin gaps between rows. The
  fix is a change in `AndroidGlyphPainter`.

## Font fallback

`TerminalFonts` builds every terminal typeface as:

`the chosen font → Symbols Nerd Font → JuliaMono → the system's monospace`

- Symbols Nerd Font draws prompt and icon glyphs from the Private Use Area.
- JuliaMono covers the symbol blocks that TUIs use: Miscellaneous Technical (⏺ ⏵ ⏸ ⎇ ⎿), Dingbats
  (✻ ✳ ✔), Geometric Shapes, Arrows, Braille spinners, Box Drawing, Block Elements and Legacy
  Computing.
- Without JuliaMono, Android falls back to Noto Color Emoji for characters that are emoji with
  text presentation by default (⏺ ⏸), so they show as emoji. Characters that no system font has
  (⏵) show as empty boxes.
- Characters whose default presentation is emoji (⌛ ⚡) still use the colour emoji font. Desktop
  terminals show them the same way.
- JuliaMono's license reserves its name, so ship the unmodified file. A subset counts as a
  modified version and would need another name.

To check a font's coverage, read its `cmap` with fontTools and count the code points it has in
each of the blocks above. To check on screen, print a line of these symbols in a pane on the
isolated server and look at it in each font.

## Fonts

- `assets/fonts/<id>/` holds `regular`, `bold`, `italic` and `bolditalic` files, or `upright` and
  `italic` for variable fonts, plus `LICENSE.txt`. `assets/fonts/fonts.json` lists the fonts the
  user can pick. Acknowledgements read each `LICENSE.txt`.
- Only fonts with an open license can be bundled. SF Mono's license limits it to Apple platforms,
  so users add it themselves: Rax imports font files, groups faces into families by name, and
  rejects fonts that are not monospaced (`OpenType` reads the tables).
- When a release drops a bundled font, `AppGraph` switches a saved choice of it back to the
  default font.

## Themes

- Rax uses Rex's own themes. `tools/rex-themes.py` reads `themes.json` in Rex's
  `SuperUI_SuperDesignTokens.bundle` inside Rex.app or Rex Beta.app, and writes
  `assets/themes/rex-themes.json`. For each theme it keeps the terminal colours, the 256-colour
  palette Rex generates for that theme, and Rex's interface colours (its CSS variables), all in
  sRGB. Run the script again after a Rex update that changes themes.
- The app's own colours (`RaxColors`) come from the same theme, so Rax looks like Rex's windows,
  and light or dark follows the theme. The link colour switches to a readable colour when the
  theme's accent has less than 4.5:1 contrast on its surface.
- The themes are Superlogical's design. Shipping them needs Superlogical's permission.

## UI font

The interface uses Google Sans Flex, cut down by `tools/ui-font.sh` to its weight and
optical-size axes. A Compose `FontFamily` can pick faces only by weight, so there are two families:
`TextFamily` at optical size 16 and `DisplayFamily` at 32 for large titles.

## Snapshot compatibility

- Rex sends libghostty's GHOSTSNP snapshot at the start of each stream. Rex has libghostty built
  in as a Go module (the version is in `docs/rex-schema/deps.txt`), and `:ghostty` builds Ghostty
  at the commit in `ghostty/GHOSTTY_COMMIT`. The two versions can drift apart.
- libghostty-vt 1.0 made its C API stable but not the snapshot format: "Snapshot format version 1
  is a work in progress and doesn't carry a binary-compatibility guarantee."
- When the versions disagree, `restore` returns null and Rax asks Rex for the screen as escape
  sequences instead (see `pane-pipeline`). The pane still works, but scrollback from before the
  pane was opened is limited to what that call returns. The fix is to move `GHOSTTY_COMMIT` to a
  Ghostty that reads the new format (`ghostty/README.md`).
- `GhosttySnapshotTest` in `:ghostty` restores a snapshot that Rex sent, so a Ghostty update that
  breaks Rex's format fails the tests.
- Rax's own `StreamParser` reads only the envelope: the `GHOSTSNP` magic and a u16 version, then
  records of u16 tag, u32 length, u32 CRC and payload, little-endian, until the record tagged 6
  (FINISH). Newer libghostty versions add record types that older decoders skip, so the envelope
  itself is stable.
