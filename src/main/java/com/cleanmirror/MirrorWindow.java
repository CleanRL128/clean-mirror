package com.cleanmirror;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.concurrent.locks.ReentrantLock;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * View-only window showing the clean frame.
 *
 * <ul>
 *   <li>Never takes focus: {@code setFocusableWindowState(false)} and {@code setAutoRequestFocus(false)}.</li>
 *   <li>No input listeners of any kind; nothing is forwarded to the game.</li>
 *   <li>Double-buffered: the client thread fills the back image and swaps with {@code tryLock},
 *   so it never waits on painting. Painting happens on the Swing thread.</li>
 * </ul>
 */
final class MirrorWindow
{
	static final String TITLE = "RuneLite Clean Mirror";
	private static final int DEFAULT_W = 765;
	private static final int DEFAULT_H = 503;

	private final ReentrantLock swapLock = new ReentrantLock();
	private final Runnable onUserClose;

	// Guarded by swapLock (swap) / client thread only (back)
	private BufferedImage front;
	private BufferedImage back;
	private int[] backPixels;
	private int backCursorX = -1, backCursorY = -1;
	private int frontCursorX = -1, frontCursorY = -1;

	private volatile boolean showing;
	private volatile MirrorScale scale = MirrorScale.X1;
	private volatile boolean smooth;

	// Swing thread only
	private JFrame frame;
	private MirrorPanel panel;
	private int lastFrameW;
	private int lastFrameH;

	MirrorWindow(Runnable onUserClose)
	{
		this.onUserClose = onUserClose;
		SwingUtilities.invokeLater(this::createFrame);
	}

