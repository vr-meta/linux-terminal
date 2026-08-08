# Review of the console panel

Four reviews of the `console` concept layout, taken in November against the
running build: a control-surface designer on **grouping**, a designer of dense
professional interfaces on **visual hierarchy**, a spatial-interaction designer
on **ray ergonomics**, and an interaction designer on **information
architecture**.

Every claim below that is marked *settled* was checked against the code or the
running panel before it was written down. Everything else is marked as a
derivation and says what would settle it.

The screenshots the reviews were taken against are the `console` skin at a shell
prompt and with Claude Code in front.

## Already fixed

**The keypad was inside a scroller with the shortcuts.** The rule everyone
checks — `KeyPad` is built once and context never touches it — was being
honoured, and the keys still moved: with enough shortcuts to overflow the
column, scrolling to reach a chord carried Enter and `^C` off the panel. The
guarantee is about where the keys *are*, not how often they are constructed.
The keypad is now outside the scroller; only the shortcuts scroll.

**The bar offered `cd` while a program owned the terminal.** `classify` returns
an empty kind for anything not in its table — `make`, `pytest`, `docker compose
up`, `journalctl -f` — and `pushContext` left `tool` nil, which sent the bar
down the at-a-prompt branch. Pressing a directory typed `cd 'server'` and a
newline into that program's stdin. The intent was already written in the comment
above `fallbackTool`; the branch was not. Now: foreground process group is not
the shell's, therefore the shell is not reading, therefore no action that
assumes a prompt is offered. Server-side, no APK.

## Defects confirmed by reading the code

These are not opinions. Each was verified.

| | |
|---|---|
| `Buttons.dangerGap()` has **zero call sites** | The double gap beside a destructive control is documented, implemented, and used nowhere. `^D` (closes the shell) sits 8dp from `^C` (pressed constantly); both are red, so colour discriminates nothing. |
| `Rotary` is never instantiated; `Lever` is reachable only from `leverCell()` and `railLever()`, **neither of which is called** | Both are dead code. The rail replaced them with keys and rockers. |
| Every `Led` is constructed lit and `setOn` is never called | A lamp that is always on trains the eye to stop reading it. |
| Chip text is 12.5dp | `docs/readability.md` puts the unreadable line at 16px ≈ 12.5dp. Section headings (9dp), mini notes (10dp) and row marks (11dp) are below it. |
| `BarActivity` declares 1150×460dp | The console layout needs roughly 840dp of height. Window size is the one property Horizon OS lets the app set, and it is set for the old layout. |
| `debounced()` keeps its timestamp **per view**, and `fillConsole` rebuilds every view on `cd` | A trigger bounce after a directory press lands on a freshly built view with a zero debounce, under a ray that has not moved. The guard is defeated by the rebuild it causes. |
| `runActions` adds `git status` / `git diff` only when `Git.Dirty > 0` | Editing a file in another window inserts two chips into a row the user never touched. |
| The server distinguishes `skill` from `skill-global`; the client draws them in one row | The distinction survives only as a colour, the weakest channel through these lenses. |
| `root` is computed, sent, and never drawn | The only context field the client ignores. In a deep subdirectory nothing says which project the path belongs to. |

## Where the reviews agree

**Spacing carries safety, not colour.** Through pancake lenses a mark beats a
tint. Everything that fires on press should carry the Enter mark, not only the
`warn` ones; everything irreversible — `/clear`, `:q!`, `^D` — needs a moat of
20dp and, on the worst of them, a hold to fire.

**Sub-threshold text is texture, not text.** Headings at 9dp are the least
readable thing on a surface whose regions they are supposed to name. Raise
anything meant to be read to 12dp minimum; chips to 14dp.

**The rocker earns its keep; the lever and the knob do not.** A rocker is
pressed, which is the verb ray input actually has, and its two halves cannot
drift apart. A lever's whole visual argument is "throw me" and it is operated by
pointing and pulling a trigger — it teaches a gesture that does not exist. A
knob promises a continuous range for a value that is stepped, and its real
feedback is detents felt in the hand, which a ray cannot feel.

**Mode may move the panel; data may not.** A section can disappear because the
foreground program changed — the user caused that and the header confirms it. A
section must not appear because a repository went dirty. Mode is a fact about
your hands; data is weather.

**State is the panel's weak half.** It says a great deal about where you are and
almost nothing about what is happening. The two readouts it does have echo the
user's own presses rather than reporting the machine.

## Where the reviews disagree — decisions for the owner

**1. Section plates: differentiate or delete.**
*Grouping* wants fixed clusters and dynamic clusters to carry different plate
finishes, so the eye learns which housings are furniture and which are weather.
*Hierarchy* wants the plates deleted wherever the content is already a screen —
the glass is the container, and a box around a box at nearly the same radius
reads as a framing error. Both note the plate is currently 2% brighter than the
panel, which through the lenses is the same colour, so today it is paid for
entirely by its outline.

