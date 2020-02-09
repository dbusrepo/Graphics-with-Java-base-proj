package com.busatod.java.graphics;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.image.BufferStrategy;
import java.text.DecimalFormat;

// vedere:
// https://stackoverflow.com/questions/35516191/what-is-the-correct-way-to-use-createbufferstrategy

// TODO
// Threading
// refactoring main..
// logic app class
// see reader comments about the book chapters on the book website
// vedi setBufferStrategy in wormChase.java full screen e la gestione del fullscreen in generale

public class GraphicsApplication extends JFrame implements WindowListener, Runnable {

	private static final String TITLE = "Java Graphics";

	private static final int NUM_BUFFERS = 3;

	private static final long NANO_IN_MILLI = 1000000L;
	private static final long NANO_IN_SEC = 1000L * NANO_IN_MILLI;

	public static final int FONT_SIZE = 20;
	public static final String FONT_NAME = "SansSerif";

	private static long MAX_STATS_INTERVAL = NANO_IN_SEC; // in ns
	// private static long MAX_STATS_INTERVAL = 1000L;
	// record stats every 1 second (roughly)X

	// Number of frames with a delay of 0 ms before the animation thread yields
	// to other running threads.
	private static final int NUM_DELAYS_PER_YIELD = 16;

	// no. of frames that can be skipped in any one animation loop
	// i.e the state is updated but not rendered
	private static int MAX_FRAME_SKIPS = 5;

	// number of FPS values stored to get an average
	private static int NUM_AVG_FPS = 10;

	private static int TARGET_FPS = 60; // -1 if not used // TODO ?? move to settings params...

	/******************************************************************************************************************/

	//    private GraphicsEnvironment graphicsEnvironment;
	//    private GraphicsDevice screenDevice;

	private int width, height;
	private boolean useFullScreen;
	private boolean printDebugInfo;

	protected Thread renderThread = null;

	private volatile boolean isRunning = false;
	private boolean appOver = false;
	private boolean isPaused = false;
	private boolean finishedOff = false;

	private long period;  // period between drawing in _nanosecs_

	// used for gathering statistics
	private long statsInterval = 0L; // ns
	private long prevStatsTime;
	private long totalElapsedTime = 0L;
	private long timeSpentInApp = 0; // seconds

	private long frameCount = 0;
	private double fpsStore[];
	private long statsCount = 0;
	private double averageFPS = 0.0;

	private long framesSkipped = 0L;
	private long totalFramesSkipped = 0L;
	private double upsStore[];
	private double averageUPS = 0.0;

	private DecimalFormat df = new DecimalFormat("0.##");  // 2 dp
	private DecimalFormat timedf = new DecimalFormat("0.####");  // 4 dp

	private Font font;
	private FontMetrics metrics;

	//	private final Dimension winDimension;
	private int windowBarHeight;

	private InputManager inputManager;
	private InputAction exitAction;
	private InputAction pauseAction;
	private InputAction toggleFullscreenAction;

	private long appStartTime;
	private long lastFpsTime;

	// used for full-screen exclusive mode
	private GraphicsDevice gd;
	private BufferStrategy bufferStrategy;

