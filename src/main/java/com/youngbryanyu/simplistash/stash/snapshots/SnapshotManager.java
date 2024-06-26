package com.youngbryanyu.simplistash.stash.snapshots;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

import com.youngbryanyu.simplistash.stash.Stash;
import com.youngbryanyu.simplistash.ttl.TTLTimeWheel;

/**
 * The snapshot manager.
 */
public class SnapshotManager {
    /**
     * The scheduler that periodically performs the snapshot.
     */
    private final ScheduledExecutorService scheduler;
    /**
     * The snapshot writer.
     */
    private final SnapshotWriter snapshotWriter;
    /**
     * The cache.
     */
    private final Map<String, String> cache;
    /**
     * The TTL time wheel.
     */
    private final TTLTimeWheel ttlTimeWheel;
    /**
     * Whether or not a backup is currently needed since a write was recently
     * performed.
     */
    private boolean backupNeeded;
    /**
     * Whether the data is stored off heap.
     */
    private final boolean offHeap;
    /**
     * The stash name.
     */
    private final String name;
    /**
     * The max key count.
     */
    private final long maxKeyCount;
    /**
     * The logger.
     */
    private final Logger logger;

    /**
     * The constructor
     * 
     * @param name           The stash name.
     * @param maxKeyCount    The max key count.
     * @param cache          The cache map.
     * @param ttlTimeWheel   The TTL data structure
     * @param snapshotWriter The snap shot writer.
     */
    public SnapshotManager(String name, long maxKeyCount, boolean offHeap, Map<String, String> cache,
            TTLTimeWheel ttlTimeWheel,
            SnapshotWriter snapshotWriter, Logger logger) {
        this.name = name;
        this.maxKeyCount = maxKeyCount;
        this.offHeap = offHeap;
        this.cache = cache;
        this.ttlTimeWheel = ttlTimeWheel;
        this.snapshotWriter = snapshotWriter;
        this.logger = logger;

        backupNeeded = false;
        scheduler = createScheduler();
    }

    /**
     * Starts the scheduler to regularly take snapshots
     */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::takeSnapshot, 0, Stash.SNAPSHOT_DELAY_S, TimeUnit.SECONDS);
    }

    /**
     * Stops the scheduler.
     */
    public void stop() {
        scheduler.shutdownNow();
    }

    /**
     * Creates a scheduler with 1 thread.
     */
    public ScheduledExecutorService createScheduler() {
        return Executors.newScheduledThreadPool(1);
    }

    /**
     * Loops over all entries in the cache and backs them up to disk.
     */
    public void takeSnapshot() {
        try {
            if (backupNeeded) {
                logger.debug("Snapshot started for stash: " + name);

                /* Open the writer */
                snapshotWriter.open();

                /* Write metadata first */
                snapshotWriter.writeMetadata(name, maxKeyCount, offHeap);

                /* Write each entry with ttl */
                for (Map.Entry<String, String> entry : cache.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    long expirationTime = ttlTimeWheel.getExpirationTime(key);
                    snapshotWriter.writeEntry(key, value, expirationTime);
                }

                /* Commit and writer */
                snapshotWriter.commit();
                snapshotWriter.close();
                backupNeeded = false;

                logger.debug("Snapshot finished for stash: " + name);
            }
        } catch (IOException e) {
            logger.info(
                    String.format("Error occurred while taking snapshot of stash \"%s\": %s", name, e.getMessage()));
        }
    }

    /**
     * Marks the flag indicated that a backup is needed since a write was performed
     * recently.
     */
    public void markBackupNeeded() {
        backupNeeded = true;
    }

    /**
     * Returns whether a backup is needed.
     */
    public boolean isBackupNeeded() {
        return backupNeeded;
    }

    /**
     * Closes the snapshot writer.
     * 
     * @throws IOException If an IO exception occurs.
     */
    public void close() throws IOException {
        snapshotWriter.close();
    }

    /**
     * Deletes the snapshot files.
     * 
     * @throws IOException If an IO exception occurs.
     */
    public void delete() throws IOException {
        snapshotWriter.delete();
    }
}
