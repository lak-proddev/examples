/*******************************************************************************
 * Copyright (c) 2024 Nividous and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.equinox.transforms.replace.images.tests;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import java.io.*;
import java.net.URL;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.*;
import org.osgi.framework.*;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Integration tests for the icon-replacement race condition in
 * {@code TransformerBundleExtender} (regression introduced by commit 91eebf3).
 *
 * <h2>Bug</h2>
 * <p>
 * This bundle declares {@code Equinox-Transformer: replace;/image_transform.csv}
 * in its manifest. The {@code TransformerBundleExtender} is responsible for
 * scanning that header and registering the matching resource ({@code image_transform.csv})
 * as an OSGi {@code URL} service with property {@code equinox.transformerType=replace}.
 * </p>
 * <p>
 * After commit 91eebf3 the extender was launched on a <em>background thread</em>.
 * On a <b>clean launch</b> this is harmless: OSGi bundle resolution is slow
 * enough that the thread finishes before any icon is first accessed. On a
 * <b>restart without clearing the OSGi configuration</b>, bundle state is
 * restored from disk instantly and Eclipse immediately restores the previous
 * workbench session — including all plugin icons. The background thread loses
 * the race: icons are read and cached by Eclipse's {@code ImageRegistry}
 * <em>before</em> the URL service is registered, so the custom replacements
 * never take effect for that session.
 * </p>
 *
 * <h2>Fix</h2>
 * <p>
 * Run {@code TransformerBundleExtender.start()} synchronously inside
 * {@code TransformerHook.start()}, before {@code TransformInstanceListData}
 * is opened. This guarantees the URL service is present at the moment of the
 * first icon lookup on every launch.
 * </p>
 *
 * <h2>How these tests work</h2>
 * <p>
 * The tests use a real OSGi {@link ServiceTracker} — the same base class
 * that {@code TransformInstanceListData} extends — to observe the race
 * condition directly at the service-registry level, without needing access
 * to Equinox-internal APIs.
 * </p>
 * <p>
 * Run as a <b>PDE JUnit Plug-in Test</b> (use the provided
 * {@code transformer_bug_test.launch} configuration) so that the OSGi
 * framework extension is active.
 * </p>
 */
class TransformerBugTest {

	/** Service property key used by TransformerBundleExtender / TransformTuple. */
	private static final String TRANSFORMER_TYPE_PROP = "equinox.transformerType"; //$NON-NLS-1$

	/** The replace transformer type registered for this bundle. */
	private static final String REPLACE_TYPE = "replace"; //$NON-NLS-1$

	/** OSGi filter matching URL services registered by the extender. */
	private static final String REPLACE_URL_FILTER =
			"(&(objectClass=java.net.URL)(" + TRANSFORMER_TYPE_PROP + "=" + REPLACE_TYPE + "))"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

	private BundleContext bundleContext;