	// init for windowed mode
	public GraphicsApplication(int width, int height, boolean useFullScreen, boolean printDebugInfo) {
		System.out.println("Initializing the graphics application...");
		//        graphicsEnvironment = GraphicsEnvironment.getLocalGraphicsEnvironment();
		//        screenDevice = graphicsEnvironment.getDefaultScreenDevice();
//		this.winDimension = new Dimension(width, height);
//		GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
//		this.gd = ge.getDefaultScreenDevice();
		this.gd = getGraphicsConfiguration().getDevice();
		this.width = width;
		this.height = height;
		this.useFullScreen = useFullScreen;
		this.printDebugInfo = printDebugInfo;

		this.font = new Font(FONT_NAME, Font.BOLD, FONT_SIZE);
		this.metrics = this.getFontMetrics(font);

		// initialise timing elements
		this.fpsStore = new double[NUM_AVG_FPS];
		this.upsStore = new double[NUM_AVG_FPS];
		for (int i = 0; i < NUM_AVG_FPS; i++) {
			fpsStore[i] = 0.0;
			upsStore[i] = 0.0;
		}

		// calc the frame period and print it
//		this.period = 0L;  // rendering FPS (nanosecs/targetFPS) // da mettere tra i settings?
		this.period = NANO_IN_SEC / TARGET_FPS; // in ns

//      setDefaultLookAndFeelDecorated(true);
//      setSize(windowedWidth, windowedHeight);
		setUndecorated(useFullScreen); // no menu bar, borders, etc. or Swing components?
		setIgnoreRepaint(true); // turn off all paint events since doing active rendering

		adjustWinSize();
		setResizable(false);
		setVisible(true);
		setLocationRelativeTo(null); // called after setVisible(true); to center the window (first screen only?)
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		this.windowBarHeight = getHeight() - getContentPane().getHeight(); // save the win bar height for later use
		if (useFullScreen) {
			initFullScreen();
		}
		setBufferStrategy();

//		System.out.println("Target FPS: " + TARGET_FPS);
//		System.out.println("Target FPS period: " + period);

		// create app components
		// TODO APP_HOOK

		initInputManager();
		addWindowListener(this);
		setFocusable(true);
		requestFocus();
		requestFocusInWindow();

		// for shutdown tasks, a shutdown may not only come from the program
		Runtime.getRuntime().addShutdownHook(buildShutdownThread());

		setTitle(TITLE);
//		setBackground(Color.BLACK);

		// start the app
		start();
	}

	private void initFullScreen() {
//			if (!GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().isFullScreenSupported()) {
////			if (!getGraphicsConfiguration().getDevice().isFullScreenSupported()) {
		if (!gd.isFullScreenSupported()) {
			System.out.println("Full-screen exclusive mode not supported");
			System.exit(0);
		}

		setExtendedState(JFrame.MAXIMIZED_BOTH);
		gd.setFullScreenWindow(this); // switch on full-screen exclusive mode

		// we can now adjust the display modes, if we wish
//		showCurrentMode();

		// setDisplayMode(800, 600, 8);   // or try 8 bits
		// setDisplayMode(1280, 1024, 32);

//		reportCapabilities();

//		pWidth = getBounds().width;
//		pHeight = getBounds().height;
//
//		setBufferStrategy();
	}  // end of initFullScreen()

	protected class ShutDownThread extends Thread {
		@Override
		public void run() {
//			super.run();
			isRunning = false;
			finishOff();
		}
	}

	// TODO APP_HOOK
	protected Thread buildShutdownThread() {
		return new ShutDownThread();
	}

	private void start() {
		if (renderThread == null || !isRunning) {
			renderThread = new Thread(this);
			renderThread.start();
		}
	}

	// going to fullscreen:
	// https://stackoverflow.com/questions/13064607/fullscreen-swing-components-fail-to-receive-keyboard-input-on-java-7-on-mac-os-x
	// https://stackoverflow.com/questions/19645243/keylistener-doesnt-work-after-dispose
	private void toggleFullscreen() {
		useFullScreen = !useFullScreen;
		setVisible(false);
		dispose();
		setUndecorated(useFullScreen);
		gd = getGraphicsConfiguration().getDevice();
		if (useFullScreen) {
			setExtendedState(JFrame.MAXIMIZED_BOTH);
			gd.setFullScreenWindow(this);
		} else {
			setExtendedState(JFrame.NORMAL);
			gd.setFullScreenWindow(null);
		}
		adjustWinSize();
		setVisible(true);
//			setLocationRelativeTo(null); // problem..it moves the window to the first screen
	}

