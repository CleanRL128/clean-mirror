package com.cleanmirror;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;

@ConfigGroup(CleanMirrorConfig.GROUP)
public interface CleanMirrorConfig extends Config
{
	String GROUP = "cleanmirror";
	String KEY_SHOW_WINDOW = "showWindow";
	String KEY_KEEP_PLUGINS = "keepPlugins";
	String KEY_SAVE_DEBUG_FRAME = "saveDebugFrame";

	@ConfigSection(
		name = "Mirror window",
		description = "The view-only window you record",
		position = 0
	)
	String windowSection = "window";

	@ConfigSection(
		name = "Keep in mirror",
		description = "Overlays that stay visible in the mirror (only if they're enabled in your client)",
		position = 1
	)
	String keepSection = "keep";

	@ConfigSection(
		name = "Advanced",
		description = "Quality, performance and troubleshooting",
		position = 2,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	// ---------------------------------------------------------------- Mirror window

	@ConfigItem(
		position = 0,
		keyName = KEY_SHOW_WINDOW,
		name = "Show mirror window",
		description = "Open the view-only mirror window. It shows the game without plugin overlays and ignores all input.",
		section = windowSection
	)
	default boolean showWindow()
	{
		return true;
	}

	@Range(min = 10, max = 144)
	@ConfigItem(
		position = 1,
		keyName = "mirrorFps",
		name = "Mirror FPS cap",
		description = "Most frames per second the mirror updates. 60 matches most recordings. "
			+ "With the GPU plugin on, each mirror frame costs the game some time, so a lower cap means higher game FPS.",
		section = windowSection
	)
	default int mirrorFps()
	{
		return 60;
	}

	@ConfigItem(
		position = 2,
		keyName = "scale",
		name = "Scale",
		description = "Size of the mirror relative to the game frame. 1x is the cheapest; let your recording software upscale if needed.",
		section = windowSection
	)
	default MirrorScale scale()
	{
		return MirrorScale.X1;
	}

	@ConfigItem(
		position = 3,
		keyName = "smoothScaling",
		name = "Smooth scaling",
		description = "Use bilinear instead of pixel-sharp scaling when the mirror is not at 1x",
		section = windowSection
	)
	default boolean smoothScaling()
	{
		return false;
	}

	@ConfigItem(
		position = 4,
		keyName = "showCursor",
		name = "Show cursor",
		description = "Draw your mouse cursor in the mirror",
		section = windowSection
	)
	default boolean showCursor()
	{
		return true;
	}

	@ConfigItem(
		position = 5,
		keyName = "screenshotHotkey",
		name = "Screenshot hotkey",
		description = "Press this while the game is focused to save a screenshot of the mirror "
			+ "(the clean frame) to .runelite/plugin-data/clean-mirror/screenshots",
		section = windowSection
	)
	default Keybind screenshotHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		position = 6,
		keyName = "screenshotCursor",
		name = "Cursor in screenshots",
		description = "Include your cursor in mirror screenshots",
		section = windowSection
	)
	default boolean screenshotCursor()
	{
		return false;
	}

	@ConfigItem(
		position = 7,
		keyName = "screenshotMessage",
		name = "Screenshot chat message",
		description = "Show a game chat message when a mirror screenshot is saved. "
			+ "Chat is part of the game, so the message also appears in the mirror and in recordings.",
		section = windowSection
	)
	default boolean screenshotMessage()
	{
		return false;
	}

	// ---------------------------------------------------------------- Keep in mirror

	@ConfigItem(
		position = 0,
		keyName = "keepFps",
		name = "FPS",
		description = "Keep the FPS plugin's counter",
		section = keepSection
	)
	default boolean keepFps()
	{
		return true;
	}

	@ConfigItem(
		position = 1,
		keyName = "keepPing",
		name = "Ping",
		description = "Keep ping displays (World Hopper ping, Ping Graph)",
		section = keepSection
	)
	default boolean keepPing()
	{
		return true;
	}

	@ConfigItem(
		position = 2,
		keyName = "keepInfoboxes",
		name = "Infoboxes",
		description = "Keep infoboxes, e.g. freeze timers (Timers plugin) and poison/venom (Poison plugin)",
		section = keepSection
	)
	default boolean keepInfoboxes()
	{
		return true;
	}

	@ConfigItem(
		position = 3,
		keyName = KEY_KEEP_PLUGINS,
		name = "Also keep plugins",
		description = "Comma-separated plugin names (as shown in the plugin list) whose overlays stay in the mirror, "
			+ "e.g. Current World. Active overlays are listed in the RuneLite log when this setting changes.",
		section = keepSection
	)
	default String keepPlugins()
	{
		return "Current World";
	}

	@ConfigItem(
		position = 4,
		keyName = "keepTopLayer",
		name = "Everything drawn on top",
		description = "Keep all overlays drawn on top of everything, e.g. tooltips",
		section = keepSection
	)
	default boolean keepTopLayer()
	{
		return false;
	}

	// ---------------------------------------------------------------- Advanced

	@Range(min = 70, max = 100)
	@ConfigItem(
		position = 0,
		keyName = "overlayOpacity",
		name = "Overlay opacity (GPU)",
		description = "GPU plugin only. Plugin overlays in the 3D view are drawn at this opacity in your client so "
			+ "the scene behind them can be rebuilt exactly in the mirror. 90 is hard to notice. 100 turns this off "
			+ "(the mirror then estimates the scene behind solid overlays, which smears in big crowds).",
		section = advancedSection
	)
	default int overlayOpacity()
	{
		return 90;
	}

	@ConfigItem(
		position = 1,
		keyName = "cleanInventory",
		name = "Clean inventory overlays",
		description = "Also remove overlays drawn on the inventory, such as inventory tags",
		section = advancedSection
	)
	default boolean cleanInventory()
	{
		return true;
	}

	@ConfigItem(
		position = 2,
		keyName = "showStats",
		name = "Show performance stats",
		description = "Show game FPS, mirror FPS and per-frame cost in the mirror's title bar. "
			+ "Turn off while recording if your recording software finds the window by its exact title.",
		section = advancedSection
	)
	default boolean showStats()
	{
		return false;
	}

	@ConfigItem(
		position = 3,
		keyName = KEY_SAVE_DEBUG_FRAME,
		name = "Save debug frame",
		description = "Saves the next frame (original and clean) plus a snapshot log to .runelite/plugin-data/clean-mirror/debug, "
			+ "then switches itself off",
		section = advancedSection
	)
	default boolean saveDebugFrame()
	{
		return false;
	}
}