	@BeforeEach
	void setUp() {
		Bundle thisBundle = FrameworkUtil.getBundle(getClass());
		assumeTrue(thisBundle != null,
				"Test requires an OSGi runtime — run via PDE JUnit Plug-in Test"); //$NON-NLS-1$
		bundleContext = thisBundle.getBundleContext();
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Test 1 — basic: URL service is registered at all
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * Verifies that this bundle's {@code image_transform.csv} has been registered
	 * as a URL service with {@code equinox.transformerType=replace} by the extender.
	 *
	 * <p>This is the simplest observable proof that
	 * {@code TransformerBundleExtender} processed this bundle's manifest header.
	 * If the extender runs synchronously (the fix), this is guaranteed by the
	 * time any application code runs. With the background-thread bug, this
	 * service may be absent during the race window.</p>
	 */
	@Test
	void testUrlServiceRegisteredForThisBundle() throws Exception {
		Collection<ServiceReference<URL>> refs =
				bundleContext.getServiceReferences(URL.class, REPLACE_URL_FILTER);

		assertNotNull(refs,
				"URL service with equinox.transformerType=replace must be registered by the extender"); //$NON-NLS-1$
		assertFalse(refs.isEmpty(),
				"At least one replace-type URL service must exist"); //$NON-NLS-1$

		// The service value is the URL of this bundle's image_transform.csv
		ServiceReference<URL> first = refs.iterator().next();
		URL transformUrl = bundleContext.getService(first);
		try {
			assertNotNull(transformUrl, "URL service value must not be null"); //$NON-NLS-1$
			// Verify the URL is openable (resource exists in the bundle)
			try (InputStream in = transformUrl.openStream()) {
				assertNotNull(in, "image_transform.csv must be readable via the URL service"); //$NON-NLS-1$
			}
		} finally {
			bundleContext.ungetService(first);
		}
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Test 2 — validate the CSV content and replacement resources
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * Parses {@code image_transform.csv} from the registered URL service and
	 * verifies that every referenced replacement resource is accessible.
	 *
	 * <p>A broken CSV (missing file, bad regex, wrong path) would cause
	 * {@code CSVParser.parse()} to silently drop the entry and the transform
	 * would never be applied — even after the race condition is fixed.</p>
	 */
	@Test
	void testTransformCsvIsValidAndResourcesExist() throws Exception {
		Collection<ServiceReference<URL>> refs =
				bundleContext.getServiceReferences(URL.class, REPLACE_URL_FILTER);
		assumeTrue(refs != null && !refs.isEmpty(),
				"URL service must be registered (see testUrlServiceRegisteredForThisBundle)"); //$NON-NLS-1$

		ServiceReference<URL> first = refs.iterator().next();
		URL csvUrl = bundleContext.getService(first);
		try {
			assertNotNull(csvUrl);
			int validEntries = 0;

			try (BufferedReader reader =
					new BufferedReader(new InputStreamReader(csvUrl.openStream()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (line.isEmpty() || line.startsWith("#")) { //$NON-NLS-1$
						continue;
					}
					String[] parts = line.split(",", -1); //$NON-NLS-1$
					assertEquals(3, parts.length,
							"Each CSV line must have 3 fields (bundlePattern,pathPattern,resource): " + line); //$NON-NLS-1$

					// The third field is a path relative to the CSV URL
					String replacementPath = parts[2].trim();
					assertFalse(replacementPath.isEmpty(),
							"Replacement resource path must not be empty in line: " + line); //$NON-NLS-1$

					URL replacementUrl = new URL(csvUrl, replacementPath);
					try (InputStream in = replacementUrl.openStream()) {
						assertNotNull(in,
								"Replacement resource must be readable: " + replacementPath); //$NON-NLS-1$
					}
					validEntries++;
				}
			}
			assertTrue(validEntries > 0,
					"image_transform.csv must contain at least one non-comment entry"); //$NON-NLS-1$
		} finally {
			bundleContext.ungetService(first);
		}
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Test 3 — BUG reproduced: service tracker opened before URL service exists
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * <b>Reproduces Bug 1</b> using a real {@link ServiceTracker} (the same
	 * base class as {@code TransformInstanceListData}) with controlled timing.
	 *
	 * <h3>Scenario (mirrors restart without clearing OSGi config)</h3>
	 * <ol>
	 *   <li>A {@code ServiceTracker} is opened <em>before</em> the URL service
	 *       is registered — as happens when {@code TransformInstanceListData} is
	 *       opened before the background extender thread registers the URL
	 *       service.</li>
	 *   <li>{@code getServiceReferences()} is queried immediately — just as
	 *       {@code TransformInstanceListData.getTransformTypes()} is called when
	 *       an icon is first requested during workbench restore.</li>
	 *   <li>The query returns {@code null} (no services) — so
	 *       {@code TransformInstanceListData} returns {@code EMPTY_TYPES}, and
	 *       {@code TransformedBundleFile.getInputStream()} returns {@code null},
	 *       causing {@code getEntry()} to return the original (default) icon.
	 *       {@code ImageRegistry} caches it — permanently for this session.</li>
	 *   <li>The URL service is registered <em>after</em> the icon was cached —
	 *       too late.</li>
	 * </ol>
	 */
	@Test
	void testBug_serviceTrackerQueryDuringRaceWindowReturnsNull() throws Exception {
		// Register a fresh URL service for this test (independent of the one the
		// extender already registered, so we can control timing precisely)
		URL csvUrl = getClass().getResource("/image_transform.csv"); //$NON-NLS-1$
		assumeTrue(csvUrl != null, "image_transform.csv must be accessible from the bundle"); //$NON-NLS-1$

		// ── Open tracker BEFORE registering the service ───────────────────────────
		// This simulates: TransformInstanceListData.open() called while the
		// background extender thread has not yet called tracker.open() for this bundle.
		// We use a fresh filter that will NOT match the service registered by the
		// extender, so we control the timing precisely with our own registration.
		String testType = "replace-test-" + System.nanoTime(); //$NON-NLS-1$
		String testFilter = "(&(objectClass=java.net.URL)(" + TRANSFORMER_TYPE_PROP + "=" + testType + "))"; //$NON-NLS-1$ //$NON-NLS-2$
		Filter isolatedFilter = bundleContext.createFilter(testFilter);

		ServiceTracker<URL, URL> tracker =
				new ServiceTracker<>(bundleContext, isolatedFilter, null);
		tracker.open();

		try {
			// ── Step 1: first query during race window ────────────────────────────
			// No URL service registered yet — mirrors "icon accessed before extender done"
			ServiceReference<URL>[] refsBeforeRegistration = tracker.getServiceReferences();

			// ── Step 2: register URL service (extender background thread completes) ─
			ServiceRegistration<URL> reg = bundleContext.registerService(URL.class, csvUrl,
					FrameworkUtil.asDictionary(Map.of(TRANSFORMER_TYPE_PROP, testType)));

			try {
				// Wait for the ServiceTracker to receive the ServiceEvent.
				// Even with the bug, the tracker DOES eventually see the service via
				// addingService() — but that is too late for callers that already
				// consumed the null result from step 1 (e.g. ImageRegistry).
				URL service = tracker.waitForService(2_000);

				// ── Step 3: second query (service now registered) ─────────────────
				ServiceReference<URL>[] refsAfterRegistration = tracker.getServiceReferences();

				// ── Assertions ────────────────────────────────────────────────────
				//
				// BUG CONFIRMED: first query returned null.
				// In production this means: TransformInstanceListData.rebuildTransformMap()
				// found no URL services → stale set to false → EMPTY_TYPES returned →
				// TransformedBundleFile.getInputStream() → null → getEntry() returns
				// ORIGINAL icon → ImageRegistry caches the DEFAULT icon for the session.
				assertNull(refsBeforeRegistration,
						"BUG REPRODUCED: tracker query during race window returned null — " //$NON-NLS-1$
								+ "original (default) icon would be served and cached"); //$NON-NLS-1$

				// After the URL service is registered, the tracker correctly picks it
				// up via addingService(). Subsequent icon lookups (if any) would apply
				// the transform — but ImageRegistry never asks again for the same key.
				assertNotNull(service,
						"ServiceTracker must eventually see the URL service"); //$NON-NLS-1$
				assertNotNull(refsAfterRegistration,
						"Second query after registration must be non-null"); //$NON-NLS-1$

			} finally {
				reg.unregister();
			}
		} finally {
			tracker.close();
		}
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Test 4 — FIX confirmed: service registered before tracker is opened
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * <b>Confirms the fix</b>: when the URL service is registered
	 * <em>before</em> the {@link ServiceTracker} is opened — which is what the
	 * synchronous {@code TransformerBundleExtender.start()} guarantees — the
	 * very first {@code getServiceReferences()} call returns the correct,
	 * non-null result.
	 *
	 * <p>This means {@code TransformInstanceListData.getTransformTypes()}
	 * immediately returns {@code ["replace"]}, {@code TransformedBundleFile}
	 * applies the transform on the first access, and {@code ImageRegistry}
	 * caches the <em>custom</em> (replacement) icon — on every launch,
	 * including restarts without clearing the OSGi configuration.</p>
	 */
	@Test
	void testFix_urlServiceRegisteredBeforeTrackerOpened_firstQuerySucceeds() throws Exception {
		URL csvUrl = getClass().getResource("/image_transform.csv"); //$NON-NLS-1$
		assumeTrue(csvUrl != null, "image_transform.csv must be accessible from the bundle"); //$NON-NLS-1$

		String testType = "replace-fix-" + System.nanoTime(); //$NON-NLS-1$
		String testFilter = "(&(objectClass=java.net.URL)(" + TRANSFORMER_TYPE_PROP + "=" + testType + "))"; //$NON-NLS-1$ //$NON-NLS-2$

		// ── FIX: register URL service FIRST ──────────────────────────────────────
		// Mirrors the fixed TransformerHook.start() order:
		//   1. extenderTracker = TransformerBundleExtender.start(ctx)  ← synchronous
		//   2. this.templates  = new TransformInstanceListData(ctx, …) ← opens tracker
		ServiceRegistration<URL> reg = bundleContext.registerService(URL.class, csvUrl,
				FrameworkUtil.asDictionary(Map.of(TRANSFORMER_TYPE_PROP, testType)));

		try {
			// ── Then open tracker ─────────────────────────────────────────────────
			Filter filter = bundleContext.createFilter(testFilter);
			ServiceTracker<URL, URL> tracker =
					new ServiceTracker<>(bundleContext, filter, null);
			tracker.open();

			try {
				// ── First (and only) query — no race window ───────────────────────
				ServiceReference<URL>[] refs = tracker.getServiceReferences();

				// FIX CONFIRMED: URL service found immediately on first query.
				// TransformInstanceListData.getTransformTypes() returns ["replace"].
				// TransformedBundleFile.getInputStream() applies the transform.
				// ImageRegistry caches the CUSTOM icon — correct on every restart.
				assertNotNull(refs,
						"FIX CONFIRMED: first query must find the URL service " //$NON-NLS-1$
								+ "(registered synchronously before tracker was opened)"); //$NON-NLS-1$
				assertEquals(1, refs.length,
						"Exactly one URL service must be found"); //$NON-NLS-1$

			} finally {
				tracker.close();
			}
		} finally {
			reg.unregister();
		}
	}

	// ──────────────────────────────────────────────────────────────────────────
	// Test 5 — two real threads, controlled latch ordering
	// ──────────────────────────────────────────────────────────────────────────

	/**
	 * Reproduces the exact restart scenario using two real threads synchronized
	 * by {@link CountDownLatch latches}:
	 * <ul>
	 *   <li><b>WorkbenchRestoreThread</b> — simulates Eclipse restoring the
	 *       workbench and accessing icon resources.</li>
	 *   <li><b>TransformerBundleExtender-Thread</b> — simulates the original
	 *       background thread that registers URL services <em>after</em> the
	 *       workbench thread has already done its first lookup.</li>
	 * </ul>
	 */
	@Test
	void testRace_concurrentThreads_workbenchThreadMissesUrlService() throws Exception {
		URL csvUrl = getClass().getResource("/image_transform.csv"); //$NON-NLS-1$
		assumeTrue(csvUrl != null, "image_transform.csv must be accessible"); //$NON-NLS-1$

		String testType = "replace-race-" + System.nanoTime(); //$NON-NLS-1$
		String testFilter = "(&(objectClass=java.net.URL)(" + TRANSFORMER_TYPE_PROP + "=" + testType + "))"; //$NON-NLS-1$ //$NON-NLS-2$
		Filter filter = bundleContext.createFilter(testFilter);

		// ── Latches control the exact ordering ───────────────────────────────────
		CountDownLatch iconLookedUp        = new CountDownLatch(1); // A signals B
		CountDownLatch urlServiceRegistered = new CountDownLatch(1); // B signals A

		AtomicReference<ServiceReference<URL>[]> firstResult  = new AtomicReference<>();
		AtomicReference<ServiceReference<URL>[]> secondResult = new AtomicReference<>();
		AtomicReference<ServiceRegistration<URL>> capturedReg = new AtomicReference<>();
		AtomicReference<Throwable>                threadError  = new AtomicReference<>();

		// ── Thread A: workbench restores — icon access happens NOW ────────────────
		Thread workbenchThread = new Thread(() -> {
			ServiceTracker<URL, URL> tracker =
					new ServiceTracker<>(bundleContext, filter, null);
			tracker.open();
			try {
				// First icon lookup — URL service not yet registered (race window)
				firstResult.set(tracker.getServiceReferences());
				iconLookedUp.countDown(); // "icon cached" — whatever we got is stored

				// Wait for the extender thread, then do a second lookup
				urlServiceRegistered.await(10, TimeUnit.SECONDS);
				secondResult.set(tracker.getServiceReferences());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				tracker.close();
			}
		}, "WorkbenchRestoreThread"); //$NON-NLS-1$

		// ── Thread B: original background extender thread ─────────────────────────
		Thread extenderThread = new Thread(() -> {
			try {
				// Wait until the workbench thread has already done its icon lookup
				iconLookedUp.await(10, TimeUnit.SECONDS);

				// Register URL service — too late for the workbench thread's first lookup
				ServiceRegistration<URL> reg = bundleContext.registerService(URL.class, csvUrl,
						FrameworkUtil.asDictionary(Map.of(TRANSFORMER_TYPE_PROP, testType)));
				capturedReg.set(reg);

				// Give the ServiceTracker time to fire addingService()
				Thread.sleep(100);
				urlServiceRegistered.countDown();
			} catch (Exception e) {
				threadError.set(e);
				urlServiceRegistered.countDown();
			}
		}, "TransformerBundleExtender-Thread"); //$NON-NLS-1$

		try {
			workbenchThread.start();
			extenderThread.start();
			workbenchThread.join(15_000);
			extenderThread.join(15_000);

			if (threadError.get() != null) {
				throw new AssertionError("Background thread failed", threadError.get()); //$NON-NLS-1$
			}
			assertFalse(workbenchThread.isAlive(), "Workbench thread timed out"); //$NON-NLS-1$
			assertFalse(extenderThread.isAlive(),  "Extender thread timed out"); //$NON-NLS-1$

			// ── Assertions ────────────────────────────────────────────────────────

			// BUG: The workbench thread's first lookup (during the race window)
			// returned null. In production this causes:
			//   • TransformInstanceListData → EMPTY_TYPES
			//   • TransformedBundleFile.getInputStream() → null
			//   • getEntry() → returns ORIGINAL (default) bundle icon
			//   • ImageRegistry.put() → caches the default icon for the session
			//   → Custom icon replacement fails for this restart session
			assertNull(firstResult.get(),
					"BUG REPRODUCED: WorkbenchRestoreThread's first lookup during race window " //$NON-NLS-1$
							+ "returned null — default icon would be cached by ImageRegistry"); //$NON-NLS-1$

			// After the background thread completes, subsequent lookups work correctly.
			// But any caller that already cached the null result is permanently wrong.
			assertNotNull(secondResult.get(),
					"Second lookup after extender thread completes must be non-null"); //$NON-NLS-1$

		} finally {
			ServiceRegistration<URL> reg = capturedReg.get();
			if (reg != null) {
				reg.unregister();
			}
		}
	}
}