	private void adjustWinSize() {
		getContentPane().setPreferredSize(new Dimension(width, height));
		pack();
//		setSize(width, height); // da chiamare prima di setVisible? title bar problem
//		setVisible(true);
		//windowBarHeight = (int) (HEIGHT or getHeight() ? - getContentPane().getSize().getHeight());
		//setSize(WIDTH, HEIGHT + windowBarHeight);
	}

	private void initInputManager() {
		this.inputManager = new InputManager(this);
		exitAction = new InputAction("Exit", InputAction.DetectBehavior.INITIAL_PRESS_ONLY);
		pauseAction = new InputAction("Pause", InputAction.DetectBehavior.INITIAL_PRESS_ONLY);
		toggleFullscreenAction = new InputAction("Toogle Fullscreen", InputAction.DetectBehavior.INITIAL_PRESS_ONLY);
		inputManager.mapToKey(exitAction, KeyEvent.VK_ESCAPE);
		inputManager.mapToKey(pauseAction, KeyEvent.VK_P);
		inputManager.mapToKey(toggleFullscreenAction, KeyEvent.VK_F1);
	}

	private void setBufferStrategy() {
		// avoid potential deadlock in 1.4.1_02
		/* Switch on page flipping: NUM_BUFFERS == 2 so
		 there will be a 'primary surface' and one 'back buffer'.

		 The use of invokeAndWait() is to avoid a possible deadlock
		 with the event dispatcher thread. Should be fixed in J2SE 1.5

		 createBufferStrategy) is an asynchronous operation, so sleep
		 a bit so that the getBufferStrategy() call will get the
		 correct details.
	  */
		try {
			EventQueue.invokeAndWait(new Runnable() {
				public void run() {
					createBufferStrategy(NUM_BUFFERS);
				}
			});
		} catch (Exception e) {
			System.out.println("Error while creating buffer strategy");
			System.exit(0);
		}

		try {  // sleep to give time for the buffer strategy to be carried out
			Thread.sleep(500);  // 0.5 sec
		} catch (InterruptedException ex) {}

		// Cache the buffer strategy
		bufferStrategy = getBufferStrategy();
	}

	// https://stackoverflow.com/questions/16364487/java-rendering-loop-and-logic-loop
	public void run() {

		// times are in ns
		long beforeTime, afterTime, timeDiff, sleepTime;
		long overSleepTime = 0L;
		int numDelays = 0;
		long excess = 0L;

		appStartTime = System.nanoTime();
		prevStatsTime = appStartTime;
		beforeTime = appStartTime;

		isRunning = true;

		// Main loop
		while (isRunning) {
			// update
			update(0); // TODO fix/change arg 0?
			screenUpdate();

			afterTime = System.nanoTime();
			timeDiff = afterTime - beforeTime;
			sleepTime = (period - timeDiff) - overSleepTime; // ns

			if (sleepTime > 0) { // time left in cycle
				//System.out.println("sleepTime: " + (sleepTime/NANO_IN_MILLI));
				try {
					Thread.sleep(sleepTime / NANO_IN_MILLI);//nano->ms
					numDelays = 0;   // reset noDelays when sleep occurs // see section Sleep is like Yield https://fivedots.coe.psu.ac.th/~ad/jg/ch1/readers.html
				} catch (InterruptedException ex) { }
				overSleepTime = (System.nanoTime() - afterTime) - sleepTime;
			} else { // sleepTime <= 0; the frame took longer than the period
//				System.out.println("Rendering too slow"); // TODO if stats?
				excess -= sleepTime;
				overSleepTime = 0L; // store excess time value
				if (++numDelays >= NUM_DELAYS_PER_YIELD) {
					Thread.yield(); // give another thread a chance to run
					numDelays = 0;
				}
			}

			beforeTime = System.nanoTime();

            /* If frame animation is taking too long, update the state
             without rendering it, to get the updates/sec nearer to
             the required FPS. */
			int skips = 0;
			while ((excess > period) && (skips < MAX_FRAME_SKIPS)) {
				// update state but don’t render
//				System.out.println("Skip renderFPS, run updateFPS"); // TODO if stats?
				excess -= period;
				update(0); // period as elapsedTime?
				skips++;
			}
			framesSkipped += skips;

			storeStats();
		}
		finishOff();
	}

