package com.youngbryanyu.simplistash.stash;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.mapdb.DB;
import org.mapdb.HTreeMap;
import org.slf4j.Logger;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.youngbryanyu.simplistash.protocol.ProtocolUtil;
import com.youngbryanyu.simplistash.ttl.TTLTimeWheel;

/**
 * A stash which serves as a single table of key-value pairs, storing values
 * on-heap.
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class OnHeapStash implements Stash {
    /**
     * The primary cache providing O(1) direct access to values by key.
     */
    private final ConcurrentMap<String, String> cache;
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
     * Constructor for the stash.
     * 
     * @param cache        The ConcurrentHashMap cache.
     * @param ttlTimeWheel The ttl timer wheel.
     * @param logger       The application logger.
     * @param name         The stash's name.
     */
    public OnHeapStash(ConcurrentMap<String, String> cache, TTLTimeWheel ttlTimeWheel, Logger logger, String name) {
        this.cache = cache;
        this.ttlTimeWheel = ttlTimeWheel;
        this.logger = logger;
        this.name = name;

        addShutDownHook();
    }

    /**
     * Sets a key value pair in the stash. Does not change existing TTL on the key.
     * 
     * @param key   The unique key.
     * @param value The value to map to the key.
     */
    public void set(String key, String value) {
        /* Remove TTL metadata in case key previously expired */
        if (ttlTimeWheel.isExpired(key)) {
            ttlTimeWheel.remove(key);
        }

        cache.put(key, value);
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
                ttlTimeWheel.remove(key);
                cache.remove(key);

                logger.debug(String.format("Lazy removed key from stash \"%s\": %s", name, key));
            }

            /* Return null since key expired */
            return null;
        } catch (NullPointerException e) {
            /*
             * The below exception can be thrown when the DB is being closed by another
             * thread:
             * 
             * java.lang.NullPointerException: Cannot read the array length because "slices"
             * is null
             */
            logger.debug("Stash get failed, stash doesn't exist (NullPointerException)");
            return ProtocolUtil.buildErrorResponse(DB_CLOSED_ERROR);
        } catch (IllegalAccessError e) {
            /*
             * The below exception can be thrown when the DB has been closed by another
             * thread:
             * 
             * java.lang.IllegalAccessError: Store was closed
             */
            logger.debug("Stash get failed, stash doesn't exist (IllegalAccessError)");
            return ProtocolUtil.buildErrorResponse(DB_CLOSED_ERROR);
        }
    }

    /**
     * Returns whether or not the stash contains the given key.
     * 
     * @param key The key.
     * @return True if the stash contains the key, false otherwise.
     */
    public boolean contains(String key, boolean readOnly) {
        return get(key, readOnly) != null;
    }

    /**
     * Deletes a key from the stash and clears its TTL.
     * 
     * @param key The key to delete.
     */
    public void delete(String key) {
        cache.remove(key);
        ttlTimeWheel.remove(key);
    }

    /**
     * Sets a key value pair in the stash. Updates the key's TTL.
     * 
     * @param key   The key.
     * @param value The value to map to the key.
     * @param ttl   The ttl of the key.
     */
    public void setWithTTL(String key, String value, long ttl) {
        cache.put(key, value);
        ttlTimeWheel.add(key, ttl);
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
        if (!contains(key, false)) {
            return false;
        }

        ttlTimeWheel.add(key, ttl);
        return true;
    }

    /**
     * Drops the stash. Closes its DB.
     */
    public void drop() {
        cache.clear();
    }

    /**
     * Add a shutdown hook to close DB and clean up resources when the application
     * is stopped.
     */
    private void addShutDownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            cache.clear();
        }));
    }

    /**
     * Gets a batch of expired keys and removes them from the stash's cache.
     */
    public void expireTTLKeys() {
        List<String> expiredKeys = ttlTimeWheel.expireKeys();
        for (String key : expiredKeys) {
            cache.remove(key);
        }

        if (!expiredKeys.isEmpty()) {
            logger.debug(String.format("Expired keys from stash \"%s\": %s", name, expiredKeys));
        }
    }

    /**
     * Returns information about the stash.
     * 
     * @return Info about the stash.
     */
    public String getInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Number of keys: %d\n", cache.size()));
        sb.append("Off-heap: false");
        return sb.toString();
    }
}
