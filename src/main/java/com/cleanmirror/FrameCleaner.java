package com.cleanmirror;

import java.util.Arrays;

/**
 * Tracks which pixels plugin overlays change during a frame and rebuilds a frame without them.
 *
 * <p>Usage per frame, all on the client thread:
 * <ol>
 *   <li>{@link #begin} just before a group of plugin overlays draws (snapshots the clip area)</li>
 *   <li>{@link #end} just after it (records every pixel that group changed, with before/after values)</li>
 *   <li>repeat for every group, in the order they run</li>
 *   <li>{@link #build} once the frame is complete, then {@link #reset}</li>
 * </ol>
 *
 * <p>Rebuilding walks the recorded segments from last to first. A changed pixel is restored to its
 * "before" value only if it still holds the "after" value, i.e. nothing (game or later overlay)
 * drew over it afterwards. Everything the game draws later, such as hitsplats and widgets, is kept.
 *
 * <p>No RuneLite types are used here so the algorithm can be unit-tested on its own. No allocations
 * happen per frame once the buffers have grown to their working size.
 */
final class FrameCleaner
{
	private static final int INITIAL_SEGMENT_CAPACITY = 4096;
	private static final int MAX_SEGMENTS = 32;

	/** Copy of the clip area taken in {@link #begin}. Same layout as the frame buffer. */
	private int[] scratch = new int[0];
	private int width;
	private int height;

	// Clip of the currently open segment
	private boolean open;
	private int clipX, clipY, clipW, clipH;
	private int openTag;

	// Recorded segments for this frame, in chronological order
	private final Segment[] segments = new Segment[MAX_SEGMENTS];
	private int segmentCount;

	FrameCleaner()
	{
		for (int i = 0; i < MAX_SEGMENTS; i++)
		{
			segments[i] = new Segment();
		}
	}

	/**
	 * Snapshot the clip area before a group of overlays draws.
	 *
	 * @param buf frame pixels (row-major, {@code width * height})
	 * @param tag caller-defined label stored with the segment (used to optionally skip it in {@link #build})
	 */
	void begin(int[] buf, int width, int height, int x, int y, int w, int h, int tag)
	{
		if (width != this.width || height != this.height || scratch.length != buf.length)
		{
			// Frame size changed (fixed/resizable switch, window resize): drop this frame's data
			this.width = width;
			this.height = height;
			if (scratch.length != buf.length)
			{
				scratch = new int[buf.length];
			}
			segmentCount = 0;
		}

		// Clamp the clip to the frame
		int x0 = Math.max(0, x);
		int y0 = Math.max(0, y);
		int x1 = Math.min(width, x + w);
		int y1 = Math.min(height, y + h);
		if (x1 <= x0 || y1 <= y0)
		{
			open = false;
			return;
		}

		clipX = x0;
		clipY = y0;
		clipW = x1 - x0;
		clipH = y1 - y0;
		openTag = tag;
		open = true;

		if (clipX == 0 && clipW == width)
		{
			// Full-width clip: one contiguous copy
			int start = clipY * width;
			System.arraycopy(buf, start, scratch, start, clipH * width);
		}
		else
		{
			for (int row = clipY, end = clipY + clipH; row < end; row++)
			{
				int off = row * width + clipX;
				System.arraycopy(buf, off, scratch, off, clipW);
			}
		}
	}

	/**
	 * Record every pixel in the clip area that changed since the matching {@link #begin}.
	 *
	 * @return number of changed pixels recorded, or -1 if there was no open segment
	 */
	int end(int[] buf)
	{
		return end(buf, 256);
	}

	/**
	 * Like {@link #end(int[])}, but also limits how opaque the changed pixels are: any changed pixel
	 * more opaque than {@code capK/256} is blended back toward its "before" value (premultiplied
	 * lerp), written back into {@code buf}, and recorded with that new value. Used in GPU mode so the
	 * scene behind every overlay pixel survives into the GPU frame and can be solved exactly.
	 *
	 * @param capK 0..256, where 256 means no cap
	 */
	int end(int[] buf, int capK)
	{
		return end(buf, capK, true);
	}

