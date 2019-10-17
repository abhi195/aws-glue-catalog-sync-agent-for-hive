package com.amazonaws.services.glue.catalog;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.MetaStoreEventListener;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.events.AddPartitionEvent;
import org.apache.hadoop.hive.metastore.events.CreateTableEvent;
import org.apache.hadoop.hive.metastore.events.DropPartitionEvent;
import org.apache.hadoop.hive.metastore.events.DropTableEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HiveGlueCatalogSyncAgent extends MetaStoreEventListener {
    private static final Logger LOG = LoggerFactory.getLogger(HiveGlueCatalogSyncAgent.class);
    private static final String GLUE_CATALOG_DROP_TABLE_IF_EXISTS = "glue.catalog.dropTableIfExists";
    private static final String GLUE_CATALOG_CREATE_MISSING_DB = "glue.catalog.createMissingDB";
    private static final String GLUE_CATALOG_USER_KEY = "glue.catalog.user.key";
    private final String ATHENA_JDBC_URL = "glue.catalog.athena.jdbc.url";
    private static final String GLUE_CATALOG_USER_SECRET = "glue.catalog.user.secret";
    private static final String GLUE_CATALOG_S3_STAGING_DIR = "glue.catalog.athena.s3.staging.dir";
    private static final String SUPPRESS_ALL_DROP_EVENTS = "glue.catalog.athena.suppressAllDropEvents";
    private static final String DEFAULT_ATHENA_CONNECTION_URL = "jdbc:awsathena://athena.us-east-1.amazonaws.com:443";
    private static final String GLUE_CATALOG_DB_TO_SYNC = "glue.catalog.db.whitelist";
    private static final String SLACK_NOTIFICATION_WEBHOOK = "slack.notify.webhook";

    private Configuration config = null;
    private Properties info;
    private String athenaURL;
    private Thread queueProcessor;
    private Connection athenaConnection;
    private volatile ConcurrentLinkedQueue<String> ddlQueue;
    private final String EXTERNAL_TABLE_TYPE = "EXTERNAL_TABLE";
    private final String MANAGED_TABLE_TYPE = "MANAGED_TABLE";
    private boolean dropTableIfExists = false;
    private boolean createMissingDB = true;
    private int noEventSleepDuration;
    private int reconnectSleepDuration;
    private boolean suppressAllDropEvents = false;
    private Set<String> dbToSync;
    private SlackNotify slackNotify;

    /**
     * Private class to cleanup the sync agent - to be used in a Runtime shutdown
     * hook
     *
     * @author meyersi
     */
    private final class SyncAgentShutdownRoutine implements Runnable {
        private AthenaQueueProcessor p;

        protected SyncAgentShutdownRoutine(AthenaQueueProcessor queueProcessor) {

            this.p = queueProcessor;
        }

        public void run() {
            // stop the queue processing thread
            p.stop();
        }
    }

    /**
     * Private class which processes the ddl queue and pushes the ddl through
     * Athena. If the Athena connection is broken, try and reconnect, and if not
     * then back off for a period of time and hope that the connection is fixed
     *
     * @author meyersi
     */
    private final class AthenaQueueProcessor implements Runnable {
        private boolean run = true;
        private CloudWatchLogsReporter cwlr;
        private final Pattern PATTERN = Pattern.compile("(?i)CREATE EXTERNAL TABLE (.*).");

        public AthenaQueueProcessor(Configuration config) {
            super();
            this.cwlr = new CloudWatchLogsReporter(config);
        }

        /**
         * Method to send a shutdown message to the queue processor
         */
        public void stop() {
            LOG.info(String.format("Stopping %s", this.getClass().getCanonicalName()));
            try {
                athenaConnection.close();
            } catch (SQLException e) {
                LOG.error(e.getMessage());
            }
            this.run = false;
        }

        public void run() {
            // run forever or until stop is called, and continue running until the queue is
            // empty when stop is called
            while (true) {
                if (!ddlQueue.isEmpty()) {
                    String query = ddlQueue.poll();

                    boolean fatalError = false;
                    LOG.info("Working on " + query);
                    // Exception logic: if it's a network issue keep retrying. Anything else log to
                    // CWL and move on.
                    boolean completed = false;
                    String slackMsg = "n/a";
                    while (!completed && !fatalError) {
                        try {
                            Statement athenaStmt = athenaConnection.createStatement();
                            cwlr.sendToCWL("Trying to execute: " + query);
                            athenaStmt.execute(query);
                            athenaStmt.close();
                            completed = true;
                            slackMsg = formatSlackMsg(null,  true, query);
                        } catch (Exception e) {
                            if (e instanceof SQLRecoverableException || e instanceof SQLTimeoutException) {
                                try {
                                    configureAthenaConnection();
                                } catch (SQLException e1) {
                                    // this will probably be because we can't open the connection
                                    try {
                                        Thread.sleep(reconnectSleepDuration);
                                    } catch (InterruptedException e2) {
                                        e2.printStackTrace();
                                    }
                                }
                            } else {
                                // Athena's JDBC Driver just throws a generic SQLException
                                // Only way to identify exception type is through string parsing :O=
                                if (e.getMessage().contains("AlreadyExistsException") && dropTableIfExists) {
                                    Matcher matcher = PATTERN.matcher(query);
                                    matcher.find();
                                    String tableName = matcher.group(1);
                                    String preRequiredDropQuery  = "n/a";
                                    try {
                                        cwlr.sendToCWL("Dropping table " + tableName);
                                        preRequiredDropQuery = "DROP TABLE " + tableName + ";";
                                        Statement athenaStmt = athenaConnection.createStatement();
                                        athenaStmt.execute("drop table " + tableName);
                                        cwlr.sendToCWL("Creating table " + tableName + " after dropping ");
                                        athenaStmt.execute(query);
                                        athenaStmt.close();
                                        completed = true;
                                        slackMsg = formatSlackMsg(null, true, preRequiredDropQuery, query);
                                    } catch (Exception e2) {
                                        cwlr.sendToCWL("Unable to drop and recreate  " + tableName);
                                        cwlr.sendToCWL("ERROR: " + e.getMessage());
                                        slackMsg = formatSlackMsg(e.getMessage(), false, preRequiredDropQuery, query);
                                        fatalError = true;
                                    }
                                } else if (e.getMessage().contains("Database does not exist:") && createMissingDB) {
                                    String preRequriedCreateDbQuery = "n/a";
                                    try {
                                        String dbName = e.getMessage().split(":")[3].trim();
                                        cwlr.sendToCWL("Trying to create database  " + dbName);
                                        preRequriedCreateDbQuery = "CREATE DATABASE IF NOT EXISTS " + dbName + ";";
                                        Statement athenaStmt = athenaConnection.createStatement();
                                        athenaStmt.execute("Create database if not exists " + dbName);
                                        cwlr.sendToCWL("Retrying table creation:" + query);
                                        athenaStmt.execute(query);
                                        athenaStmt.close();
                                        completed = true;
                                        slackMsg = formatSlackMsg(null, true, preRequriedCreateDbQuery, query);
                                    } catch (Throwable e2) {
                                        LOG.info("ERROR: " + e.getMessage());
                                        LOG.info("DB doesn't exist for: " + query);
                                        slackMsg = formatSlackMsg(e.getMessage(), false, preRequriedCreateDbQuery, query);
                                        fatalError = true;
                                    }
                                } else {
                                    LOG.info("Unable to complete query: " + query);
                                    LOG.info("ERROR: " + e.getMessage());
                                    slackMsg = formatSlackMsg(e.getMessage(), false, query);
                                    fatalError = true;
                                }
                            }
                        }
                    }
                    slackNotify.sendNotification(slackMsg);

                } else {
                    // put the thread to sleep for a configured duration
                    try {
                        LOG.debug(String.format("DDL Queue is empty. Sleeping for %s, queue state is %s",
                                noEventSleepDuration, ddlQueue.size()));
                        Thread.sleep(noEventSleepDuration);
                    } catch (InterruptedException e) {
                        LOG.error(e.getMessage());
                    }
                }
            }
        }
    }

    public HiveGlueCatalogSyncAgent(final Configuration conf) throws Exception {
        super(conf);
        this.config = conf;

        String noopSleepDuration = this.config.get("no-event-sleep-duration");
        if (noopSleepDuration == null) {
            this.noEventSleepDuration = 1000;
        } else {
            this.noEventSleepDuration = new Integer(noopSleepDuration).intValue();
        }

        String reconnectSleepDuration = conf.get("reconnect-failed-sleep-duration");
        if (reconnectSleepDuration == null) {
            this.reconnectSleepDuration = 1000;
        } else {
            this.reconnectSleepDuration = new Integer(noopSleepDuration).intValue();
        }

        this.info = new Properties();
        this.info.put("log_path", "/tmp/jdbc.log");
        this.info.put("log_level", "ERROR");
        this.info.put("s3_staging_dir", config.get(GLUE_CATALOG_S3_STAGING_DIR));

        dropTableIfExists = config.getBoolean(GLUE_CATALOG_DROP_TABLE_IF_EXISTS, false);
        createMissingDB = config.getBoolean(GLUE_CATALOG_CREATE_MISSING_DB, true);
        suppressAllDropEvents = config.getBoolean(SUPPRESS_ALL_DROP_EVENTS, false);
        this.athenaURL = conf.get(ATHENA_JDBC_URL, DEFAULT_ATHENA_CONNECTION_URL);

        if (config.get(GLUE_CATALOG_USER_KEY) != null) {
            info.put("user", config.get(GLUE_CATALOG_USER_KEY));
            info.put("password", config.get(GLUE_CATALOG_USER_SECRET));
        } else {
            this.info.put("aws_credentials_provider_class",
                    com.amazonaws.auth.InstanceProfileCredentialsProvider.class.getName());
        }

        ddlQueue = new ConcurrentLinkedQueue<>();

        dbToSync = new HashSet<>();
        String dbToSyncString = config.get(GLUE_CATALOG_DB_TO_SYNC);
        if(StringUtils.isNotEmpty(dbToSyncString)) {
            String[] dbToSyncArr = dbToSyncString.trim().split(",");
            for(String db : dbToSyncArr) {
                dbToSync.add(db.trim());
            }
        }

        String slackWebhook = config.get(SLACK_NOTIFICATION_WEBHOOK
                ,"https://hooks.slack.com/services/default");
        this.slackNotify = new SlackNotify(slackWebhook);

        StringBuilder syncAgentStartSlackMsg = new StringBuilder();
        syncAgentStartSlackMsg.append("*Starting Hive-Glue Sync Agent*");
        syncAgentStartSlackMsg.append("\n");
        syncAgentStartSlackMsg.append("Properties : ");
        syncAgentStartSlackMsg.append("\n");
        syncAgentStartSlackMsg.append("```");
        syncAgentStartSlackMsg.append("\n");
        syncAgentStartSlackMsg.append(ATHENA_JDBC_URL + " : " + athenaURL);
        syncAgentStartSlackMsg.append("\n");
        syncAgentStartSlackMsg.append(GLUE_CATALOG_S3_STAGING_DIR + " : " + config.get(GLUE_CATALOG_S3_STAGING_DIR));
        syncAgentStartSlackMsg.append("\n");
        syncAgentStartSlackMsg.append(GLUE_CATALOG_DROP_TABLE_IF_EXISTS + " : " + dropTableIfExists);
        syncAgentStartSlackMsg.append("\n");
        syncAgentStartSlackMsg.append(GLUE_CATALOG_CREATE_MISSING_DB + " : " + createMissingDB);
        syncAgentStartSlackMsg.append("\n");
        syncAgentStartSlackMsg.append(SUPPRESS_ALL_DROP_EVENTS + " : " + suppressAllDropEvents);
        syncAgentStartSlackMsg.append("\n");
        syncAgentStartSlackMsg.append(GLUE_CATALOG_DB_TO_SYNC + " : " + dbToSyncString);
        syncAgentStartSlackMsg.append("\n");
        syncAgentStartSlackMsg.append("```");

        slackNotify.sendNotification(syncAgentStartSlackMsg.toString());

        configureAthenaConnection();

        // start the queue processor thread
        AthenaQueueProcessor athenaQueueProcessor = new AthenaQueueProcessor(this.config);
        queueProcessor = new Thread(athenaQueueProcessor, "GlueSyncThread");
        queueProcessor.start();

        // add a shutdown hook to close the connections
        Runtime.getRuntime()
                .addShutdownHook(new Thread(new SyncAgentShutdownRoutine(athenaQueueProcessor), "Shutdown-thread"));

        LOG.info(String.format("%s online, connected to %s", this.getClass().getCanonicalName(), this.athenaURL));
    }

    private final void configureAthenaConnection() throws SQLException, SQLTimeoutException {
        LOG.info(String.format("Connecting to Amazon Athena using endpoint %s", this.athenaURL));
        athenaConnection = DriverManager.getConnection(this.athenaURL, this.info);
    }

    private String formatSlackMsg(String errorMsg, boolean isSuccess, String ... queries) {

        StringBuilder formattedSlackMsg = new StringBuilder();

        formattedSlackMsg.append(isSuccess ? "*Sync result* : :white_check_mark:" : "Sync result : :x: \n*Error* : " + errorMsg);
        formattedSlackMsg.append("\n");
        formattedSlackMsg.append("*Query* : ");
        formattedSlackMsg.append("\n");
        formattedSlackMsg.append("```");
        for(String query : queries) {
            formattedSlackMsg.append(query);
            formattedSlackMsg.append("\n");
        }
        formattedSlackMsg.append("```");


        return formattedSlackMsg.toString();
    }

    private boolean addToAthenaQueue(String query) {
        try {
            ddlQueue.add(query);
        } catch (Exception e) {
            LOG.error(e.getMessage());
            return false;
        }
        return true;
    }

    /** Return the fully qualified table name for a table */
    private String getFqtn(Table table) {
        return table.getDbName() + "." + table.getTableName();
    }

    /** Return true if sync of a db is whitelisted else false*/
    private boolean toSyncDB(Table table) {
        return dbToSync.contains(table.getDbName());
    }

    /**
     * function to extract and return the partition specification for a given spec,
     * in format of (name=value, name=value)
     */
    private String getPartitionSpec(Table table, Partition partition) {
        String partitionSpec = "";

        for (int i = 0; i < table.getPartitionKeysSize(); ++i) {
            FieldSchema p = table.getPartitionKeys().get(i);

            String specAppend;
            if (p.getType().equals("string")) {
                // add quotes to appended value
                specAppend = "'" + partition.getValues().get(i) + "'";
            } else {
                // don't quote the appended value
                specAppend = partition.getValues().get(i);
            }

            partitionSpec += p.getName() + "=" + specAppend + ",";
        }
        return StringUtils.stripEnd(partitionSpec, ",");
    }

    /**
     * Handler for a Drop Table event
     */
    public void onDropTable(DropTableEvent tableEvent) throws MetaException {
        super.onDropTable(tableEvent);

        if (!suppressAllDropEvents) {

            Table table = tableEvent.getTable();
            String ddl = "";

            if (toSyncDB(table)) {

                if ((table.getTableType().equals(EXTERNAL_TABLE_TYPE) || table.getTableType().equals(MANAGED_TABLE_TYPE))
                        && table.getSd().getLocation().startsWith("s3")) {
                    ddl = String.format("drop table if exists %s", getFqtn(table));

                    if (!addToAthenaQueue(ddl)) {
                        LOG.error("Failed to add the DropTable event to the processing queue");
                    } else {
                        LOG.debug(String.format("Requested Drop of table: %s", table.getTableName()));
                    }
                }
            }
        } else {
            LOG.debug(String.format("Ignoring DropTable event as %s set to True", SUPPRESS_ALL_DROP_EVENTS));
        }
    }

    /**
     * Handler for a CreateTable Event
     */
    public void onCreateTable(CreateTableEvent tableEvent) throws MetaException {
        super.onCreateTable(tableEvent);

        Table table = tableEvent.getTable();
        String ddl = "";

        if (toSyncDB(table)) {

            if ((table.getTableType().equals(EXTERNAL_TABLE_TYPE) || table.getTableType().equals(MANAGED_TABLE_TYPE))
                    && table.getSd().getLocation().startsWith("s3")) {
                try {
                    ddl = HiveUtils.showCreateTable(tableEvent.getTable());
                    LOG.info("The table: " + ddl);

                    if (!addToAthenaQueue(ddl)) {
                        LOG.error("Failed to add the CreateTable event to the processing queue");
                    } else {
                        LOG.debug(String.format("Requested replication of %s to AWS Glue Catalog.", table.getTableName()));
                    }
                } catch (Exception e) {
                    LOG.error("Unable to get current Create Table statement for replication:" + e.getMessage());
                }
            }
        }

    }

    /**
     * Handler for an AddPartition event
     */
    public void onAddPartition(AddPartitionEvent partitionEvent) throws MetaException {
        super.onAddPartition(partitionEvent);

        if (partitionEvent.getStatus()) {
            Table table = partitionEvent.getTable();

            if (toSyncDB(table)) {

                if ((table.getTableType().equals(EXTERNAL_TABLE_TYPE) || table.getTableType().equals(MANAGED_TABLE_TYPE))
                        && table.getSd().getLocation().startsWith("s3")) {
                    String fqtn = getFqtn(table);

                    if (fqtn != null && !fqtn.equals("")) {
                        List<Partition> partitionList = partitionEvent.getPartitions();
                        partitionList.iterator().forEachRemaining(p -> {
                            String partitionSpec = getPartitionSpec(table, p);

                            if (p.getSd().getLocation().startsWith("s3")) {
                                String addPartitionDDL = String.format(
                                        "alter table %s add if not exists partition(%s) location '%s'", fqtn, partitionSpec,
                                        p.getSd().getLocation().replaceFirst("s3[a,n]://", "s3://"));
                                if (!addToAthenaQueue(addPartitionDDL)) {
                                    LOG.error("Failed to add the AddPartition event to the processing queue");
                                }
                            } else {
                                LOG.debug(String.format("Not adding partition (%s) as it is not S3 based (location %s)",
                                        partitionSpec, p.getSd().getLocation()));
                            }
                        });
                    }
                } else {
                    LOG.debug(String.format("Ignoring Add Partition Event for Table %s as it is not stored on S3",
                            table.getTableName()));
                }
            }
        }
    }

    /**
     * Handler to deal with partition drop events. Receives a single partition drop
     * event and drops all partitions included in the event.
     */
    public void onDropPartition(DropPartitionEvent partitionEvent) throws MetaException {
        super.onDropPartition(partitionEvent);
        if (!suppressAllDropEvents) {
            if (partitionEvent.getStatus()) {
                Table table = partitionEvent.getTable();

                if (toSyncDB(table)) {

                    if ((table.getTableType().equals(EXTERNAL_TABLE_TYPE) || table.getTableType().equals(MANAGED_TABLE_TYPE))
                            && table.getSd().getLocation().startsWith("s3")) {
                        String fqtn = getFqtn(table);

                        if (fqtn != null && !fqtn.equals("")) {
                            List<Partition> partitionList = Arrays.asList(partitionEvent.getPartition());
                            partitionList.iterator().forEachRemaining(p -> {
                                String partitionSpec = getPartitionSpec(table, p);

                                if (p.getSd().getLocation().startsWith("s3")) {
                                    String ddl = String.format("alter table %s drop if exists partition(%s);", fqtn,
                                            partitionSpec);

                                    if (!addToAthenaQueue(ddl)) {
                                        LOG.error(String.format(
                                                "Failed to add the DropPartition event to the processing queue for specification %s",
                                                partitionSpec));
                                    } else {
                                        LOG.debug(String.format("Requested Drop of Partition with Specification (%s)",
                                                partitionSpec));
                                    }
                                } else {
                                    LOG.debug(
                                            String.format("Not dropping partition (%s) as it is not S3 based (location %s)",
                                                    partitionSpec, p.getSd().getLocation()));
                                }
                            });
                        }
                    } else {
                        LOG.debug(String.format("Ignoring Drop Partition Event for Table %s as it is not stored on S3",
                                table.getTableName()));
                    }
                }
            }
        } else {
            LOG.debug(String.format("Ignoring DropPartition event as %s set to True", SUPPRESS_ALL_DROP_EVENTS));
        }
    }
}
