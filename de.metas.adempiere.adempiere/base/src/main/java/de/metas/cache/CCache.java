/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved. *
 * This program is free software; you can redistribute it and/or modify it *
 * under the terms version 2 of the GNU General Public License as published *
 * by the Free Software Foundation. This program is distributed in the hope *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. *
 * See the GNU General Public License for more details. *
 * You should have received a copy of the GNU General Public License along *
 * with this program; if not, write to the Free Software Foundation, Inc., *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA. *
 * For the text or an alternative of this public license, you may reach us *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA *
 * or via info@compiere.org or http://www.compiere.org/license.html *
 *****************************************************************************/
package de.metas.cache;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.lang.impl.TableRecordReference;
import org.compiere.util.Util;
import org.slf4j.Logger;

import com.google.common.base.MoreObjects;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.CacheStats;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ExecutionError;
import com.google.common.util.concurrent.UncheckedExecutionException;

import de.metas.logging.LogManager;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;

/**
 * metasfresh Cache.
 *
 * @param <K> Key
 * @param <V> Value
 *
 * @author Jorg Janke
 * @version $Id: CCache.java,v 1.2 2006/07/30 00:54:35 jjanke Exp $
 */
public class CCache<K, V> implements CacheInterface
{
	/**
	 * Creates a new LRU cache
	 *
	 * @param cacheName cache name; shall respect the current naming conventions, see {@link #extractTableNameForCacheName(String)}
	 * @param maxSize LRU cache maximum size
	 * @param expireAfterMinutes if positive, the entries will expire after given number of minutes
	 * @return new cache instance
	 */
	public static final <K, V> CCache<K, V> newLRUCache(final String cacheName, final int maxSize, final int expireAfterMinutes)
	{
		return CCache.<K, V> builder()
				.cacheName(cacheName)
				// .tableName(null) // auto-detect tableName
				.initialCapacity(maxSize) // FIXME this is confusing because in case of LRU, initialCapacity is used as maxSize
				.expireMinutes(expireAfterMinutes)
				.cacheMapType(CacheMapType.LRU)
				.build();
	}

	/**
	 * Similar to {@link #newLRUCache(String, int, int)}.
	 *
	 * @param cacheName cache name; shall respect the current naming conventions, see {@link #extractTableNameForCacheName(String)}
	 */
	public static final <K, V> CCache<K, V> newCache(final String cacheName, final int initialCapacity, final int expireAfterMinutes)
	{
		return CCache.<K, V> builder()
				.cacheName(cacheName)
				// .tableName(null) // auto-detect tableName
				.initialCapacity(initialCapacity)
				.expireMinutes(expireAfterMinutes)
				.cacheMapType(CacheMapType.HashMap)
				.build();
	}

	public enum CacheMapType
	{
		/**
		 * Data is cached in a normal hash map
		 */
		HashMap,

		/**
		 * Data is cached in a LRU map (least recently used). This means that if the caches size limit is reached, then the oldest record is removed from cache in order to add a new record.
		 * This means that we can have a have a cache with a defined (limited) size without any expiration time.
		 */
		LRU,
	}

	/**
	 * If active, following informations will be stored:
	 * <ul>
	 * <li>{@link #debugId} - unique JVM object ID
	 * <li>{@link #debugAquireStacktrace} - stack trace for when the cache object was instantiated
	 * </ul>
	 */
	private static final boolean DEBUG = false;

	private static final Logger logger = LogManager.getLogger(CCache.class);

	/** Internal map that is used as cache */
	private final Cache<K, V> cache;

	static final AtomicLong NEXT_CACHE_ID = new AtomicLong(1);
	/** unique cache ID, mainly used for tracking, logging and debugging */
	private final long cacheId;

	private final String cacheName;
	private final ImmutableSet<CacheLabel> labels;

	/** Expire after minutes */
	private final int expireMinutes;
	public static final int EXPIREMINUTES_Never = 0;
	/** Just reset */
	private boolean m_justReset = true;

	/** Can provide a collection of cache keys for a given record reference. */
	private final Optional<CachingKeysMapper<K>> invalidationKeysMapper;

	/**
	 * If {@link #DEBUG} is enabled, this variable contains the object's identity code (see {@link System#identityHashCode(Object)}).
	 */
	private final String debugId;

