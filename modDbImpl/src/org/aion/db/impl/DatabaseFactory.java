package org.aion.db.impl;

import java.util.Properties;
import org.aion.db.generic.DatabaseWithCache;
import org.aion.db.generic.LockedDatabase;
import org.aion.db.generic.SpecialLockedDatabase;
import org.aion.db.generic.TimedDatabase;
import org.aion.db.impl.h2.H2MVMap;
import org.aion.db.impl.leveldb.LevelDB;
import org.aion.db.impl.leveldb.LevelDBConstants;
import org.aion.db.impl.mockdb.MockDB;
import org.aion.db.impl.mockdb.PersistentMockDB;
import org.aion.db.impl.mongodb.MongoDB;
import org.aion.db.impl.rocksdb.RocksDBConstants;
import org.aion.db.impl.rocksdb.RocksDBWrapper;
import org.aion.interfaces.db.ByteArrayKeyValueDatabase;
import org.aion.log.AionLoggerFactory;
import org.aion.log.LogEnum;
import org.slf4j.Logger;

/**
 * Returns an instance of {@link ByteArrayKeyValueDatabase} based on the given properties.
 *
 * @author Alexandra Roatis
 */
public abstract class DatabaseFactory {

    private static final Logger LOG = AionLoggerFactory.getLogger(LogEnum.DB.name());

    public static class Props {
        public static final String DB_TYPE = "db_type";

        public static final String DB_NAME = "db_name";
        public static final String DB_PATH = "db_path";

        public static final String CHECK_INTEGRITY = "check_integrity";
        public static final String PERSISTENT = "persistent";

        public static final String ENABLE_AUTO_COMMIT = "enable_auto_commit";
        public static final String ENABLE_DB_CACHE = "enable_db_cache";
        public static final String ENABLE_DB_COMPRESSION = "enable_db_compression";
        public static final String DB_CACHE_SIZE = "cache_size";

        public static final String ENABLE_HEAP_CACHE = "enable_heap_cache";
        public static final String ENABLE_HEAP_CACHE_STATS = "enable_heap_cache_stats";
        public static final String MAX_HEAP_CACHE_SIZE = "max_heap_cache_size";

        public static final String ENABLE_LOCKING = "enable_locking";

        public static final String MAX_FD_ALLOC = "max_fd_alloc_size";
        public static final String BLOCK_SIZE = "block_size";

        public static final String WRITE_BUFFER_SIZE = "write_buffer_size";
        public static final String READ_BUFFER_SIZE = "read_buffer_size";
    }

    public static ByteArrayKeyValueDatabase connect(Properties info) {
        return connect(info, false);
    }

    public static ByteArrayKeyValueDatabase connect(Properties info, boolean debug) {

        DBVendor dbType = DBVendor.fromString(info.getProperty(Props.DB_TYPE));
        ByteArrayKeyValueDatabase db;

        if (dbType == DBVendor.UNKNOWN) {
            // the driver, if correct should check path and name
            db = connect(info.getProperty(Props.DB_TYPE), info);
        } else {

            boolean enableLocking = getBoolean(info, Props.ENABLE_LOCKING);

            // first check for locking
            if (enableLocking) {
                db = connectWithLocks(info);
            } else {
                // next check for heap cache
                if (getBoolean(info, Props.ENABLE_HEAP_CACHE)) {
                    db = connectWithCache(info);
                } else {
                    db = connectBasic(info);
                }
            }
        }

        // time operations during debug
        if (debug) {
            return new TimedDatabase(db);
        } else {
            return db;
        }
    }

    /**
     * If enabled, the topmost database will be the one enforcing the locking functionality.
     *
     * @return A database implementation with read-write locks.
     */
    private static ByteArrayKeyValueDatabase connectWithLocks(Properties info) {
        boolean enableHeapCache = getBoolean(info, Props.ENABLE_HEAP_CACHE);
        if (enableHeapCache) {
            return new LockedDatabase(connectWithCache(info));
        } else {
            DBVendor vendor = DBVendor.fromString(info.getProperty(Props.DB_TYPE));
            if (vendor == DBVendor.LEVELDB || vendor == DBVendor.ROCKSDB) {
                return new SpecialLockedDatabase(connectBasic(info));
            } else {
                return new LockedDatabase(connectBasic(info));
            }
        }
    }