	/**
	 * @param record false to only apply the opacity cap without remembering the changes
	 *               (non-mirror frames in exact mode, where nothing gets rebuilt)
	 */
	int end(int[] buf, int capK, boolean record)
	{
		// Alpha above this gets capped; -1 means no cap
		final int capAlphaThreshold = capK >= 256 ? -1 : (capK * 255) >> 8;
		if (!open)
		{
			return -1;
		}
		open = false;

		if (buf.length != scratch.length || segmentCount >= MAX_SEGMENTS)
		{
			return -1;
		}

		Segment seg = segments[segmentCount];
		seg.count = 0;
		seg.tag = openTag;

		for (int row = clipY, end = clipY + clipH; row < end; row++)
		{
			int from = row * width + clipX;
			int to = from + clipW;
			while (from < to)
			{
				// Vectorised skip over identical runs
				int m = Arrays.mismatch(scratch, from, to, buf, from, to);
				if (m < 0)
				{
					break;
				}
				int i = from + m;
				// Overlay text comes in runs of changed pixels: walk the run directly
				do
				{
					int before = scratch[i];
					int after = buf[i];
					if (capAlphaThreshold < 0 || (after >>> 24) <= capAlphaThreshold)
					{
						if (record)
						{
							seg.add(i, before, after);
						}
					}
					else
					{
						after = capMix(before, after, capK);
						buf[i] = after;
						if (record)
						{
							seg.add(i, before, after);
						}
					}
					i++;
				}
				while (i < to && scratch[i] != buf[i]);
				from = i;
			}
		}

		if (!record)
		{
			return 0;
		}
		if (seg.count > 0)
		{
			segmentCount++;
		}
		return seg.count;
	}

	/**
	 * Write the clean frame into {@code out}.
	 *
	 * @param finalBuf  the completed frame
	 * @param out       destination, same length as {@code finalBuf}
	 * @param skipTag   segments with this tag are left in (pass -1 to clean everything)
	 */
	void build(int[] finalBuf, int[] out, int skipTag)
	{
		// RuneLite's frame buffer can be longer than width*height (it has one spare element),
		// so copy only what fits in the output image.
		System.arraycopy(finalBuf, 0, out, 0, Math.min(finalBuf.length, out.length));
		if (finalBuf.length != scratch.length)
		{
			return;
		}

		for (int s = segmentCount - 1; s >= 0; s--)
		{
			Segment seg = segments[s];
			if (seg.tag == skipTag)
			{
				continue;
			}
			int[] idx = seg.index;
			int[] before = seg.before;
			int[] after = seg.after;
			for (int k = seg.count - 1; k >= 0; k--)
			{
				int i = idx[k];
				if (i < out.length && out[i] == after[k])
				{
					out[i] = before[k];
				}
			}
		}
	}

	/** Per-channel (incl. alpha) {@code p + (u - p) * k / 256}. */
	static int capMix(int p, int u, int k)
	{
		int r = 0;
		for (int sh = 0; sh <= 24; sh += 8)
		{
			int pc = (p >>> sh) & 0xFF;
			int uc = (u >>> sh) & 0xFF;
			int v = pc + (((uc - pc) * k) >> 8);
			r |= (v & 0xFF) << sh;
		}
		return r;
	}

	/** Clear this frame's recorded segments. Call once per frame after {@link #build}. */
	void reset()
	{
		segmentCount = 0;
		open = false;
	}

	int segmentCount()
	{
		return segmentCount;
	}

	int changedPixelCount()
	{
		int total = 0;
		for (int s = 0; s < segmentCount; s++)
		{
			total += segments[s].count;
		}
		return total;
	}

	private static final class Segment
	{
		int[] index = new int[INITIAL_SEGMENT_CAPACITY];
		int[] before = new int[INITIAL_SEGMENT_CAPACITY];
		int[] after = new int[INITIAL_SEGMENT_CAPACITY];
		int count;
		int tag;

		void add(int i, int b, int a)
		{
			if (count == index.length)
			{
				int cap = index.length * 2;
				index = Arrays.copyOf(index, cap);
				before = Arrays.copyOf(before, cap);
				after = Arrays.copyOf(after, cap);
			}
			index[count] = i;
			before[count] = b;
			after[count] = a;
			count++;
		}
	}
}