	/**
	 * If {@link #DEBUG} is enabled, this variable contains the constructor's stack trace (when this object was created)
	 */
	private final String debugAquireStacktrace;

	/**
	 * Metasfresh Cache - expires after 2 hours
	 *
	 * @param name (table) name of the cache
	 * @param initialCapacity initial capacity
	 */
	public CCache(final String name, final int initialCapacity)
	{
		this(name, initialCapacity, 120);
	}	// CCache

	/**
	 * Metasfresh Cache
	 *
	 * @param name (table) name of the cache
	 * @param initialCapacity initial capacity
	 * @param expireMinutes expire after minutes (0=no expire)
	 */
	public CCache(final String name, final int initialCapacity, final int expireMinutes)
	{
		this(name, // cache name
				null, // auto-detect tableName
				null, // additionalTableNamesToResetFor
				initialCapacity,
				expireMinutes,
				CacheMapType.HashMap,
				(CachingKeysMapper<K>)null,
				(CacheRemovalListener<K, V>)null);
	}

	@Builder
	protected CCache(
			final String cacheName,
			final String tableName,
			@Singular("additionalTableNameToResetFor") final Set<String> additionalTableNamesToResetFor,
			final Integer initialCapacity,
			final Integer expireMinutes,
			final CacheMapType cacheMapType,
			@Nullable final CachingKeysMapper<K> invalidationKeysMapper,
			@Nullable final CacheRemovalListener<K, V> removalListener)
	{
		this.cacheId = NEXT_CACHE_ID.getAndIncrement();

		this.invalidationKeysMapper = Optional.ofNullable(invalidationKeysMapper);

		final String tableNameEffective;
		if (cacheName == null)
		{
			if (tableName == null)
			{
				this.cacheName = "$NoCacheName$" + cacheId;
				tableNameEffective = "$NoTableName$" + cacheId;
			}
			else
			{
				this.cacheName = tableName;
				tableNameEffective = tableName;
			}
		}
		else // cacheName != null
		{
			this.cacheName = cacheName;

			if (tableName == null)
			{
				final String extractedTableName = extractTableNameForCacheName(cacheName);
				if (extractedTableName != null)
				{
					tableNameEffective = extractedTableName;
				}
				else
				{
					tableNameEffective = cacheName;
				}
			}
			else
			{
				tableNameEffective = tableName;
			}
		}

		this.labels = buildCacheLabels(tableNameEffective, additionalTableNamesToResetFor);

		this.expireMinutes = expireMinutes != null ? expireMinutes : EXPIREMINUTES_Never;
		this.cache = buildGuavaCache(
				cacheMapType != null ? cacheMapType : CacheMapType.HashMap,
				initialCapacity != null ? initialCapacity : 0,
				this.expireMinutes,
				removalListener);

		if (DEBUG)
		{
			final Exception ex = new Exception("Aquire stack trace");
			this.debugAquireStacktrace = Util.dumpStackTraceToString(ex);
			this.debugId = Integer.toHexString(System.identityHashCode(this));
		}
		else
		{
			this.debugAquireStacktrace = null; // N/A
			this.debugId = null; // N/A
		}

		//
		// Register it to CacheMgt
		CacheMgt.get().register(this);
	}	// CCache

	/**
	 * Extracts TableName from given <code>cacheName</code>.
	 *
	 * NOTE: we assume cacheName has following format: TableName#by#ColumnName1#ColumnName2...
	 *
	 * @param cacheName
	 * @return tableName or null
	 */
	// NOTE: public for testing
	public static final String extractTableNameForCacheName(final String cacheName)
	{
		if (cacheName == null || cacheName.isEmpty())
		{
			return null;
		}
		final int idx = cacheName.indexOf("#");
		if (idx <= 0)
		{
			return null;
		}

		final String tableName = cacheName.substring(0, idx).trim();
		if (tableName.isEmpty())
		{
			return null;
		}

		return tableName;
	}

	private static ImmutableSet<CacheLabel> buildCacheLabels(@NonNull final String tableName, final Set<String> additionalTableNamesToResetFor)
	{
		final ImmutableSet.Builder<CacheLabel> builder = ImmutableSet.<CacheLabel> builder();
		builder.add(CacheLabel.ofTableName(tableName));

		if (additionalTableNamesToResetFor != null)
		{
			additionalTableNamesToResetFor.stream()
					.map(CacheLabel::ofTableName)
					.forEach(builder::add);
		}

		return builder.build();
	}

