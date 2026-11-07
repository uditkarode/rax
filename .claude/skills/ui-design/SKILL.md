---
name: ui-design
description: The design rules for Rax's interface and the reasons behind them. Covers the owner's standard for what may appear on screen, wording, how colour is used, where colours and type come from, the shared components, and the structure of Home and the terminal screen. Use before adding or changing any screen, row, button, label or message.
---

# UI design

## The standard

The owner wants Rax to feel as carefully made as a good iPhone app: clear hierarchy, few
surprises, everything where the thumb expects it. That means the thinking behind iOS apps, not
their look. Liquid glass and copied iOS chrome are out.

### Nothing without a reason

Every element on screen needs a reason to be there, at that place, all the time. The owner calls
the rest slop. Common forms:

- Buttons that are always visible but rarely used. Put them in a menu or a sheet.
- Subtitles that explain what a row or setting does. The label must be clear on its own, and if it
  is not, rename it.
- Status text for a normal state ("Connected", "All good"). Show state only when it needs the
  user's attention or answers a question they have.
- Headers, counts, dividers and empty sections that carry no information.
- Two controls for one action.

Before adding anything, ask what the user loses if it is gone. If the answer is nothing, leave it
out.

### Wording

- Plain words in normal sentence order, the way a person says it. Not marketing, not academic.
- Short. A message says what happened and, if the user can do something, what to do.
- Errors name the cause in the user's terms ("Can't reach Rex at this address"), not the
  exception.
- One name for one thing everywhere: host, session, tab, pane.
- All text is in `strings.xml`. Rax is English only.

## Colour

- Colours come from the active Rex theme (`RaxColors`, built from Rex's interface colours), so the
  app matches the user's terminal and Rex's own windows. The user picks one light and one dark
  theme, and the appearance setting (system, light or dark) decides which one is active.
- The accent is for actions only: buttons, links, selection and the cursor. Use `link` for accent
  text and icons. It switches to a readable colour when the theme's accent is too faint.
- Coloured dots mean connectivity and nothing else (`StatusDot`: filled when the computer is
  online, a ring when it is not).
- A program that waits for the user is shown with the `warning` colour on its own row or tab.
- `danger` is only for actions that remove something.

## Type

Google Sans Flex for the interface, through the `RaxType` scale (large title, headline, body,
subhead, footnote and so on). Do not set sizes by hand. The terminal font is separate and chosen by
the user. Outside the terminal it is used only for text the user would type into a terminal, such
as the commands on the welcome screen.

## Components

`ui/components` has the shared pieces: `LargeTitleScreen`, `Group` with `NavRow`, `ToggleRow`,
`ActionRow` and `CheckRow`, `SectionHeader`, `Footnote`, `PrimaryButton`, `SegmentedControl`,
`RaxSlider`, `ProgramGlyph`, `IconAction` and `StatusDot`. Build new screens from them, so
spacing, touch targets and colours stay the same everywhere. Icons are Material Symbols vector
drawables in `res/drawable`. Delete an icon when its last use goes.

Program logos (`ic_program_*`) come from Simple Icons (CC0), and from Lobe Icons (MIT) for AI
tools that Simple Icons lacks, such as Codex. `tools/program-icons.py` converts both. Rex.app has
its own logo set, but those files belong to Superlogical.
`programIcon` in `HomeMapping.kt` picks a logo from the agent's ID or the process names. A program
without a logo gets the terminal icon if it is a shell, else its first letter.

## Home

Hosts, then their sessions, then one row per tab: the program running in it, its directory, and
whether it waits for the user. A host that cannot be reached says why in one line, with Retry,
Sign in or Allow when the user can act. The "Needs you" section appears only when there are at
least two sessions; with one session, the waiting tab's own row is enough.

## The terminal screen

- The top bar has back, the tab's program, title and session (tap it to switch tabs), and a menu
  for pane, tab and session actions.
- Below the terminal there is a pane switcher (only when the tab has more than one pane) and a dock
  with the keys a pane needs most without a keyboard: keyboard, Esc, up, down, Return, and more
  keys.
- With the keyboard open, a key row above it gives Esc, Tab, Ctrl, Alt and arrows. Ctrl and Alt
  apply to the next key on a tap and stay on after a double tap. Arrows repeat while held. Holding
  Tab sends Shift+Tab.
- The terminal area is never blank without a reason on screen. It shows loading, the terminal,
  reconnecting over the last screen, or a message with one action. See `pane-pipeline`.
