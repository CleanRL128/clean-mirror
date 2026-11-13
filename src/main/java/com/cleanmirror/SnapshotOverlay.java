package com.cleanmirror;

import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Invisible overlay that takes a snapshot of the frame just before (PRE) or just after (POST)
 * the plugin overlays of one stage draw. It never draws anything.
 *
 * <p>Ordering inside a stage is by position, then priority:
 * <ul>
 *   <li>PRE uses DYNAMIC, which sorts before every other non-deprecated position. Dynamic
 *   overlays draw in ascending priority, so the lowest priority draws first.</li>
 *   <li>POST uses TOOLTIP, the last position. Non-dynamic overlays draw in descending
 *   priority, so the lowest priority draws last.</li>
 * </ul>
 */
final class SnapshotOverlay extends Overlay
{
	enum Phase
	{
		PRE,
		POST
	}

	/** Lower than any priority a normal plugin uses (RuneLite's own constants are 0..1). */
	private static final float EXTREME_LOW_PRIORITY = -1_000_000f;

	private final CleanMirrorPlugin plugin;
	final Phase phase;
	final int tag;
	final String stageName;
	/** True if this stage can only draw inside the 3D viewport, so only that area is compared. */
	final boolean viewportClip;

	SnapshotOverlay(CleanMirrorPlugin plugin, String stageName, OverlayLayer layer, Phase phase, int tag,
		boolean viewportClip, int hookInterfaceId)
	{
		super(plugin);
		this.plugin = plugin;
		this.phase = phase;
		this.tag = tag;
		this.stageName = stageName;
		this.viewportClip = viewportClip;

		setPosition(phase == Phase.PRE ? OverlayPosition.DYNAMIC : OverlayPosition.TOOLTIP);
		setPriority(EXTREME_LOW_PRIORITY);
		setLayer(layer);
		setMovable(false);
		if (hookInterfaceId >= 0)
		{
			drawAfterInterface(hookInterfaceId);
		}
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		plugin.onSnapshot(this);
		return null;
	}
}