	private static final <K, V> Cache<K, V> buildGuavaCache(
			@NonNull final CacheMapType cacheMapType,
			final int initialCapacity,
			final int expireMinutes,
			@Nullable final CacheRemovalListener<K, V> removalListener)
	{
		CacheBuilder<Object, Object> cacheBuilder = CacheBuilder.newBuilder();
		if (cacheMapType == CacheMapType.HashMap)
		{
			cacheBuilder = cacheBuilder
					.initialCapacity(initialCapacity);
		}
		else if (cacheMapType == CacheMapType.LRU)
		{
			cacheBuilder = cacheBuilder
					.maximumSize(initialCapacity); // FIXME: this is confusing
		}
		else
		{
			throw new AdempiereException("Unknown CacheMapType: " + cacheMapType);
		}

		if (expireMinutes > 0)
		{
			cacheBuilder = cacheBuilder.expireAfterWrite(expireMinutes, TimeUnit.MINUTES);
		}

		if (removalListener != null)
		{
			cacheBuilder.removalListener(notif -> {
				@SuppressWarnings("unchecked")
				final K key = (K)notif.getKey();

				@SuppressWarnings("unchecked")
				final V value = (V)notif.getValue();

				removalListener.itemRemoved(key, value);
			});
		}

		return cacheBuilder.build();
	}

	/**
	 * @return unique cache ID
	 */
	@Override
	public final long getCacheId()
	{
		return cacheId;
	}

	public final String getCacheName()
	{
		return cacheName;
	}	// getName

	@Override
	public Set<CacheLabel> getLabels()
	{
		return labels;
	}

	/**
	 * Cache was just reset.
	 *
	 * NOTE:
	 * <ul>
	 * <li>this flag is set to <code>false</code> after any change operation (e.g. {@link #put(Object, Object)})
	 * <li>this flag is set to <code>false</code> programatically by calling {@link #setUsed()}
	 * <li>this flag is set to <code>true</code> after {@link #reset()}
	 *
	 * @return true if it was just reset
	 */
	public final boolean isReset()
	{
		return m_justReset;
	}	// isReset

	/**
	 * Resets the Reset flag
	 *
	 * @see #isReset()
	 */
	public final void setUsed()
	{
		m_justReset = false;
	}	// setUsed

	/**
	 * Reset Cache.
	 *
	 * It is the same as calling {@link #clear()} but this method will return how many items were cleared.
	 *
	 * @return number of items cleared
	 * @see de.metas.cache.CacheInterface#reset()
	 */
	@Override
	public long reset()
	{
		final long no = cache.size();
		clear();
		if (no > 0)
		{
			logger.trace("Reset {} entries from {}", no, this);
		}
		return no;
	}

	private void clear()
	{
		// Clear
		cache.invalidateAll();
		cache.cleanUp();

		m_justReset = true;
	}	// clear

	@Override
	public long resetForRecordId(@NonNull final TableRecordReference recordRef)
	{
		if (!invalidationKeysMapper.isPresent())
		{
			// NOTE: reseting only by "key" is not supported, so we are reseting everything
			return reset();
		}

		return resetForRecordIdUsingKeysMapper(recordRef, invalidationKeysMapper.get());
	}

	private long resetForRecordIdUsingKeysMapper(
			@NonNull final TableRecordReference recordRef,
			@NonNull final CachingKeysMapper<K> keysMapper)
	{
		if (keysMapper.isResetAll(recordRef))
		{
			return reset();
		}

		long counter = 0; // note that also the "reset-all" reset() method only returns an approx number.
		for (final K key : keysMapper.computeCachingKeys(recordRef))
		{
			final boolean keyRemoved = remove(key) != null;
			if (keyRemoved)
			{
				counter++;
			}
		}
		return counter;
	}

	@Override
	public String toString()
	{
		final StringBuilder sb = new StringBuilder("CCache[")
				.append(cacheName)
				.append(", size").append(cache.size())
				.append(", id=").append(cacheId);

		if (DEBUG)
		{
			sb.append("\ncacheId=").append(debugId);
			sb.append("\n").append(this.debugAquireStacktrace);
		}

		sb.append("]");

		return sb.toString();
	}

