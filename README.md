# Clean Mirror

A view-only second window that shows your game **without the overlays RuneLite plugins draw on top of it**, such as Player Indicators names, highlights, tile markers, ground item text and status icons. Play in your normal client with all your plugins, and record the mirror for clean POV footage.

## Using it

1. Enable **Clean Mirror**. The "RuneLite Clean Mirror" window opens.
2. In your recording software (e.g. OBS), add a window capture of the window named `RuneLite Clean Mirror`.
3. Play in the main client as usual.

The mirror never takes keyboard or mouse focus, ignores all input, and sends nothing over the network.

## Settings

**Mirror window**

| Setting | Default | What it does |
|---|---|---|
| Show mirror window | On | Opens or closes the mirror. Closing the window also turns this off. |
| Mirror FPS cap | 60 | Most frames per second the mirror updates. With the GPU plugin on, each mirror frame costs the game some time (see Performance). |
| Scale | 1x | 1x, 1.5x, 2x or fit to window. 1x is cheapest. |
| Smooth scaling | Off | Bilinear instead of pixel-sharp scaling when not at 1x. |
| Show cursor | On | Draws your mouse cursor in the mirror. |
| Screenshot hotkey | Not set | Press it while the game is focused to save a PNG of the mirror (the clean frame) to `.runelite/plugin-data/clean-mirror/screenshots/`. |
| Cursor in screenshots | Off | Include your cursor in mirror screenshots. |
| Screenshot chat message | Off | Confirms each screenshot in game chat. Chat is part of the game, so the message also appears in the mirror. |

**Keep in mirror.** These stay visible in the mirror, but only if they're enabled in your client.

| Setting | Default | What it keeps |
|---|---|---|
| FPS | On | The FPS plugin's counter. |
| Ping | On | World Hopper ping and Ping Graph. |
| Infoboxes | On | All infoboxes, e.g. freeze timers (Timers) and poison/venom (Poison). |
| Also keep plugins | `Current World` | Overlays of the plugins you list, by the name shown in the plugin list, separated by commas. |
| Everything drawn on top | Off | Every overlay drawn on top of everything, e.g. tooltips. |

**Advanced**

| Setting | Default | What it does |
|---|---|---|
| Overlay opacity (GPU) | 90 | GPU plugin only; see below. 100 turns it off. |
| Clean inventory overlays | On | Also removes overlays on the inventory, such as inventory tags. |
| Show performance stats | Off | Shows game FPS, mirror FPS and per-frame cost in the mirror's title bar. |
| Save debug frame | Off | Saves the next frame (original and clean PNGs plus a log) to `.runelite/plugin-data/clean-mirror/debug/`, then turns itself off. |

## How it works

RuneLite draws one image per frame. The game draws its natural view, and plugins paint overlays into that same image in stages. Clean Mirror places an invisible snapshot overlay just before and just after each stage. Together they record exactly which pixels plugins changed, and those pixels are restored once the frame is finished. Anything the game draws over them later, like hitsplats, skulls, health bars and the interface, is kept. Overlays you chose to keep are drawn again on top of the clean frame.

### With the GPU plugin on

The 3D scene is drawn on the graphics card, and RuneLite's 2D layer (interface and overlays) is blended over it. The mirror gets the finished frame through RuneLite's frame capture, then works out the scene behind each overlay pixel from the blend.

So that this is exact, plugin overlays in the 3D view are drawn at **90% opacity** in your client by default. That's hard to notice, and it leaves enough of the scene behind them to rebuild. At 100%, the scene behind solid overlay pixels has to be estimated from the pixels around it. On thin text that's hard to spot, but in big crowds it smears.

## Performance

- The plugin costs nothing while the mirror window is closed.
- **GPU plugin on:** every mirror frame needs RuneLite to read the finished frame back from the graphics card. The game has to wait for the graphics card to finish that frame, which is the main cost. It grows with how busy the scene is. In very large fights, a 60 FPS mirror can roughly halve game FPS. Lower the mirror FPS cap if you need more game FPS.
- **GPU plugin off:** no read-back is needed, and the per-frame cost is small. The game itself is limited to 50 FPS without GPU, though.
- The plugin's own work on the game thread is about 1 ms per mirror frame. Rebuilding and painting run on separate threads.

## Limitations

- Plugins that change the game itself, rather than drawing on top of it, can't be undone. Examples: chat recolours and timestamps, right-click menu recolours and swaps, entity hiding, interface rearranging.
- A faint trace can remain where an overlay sits under a translucent interface, such as the transparent chatbox in resizable mode.
- Overlays attached to interfaces other than the inventory may still appear.
- Very rarely, a pixel is restored wrongly when the game draws the exact same colour over an overlay pixel. This can leave a stray pixel, for example in the right-click menu.
- The cursor is drawn as a standard arrow, not the game's cursor.

## Building and running locally

Requires JDK 11 or newer.

```
./gradlew run       # starts RuneLite (developer mode) with Clean Mirror loaded
./gradlew test      # runs the unit tests
```

The development client runs with Java assertions on, which RuneLite requires for loading plugins in development. That makes it somewhat slower than a normal RuneLite install.
