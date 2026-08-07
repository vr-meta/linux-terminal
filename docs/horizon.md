# Horizon OS: what is settled, and what it rests on

Claims about this platform made from memory have been wrong three times in this
project's history. What follows was researched against Meta's own documentation
and, where marked, measured on the device. Every claim carries its source.

## Window placement for a 2D app: settled, with sources

Asked in earnest, researched against Meta's own documentation, and the answer is
**no** — a 2D panel app cannot influence where its window appears in the room.
Size is the only window property an app controls.

- Meta's complete feature surface for Android apps lists one window entry, "Panel
  sizing": default dimensions via `<layout>`, user resizing, multiple panels.
  <https://developers.meta.com/horizon/documentation/android-apps/features-overview/>
- Placement belongs to the shell: "Window affordances (control bar, edge handles,
  resize handles) are provided automatically by Horizon OS for Android panel apps."
  <https://developers.meta.com/horizon/design/windows_implementation/>
- "follow me", "theater view" and "pin to space" are actions in the system control
  bar, with no app-facing API documented.
  <https://developers.meta.com/horizon/design/windows/>
- A launched 2D window arrives attached to the Navigator, which is in front of the
  user — so it already appears where you wanted it, without the app asking.
  <https://www.meta.com/help/quest/542427545314119/>
- `ActivityOptions.setLaunchBounds` is ignored without
  `FEATURE_FREEFORM_WINDOW_MANAGEMENT` or `FEATURE_PICTURE_IN_PICTURE`. **Measured
  on this Quest 3: neither is reported** (`adb shell pm list features`), so that
  avenue is closed by fact and not by inference. It is a 2D pixel rectangle in any
  case and cannot express distance or facing.

`android:gravity` in `<layout>` is an Android 2D free-form concept and carries no
depth or yaw. Meta documents only `defaultWidth` and `defaultHeight`; whether it
honours `gravity`, `minWidth` or `minHeight` at all is **not documented and not
measured**.

The one placement Meta does document for a second window is
`FLAG_ACTIVITY_LAUNCH_ADJACENT`: "the panel activity will be launched next to the
actively running activity from your app". `documentLaunchMode` and
`FLAG_ACTIVITY_NEW_DOCUMENT` appear nowhere in Meta's documentation — they work,
but where the window lands is unspecified.
<https://developers.meta.com/horizon/documentation/spatial-sdk/hybrid-apps-overview/>

Only three windows attach to the Navigator; beyond that the user detaches them
into the room.

### The Spatial SDK question, previously recorded as unresolved

Meta Spatial SDK **does** place panels precisely — `Entity.createPanelEntity` takes
a pose in metres.
<https://developers.meta.com/horizon/documentation/spatial-sdk/spatial-sdk-2dpanel-spawn/>

The cost is the thing that was unknown. Assembling it from four documented facts:
activities declare `com.oculus.intent.category.2D` or `.VR`; an immersive activity
is an `AppSystemActivity extends VrActivity`; Home is "the landing point... when
exiting an immersive app"; cooperative mode is defined only for panel and immersive
activities **of the same app**; and another app enters an immersive app only via
`OVERLAY_LAUNCHER`.

So: the free window layout you multitask in **is** the Home environment, and
`createPanelEntity`'s placement freedom applies to panels inside your own immersive
scene. Controllable placement and immersive mode come together, and they cost
coexistence with other apps.

**Stated honestly:** no single sentence in Meta's documentation says "an immersive
app takes over the headset". That conclusion is strongly implied by the five quotes
above, not quoted. Treat it as such.

### The metavr CLI does not run on Linux

`npx metavr` refuses with "unsupported platform 'linux-x64' (supported:
darwin-arm64, darwin-x64, win32-x64)". The skills in
[meta-quest/agentic-tools](https://github.com/meta-quest/agentic-tools) are plain
markdown and are readable from a clone; only the CLI and its doc-search are
unavailable here.