    /** @return A database implementation with a caching layer. */
    private static ByteArrayKeyValueDatabase connectWithCache(Properties info) {
        boolean enableAutoCommit = getBoolean(info, Props.ENABLE_AUTO_COMMIT);
        return new DatabaseWithCache(
                connectBasic(info),
                enableAutoCommit,
                info.getProperty(Props.MAX_HEAP_CACHE_SIZE),
                getBoolean(info, Props.ENABLE_HEAP_CACHE_STATS));
    }

    /** @return A database implementation for each of the vendors in {@link DBVendor}. */
    private static AbstractDB connectBasic(Properties info) {
        DBVendor dbType = DBVendor.fromString(info.getProperty(Props.DB_TYPE));

        String dbName = info.getProperty(Props.DB_NAME);

        if (dbType == DBVendor.MOCKDB) {
            // MockDB does not require name and path checks
            LOG.warn("WARNING: Active vendor is set to MockDB, data will not persist!");
            return new MockDB(dbName);
        }

        String dbPath = info.getProperty(Props.DB_PATH);

        if (dbType == DBVendor.PERSISTENTMOCKDB) {
            LOG.warn(
                    "WARNING: Active vendor is set to PersistentMockDB, data will be saved only at close!");
            return new PersistentMockDB(dbName, dbPath);
        }

        boolean enableDbCache = getBoolean(info, Props.ENABLE_DB_CACHE);
        boolean enableDbCompression = getBoolean(info, Props.ENABLE_DB_COMPRESSION);

        // ensure not null name for other databases
        if (dbName == null) {
            LOG.error("Please provide a database name value that is not null.");
            return null;
        }

        // ensure not null path for other databases
        if (dbPath == null) {
            LOG.error("Please provide a database path value that is not null.");
            return null;
        }

        // select database implementation
        switch (dbType) {
            case LEVELDB:
                {
                    return new LevelDB(
                            dbName,
                            dbPath,
                            enableDbCache,
                            enableDbCompression,
                            getInt(info, Props.MAX_FD_ALLOC, LevelDBConstants.MAX_OPEN_FILES),
                            getInt(info, Props.BLOCK_SIZE, LevelDBConstants.BLOCK_SIZE),
                            getInt(
                                    info,
                                    Props.WRITE_BUFFER_SIZE,
                                    LevelDBConstants.WRITE_BUFFER_SIZE),
                            getInt(info, Props.DB_CACHE_SIZE, LevelDBConstants.CACHE_SIZE));
                }
            case ROCKSDB:
                {
                    return new RocksDBWrapper(
                            dbName,
                            dbPath,
                            enableDbCache,
                            enableDbCompression,
                            getInt(info, Props.MAX_FD_ALLOC, RocksDBConstants.MAX_OPEN_FILES),
                            getInt(info, Props.BLOCK_SIZE, RocksDBConstants.BLOCK_SIZE),
                            getInt(
                                    info,
                                    Props.WRITE_BUFFER_SIZE,
                                    RocksDBConstants.WRITE_BUFFER_SIZE),
                            getInt(info, Props.READ_BUFFER_SIZE, RocksDBConstants.READ_BUFFER_SIZE),
                            getInt(info, Props.DB_CACHE_SIZE, RocksDBConstants.CACHE_SIZE));
                }
            case H2:
                {
                    return new H2MVMap(dbName, dbPath, enableDbCache, enableDbCompression);
                }
            case MONGODB:
                {
                    return new MongoDB(dbName, dbPath);
                }
            default:
                break;
        }

        LOG.error("Invalid database type provided: {}", dbType);
        return null;
    }

    /**
     * @return A database implementation based on a driver implementing the {@link IDriver}
     *     interface.
     */
    public static ByteArrayKeyValueDatabase connect(String driverName, Properties info) {
        try {
            // see if the given name is a valid driver
            IDriver driver =
                    ((Class<? extends IDriver>) Class.forName(driverName))
                            .getDeclaredConstructor()
                            .newInstance();
            // return a connection
            return driver.connect(info);
        } catch (Exception e) {
            LOG.error("Could not load database driver.", e);
        }

        LOG.error("Invalid database driver provided: {}", driverName);
        return null;
    }

    /** @return A mock database. */
    public static ByteArrayKeyValueDatabase connect(String _dbName) {
        return new MockDB(_dbName);
    }

    private static boolean getBoolean(Properties info, String prop) {
        return Boolean.parseBoolean(info.getProperty(prop));
    }

    private static int getInt(Properties info, String prop, int defaultValue) {
        return Integer.parseInt(info.getProperty(prop, String.valueOf(defaultValue)));
    }
}