	private void screenUpdate() {
		// use active rendering
		try {
			Graphics gScr = bufferStrategy.getDrawGraphics();
			appRender(gScr);
			if (printDebugInfo) {
				drawDebugInfo(gScr);
			}
			gScr.dispose();
			if (!bufferStrategy.contentsLost()) {
				bufferStrategy.show();
			} else {
				System.out.println("Contents Lost");
			}
			// Sync the display on some systems.
			// (on Linux, this fixes event queue problems)
			Toolkit.getDefaultToolkit().sync();
		} catch (Exception e) {
			e.printStackTrace();
			isRunning = false;
		}
	}

	// TODO APP_HOOK
	protected void drawDebugInfo(Graphics g) {
		g.setFont(font);
		g.setColor(Color.YELLOW);
		if (g instanceof Graphics2D) {
			((Graphics2D) g).setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		}
//		g.drawString("Frame Count " + frameCount, 10, winBarHeight + 25);
		String debugInfo = "FPS/UPS: " + df.format(averageFPS) + ", " + df.format(averageUPS);
		g.drawString(debugInfo, 2, windowBarHeight + metrics.getHeight());  // was (10,55)
	}

	// TODO APP_HOOK
	protected void appRender(Graphics g) {
		// Note: render only if (!isPaused && !appOver) ? // see section Inefficient Pausing https://fivedots.coe.psu.ac.th/~ad/jg/ch1/readers.html
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, getWidth(), getHeight());
	}

	private void update(long elapsedTime) {
		checkSystemInput(); // check input that can happen whether paused or not
		if (canUpdateState()) {
			updateState(elapsedTime);
		}
	}

	private boolean canUpdateState() {
		return !isPaused && !appOver;
	}

	// TODO APP_HOOK update logic
	protected void updateState(long elapsedTime) {
	}

	// vedi anche https://stackoverflow.com/questions/19823633/multiple-keys-in-keyevent-listener
	private void checkSystemInput() {
		if (pauseAction.isPressed()) {
			isPaused = !isPaused;
		}
		if (exitAction.isPressed()) {
			stopApp();
		}
		if (toggleFullscreenAction.isPressed()) {
			toggleFullscreenAction.release(); // to avoid a subtle bug of keyReleased not invoked after switching to fs...
			toggleFullscreen();
		}
		// ...
	}

	/* Tasks to do before terminating. Called at end of run()
	   and via the shutdown hook in readyForTermination().

	   The call at the end of run() is not really necessary, but
	   included for safety. The flag stops the code being called
	   twice.
	*/
	protected void finishOff() {
		// System.out.println("finishOff");
		if (!finishedOff) {
			finishedOff = true;
			printStats();
			restoreScreen(); // make sure we restore the video mode before exiting
			System.exit(0);
		}
	}

	/**
	 * Remove the window from the screen, if we are in full screen
	 * mode then we need to reset the video mode.
	 */
	protected void restoreScreen() {
		setVisible(false); //you can't see me!
		GraphicsDevice gd = getGraphicsConfiguration().getDevice();
		Window w = gd.getFullScreenWindow();
		if (w != null) {
			w.dispose(); // destroy the JFrame object (this)
		}
		gd.setFullScreenWindow(null);
	}

	/* The statistics:
     - the summed periods for all the iterations in this interval
       (period is the amount of time a single frame iteration should take),
       the actual elapsed time in this interval,
       the error between these two numbers;

     - the total frame count, which is the total number of calls to run();

     - the frames skipped in this interval, the total number of frames
       skipped. A frame skip is a state update without a corresponding render;

     - the FPS (frames/sec) and UPS (updates/sec) for this interval,
       the average FPS & UPS over the last NUM_FPSs intervals.

    	The data is collected every MAX_STATS_INTERVAL  (1 sec).
    */
	private void storeStats() {
		frameCount++;
		statsInterval += period;
		if (statsInterval >= MAX_STATS_INTERVAL) {     // record stats every MAX_STATS_INTERVAL
			long timeNow = System.nanoTime();
			timeSpentInApp = (timeNow - appStartTime) / NANO_IN_SEC;  // ns --> secs

			long realElapsedTime = timeNow - prevStatsTime;   // time since last stats collection
			totalElapsedTime += realElapsedTime;

			double timingError = ((double) (realElapsedTime - statsInterval) / statsInterval) * 100.0;

			totalFramesSkipped += framesSkipped;

			double actualFPS = 0.0;     // calculate the latest FPS and UPS
			double actualUPS = 0.0;
			if (totalElapsedTime > 0) {
				actualFPS = (((double) frameCount / totalElapsedTime) * NANO_IN_SEC);
				actualUPS = (((double) (frameCount + totalFramesSkipped) / totalElapsedTime) * NANO_IN_SEC);
			}

			// store the latest FPS and UPS
			fpsStore[(int) statsCount % NUM_AVG_FPS] = actualFPS;
			upsStore[(int) statsCount % NUM_AVG_FPS] = actualUPS;
			statsCount++;

			double totalFPS = 0.0;     // total the stored FPSs and UPSs
			double totalUPS = 0.0;
			for (int i = 0; i < NUM_AVG_FPS; i++) {
				totalFPS += fpsStore[i];
				totalUPS += upsStore[i];
			}

			if (statsCount < NUM_AVG_FPS) { // obtain the average FPS and UPS
				averageFPS = totalFPS / statsCount;
				averageUPS = totalUPS / statsCount;
			} else {
				averageFPS = totalFPS / NUM_AVG_FPS;
				averageUPS = totalUPS / NUM_AVG_FPS;
			}

//			System.out.println(timedf.format((double) statsInterval / NANO_IN_SEC) + " " +
//					timedf.format((double) realElapsedTime / NANO_IN_SEC) + "s " +
//					df.format(timingError) + "% " +
//					frameCount + "c " +
//					framesSkipped + "/" + totalFramesSkipped + " skip; " +
//					df.format(actualFPS) + " " + df.format(averageFPS) + " afps; " +
//					df.format(actualUPS) + " " + df.format(averageUPS) + " aups");

			framesSkipped = 0;
			prevStatsTime = timeNow;
			statsInterval = 0L;   // reset
		}
	}

	private void printStats() {
		System.out.println("Frame Count/Loss: " + frameCount + " / " + totalFramesSkipped);
		System.out.println("Average FPS: " + df.format(averageFPS));
		System.out.println("Average UPS: " + df.format(averageUPS));
		System.out.println("Time Spent: " + timeSpentInApp + " secs");
		// TODO invoke app logic print stats?? APP_HOOK
		System.out.flush();
//		System.err.flush();
	}

	// called when the JFrame is activated / deiconified
	public void resumeApp() { isPaused = false; }

	// called when the JFrame is deactivated / iconified
	public void pauseApp() { isPaused = true; }

	// called when the JFrame is closing
	public void stopApp() { isRunning = false; }

	/**
	 * We may need to handle different window events
	 */
	@Override
	public void windowClosing(java.awt.event.WindowEvent evt) {
		stopApp();
	}

	@Override
	public void windowIconified(java.awt.event.WindowEvent evt) {
		pauseApp();
	}

	@Override
	public void windowDeiconified(java.awt.event.WindowEvent evt) {
		resumeApp();
	}

	@Override
	public void windowActivated(java.awt.event.WindowEvent evt) {
		resumeApp();
	}

	@Override
	public void windowDeactivated(java.awt.event.WindowEvent evt) {
		pauseApp();
	}

	@Override
	public void windowOpened(WindowEvent e) {}

	@Override
	public void windowClosed(WindowEvent e) {}

}
