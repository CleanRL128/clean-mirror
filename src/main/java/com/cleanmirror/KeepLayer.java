package com.cleanmirror;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.function.Predicate;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Overlays the user chose to keep in the mirror (FPS, ping, infoboxes such as freeze timers and
 * poison, ...). After the frame is cleaned, these overlays are rendered again into a transparent
 * layer which is then drawn over the clean frame, so they look exactly like in the client.
 *
 * <p>Must be used on the client thread, like any overlay rendering.
 */
final class KeepLayer
{
	private BufferedImage image;
	private int[] pixels;
	boolean drewAnything;

	/** Transparent premultiplied ARGB layer sized to the frame. */
	int[] pixels()
	{
		return pixels;
	}

	/**
	 * Render every overlay matching {@code keep} into this layer.
	 *
	 * @return true if at least one overlay was rendered
	 */
	boolean render(int width, int height, OverlayManager overlayManager, RuneLiteConfig runeLiteConfig,
		Predicate<Overlay> keep)
	{
		if (image == null || image.getWidth() != width || image.getHeight() != height)
		{
			image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
			pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		}
		Arrays.fill(pixels, 0);
		drewAnything = false;

		Graphics2D g = image.createGraphics();
		try
		{
			// anyMatch is the public way to visit overlays; we never return true so it visits all
			overlayManager.anyMatch(overlay ->
			{
				if (!(overlay instanceof SnapshotOverlay) && keep.test(overlay))
				{
					renderOne(g, overlay, runeLiteConfig);
				}
				return false;
			});
		}
		finally
		{
			g.dispose();
		}
		return drewAnything;
	}

	private void renderOne(Graphics2D g, Overlay overlay, RuneLiteConfig runeLiteConfig)
	{
		OverlayPosition position = overlay.getPreferredPosition() != null
			? overlay.getPreferredPosition()
			: overlay.getPosition();
		boolean dynamic = position == OverlayPosition.DYNAMIC || position == OverlayPosition.DETACHED;

		Graphics2D sg = (Graphics2D) g.create();
		try
		{
			// Same font choice as RuneLite's overlay renderer
			if (dynamic)
			{
				sg.setFont(runeLiteConfig.dynamicOverlayFont().getFont());
			}
			else if (position == OverlayPosition.TOOLTIP)
			{
				sg.setFont(runeLiteConfig.tooltipFont().getFont());
			}
			else
			{
				sg.setFont(runeLiteConfig.interfaceFont().getFont());
			}

			// Positioned (and moved) overlays draw relative to where the renderer placed them this frame
			if (!dynamic || overlay.getPreferredLocation() != null)
			{
				Rectangle b = overlay.getBounds();
				if (b == null || b.isEmpty())
				{
					return;
				}
				sg.translate(b.x, b.y);
			}

			overlay.render(sg);
			drewAnything = true;
		}
		catch (RuntimeException e)
		{
			// A kept overlay failing must never break the mirror
		}
		finally
		{
			sg.dispose();
		}
	}

	/** Draw a premultiplied ARGB layer over an RGB frame. */
	static void compositeOver(int[] layer, int[] rgb, int n)
	{
		int from = 0;
		while (from < n)
		{
			// Skip fully transparent runs quickly
			int i = from;
			while (i < n && layer[i] == 0)
			{
				i++;
			}
			if (i >= n)
			{
				break;
			}
			int k = layer[i];
			int a = k >>> 24;
			if (a == 255)
			{
				rgb[i] = k & 0xFFFFFF;
			}
			else
			{
				int inv = 255 - a;
				int d = rgb[i];
				int r = ((k >> 16) & 0xFF) + div255(((d >> 16) & 0xFF) * inv);
				int gg = ((k >> 8) & 0xFF) + div255(((d >> 8) & 0xFF) * inv);
				int b = (k & 0xFF) + div255((d & 0xFF) * inv);
				rgb[i] = (Math.min(r, 255) << 16) | (Math.min(gg, 255) << 8) | Math.min(b, 255);
			}
			from = i + 1;
		}
	}

	private static int div255(int x)
	{
		x += 128;
		return (x + (x >> 8)) >> 8;
	}
}