	/**
	 * @see java.util.Map#containsKey(java.lang.Object)
	 */
	public boolean containsKey(final K key)
	{
		return cache.getIfPresent(key) != null;
	}	// containsKey

	public V remove(final K key)
	{
		final V value = cache.getIfPresent(key);
		cache.invalidate(key);
		return value;
	}

	public void removeAll(final Iterable<K> keys)
	{
		cache.invalidateAll(keys);
	}

	/**
	 * @see java.util.Map#get(java.lang.Object)
	 */
	@Nullable public V get(final K key)
	{
		return cache.getIfPresent(key);
	}	// get

	/**
	 * Gets cached value by <code>key</code>.
	 *
	 * For more informations, see {@link #get(Object, Callable)}.
	 *
	 * @param key
	 * @param valueInitializer
	 * @return cached value
	 */
	public V get(final K key, final Supplier<V> valueInitializer)
	{
		if (valueInitializer == null)
		{
			return cache.getIfPresent(key);
		}

		return get(key, new Callable<V>()
		{
			@Override
			public String toString()
			{
				return "Callable[" + valueInitializer + "]";
			}

			@Override
			public V call() throws Exception
			{
				return valueInitializer.get();
			}
		});
	}

	/**
	 * Gets cached value by <code>key</code>.
	 *
	 * If value is not present in case it will try to initialize it by using <code>valueInitializer</code>.
	 *
	 * If the <code>valueInitializer</code> returns null then this method will return <code>null</code> and the value will NOT be cached.
	 *
	 * @param key
	 * @param valueInitializer optional cache initializer.
	 * @return cached value or <code>null</code>
	 */
	public V get(final K key, final Callable<V> valueInitializer)
	{
		if (valueInitializer == null)
		{
			return cache.getIfPresent(key);
		}

		try
		{
			return cache.get(key, valueInitializer);
		}
		catch (final InvalidCacheLoadException e)
		{
			// Exception thrown when the Callable returns null
			// We can safely ignore it and return null.
			// The value was not cached.
			return null;
		}
		catch (final ExecutionException e)
		{
			throw AdempiereException.wrapIfNeeded(e);
		}
		catch (final UncheckedExecutionException e)
		{
			throw (RuntimeException)e.getCause();
		}
		catch (final ExecutionError e)
		{
			throw (Error)e.getCause();
		}
	}

	/**
	 * Same as {@link #get(Object, Callable)}. Introduced here to be able to use it with lambdas, without having ambiguous method calls.
	 *
	 * @param key
	 * @param valueLoader
	 * @return
	 * @see #get(Object, Callable).
	 * @see #get(Object, Supplier)
	 */
	public V getOrLoad(final K key, final Callable<V> valueLoader)
	{
		return get(key, valueLoader);
	}

	public V getOrLoad(final K key, @NonNull final Function<K, V> valueLoader)
	{
		final Callable<V> callable = () -> valueLoader.apply(key);
		return get(key, callable);
	}

	/**
	 * Gets all values which are identified by given keys.
	 *
	 * For those keys which were not found in cache, the <code>valuesLoader</code> will be called <b>one time</b> with all missing keys.
	 * The expected return of <code>valuesLoader</code> is a key/value map of all those values loaded.
	 *
	 * The values which were just loaded will be also added to cache.
	 *
	 * @param keys
	 * @param valuesLoader
	 * @return values (IMPORTANT: order is not guaranteed)
	 */
	public Collection<V> getAllOrLoad(final Collection<K> keys, final Function<Collection<K>, Map<K, V>> valuesLoader)
	{
		if (keys.isEmpty())
		{
			return ImmutableList.of();
		}

		//
		// Fetch from cache what's available
		final List<V> values = new ArrayList<>(keys.size());
		final Set<K> keysToLoad = new HashSet<>();
		for (final K key : ImmutableSet.copyOf(keys))
		{
			final V value = cache.getIfPresent(key);
			if (value == null)
			{
				keysToLoad.add(key);
			}
			else
			{
				values.add(value);
			}
		}

		//
		// Load the missing keys if any
		if (!keysToLoad.isEmpty())
		{
			final Map<K, V> valuesLoaded = valuesLoader.apply(keysToLoad);
			valuesLoaded.forEach(cache::put); // add loaded values to cache
			values.addAll(valuesLoaded.values()); // add loaded values to the list we will return
		}

		//
		return values;
	}

