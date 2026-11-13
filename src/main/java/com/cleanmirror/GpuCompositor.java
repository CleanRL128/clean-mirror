package com.cleanmirror;

import java.util.Arrays;

/**
 * Rebuilds a clean frame in GPU mode.
 *
 * <p>With the GPU plugin on, the 3D scene is drawn on the graphics card and RuneLite's 2D layer
 * (overheads, interface and plugin overlays) is blended on top of it with premultiplied alpha:
 * <pre>  final = ui.rgb + scene * (1 - ui.a)</pre>
 * We have three inputs:
 * <ul>
 *   <li>{@code finalFrame}: the composited frame the GPU produced (with overlays)</li>
 *   <li>{@code ui}: the 2D layer as uploaded (with overlays)</li>
 *   <li>{@code cleanUi}: the same 2D layer with plugin overlays removed (from {@link FrameCleaner})</li>
 * </ul>
 * For every pixel where {@code ui != cleanUi}:
 * <ul>
 *   <li>if the overlay pixel is see-through, the scene behind it is solved exactly from the blend
 *   equation, then re-blended with the clean 2D layer;</li>
 *   <li>if the overlay pixel is solid (e.g. name text), the scene behind it was never drawn to the
 *   screen, so it is filled in from the surrounding scene pixels.</li>
 * </ul>
 * Pixels plugins didn't touch are copied straight from the final frame.
 *
 * <p>Pure Java, no RuneLite types. Buffers are reused; nothing is allocated per frame once sizes are stable.
 */
final class GpuCompositor
{
	/** Overlay alpha at or above this is treated as solid: the scene behind it can't be solved. */
	static final int SOLID_ALPHA = 250;
	private static final int MAX_FILL_PASSES = 24;
	/** RECIP[d] = round(65536 / d), for fast division by (255 - alpha). */
	private static final int[] RECIP = new int[256];

	static
	{
		for (int d = 1; d < 256; d++)
		{
			RECIP[d] = (int) Math.round(65536.0 / d);
		}
	}

	private static final int[] DX = {-1, 0, 1, -1, 1, -1, 0, 1};
	private static final int[] DY = {-1, -1, -1, 0, 0, 1, 1, 1};

	// Mapping from 2D-layer coordinates to final-frame coordinates (identity when sizes match)
	private int mapW = -1, mapH = -1, mapFw = -1, mapFh = -1;
	private int[] xMap = new int[0];
	private int[] yRowMap = new int[0];
	private boolean identity;

	// Fill state, reused across frames; stamp[i] == gen marks fill[i] as valid this frame
	private int[] fill = new int[0];
	private int[] stamp = new int[0];
	private int gen;
	private int[] pending = new int[1024];

	// Last-frame stats
	int lastChanged;
	int lastSolved;
	int lastFilled;
	int lastUnfilled;
	boolean lastPremultiplied = true;

	/**
	 * @param finalFrame RGB pixels of the composited GPU frame, {@code fw * fh}
	 * @param ui         2D layer with overlays, ARGB, at least {@code w * h}
	 * @param cleanUi    2D layer without overlays, ARGB, at least {@code w * h}
	 * @param out        destination RGB, at least {@code w * h}
	 */
	void compose(int[] finalFrame, int fw, int fh, int[] ui, int[] cleanUi, int w, int h, int[] out)
	{
		ensureMapping(w, h, fw, fh);
		int n = w * h;
		if (fill.length < n)
		{
			fill = new int[n];
			stamp = new int[n];
		}
		if (++gen == 0)
		{
			Arrays.fill(stamp, 0);
			gen = 1;
		}

		// 1. Start from the final frame
		if (identity)
		{
			System.arraycopy(finalFrame, 0, out, 0, n);
		}
		else
		{
			for (int y = 0, i = 0; y < h; y++)
			{
				int row = yRowMap[y];
				for (int x = 0; x < w; x++, i++)
				{
					out[i] = finalFrame[row + xMap[x]];
				}
			}
		}

		// 2. Work out whether the 2D layer is premultiplied (it should be; fall back if not)
		boolean premul = detectPremultiplied(ui, cleanUi, n);
		lastPremultiplied = premul;

		// 3. Every pixel plugins changed: solve or queue for filling
		int changed = 0, solved = 0, queued = 0;
		int from = 0;
		while (from < n)
		{
			int m = Arrays.mismatch(ui, from, n, cleanUi, from, n);
			if (m < 0)
			{
				break;
			}
			int i = from + m;
			from = i + 1;
			changed++;

			int c = cleanUi[i];
			int ca = c >>> 24;
			if (ca == 255)
			{
				// Clean 2D layer is solid here: no scene needed
				out[i] = c & 0xFFFFFF;
				continue;
			}

			int u = ui[i];
			int ua = u >>> 24;
			if (ua < SOLID_ALPHA)
			{
				int s = unblend(out[i], u, ua, premul);
				fill[i] = s;
				stamp[i] = gen;
				out[i] = blend(c, ca, s, premul);
				solved++;
			}
			else
			{
				if (queued == pending.length)
				{
					pending = Arrays.copyOf(pending, queued * 2);
				}
				pending[queued++] = i;
			}
		}

		// 4. Fill solid-overlay pixels from surrounding scene, onion-peel style
		int remaining = queued;
		int filled = 0;
		for (int pass = 0; pass < MAX_FILL_PASSES && remaining > 0; pass++)
		{
			int doneThisPass = 0;
			for (int k = 0; k < remaining; )
			{
				int i = pending[k];
				int x = i % w;
				int y = i / w;
				int r = 0, g = 0, b = 0, cnt = 0;
				for (int d = 0; d < 8; d++)
				{
					int nx = x + DX[d];
					int ny = y + DY[d];
					if (nx < 0 || ny < 0 || nx >= w || ny >= h)
					{
						continue;
					}
					int q = ny * w + nx;
					int s = sceneAt(q, out, ui, cleanUi, premul);
					if (s >= 0)
					{
						r += (s >> 16) & 0xFF;
						g += (s >> 8) & 0xFF;
						b += s & 0xFF;
						cnt++;
					}
				}
				if (cnt > 0)
				{
					int s = ((r / cnt) << 16) | ((g / cnt) << 8) | (b / cnt);
					fill[i] = s;
					stamp[i] = gen;
					int c = cleanUi[i];
					out[i] = blend(c, c >>> 24, s, premul);
					// swap-remove
					pending[k] = pending[--remaining];
					doneThisPass++;
				}
				else
				{
					k++;
				}
			}
			filled += doneThisPass;
			if (doneThisPass == 0)
			{
				break;
			}
		}

		lastChanged = changed;
		lastSolved = solved;
		lastFilled = filled;
		lastUnfilled = remaining;
	}

