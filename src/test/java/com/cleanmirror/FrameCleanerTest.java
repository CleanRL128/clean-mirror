package com.cleanmirror;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import org.junit.Test;

public class FrameCleanerTest
{
	private static final int W = 20;
	private static final int H = 10;
	private static final int SCENE = 0x112233;
	private static final int NAME = 0xFF0000;     // plugin overlay (e.g. Player Indicators name)
	private static final int HITSPLAT = 0x00AA00; // native game, drawn after ABOVE_SCENE overlays
	private static final int WIDGET = 0x777777;   // native interface
	private static final int ICON = 0x0000FF;     // plugin overlay in a later stage

	private static int[] frame(int fill)
	{
		int[] f = new int[W * H];
		Arrays.fill(f, fill);
		return f;
	}

	private static void rect(int[] f, int x, int y, int w, int h, int c)
	{
		for (int yy = y; yy < y + h; yy++)
		{
			for (int xx = x; xx < x + w; xx++)
			{
				f[yy * W + xx] = c;
			}
		}
	}

	/** Simulates: scene, ABOVE_SCENE overlays, overheads, widgets, ALWAYS_ON_TOP overlays. */
	@Test
	public void removesOverlaysKeepsNativeDrawing()
	{
		FrameCleaner fc = new FrameCleaner();
		int[] buf = frame(SCENE);
		int[] expected = frame(SCENE);

		// Stage 1: ABOVE_SCENE plugin overlays (viewport clip 0,0,15,8)
		fc.begin(buf, W, H, 0, 0, 15, 8, 1);
		rect(buf, 2, 2, 6, 1, NAME);
		fc.end(buf);

		// Native overheads drawn partly over the name
		rect(buf, 4, 1, 2, 3, HITSPLAT);
		rect(expected, 4, 1, 2, 3, HITSPLAT);

		// Native widgets on the right
		rect(buf, 15, 0, 5, 10, WIDGET);
		rect(expected, 15, 0, 5, 10, WIDGET);

		// Stage 2: ALWAYS_ON_TOP overlay over both scene and a widget, overlapping the name
		fc.begin(buf, W, H, 0, 0, W, H, 2);
		rect(buf, 6, 2, 12, 2, ICON);
		fc.end(buf);

		int[] out = new int[W * H];
		fc.build(buf, out, -1);
		assertArrayEquals(expected, out);
	}

	@Test
	public void skipTagKeepsThatStage()
	{
		FrameCleaner fc = new FrameCleaner();
		int[] buf = frame(SCENE);

		fc.begin(buf, W, H, 0, 0, W, H, 1);
		rect(buf, 0, 0, 3, 3, NAME);
		fc.end(buf);

		fc.begin(buf, W, H, 0, 0, W, H, 2);
		rect(buf, 10, 5, 3, 3, ICON);
		fc.end(buf);

		int[] expected = frame(SCENE);
		rect(expected, 10, 5, 3, 3, ICON);

		int[] out = new int[W * H];
		fc.build(buf, out, 2);
		assertArrayEquals(expected, out);
	}

	@Test
	public void clipLimitsTracking()
	{
		FrameCleaner fc = new FrameCleaner();
		int[] buf = frame(SCENE);

		// Overlay draws inside the clip; the game changes something outside it in the same window
		fc.begin(buf, W, H, 0, 0, 10, 10, 1);
		rect(buf, 1, 1, 2, 2, NAME);
		rect(buf, 12, 1, 2, 2, WIDGET);
		fc.end(buf);

		int[] expected = frame(SCENE);
		rect(expected, 12, 1, 2, 2, WIDGET);

		int[] out = new int[W * H];
		fc.build(buf, out, -1);
		assertArrayEquals(expected, out);
		assertEquals(4, fc.changedPixelCount());
	}

	/** RuneLite's buffer is width*height+1 long while the output image is exactly width*height. */
	@Test
	public void bufferLongerThanOutput()
	{
		FrameCleaner fc = new FrameCleaner();
		int[] buf = new int[W * H + 1];
		Arrays.fill(buf, SCENE);
		fc.begin(buf, W, H, 0, 0, W, H, 1);
		rect(buf, 2, 2, 4, 1, NAME);
		fc.end(buf);

		int[] out = new int[W * H];
		fc.build(buf, out, -1);
		int[] expected = new int[W * H];
		Arrays.fill(expected, SCENE);
		assertArrayEquals(expected, out);
	}

	@Test
	public void opacityCapMakesSolidOverlaySeeThrough()
	{
		FrameCleaner fc = new FrameCleaner();
		int[] buf = new int[W * H]; // transparent 2D layer (GPU mode)
		fc.begin(buf, W, H, 0, 0, W, H, 1);
		buf[5] = 0xFFFF0000; // solid red overlay pixel
		buf[6] = 0x40400000; // already see-through: left alone
		fc.end(buf, 230);    // ~90%
		assertEquals(229, buf[5] >>> 24);
		assertEquals(0x40400000, buf[6]);
		int[] out = new int[W * H];
		fc.build(buf, out, -1);
		assertEquals(0, out[5]); // still removed in the clean layer
	}

	@Test
	public void sizeChangeDropsFrame()
	{
		FrameCleaner fc = new FrameCleaner();
		int[] buf = frame(SCENE);
		fc.begin(buf, W, H, 0, 0, W, H, 1);
		rect(buf, 0, 0, 2, 2, NAME);
		fc.end(buf);
		assertEquals(1, fc.segmentCount());

		int[] bigger = new int[(W + 5) * H];
		fc.begin(bigger, W + 5, H, 0, 0, W + 5, H, 1);
		assertEquals(0, fc.segmentCount());
	}

	@Test
	public void unchangedStageRecordsNothing()
	{
		FrameCleaner fc = new FrameCleaner();
		int[] buf = frame(SCENE);
		fc.begin(buf, W, H, 0, 0, W, H, 1);
		fc.end(buf);
		assertEquals(0, fc.segmentCount());
	}

	@Test
	public void growsPastInitialCapacity()
	{
		int w = 200;
		int h = 100;
		FrameCleaner fc = new FrameCleaner();
		int[] buf = new int[w * h];
		fc.begin(buf, w, h, 0, 0, w, h, 1);
		Arrays.fill(buf, NAME); // 20,000 changed pixels > initial 4,096
		fc.end(buf);
		int[] out = new int[w * h];
		fc.build(buf, out, -1);
		assertArrayEquals(new int[w * h], out);
	}
}