	/**
	 * Return the value, if present, otherwise throw an exception to be created by the provided supplier.
	 *
	 * @param key
	 * @param exceptionSupplier
	 * @return value; not null
	 * @throws E
	 */
	public <E extends Throwable> V getOrElseThrow(final K key, final Supplier<E> exceptionSupplier) throws E
	{
		final V value = get(key);
		if (value == null)
		{
			throw exceptionSupplier.get();
		}
		return value;
	}

	/**
	 * Put value
	 *
	 * @param key key
	 * @param value value
	 * @return previous value
	 */
	public void put(final K key, final V value)
	{
		m_justReset = false;
		if (value == null)
		{
			cache.invalidate(key);
		}
		else
		{
			cache.put(key, value);
		}
	}	// put

	/**
	 * Add all key/value pairs to this cache.
	 *
	 * @param map key/value pairs
	 */
	public void putAll(final Map<? extends K, ? extends V> map)
	{
		cache.putAll(map);
	}

	/**
	 * @see java.util.Map#isEmpty()
	 */
	public boolean isEmpty()
	{
		return cache.size() == 0;
	}	// isEmpty

	/**
	 * @see java.util.Map#keySet()
	 */
	public Set<K> keySet()
	{
		return cache.asMap().keySet();
	}	// keySet

	/**
	 * @see java.util.Map#size()
	 */
	@Override
	public long size()
	{
		return cache.size();
	}	// size

	/**
	 * @see java.util.Map#values()
	 */
	public Collection<V> values()
	{
		return cache.asMap().values();
	}	// values

	@Override
	protected final void finalize() throws Throwable
	{
		// NOTE: to avoid memory leaks we need to programatically clear our internal state

		if (cache != null)
		{
			cache.invalidateAll();
		}
	}

	// /**
	// * Adds an additional table which when the cache is reset for that one, it also shall reset this cache.
	// *
	// * @param tableName
	// * @return this
	// */
	// public CCache<K, V> addResetForTableName(final String tableName)
	// {
	// Check.assumeNotEmpty(tableName, "tableName not empty");
	// CacheMgt.get().addCacheResetListener(tableName, new ICacheResetListener()
	// {
	// @Override
	// public int reset(final CacheInvalidateMultiRequest multiRequest)
	// {
	// if (!multiRequest.matchesTableNameEffective(tableName))
	// {
	// return 0;
	// }
	//
	// return CCache.this.reset();
	// }
	//
	// @Override
	// public String toString()
	// {
	// return "->" + CCache.this.toString();
	// }
	// });
	//
	// return this;
	// }

	/**
	 * @return cache statistics
	 */
	public CCacheStats stats()
	{
		return new CCacheStats(cacheId, cacheName, cache.size(), cache.stats());
	}

	@SuppressWarnings("serial")
	public static final class CCacheStats implements Serializable
	{
		// NOTE: must be Json serializable!!!

		private final long cacheId;
		private final String name;
		private final long size;
		private final CacheStats guavaStats;

		private CCacheStats(final long cacheId, final String name, final long size, final CacheStats guavaStats)
		{
			super();
			this.cacheId = cacheId;
			this.name = name;
			this.size = size;
			this.guavaStats = guavaStats;
		}

		@Override
		public String toString()
		{
			return MoreObjects.toStringHelper(this)
					.add("name", name)
					.add("size", size)
					.add("guavaStats", guavaStats)
					.add("cacheId", cacheId)
					.toString();
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(cacheId, name, size, guavaStats);
		}

		@Override
		public boolean equals(final Object obj)
		{
			if (obj instanceof CCacheStats)
			{
				final CCacheStats other = (CCacheStats)obj;
				return cacheId == other.cacheId
						&& name.equals(other.name)
						&& size == other.size
						&& guavaStats.equals(other.guavaStats);
			}
			return false;
		}

		public long getCacheId()
		{
			return cacheId;
		}

		public String getName()
		{
			return name;
		}

		public long getSize()
		{
			return size;
		}

		public CacheStats getGuavaStats()
		{
			return guavaStats;
		}
	}
}	// CCache