	/**
	 * Scene colour at pixel q if it can be known, else -1.
	 * Uses this frame's solved/filled value, or solves it from an untouched pixel.
	 */
	private int sceneAt(int q, int[] out, int[] ui, int[] cleanUi, boolean premul)
	{
		if (stamp[q] == gen)
		{
			return fill[q];
		}
		int u = ui[q];
		if (u != cleanUi[q])
		{
			// Changed by a plugin and not solved/filled yet
			return -1;
		}
		int ua = u >>> 24;
		int s;
		if (ua == 0)
		{
			s = out[q] & 0xFFFFFF;
		}
		else if (ua < SOLID_ALPHA)
		{
			s = unblend(out[q], u, ua, premul);
		}
		else
		{
			// Solid native 2D element (widget, hitsplat): not scene
			return -1;
		}
		fill[q] = s;
		stamp[q] = gen;
		return s;
	}

	/** Solve scene colour from final = ui + scene*(1-a). */
	static int unblend(int f, int u, int ua, boolean premul)
	{
		int inv = 255 - ua;
		if (inv <= 0)
		{
			return f & 0xFFFFFF;
		}
		int r = solve((f >> 16) & 0xFF, (u >> 16) & 0xFF, ua, inv, premul);
		int g = solve((f >> 8) & 0xFF, (u >> 8) & 0xFF, ua, inv, premul);
		int b = solve(f & 0xFF, u & 0xFF, ua, inv, premul);
		return (r << 16) | (g << 8) | b;
	}

	private static int solve(int f, int uc, int ua, int inv, boolean premul)
	{
		int contrib = premul ? uc * 255 : uc * ua; // ui contribution scaled by 255
		int num = f * 255 - contrib;
		if (num <= 0)
		{
			return 0;
		}
		int v = (int) ((num * (long) RECIP[inv] + (1 << 15)) >>> 16);
		return v > 255 ? 255 : v;
	}

	/** Composite the clean 2D layer pixel over a scene colour. */
	static int blend(int c, int ca, int s, boolean premul)
	{
		if (ca == 0)
		{
			return s;
		}
		int inv = 255 - ca;
		int r = mix((c >> 16) & 0xFF, ca, (s >> 16) & 0xFF, inv, premul);
		int g = mix((c >> 8) & 0xFF, ca, (s >> 8) & 0xFF, inv, premul);
		int b = mix(c & 0xFF, ca, s & 0xFF, inv, premul);
		return (r << 16) | (g << 8) | b;
	}

	private static int mix(int cc, int ca, int sc, int inv, boolean premul)
	{
		int v = premul
			? cc + div255(sc * inv)
			: div255(cc * ca + sc * inv);
		return v > 255 ? 255 : v;
	}

	/** Rounded x / 255 for 0 <= x <= 65025, without a division. */
	private static int div255(int x)
	{
		x += 128;
		return (x + (x >> 8)) >> 8;
	}

	/**
	 * Premultiplied pixels never have a colour channel above alpha. Check the changed pixels;
	 * if many break that rule the layer is straight alpha.
	 */
	private static boolean detectPremultiplied(int[] ui, int[] cleanUi, int n)
	{
		int checked = 0, violations = 0;
		int from = 0;
		while (from < n && checked < 2000)
		{
			int m = Arrays.mismatch(ui, from, n, cleanUi, from, n);
			if (m < 0)
			{
				break;
			}
			int i = from + m;
			from = i + 1;
			int u = ui[i];
			int a = u >>> 24;
			if (a == 255 || a == 0)
			{
				continue;
			}
			checked++;
			if (((u >> 16) & 0xFF) > a || ((u >> 8) & 0xFF) > a || (u & 0xFF) > a)
			{
				violations++;
			}
		}
		return checked == 0 || violations * 20 < checked; // allow ~5% rounding noise
	}

	private void ensureMapping(int w, int h, int fw, int fh)
	{
		if (w == mapW && h == mapH && fw == mapFw && fh == mapFh)
		{
			return;
		}
		mapW = w;
		mapH = h;
		mapFw = fw;
		mapFh = fh;
		identity = w == fw && h == fh;
		xMap = new int[w];
		yRowMap = new int[h];
		for (int x = 0; x < w; x++)
		{
			xMap[x] = Math.min(fw - 1, (int) ((x + 0.5) * fw / w));
		}
		for (int y = 0; y < h; y++)
		{
			yRowMap[y] = Math.min(fh - 1, (int) ((y + 0.5) * fh / h)) * fw;
		}
	}
}