	private void createFrame()
	{
		panel = new MirrorPanel();
		panel.setBackground(Color.BLACK);
		panel.setPreferredSize(new Dimension(DEFAULT_W, DEFAULT_H));

		frame = new JFrame(TITLE);
		// Focusable so you can click it or bring it forward from the taskbar, but it never grabs
		// focus on its own when it opens or updates.
		frame.setAutoRequestFocus(false);
		frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent e)
			{
				setVisible(false);
				onUserClose.run();
			}
		});
		frame.setContentPane(panel);
		frame.pack();
		frame.setLocationByPlatform(true);
		if (showing)
		{
			frame.setVisible(true);
		}
	}

	/** True while the window is meant to be on screen. Safe to call from any thread. */
	boolean isShowing()
	{
		return showing;
	}

	void setVisible(boolean visible)
	{
		showing = visible;
		SwingUtilities.invokeLater(() ->
		{
			if (frame != null && frame.isVisible() != visible)
			{
				frame.setVisible(visible);
			}
		});
	}

	void setScale(MirrorScale scale, boolean smooth)
	{
		this.scale = scale;
		this.smooth = smooth;
		SwingUtilities.invokeLater(this::resizeToFrame);
	}

	/**
	 * Optional performance readout appended to the window title (only when "Show performance stats"
	 * is on; recording software that matches windows by exact title may need re-pointing).
	 */
	void setStatus(String status)
	{
		String title = status == null ? TITLE : TITLE + " | " + status;
		SwingUtilities.invokeLater(() ->
		{
			if (frame != null && !title.equals(frame.getTitle()))
			{
				frame.setTitle(title);
			}
		});
	}

	/** Producer thread (client thread in CPU mode, worker in GPU mode): back-buffer pixels sized to the frame. */
	synchronized int[] backBuffer(int width, int height)
	{
		if (back == null || back.getWidth() != width || back.getHeight() != height)
		{
			back = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			backPixels = ((DataBufferInt) back.getRaster().getDataBuffer()).getData();
		}
		return backPixels;
	}

	/**
	 * Copy of the frame currently shown in the mirror (without window scaling), or null if nothing
	 * has been shown yet. Safe to call from any thread.
	 */
	BufferedImage snapshot(boolean withCursor)
	{
		BufferedImage copy;
		int cx;
		int cy;
		swapLock.lock();
		try
		{
			if (front == null)
			{
				return null;
			}
			copy = new BufferedImage(front.getWidth(), front.getHeight(), BufferedImage.TYPE_INT_RGB);
			int[] src = ((DataBufferInt) front.getRaster().getDataBuffer()).getData();
			int[] dst = ((DataBufferInt) copy.getRaster().getDataBuffer()).getData();
			System.arraycopy(src, 0, dst, 0, Math.min(src.length, dst.length));
			cx = frontCursorX;
			cy = frontCursorY;
		}
		finally
		{
			swapLock.unlock();
		}
		if (withCursor && cx >= 0 && cy >= 0 && cx < copy.getWidth() && cy < copy.getHeight())
		{
			Graphics2D g = copy.createGraphics();
			drawCursor(g, cx, cy, 1.0);
			g.dispose();
		}
		return copy;
	}

	/** Producer thread: cursor position (in frame pixels) for the frame in the back buffer; -1 hides it. */
	synchronized void setBackCursor(int x, int y)
	{
		backCursorX = x;
		backCursorY = y;
	}

	/** Producer thread: make the back buffer visible. Skips the frame rather than waiting on a paint. */
	synchronized void publish()
	{
		if (!swapLock.tryLock())
		{
			return;
		}
		boolean sizeChanged;
		try
		{
			sizeChanged = front == null || front.getWidth() != back.getWidth() || front.getHeight() != back.getHeight();
			BufferedImage t = front;
			front = back;
			back = t;
			frontCursorX = backCursorX;
			frontCursorY = backCursorY;
			backPixels = back == null ? null : ((DataBufferInt) back.getRaster().getDataBuffer()).getData();
		}
		finally
		{
			swapLock.unlock();
		}

		if (sizeChanged)
		{
			SwingUtilities.invokeLater(this::resizeToFrame);
		}
		if (panel != null)
		{
			// Coalesced by Swing's RepaintManager
			panel.repaint();
		}
	}

	private void resizeToFrame()
	{
		if (frame == null)
		{
			return;
		}
		int w;
		int h;
		swapLock.lock();
		try
		{
			w = front != null ? front.getWidth() : DEFAULT_W;
			h = front != null ? front.getHeight() : DEFAULT_H;
		}
		finally
		{
			swapLock.unlock();
		}

		MirrorScale s = scale;
		if (s.factor > 0)
		{
			Dimension d = new Dimension((int) Math.round(w * s.factor), (int) Math.round(h * s.factor));
			if (!d.equals(panel.getPreferredSize()) || w != lastFrameW || h != lastFrameH)
			{
				panel.setPreferredSize(d);
				frame.pack();
			}
		}
		lastFrameW = w;
		lastFrameH = h;
		panel.repaint();
	}

	void dispose()
	{
		showing = false;
		SwingUtilities.invokeLater(() ->
		{
			if (frame != null)
			{
				frame.dispose();
				frame = null;
			}
		});
	}

	/** Classic arrow cursor; tip at (x, y). */
	private static final int[] CURSOR_X = {0, 0, 4, 7, 9, 6, 11};
	private static final int[] CURSOR_Y = {0, 16, 12, 18, 17, 11, 11};

	private static void drawCursor(Graphics2D g, int x, int y, double scale)
	{
		double s = Math.max(1.0, scale);
		int n = CURSOR_X.length;
		int[] xs = new int[n];
		int[] ys = new int[n];
		for (int i = 0; i < n; i++)
		{
			xs[i] = x + (int) Math.round(CURSOR_X[i] * s);
			ys[i] = y + (int) Math.round(CURSOR_Y[i] * s);
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(Color.WHITE);
		g.fillPolygon(xs, ys, n);
		g.setColor(Color.BLACK);
		g.drawPolygon(xs, ys, n);
	}

	private final class MirrorPanel extends JPanel
	{
		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			Graphics2D g2 = (Graphics2D) g;

			swapLock.lock();
			try
			{
				if (front == null)
				{
					return;
				}
				int iw = front.getWidth();
				int ih = front.getHeight();
				int pw = getWidth();
				int ph = getHeight();

				double f = scale.factor > 0 ? scale.factor : Math.min((double) pw / iw, (double) ph / ih);
				int dw = (int) Math.round(iw * f);
				int dh = (int) Math.round(ih * f);
				int dx = (pw - dw) / 2;
				int dy = (ph - dh) / 2;

				if (dw == iw && dh == ih)
				{
					g2.drawImage(front, dx, dy, null);
				}
				else
				{
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smooth
						? RenderingHints.VALUE_INTERPOLATION_BILINEAR
						: RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
					g2.drawImage(front, dx, dy, dw, dh, null);
				}
				if (frontCursorX >= 0 && frontCursorY >= 0 && frontCursorX < iw && frontCursorY < ih)
				{
					drawCursor(g2, dx + (int) Math.round(frontCursorX * f), dy + (int) Math.round(frontCursorY * f), f);
				}
			}
			finally
			{
				swapLock.unlock();
			}
		}
	}
}
