package com.youngbryanyu.simplistash.stash;

import java.util.List;

import org.mapdb.DB;
import org.mapdb.HTreeMap;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.youngbryanyu.simplistash.protocol.ProtocolUtil;
import com.youngbryanyu.simplistash.ttl.TTLTimeWheel;

/**
 * A stash which serves as a single table of key-value pairs.
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class Stash {
    /**
     * The max key length allowed in the stash.
     */
    public static final int MAX_KEY_LENGTH = 256;
    /**
     * The max value length allowed in the stash.
     */
    public static final int MAX_VALUE_LENGTH = 65536;
    /**
     * The max alloed length of a stash's name.
     */
    public static final int MAX_NAME_LENGTH = 64;
    /**
     * Error message when attempting to access a closed DB.
     */
    public static final String DB_CLOSED_ERROR = "The specified stash doesn't exist.";
    /**
     * A single DB store instance tied to the stash.
     */
    private final DB db;
    /**
     * The primary cache providing O(1) direct access to values by key, and off-heap
     * storage.
     */
    private final HTreeMap<String, String> cache;
    /**
     * Time wheel structure used to actively expire TTLed keys.
     */
    private final TTLTimeWheel ttlTimeWheel;
    /**
     * The application logger.
     */
    private final Logger logger;
    /**
     * The name of the Stash.
     */
    private final String name;
    /**
     * The lock service.
     */
    private final LockService lockService; // TODO: inject

    /**
     * Constructor for the stash.
     * 
     * @param db     The DB instance.
     * @param cache  The HTreeMap cache.
     * @param logger The application logger.
     * @param name   The stash's name.
     */
    @Autowired
    public Stash(DB db, HTreeMap<String, String> cache, TTLTimeWheel ttlTimeWheel, Logger logger, String name) {
        this.db = db;
        this.cache = cache;
        this.ttlTimeWheel = ttlTimeWheel;
        this.logger = logger;
        this.name = name;
        addShutDownHook();

        lockService = new LockService(16);
    }

    /**
     * Sets a key value pair in the stash. Does not change existing TTL on the key.
     * 
     * @param key   The unique key.
     * @param value The value to map to the key.
     */
    public void set(String key, String value) {
        try {
            lockService.lock(key); /* Lock */

            /* Remove TTL metadata in case key previously expired */
            if (ttlTimeWheel.isExpired(key)) {
                ttlTimeWheel.remove(key);
            }

            cache.put(key, value);
        } catch (NullPointerException | IllegalAccessError e) {
            logger.debug("Stash set failed, stash was closed.");
        } finally {
            lockService.unlock(key); /* Unlock */
        }

    }

    /**
     * Retrieves a value from the stash matching the key. Returns an error message
     * if the DB is being closed or has already been closed by another concurrent
     * client. Lazy expires the key if it has expired and the client isn't
     * read-only.
     * 
     * @param key      The key of the value to get.
     * @param readOnly Whether or not the client is read-only.
     * @return The value matching the key.
     */
    public String get(String key, boolean readOnly) {
        try {
            /* Get value if key isn't expired */
            if (!ttlTimeWheel.isExpired(key)) {
                return cache.get(key);
            }

            /* Lazy expire if not read-only */
            if (!readOnly) {
                lockService.lock(key); /* Lock */

                /* Check expiration again so its an atomic check-then-act */
                if (!ttlTimeWheel.isExpired(key)) {
                    ttlTimeWheel.remove(key);
                    cache.remove(key);
                    logger.debug(String.format("Lazy removed key from stash \"%s\": %s", name, key));
                }
            }

            /* Return null since key expired */
            return null;
        } catch (NullPointerException | IllegalAccessError e) {
            logger.debug("Stash get failed, stash was closed.");
            return ProtocolUtil.buildErrorResponse(DB_CLOSED_ERROR);
        } finally {
            lockService.unlock(key); /* Unlock */
        }
    }

    /**
     * Deletes a key from the stash and clears its TTL.
     * 
     * @param key The key to delete.
     */
    public void delete(String key) {
        try {
            lockService.lock(key); /* Lock */

            cache.remove(key);
            ttlTimeWheel.remove(key);
        } catch (NullPointerException | IllegalAccessError e) {
            logger.debug("Stash delete failed, stash was closed.");
        } finally {
            lockService.unlock(key); /* Unlock */
        }
    }

    /**
     * Sets a key value pair in the stash. Updates the key's TTL.
     * 
     * @param key   The key.
     * @param value The value to map to the key.
     * @param ttl   The ttl of the key.
     */
    public void setWithTTL(String key, String value, long ttl) {
        try {
            lockService.lock(key); /* Lock */

            cache.put(key, value);
            ttlTimeWheel.add(key, ttl);
        } catch (NullPointerException | IllegalAccessError e) {
            logger.debug("Stash set with TTL failed, stash was closed.");
        } finally {
            lockService.unlock(key); /* Unlock */
        }
    }

    /**
     * Updates the TTL of a given key. Returns whether or not the key exists and the
     * TTL operation succeeded.
     * 
     * @param key The key.
     * @param ttl The key's new TTL.
     * @return True if the TTL was updated, false if the key doesn't exist.
     */
    public boolean updateTTL(String key, long ttl) {
        try {
            lockService.lock(key); /* Lock */

            if (!cache.containsKey(key)) {
                return false;
            }

            ttlTimeWheel.add(key, ttl);
            return true;
        } catch (NullPointerException | IllegalAccessError e) {
            return false;
        } finally {
            lockService.unlock(key); /* Unlock */
        }
    }

    /**
     * Drops the stash. Closes its DB.
     */
    public void drop() {
        db.close();
    }

    /**
     * Add a shutdown hook to close DB and clean up resources when the application
     * is stopped.
     */
    private void addShutDownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            db.close();
        }));
    }

    /**
     * Gets a batch of expired keys and removes them from the stash's cache.
     */
    public void expireTTLKeys() {
        List<String> expiredKeys = ttlTimeWheel.expireKeys();
        for (String key : expiredKeys) {
            delete(key); /* Delete method manages locks */
        }

        if (!expiredKeys.isEmpty()) {
            logger.debug(String.format("Expired keys from stash \"%s\": %s", name, expiredKeys));
        }
    }
}

// TODO: set segments in HTreeMap
// TODO: make null checks when getting stash in commands

/**
 * Notes about DB and HTreeMap exceptions and errors:
 * 
 * Thrown when the DB is being closed by another thread:
 * 1. java.lang.NullPointerException: Cannot read the array length because
 * "slices" is null
 * 2. java.lang.IllegalAccessError: Store was closed
 * 
 * Because the HTreeMap can be closed, any operation with the HTreeMap (cache)
 * must be
 * guarded with the try-catch.
 */

// TODO: remove read only server if concurrency works.