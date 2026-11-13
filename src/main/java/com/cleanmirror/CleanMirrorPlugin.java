package com.cleanmirror;

import com.google.inject.Provides;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import net.runelite.api.BufferProvider;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.fps.FpsOverlay;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxOverlay;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.HotkeyListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Clean Mirror",
	internalName = "clean-mirror",
	description = "View-only second window showing the game without plugin overlays, for clean recordings",
	tags = {"record", "recording", "stream", "obs", "video", "clean", "mirror", "content"}
)
public class CleanMirrorPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(CleanMirrorPlugin.class);

	// Stage tags, in the order the stages draw
	static final int TAG_ABOVE_SCENE = 0;
	static final int TAG_UNDER_WIDGETS = 1;
	static final int TAG_INVENTORY = 2;
	static final int TAG_ABOVE_WIDGETS = 3;
	static final int TAG_ALWAYS_ON_TOP = 4;

	/** OSRS inventory interface group id (InterfaceID.INVENTORY). */
	private static final int INVENTORY_INTERFACE_ID = 149;
	private static final long STATS_INTERVAL_NANOS = 2_000_000_000L;
	/** Give up waiting for a GPU frame after this long (e.g. GPU plugin switched off mid-frame). */
	private static final long GPU_REQUEST_TIMEOUT_NANOS = 1_000_000_000L;

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private DrawManager drawManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private CleanMirrorConfig config;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private RuneLiteConfig runeLiteConfig;

	@Inject
	private KeyManager keyManager;

	@Inject
	private ClientThread clientThread;

	private final HotkeyListener screenshotHotkey = new HotkeyListener(() -> config.screenshotHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			takeMirrorScreenshot();
		}
	};

	/** CPU-mode cleaner. GPU mode uses one cleaner per job so the worker can build off the game thread. */
	private final FrameCleaner cleaner = new FrameCleaner();
	/** Used on non-mirror frames in GPU mode, only to cap overlay opacity. */
	private final FrameCleaner capCleaner = new FrameCleaner();
	/** Cleaner taking snapshots this frame (one of the above, or the current GPU job's). */
	private FrameCleaner active;
	private final GpuCompositor compositor = new GpuCompositor();
	private final KeepLayer cpuKeep = new KeepLayer();
	private final List<SnapshotOverlay> stageOverlays = new ArrayList<>();
	private final List<SnapshotOverlay> inventoryOverlays = new ArrayList<>();
	private final Runnable frameListener = this::onFrameComplete;
	private final Consumer<Image> gpuFrameListener = this::onGpuFrame;

	private MirrorWindow window;
	private ExecutorService worker;

	// Settings (written on the config thread, read on the client thread)
	private volatile boolean keepTopLayer;
	private volatile long frameIntervalNanos = 1_000_000_000L / 60;
	private volatile boolean showStats;
	private volatile int capK = 256;
	private volatile boolean showCursor = true;
	private volatile boolean keepFps;
	private volatile boolean keepPing;
	private volatile boolean keepInfoboxes;
	private volatile Set<String> keepPlugins = new HashSet<>();

	// Client thread state for the current frame
	private boolean mirrorFrame;
	/** GPU mode: cap overlay opacity in the 3D view this frame (every frame, not just mirror frames). */
	private boolean capFrame;
	private boolean frameGpu;
	private boolean framePublished;
	private long lastBeforeRenderNanos;
	private long frameCredit;
	private boolean gpuRequestPending;
	private long gpuRequestNanos;
	private long frameCompleteNanos;
	private long frameClientNanos;

	// GPU jobs handed to the worker thread. Two slots: the game fills one while the worker
	// finishes the other, so a frame that starts right after the previous one isn't skipped.
	private final GpuJob[] jobs = {new GpuJob(), new GpuJob()};
	private GpuJob currentJob;

	// Debug capture: requested -> armed at end of a frame -> recorded during the next mirror frame
	private volatile boolean debugRequested;
	private boolean debugArmed;
	private StringBuilder debugLog;

	// Performance stats (only shown when "Show performance stats" is on)
	private long statsWindowStart;
	private int statsPublished;
	private long statsClientNanos;
	private long statsCaptureNanos;
	private int statsCaptures;
	private volatile long statsWorkerNanos;
	private volatile int statsWorkerFrames;

	@Provides
	CleanMirrorConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CleanMirrorConfig.class);
	}

	@Override
	protected void startUp()
	{
		window = new MirrorWindow(this::onWindowClosedByUser);
		worker = Executors.newSingleThreadExecutor(r ->
		{
			Thread t = new Thread(r, "clean-mirror");
			t.setDaemon(true);
			return t;
		});

		addStage("ABOVE_SCENE", OverlayLayer.ABOVE_SCENE, TAG_ABOVE_SCENE, true, -1, stageOverlays);
		addStage("UNDER_WIDGETS", OverlayLayer.UNDER_WIDGETS, TAG_UNDER_WIDGETS, true, -1, stageOverlays);
		addStage("ABOVE_WIDGETS", OverlayLayer.ABOVE_WIDGETS, TAG_ABOVE_WIDGETS, false, -1, stageOverlays);
		addStage("ALWAYS_ON_TOP", OverlayLayer.ALWAYS_ON_TOP, TAG_ALWAYS_ON_TOP, false, -1, stageOverlays);
		stageOverlays.forEach(overlayManager::add);

		applyConfig();
		logActiveOverlays();
		drawManager.registerEveryFrameListener(frameListener);
		keyManager.registerKeyListener(screenshotHotkey);
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(screenshotHotkey);
		drawManager.unregisterEveryFrameListener(frameListener);
		stageOverlays.forEach(overlayManager::remove);
		stageOverlays.clear();
		inventoryOverlays.forEach(overlayManager::remove);
		inventoryOverlays.clear();

		if (worker != null)
		{
			worker.shutdownNow();
			worker = null;
		}
		if (window != null)
		{
			window.dispose();
			window = null;
		}
		cleaner.reset();
		capCleaner.reset();
		active = null;
		mirrorFrame = false;
		capFrame = false;
		framePublished = false;
		gpuRequestPending = false;
		debugArmed = false;
		debugLog = null;
		for (GpuJob j : jobs)
		{
			j.inUse.set(false);
			j.image = null;
		}
		currentJob = null;
	}

	private void addStage(String name, OverlayLayer layer, int tag, boolean viewportClip, int hookInterfaceId,
		List<SnapshotOverlay> into)
	{
		into.add(new SnapshotOverlay(this, name, layer, SnapshotOverlay.Phase.PRE, tag, viewportClip, hookInterfaceId));
		into.add(new SnapshotOverlay(this, name, layer, SnapshotOverlay.Phase.POST, tag, viewportClip, hookInterfaceId));
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CleanMirrorConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		applyConfig();
		if (CleanMirrorConfig.KEY_KEEP_PLUGINS.equals(event.getKey()))
		{
			logActiveOverlays();
		}
	}

	private void applyConfig()
	{
		if (window == null)
		{
			return;
		}
		window.setScale(config.scale(), config.smoothScaling());
		window.setVisible(config.showWindow());
		frameIntervalNanos = 1_000_000_000L / Math.max(1, config.mirrorFps());
		showCursor = config.showCursor();

		int opacity = config.overlayOpacity();
		capK = opacity >= 100 ? 256 : Math.max(1, opacity * 256 / 100);

		keepTopLayer = config.keepTopLayer();
		keepFps = config.keepFps();
		keepPing = config.keepPing();
		keepInfoboxes = config.keepInfoboxes();
		Set<String> plugins = new HashSet<>();
		for (String n : config.keepPlugins().split(","))
		{
			if (!n.trim().isEmpty())
			{
				plugins.add(normalize(n));
			}
		}
		keepPlugins = plugins;

		boolean wantInventory = config.cleanInventory();
		if (wantInventory && inventoryOverlays.isEmpty())
		{
			addStage("INVENTORY", OverlayLayer.MANUAL, TAG_INVENTORY, false, INVENTORY_INTERFACE_ID, inventoryOverlays);
			inventoryOverlays.forEach(overlayManager::add);
		}
		else if (!wantInventory && !inventoryOverlays.isEmpty())
		{
			inventoryOverlays.forEach(overlayManager::remove);
			inventoryOverlays.clear();
		}

		showStats = config.showStats();
		if (!showStats)
		{
			window.setStatus(null);
		}
		if (config.saveDebugFrame())
		{
			debugRequested = true;
		}
	}

	private void onWindowClosedByUser()
	{
		configManager.setConfiguration(CleanMirrorConfig.GROUP, CleanMirrorConfig.KEY_SHOW_WINDOW, false);
	}

	/**
	 * Start of every frame: decide whether this frame feeds the mirror. Frames that don't
	 * (window closed, above the mirror FPS cap, both GPU slots busy) cost nothing beyond the opacity cap.
	 */
	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		updateStats();

		mirrorFrame = false;
		capFrame = false;
		long now = System.nanoTime();
		long interval = frameIntervalNanos;
		// Frame budget: carry leftover time forward so uneven frame pacing doesn't halve the mirror rate
		if (lastBeforeRenderNanos != 0)
		{
			frameCredit = Math.min(frameCredit + (now - lastBeforeRenderNanos), 2 * interval);
		}
		lastBeforeRenderNanos = now;

		MirrorWindow w = window;
		if (w == null || !w.isShowing())
		{
			return;
		}
		boolean gpu = client.isGpu();
		// The opacity cap runs on every frame so overlay opacity in the client stays steady (no flicker)
		capFrame = capK < 256 && gpu;
		if (frameCredit < interval)
		{
			return;
		}
		if (gpu)
		{
			if (gpuRequestPending && now - gpuRequestNanos > GPU_REQUEST_TIMEOUT_NANOS)
			{
				gpuRequestPending = false;
			}
			GpuJob free = gpuRequestPending ? null : freeJob();
			if (free == null)
			{
				// Previous GPU frame hasn't arrived, or both slots are busy: skip rather than queue
				return;
			}
			currentJob = free;
			free.hasClean = false;
			free.cleaner.reset();
			active = free.cleaner;
			gpuRequestPending = true;
			gpuRequestNanos = now;
			drawManager.requestNextFrameListener(gpuFrameListener);
		}
		else
		{
			active = cleaner;
		}
		frameCredit -= interval;
		mirrorFrame = true;
		frameGpu = gpu;
		framePublished = false;
	}

	/** Called by {@link SnapshotOverlay#render} on the client thread. */
	void onSnapshot(SnapshotOverlay overlay)
	{
		boolean capStage = capFrame && overlay.viewportClip;
		if (!mirrorFrame && !capStage)
		{
			return;
		}
		FrameCleaner fc = mirrorFrame ? active : capCleaner;
		if (fc == null)
		{
			return;
		}
		// With "Keep top-layer overlays" on, the top layer isn't cleaned, so don't snapshot it.
		// Its POST still marks the end of the 2D layer.
		boolean skipStage = overlay.tag == TAG_ALWAYS_ON_TOP && keepTopLayer;
		long t0 = System.nanoTime();
		BufferProvider bp = client.getBufferProvider();
		if (bp == null)
		{
			return;
		}
		int[] pixels = bp.getPixels();
		int w = bp.getWidth();
		int h = bp.getHeight();

		if (overlay.phase == SnapshotOverlay.Phase.PRE)
		{
			if (skipStage)
			{
				return;
			}
			int x = 0;
			int y = 0;
			int cw = w;
			int ch = h;
			if (overlay.viewportClip)
			{
				x = client.getViewportXOffset();
				y = client.getViewportYOffset();
				cw = client.getViewportWidth();
				ch = client.getViewportHeight();
			}
			else if (overlay.tag == TAG_INVENTORY)
			{
				// Only compare the inventory area, not the whole frame
				Widget inv = client.getWidget(INVENTORY_INTERFACE_ID, 0);
				if (inv == null || inv.isHidden())
				{
					return;
				}
				Rectangle b = inv.getBounds();
				x = b.x;
				y = b.y;
				cw = b.width;
				ch = b.height;
			}
			fc.begin(pixels, w, h, x, y, cw, ch, overlay.tag);
			if (debugLog != null)
			{
				debugLog.append(String.format("PRE  %-13s clip=%d,%d %dx%d%n", overlay.stageName, x, y, cw, ch));
			}
		}
		else
		{
			int changed = skipStage ? 0 : fc.end(pixels, capStage ? capK : 256, mirrorFrame);
			if (debugLog != null)
			{
				debugLog.append(String.format("POST %-13s changed=%d%n", overlay.stageName, changed));
			}
			if (overlay.tag == TAG_ALWAYS_ON_TOP && mirrorFrame)
			{
				if (frameGpu)
				{
					prepareGpuJob(pixels, w, h);
				}
				else
				{
					// Build here rather than at frame end so RuneLite's notification flash is excluded
					buildAndPublishCpu(pixels, w, h);
				}
			}
		}
		frameClientNanos += System.nanoTime() - t0;
	}

	/** Every-frame listener, called after the frame is drawn (client thread). */
	private void onFrameComplete()
	{
		MirrorWindow w = window;
		if (w == null)
		{
			return;
		}

		if (mirrorFrame && !frameGpu && !framePublished)
		{
			// No ALWAYS_ON_TOP pass this frame (e.g. login screen): publish the frame as-is
			BufferProvider bp = client.getBufferProvider();
			if (bp != null)
			{
				buildAndPublishCpu(bp.getPixels(), bp.getWidth(), bp.getHeight());
			}
		}
		if (mirrorFrame)
		{
			statsClientNanos += frameClientNanos;
		}
		frameClientNanos = 0;

		cleaner.reset();
		capCleaner.reset();
		active = null;
		mirrorFrame = false;
		frameCompleteNanos = System.nanoTime();

		if (debugRequested && !debugArmed && w.isShowing())
		{
			debugRequested = false;
			debugArmed = true;
			debugLog = new StringBuilder();
		}
	}

	// ---------------------------------------------------------------- CPU (GPU plugin off)

	private void buildAndPublishCpu(int[] pixels, int w, int h)
	{
		MirrorWindow win = window;
		if (framePublished || win == null || anyJobInUse())
		{
			return;
		}
		framePublished = true;

		int[] out = win.backBuffer(w, h);
		cleaner.build(pixels, out, keepTopLayer ? TAG_ALWAYS_ON_TOP : -1);
		if (anyKeep() && cpuKeep.render(w, h, overlayManager, runeLiteConfig, this::shouldKeep))
		{
			KeepLayer.compositeOver(cpuKeep.pixels(), out, w * h);
		}
		Point mouse = cursorPosition();
		win.setBackCursor(mouse == null ? -1 : mouse.getX(), mouse == null ? -1 : mouse.getY());

		if (debugArmed && debugLog != null)
		{
			debugArmed = false;
			String text = debugLog + String.format("mode=CPU size=%dx%d segments=%d changedPixels=%d%n",
				w, h, cleaner.segmentCount(), cleaner.changedPixelCount());
			debugLog = null;
			saveDebugFrame(copyRgb(pixels, w, h), w, h, out.clone(), w, h, null, null, text);
		}

		win.publish();
		statsPublished++;
	}

	// ---------------------------------------------------------------- GPU (GPU plugin on)

	/** At the end of the 2D layer: copy it (with overlays) for the worker, and render kept overlays. */
	private void prepareGpuJob(int[] pixels, int w, int h)
	{
		GpuJob job = currentJob;
		if (job == null || job.inUse.get())
		{
			return;
		}
		int len = pixels.length;
		if (job.ui.length != len)
		{
			job.ui = new int[len];
			job.clean = new int[len];
		}
		// Only a copy on the game thread; the clean layer is built on the worker
		System.arraycopy(pixels, 0, job.ui, 0, len);
		job.skipTag = keepTopLayer ? TAG_ALWAYS_ON_TOP : -1;
		// Kept overlays must be rendered on the client thread; the worker draws them over the result
		job.hasKeep = anyKeep() && job.keep.render(w, h, overlayManager, runeLiteConfig, this::shouldKeep);
		Point mouse = cursorPosition();
		job.cursorX = mouse == null ? -1 : mouse.getX();
		job.cursorY = mouse == null ? -1 : mouse.getY();
		job.w = w;
		job.h = h;
		job.hasClean = true;
	}

	/** Next-frame listener: the GPU's composited frame for this mirror frame (client thread). */
	private void onGpuFrame(Image image)
	{
		gpuRequestPending = false;
		// RuneLite's GPU frame capture runs between the every-frame listener and this callback
		statsCaptureNanos += System.nanoTime() - frameCompleteNanos;
		statsCaptures++;

		ExecutorService exec = worker;
		GpuJob job = currentJob;
		currentJob = null;
		if (exec == null || image == null || job == null || !job.inUse.compareAndSet(false, true))
		{
			return;
		}
		job.image = image;
		if (debugArmed && debugLog != null)
		{
			debugArmed = false;
			job.debugText = debugLog.toString();
			debugLog = null;
		}
		else
		{
			job.debugText = null;
		}
		try
		{
			exec.execute(() -> runGpuJob(job));
		}
		catch (RuntimeException e)
		{
			job.inUse.set(false);
		}
	}

	/** Worker thread: rebuild the clean frame from the GPU frame and the 2D layer. */
	private void runGpuJob(GpuJob job)
	{
		long t0 = System.nanoTime();
		try
		{
			MirrorWindow win = window;
			if (win == null)
			{
				return;
			}
			BufferedImage frameImg = toIntRgb(job.image);
			int fw = frameImg.getWidth();
			int fh = frameImg.getHeight();
			int[] frame = ((DataBufferInt) frameImg.getRaster().getDataBuffer()).getData();

			if (job.hasClean)
			{
				int w = job.w;
				int h = job.h;
				job.cleaner.build(job.ui, job.clean, job.skipTag);
				int[] out = win.backBuffer(w, h);
				compositor.compose(frame, fw, fh, job.ui, job.clean, w, h, out);
				if (job.hasKeep)
				{
					KeepLayer.compositeOver(job.keep.pixels(), out, w * h);
				}
				win.setBackCursor(job.cursorX, job.cursorY);
				if (job.debugText != null)
				{
					String text = job.debugText + String.format(
						"mode=GPU ui=%dx%d frame=%dx%d premultiplied=%s changed=%d solved=%d filled=%d unfilled=%d%n",
						w, h, fw, fh, compositor.lastPremultiplied, compositor.lastChanged,
						compositor.lastSolved, compositor.lastFilled, compositor.lastUnfilled);
					saveDebugFrame(frame.clone(), fw, fh, out.clone(), w, h,
						argbCopy(job.ui, w, h), argbCopy(job.clean, w, h), text);
				}
			}
			else
			{
				// No overlays drawn this frame (e.g. login screen): show the frame as-is
				int[] out = win.backBuffer(fw, fh);
				System.arraycopy(frame, 0, out, 0, fw * fh);
				win.setBackCursor(-1, -1);
			}
			win.publish();
			statsWorkerNanos += System.nanoTime() - t0;
			statsWorkerFrames++;
		}
		catch (RuntimeException e)
		{
			log.warn("Clean Mirror could not build a GPU frame", e);
		}
		finally
		{
			job.image = null;
			job.inUse.set(false);
		}
	}

	private static BufferedImage toIntRgb(Image image)
	{
		if (image instanceof BufferedImage)
		{
			BufferedImage bi = (BufferedImage) image;
			if ((bi.getType() == BufferedImage.TYPE_INT_RGB || bi.getType() == BufferedImage.TYPE_INT_ARGB)
				&& bi.getRaster().getDataBuffer() instanceof DataBufferInt
				&& ((DataBufferInt) bi.getRaster().getDataBuffer()).getData().length == bi.getWidth() * bi.getHeight())
			{
				return bi;
			}
		}
		int w = image.getWidth(null);
		int h = image.getHeight(null);
		BufferedImage converted = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = converted.createGraphics();
		g.drawImage(image, 0, 0, null);
		g.dispose();
		return converted;
	}

	private GpuJob freeJob()
	{
		for (GpuJob j : jobs)
		{
			if (!j.inUse.get())
			{
				return j;
			}
		}
		return null;
	}

	private boolean anyJobInUse()
	{
		for (GpuJob j : jobs)
		{
			if (j.inUse.get())
			{
				return true;
			}
		}
		return false;
	}

	private static final class GpuJob
	{
		final AtomicBoolean inUse = new AtomicBoolean();
		final FrameCleaner cleaner = new FrameCleaner();
		final KeepLayer keep = new KeepLayer();
		int[] ui = new int[0];
		int[] clean = new int[0];
		int w;
		int h;
		int skipTag = -1;
		boolean hasKeep;
		int cursorX = -1;
		int cursorY = -1;
		volatile boolean hasClean;
		volatile Image image;
		volatile String debugText;
	}

	// ---------------------------------------------------------------- kept overlays and cursor

	private boolean anyKeep()
	{
		return keepFps || keepPing || keepInfoboxes || !keepPlugins.isEmpty();
	}

	private boolean shouldKeep(Overlay overlay)
	{
		if (keepFps && overlay instanceof FpsOverlay)
		{
			return true;
		}
		if (keepInfoboxes && overlay instanceof InfoBoxOverlay)
		{
			return true;
		}
		// Fully-qualified class name, e.g. com.example.currentworld.CurrentWorldOverlay. Plugins keep their
		// overlays in their own package, so this identifies the plugin even when the overlay doesn't record it.
		String cls = overlay.getClass().getName().toLowerCase();
		String simple = overlay.getClass().getSimpleName();
		if (keepPing && (simple.equals("WorldHopperPingOverlay") || cls.contains("pinggraph")
			|| simple.startsWith("Ping") || simple.contains("PingOverlay")))
		{
			return true;
		}
		Set<String> plugins = keepPlugins;
		if (plugins.isEmpty())
		{
			return false;
		}
		Plugin owner = overlay.getPlugin();
		String ownerName = owner == null || owner.getName() == null ? null : normalize(owner.getName());
		for (String key : plugins)
		{
			if (cls.contains(key) || key.equals(ownerName))
			{
				return true;
			}
		}
		return false;
	}

	/** "Current World" -> "currentworld", to compare with plugin and package names. */
	private static String normalize(String s)
	{
		return s.trim().toLowerCase().replace(" ", "").replace("-", "").replace("_", "");
	}

	/** Mouse position on the game canvas, or null if the cursor is hidden or off the canvas. */
	private Point cursorPosition()
	{
		if (!showCursor)
		{
			return null;
		}
		Point mouse = client.getMouseCanvasPosition();
		return mouse == null || mouse.getX() < 0 || mouse.getY() < 0 ? null : mouse;
	}

	/** Write the active overlays to the log, to help users fill in "Also keep plugins". */
	private void logActiveOverlays()
	{
		StringBuilder sb = new StringBuilder();
		overlayManager.anyMatch(o ->
		{
			if (!(o instanceof SnapshotOverlay))
			{
				if (sb.length() > 0)
				{
					sb.append(", ");
				}
				Plugin owner = o.getPlugin();
				sb.append(o.getClass().getName()).append(owner == null ? "" : " [" + owner.getName() + "]");
			}
			return false;
		});
		log.info("Clean Mirror: active overlays: {}", sb);
	}

	// ---------------------------------------------------------------- stats

	private void updateStats()
	{
		long now = System.nanoTime();
		if (statsWindowStart == 0)
		{
			statsWindowStart = now;
			return;
		}
		long elapsed = now - statsWindowStart;
		if (elapsed < STATS_INTERVAL_NANOS)
		{
			return;
		}

		if (showStats && window != null)
		{
			int workerFrames = statsWorkerFrames;
			int published = statsPublished + workerFrames;
			double mirrorFps = published * 1e9 / elapsed;
			double clientMs = published == 0 ? 0 : statsClientNanos / 1e6 / published;
			double captureMs = statsCaptures == 0 ? 0 : statsCaptureNanos / 1e6 / statsCaptures;
			double workerMs = workerFrames == 0 ? 0 : statsWorkerNanos / 1e6 / workerFrames;
			String status = String.format(
				"%s | game %d FPS | mirror %.0f FPS | game thread %.2f ms | GPU capture %.2f ms | rebuild %.2f ms",
				client.isGpu() ? "GPU" : "CPU", client.getFPS(), mirrorFps, clientMs, captureMs, workerMs);
			window.setStatus(status);
			log.debug("Clean Mirror: {}", status);
		}

		statsWindowStart = now;
		statsPublished = 0;
		statsClientNanos = 0;
		statsCaptureNanos = 0;
		statsCaptures = 0;
		statsWorkerNanos = 0;
		statsWorkerFrames = 0;
	}

	// ---------------------------------------------------------------- screenshots

	/** Save the frame currently shown in the mirror as a PNG. Called from the hotkey (Swing thread). */
	private void takeMirrorScreenshot()
	{
		MirrorWindow win = window;
		BufferedImage image = win == null || !win.isShowing() ? null : win.snapshot(config.screenshotCursor());
		if (image == null)
		{
			screenshotMessage("Clean Mirror: open the mirror window to take a screenshot.");
			return;
		}
		executor.execute(() ->
		{
			String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
			try
			{
				Filepath dir = getPluginDirectory().join("screenshots");
				dir.createDirectories();
				Filepath file = dir.joinSegment("Clean Mirror " + stamp + ".png");
				for (int n = 2; file.exists(); n++)
				{
					file = dir.joinSegment("Clean Mirror " + stamp + " (" + n + ").png");
				}
				writePng(image, file);
				log.debug("Clean Mirror screenshot saved to {}", file);
				screenshotMessage("Clean Mirror screenshot saved: " + file.getFileName());
			}
			catch (IOException | IllegalArgumentException e)
			{
				log.warn("Could not save Clean Mirror screenshot", e);
				screenshotMessage("Clean Mirror: could not save the screenshot.");
			}
		});
	}

	private static void writePng(BufferedImage image, Filepath file) throws IOException
	{
		try (OutputStream out = file.openOutputStream())
		{
			if (!ImageIO.write(image, "png", out))
			{
				throw new IOException("No PNG writer available");
			}
		}
	}

	private void screenshotMessage(String text)
	{
		if (!config.screenshotMessage())
		{
			return;
		}
		clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", text, null));
	}

	// ---------------------------------------------------------------- debug output

	private void saveDebugFrame(int[] original, int ow, int oh, int[] clean, int cw, int ch, int[] uiArgb,
		int[] cleanUiArgb, String snapshotLog)
	{
		configManager.setConfiguration(CleanMirrorConfig.GROUP, CleanMirrorConfig.KEY_SAVE_DEBUG_FRAME, false);
		executor.execute(() ->
		{
			String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
			try
			{
				Filepath dir = getPluginDirectory().join("debug");
				dir.createDirectories();
				writePng(toImage(original, ow, oh, BufferedImage.TYPE_INT_RGB), dir.joinSegment(stamp + "-original.png"));
				writePng(toImage(clean, cw, ch, BufferedImage.TYPE_INT_RGB), dir.joinSegment(stamp + "-clean.png"));
				if (uiArgb != null)
				{
					writePng(toImage(uiArgb, cw, ch, BufferedImage.TYPE_INT_ARGB), dir.joinSegment(stamp + "-ui-layer.png"));
					writePng(toImage(cleanUiArgb, cw, ch, BufferedImage.TYPE_INT_ARGB), dir.joinSegment(stamp + "-ui-layer-clean.png"));
				}
				dir.joinSegment(stamp + "-log.txt").write(snapshotLog);
				log.info("Clean Mirror debug frame saved to {}", dir);
			}
			catch (IOException | IllegalArgumentException e)
			{
				log.warn("Could not save Clean Mirror debug frame", e);
			}
		});
	}

	private static int[] copyRgb(int[] pixels, int w, int h)
	{
		int[] c = new int[w * h];
		System.arraycopy(pixels, 0, c, 0, Math.min(c.length, pixels.length));
		return c;
	}

	/** Copy of a (premultiplied) 2D layer as straight ARGB, so the PNG looks right. */
	private static int[] argbCopy(int[] src, int w, int h)
	{
		int[] c = new int[w * h];
		for (int i = 0; i < c.length; i++)
		{
			int p = src[i];
			int a = p >>> 24;
			if (a == 0 || a == 255)
			{
				c[i] = p;
				continue;
			}
			int r = Math.min(255, ((p >> 16) & 0xFF) * 255 / a);
			int g = Math.min(255, ((p >> 8) & 0xFF) * 255 / a);
			int b = Math.min(255, (p & 0xFF) * 255 / a);
			c[i] = (a << 24) | (r << 16) | (g << 8) | b;
		}
		return c;
	}

	private static BufferedImage toImage(int[] pixels, int w, int h, int type)
	{
		BufferedImage img = new BufferedImage(w, h, type);
		int[] dst = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
		System.arraycopy(pixels, 0, dst, 0, Math.min(dst.length, pixels.length));
		return img;
	}
}
