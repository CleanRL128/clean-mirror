package com.cleanmirror;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GpuCompositorTest
{
	private static final int W = 64;
	private static final int H = 48;

	/** Smooth gradient "scene", like terrain. */
	private static int[] scene()
	{
		int[] s = new int[W * H];
		for (int y = 0; y < H; y++)
		{
			for (int x = 0; x < W; x++)
			{
				int r = 60 + x;
				int g = 90 + y;
				int b = 40 + (x + y) / 2;
				s[y * W + x] = (r << 16) | (g << 8) | b;
			}
		}
		return s;
	}

	private static int premul(int a, int r, int g, int b)
	{
		return (a << 24) | ((r * a / 255) << 16) | ((g * a / 255) << 8) | (b * a / 255);
	}

	/** What the GPU does: final = ui + scene*(1-a), premultiplied. */
	private static int[] composite(int[] ui, int[] scene)
	{
		int[] f = new int[W * H];
		for (int i = 0; i < f.length; i++)
		{
			int u = ui[i];
			int a = u >>> 24;
			int inv = 255 - a;
			int s = scene[i];
			int r = ((u >> 16) & 0xFF) + (((s >> 16) & 0xFF) * inv + 127) / 255;
			int g = ((u >> 8) & 0xFF) + (((s >> 8) & 0xFF) * inv + 127) / 255;
			int b = (u & 0xFF) + ((s & 0xFF) * inv + 127) / 255;
			f[i] = (Math.min(r, 255) << 16) | (Math.min(g, 255) << 8) | Math.min(b, 255);
		}
		return f;
	}

	private static int maxChannelDiff(int a, int b)
	{
		int d = 0;
		for (int sh = 0; sh <= 16; sh += 8)
		{
			d = Math.max(d, Math.abs(((a >> sh) & 0xFF) - ((b >> sh) & 0xFF)));
		}
		return d;
	}

	@Test
	public void recoversSceneUnderTranslucentAndSolidOverlays()
	{
		int[] scene = scene();

		// Clean 2D layer: transparent viewport, a solid native widget strip on the right,
		// and a translucent native element (like a health bar shadow)
		int[] clean = new int[W * H];
		for (int y = 0; y < H; y++)
		{
			for (int x = 56; x < W; x++)
			{
				clean[y * W + x] = premul(255, 120, 110, 90);
			}
		}
		for (int x = 5; x < 15; x++)
		{
			clean[40 * W + x] = premul(128, 0, 0, 0);
		}

		// Plugin overlays: translucent tile fill + solid 1px "name text" line + solid over widget
		int[] ui = clean.clone();
		for (int y = 10; y < 20; y++)
		{
			for (int x = 10; x < 30; x++)
			{
				ui[y * W + x] = premul(80, 0, 255, 255);
			}
		}
		for (int x = 30; x < 50; x++)
		{
			ui[25 * W + x] = premul(255, 255, 0, 255);
		}
		ui[5 * W + 58] = premul(255, 255, 255, 255);

		int[] fin = composite(ui, scene);
		int[] expected = composite(clean, scene);

		int[] out = new int[W * H];
		GpuCompositor gc = new GpuCompositor();
		gc.compose(fin, W, H, ui, clean, W, H, out);

		assertTrue(gc.lastPremultiplied);
		// Translucent tile fill: solved exactly (rounding only)
		for (int y = 10; y < 20; y++)
		{
			for (int x = 10; x < 30; x++)
			{
				int i = y * W + x;
				assertTrue("tile px " + x + "," + y, maxChannelDiff(out[i], expected[i]) <= 3);
			}
		}
		// Solid text line: filled from neighbours; scene is a smooth gradient so error is tiny
		for (int x = 30; x < 50; x++)
		{
			int i = 25 * W + x;
			assertTrue("text px " + x, maxChannelDiff(out[i], expected[i]) <= 3);
		}
		// Overlay over solid widget: restored exactly
		assertEquals(expected[5 * W + 58], out[5 * W + 58]);
		// Untouched pixels: identical
		assertEquals(expected[0], out[0]);
		assertEquals(expected[40 * W + 7], out[40 * W + 7]);
		assertEquals(0, gc.lastUnfilled);
	}

	@Test
	public void thickSolidBlockStillFills()
	{
		int[] scene = scene();
		int[] clean = new int[W * H];
		int[] ui = clean.clone();
		for (int y = 15; y < 30; y++)
		{
			for (int x = 15; x < 40; x++)
			{
				ui[y * W + x] = premul(255, 250, 10, 10);
			}
		}
		int[] fin = composite(ui, scene);
		int[] out = new int[W * H];
		GpuCompositor gc = new GpuCompositor();
		gc.compose(fin, W, H, ui, clean, W, H, out);
		assertEquals(0, gc.lastUnfilled);
		// No red left anywhere
		for (int i = 0; i < out.length; i++)
		{
			assertTrue(((out[i] >> 16) & 0xFF) < 200);
		}
	}

	@Test
	public void differentFrameSizeUsesMapping()
	{
		// Final frame at 2x (stretched / DPI scaling)
		int[] scene = scene();
		int[] clean = new int[W * H];
		int[] ui = clean.clone();
		int[] fin1 = composite(ui, scene);
		int fw = W * 2, fh = H * 2;
		int[] fin2 = new int[fw * fh];
		for (int y = 0; y < fh; y++)
		{
			for (int x = 0; x < fw; x++)
			{
				fin2[y * fw + x] = fin1[(y / 2) * W + x / 2];
			}
		}
		int[] out = new int[W * H];
		new GpuCompositor().compose(fin2, fw, fh, ui, clean, W, H, out);
		for (int i = 0; i < out.length; i++)
		{
			assertEquals(fin1[i], out[i]);
		}
	}
}