**2. The phosphor's colour.**
*Hierarchy* argues green now means five things at once — confirm, data, voice,
connection, cursor — so "green = go" cannot survive, and wants the screens moved
to amber. Against it: `capped` already uses amber for exactly this reason, and
moving `console` there costs the CRT reference that the whole reading layer is
built on.

**3. Column order.**
*Ergonomics* observes that precision demand and reach are inversely matched: the
smallest targets (listing rows) sit in the left column, the longest reach for a
right hand, while the rail — high frequency, low precision — is correctly at the
cheap edge. It prefers fixing sizes over reordering. *Architecture* wants
`COMMANDS` moved to the top of the reading column, so that hiding the browser
and the projects does not lift the one section that survives both modes.

## Sizes are settled, and not by arithmetic

**The panel is worn close.** It is pulled in until it sits where a console sits
when you are sitting at one — much nearer than the 1.5 m the readability figures
were measured at. Read at that distance, every size on the panel is comfortable,
including the ones the reviews flagged as sub-threshold.

That single fact retires most of the sizing section below. Angular size is what
readability follows, and angular size is distance divided into height: a panel
brought to half the distance doubles every glyph without a single number
changing in the code. The reviews were right about the arithmetic and were
reasoning from a distance nobody uses.

So: **no size changes, and no dp-per-degree measurement.** The numbers below are
kept because they are correct *if the panel is ever pushed out* — if a future
layout is too wide to keep close, or somebody works with it across the room,
this is the analysis that applies. Until then it is not a defect list.

What survives from it regardless of distance is everything about **spacing and
consequence** rather than size: a moat beside a control that closes the shell is
about the cost of a miss, and that cost does not shrink when the panel comes
closer.

## What the reviews assumed about distance

The reviews converge on one measurement that everything else depends on:
**dp per degree for this panel at its own default distance.**

`docs/readability.md` measured 51.2 px/degree on a 1.5 m cylindrical layer, not
on a native Horizon window. Dividing by the manifest's ~1.25 px/dp gives roughly
**40dp per degree**, and from that: a 46×40dp key is 1.15°×1.00°, a listing row
pitch is 0.68°, chip text is 0.31°.

That conversion also reconciles the old argument. If "1° of ray error" is the
full spread rather than one standard deviation, a 1.0° target is 2σ — correct
for a harmless key. **The wearer was right and the arithmetic was wrong**, and
40dp becomes a validated floor rather than a compromise.

Until the number is measured on this window, every size recommendation here is
scaled from an assumption. Measure it first.

Also for the headset, in order: whether the 1dp key border survives at all (it
is ~0.02°, an order of magnitude under the smear floor — if it does not survive,
the entire role-coding budget is being spent on something invisible); whether
the paging rocker and the scrolling rocker are confusable when they differ only
by one chevron against two; whether any section plate can be made perceptible
while staying dark; and whether phosphor green and Enter green actually confuse.

## Protocol additions the grouping needs

Keep `Group{name, items[]}` and give each item a `kind`. Fewer kinds is better:

- **`key`** — as today.
- **`rocker`** — `{up, down, repeat, axis}`. A stepper is a rocker with a
  different glyph pair, not a separate kind.
- **`segmented`** — `{label, options[]}`, momentary. **No `selected` field, by
  design**: the server cannot read which answer a program is waiting for, and a
  control that shows unreadable state is forbidden.
- **`cluster`** — `{name, items[]}`, a named housing inside a group. This is
  what restores headings to the keystroke side, which the client currently
  discards when it hoists keystrokes out of every group into one flow.
- **`place`** — `{label, send, rank}` where rank is parent / child / jump.
- **`readout`** — `{label, value, lamp}`, so the server owns what the lamps say.
- **`isolate: true`** — an item-level flag asking for `dangerGap()` around this
  control. One boolean beats a spacer widget.

`⇧Tab` must stay a plain key. It cycles a permission mode that lives inside
Claude, where nothing can read it; drawing it as a selector would promise a
state nobody can honour. This is the most tempting wrong move on the panel.

## Order of work

Sizes and the dp-per-degree measurement are **out of scope** — see above; the
panel is worn close and reads comfortably as it is. The visual language is what
is being worked on, so:

1. The two decisions on plates and phosphor, and the visual hierarchy work that
   follows from them.
2. Spacing where a miss is expensive — `dangerGap()` around anything that closes
   a shell or wipes a conversation. Distance does not make a mis-hit cheaper.
3. Delete what is dead: `Rotary`, `Lever`, and the two methods that are the only
   things reaching them.
4. The confirmed behavioural defects: lamps that never change, the debounce
   defeated by the rebuild, chips that appear because a repository went dirty.
5. The protocol additions, once the grouping they serve is settled.
