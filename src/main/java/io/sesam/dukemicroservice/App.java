package io.sesam.dukemicroservice;

import static io.sesam.dukemicroservice.IncrementalLuceneDatabase.DELETED_PROPERTY_NAME;
import static io.sesam.dukemicroservice.IncrementalRecordLinkageLuceneDatabase.DATASET_ID_PROPERTY_NAME;
import static io.sesam.dukemicroservice.IncrementalRecordLinkageLuceneDatabase.GROUP_NO_PROPERTY_NAME;
import static io.sesam.dukemicroservice.IncrementalRecordLinkageLuceneDatabase.ORIGINAL_ENTITY_ID_PROPERTY_NAME;
import static spark.Spark.get;
import static spark.Spark.halt;
import static spark.Spark.post;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

import no.priv.garshol.duke.ConfigLoader;
import no.priv.garshol.duke.Configuration;
import no.priv.garshol.duke.ConfigurationImpl;
import no.priv.garshol.duke.DataSource;
import no.priv.garshol.duke.JDBCLinkDatabase;
import no.priv.garshol.duke.Link;
import no.priv.garshol.duke.LinkStatus;
import no.priv.garshol.duke.Processor;
import no.priv.garshol.duke.Property;
import no.priv.garshol.duke.PropertyImpl;
import no.priv.garshol.duke.Record;
import no.priv.garshol.duke.RecordIterator;
import no.priv.garshol.duke.datasources.Column;
import spark.Request;
import spark.Response;

public class App {
    private class RecordLinkage {
        final String recordLinkageName;
        final boolean doOneToOneLinking;
        final Map<String, IncrementalRecordLinkageDataSource> dataSetId2dataSource;
        final IncrementalRecordLinkageMatchListener matchListener;
        final Processor processor;
        final Configuration config;
        final Lock lock = new ReentrantLock();

        RecordLinkage(String recordLinkageName,
                      String linkMode, Map<String, IncrementalRecordLinkageDataSource> dataSetId2dataSource,
                      IncrementalRecordLinkageMatchListener matchListener, Processor processor,
                      Configuration config
        ) {
            this.recordLinkageName = recordLinkageName;
            this.dataSetId2dataSource = dataSetId2dataSource;
            this.matchListener = matchListener;
            this.processor = processor;
            this.config = config;

            switch(linkMode) {
                case "one-to-one":
                    this.doOneToOneLinking = true;
                    break;
                default:
                    throw new RuntimeException(String.format("Invalid link-mode '%s' specified for the '%s' recordlinkage.",
                                                             linkMode, recordLinkageName));
            }
        }
    }

    private Map<String, RecordLinkage> recordLinkages = new HashMap<>();


    private class Deduplication {
        private final String deduplicationName;
        final Map<String, IncrementalDeduplicationDataSource> dataSetId2dataSource;
        final IncrementalDeduplicationMatchListener matchListener;
        final Processor processor;
        final Configuration config;
        final IncrementalDeduplicationLuceneDatabase luceneDatabase;
        final Lock lock = new ReentrantLock();
        final JDBCLinkDatabase linkDatabase;

        Deduplication(String deduplicationName,
                      Map<String, IncrementalDeduplicationDataSource> dataSetId2dataSource,
                      IncrementalDeduplicationMatchListener matchListener,
                      JDBCLinkDatabase linkDatabase,
                      Processor processor,
                      Configuration config,
                      IncrementalDeduplicationLuceneDatabase luceneDatabase
                      ) {
            this.deduplicationName= deduplicationName;
            this.dataSetId2dataSource = dataSetId2dataSource;
            this.matchListener = matchListener;
            this.linkDatabase = linkDatabase;
            this.processor = processor;
            this.config = config;

            this.luceneDatabase = luceneDatabase;
        }
    }

    private Map<String, Deduplication> deduplications = new HashMap<>();

    private Logger logger = LoggerFactory.getLogger(App.class);


    public App() throws Exception {
        // Create the Duke processor

        // TODO: load the config from an environment variable or something.

        String configFileName = "classpath:testdukeconfig.xml";
        Reader configFileReader;
        if (configFileName.startsWith("classpath:")) {
            String resource = configFileName.substring("classpath:".length());
            ClassLoader cloader = Thread.currentThread().getContextClassLoader();
            InputStream istream = cloader.getResourceAsStream(resource);
            configFileReader = new InputStreamReader(istream);
        } else {
            configFileReader = new FileReader(configFileName);
        }

        logger.info("Parsing the config-file...");

        // TODO: perhaps get the datafolder from config?
        Path rootDataFolder = Paths.get("").toAbsolutePath().resolve("data");

        XPath xPath = XPathFactory.newInstance().newXPath();

        // TODO: Do config-file validation with a schema or something.

        InputSource xml = new InputSource(configFileReader);
        NodeList dukeMicroServiceNodes = (NodeList) xPath.evaluate("//DukeMicroService", xml, XPathConstants.NODESET);
        if (dukeMicroServiceNodes.getLength() == 0) {
            throw new RuntimeException("The configfile didn't contain a 'DukeMicroService' entity!");
        }
        if (dukeMicroServiceNodes.getLength() > 1) {
            throw new RuntimeException("The configfile contain more than one 'DukeMicroService' entity!");
        }
        Node dukeMicroServiceNode = dukeMicroServiceNodes.item(0);

        for (int dukeChildNodeIndex=0; dukeChildNodeIndex < dukeMicroServiceNode.getChildNodes().getLength(); dukeChildNodeIndex++) {
            Node dukeChildNode = dukeMicroServiceNode.getChildNodes().item(dukeChildNodeIndex);
            if (dukeChildNode instanceof Element) {
                Element element = (Element)dukeChildNode;

                String tagName = element.getTagName();
                switch (tagName) {
                    case "Deduplication": {
                        String deduplicationName = element.getAttributes().getNamedItem("name").getTextContent();

                        Path deduplicationDataFolder = rootDataFolder.resolve("deduplication").resolve(deduplicationName);

                        logger.info("  Parsing the config for the '{}' deduplication.", deduplicationName);
                        ConfigurationImpl config = parseDukeConfig("deduplication'" + deduplicationName + "'",
                                                                   element);

                        // Add the special "id", "datasetId" properties.
                        List<Property> properties = config.getProperties();
                        for (Property property : properties) {
                            if (property.isIdProperty()) {
                                throw new RuntimeException(String.format("    The schema contained an 'id'-property: '%s'", property.getName()));
                            }
                        }

                        PropertyImpl IdProperty = new PropertyImpl("ID");
                        assert IdProperty.isIdProperty();
                        properties.add(IdProperty);

                        PropertyImpl datasetIdProperty = new PropertyImpl(DATASET_ID_PROPERTY_NAME, null, 0, 0);
                        datasetIdProperty.setIgnoreProperty(true);
                        properties.add(datasetIdProperty);

                        PropertyImpl originalEntityIdProperty = new PropertyImpl(ORIGINAL_ENTITY_ID_PROPERTY_NAME, null, 0, 0);
                        originalEntityIdProperty.setIgnoreProperty(true);
                        properties.add(originalEntityIdProperty);

                        PropertyImpl deletedProperty = new PropertyImpl(DELETED_PROPERTY_NAME, null, 0, 0);
                        deletedProperty.setIgnoreProperty(true);
                        properties.add(deletedProperty);

                        config.setProperties(properties);

                        logger.info("    Created the Duke config for the deduplication '{}' ok.", deduplicationName);

                        IncrementalDeduplicationLuceneDatabase luceneDatabase = new IncrementalDeduplicationLuceneDatabase();
                        Path luceneFolderPath = deduplicationDataFolder.resolve("lucene-index");
                        File luceneFolderFile = luceneFolderPath.toFile();
                        boolean wasCreated = luceneFolderFile.mkdirs();
                        if (wasCreated) {
                            logger.info("    Created the folder '{}'.", luceneFolderPath.toString());
                        }
                        if (!luceneFolderFile.exists()) {
                            throw new RuntimeException(String.format("Failed to create the folder '%s'!", luceneFolderPath.toString()));
                        }
                        logger.info("    Using this folder for the lucene index: '{}'.", luceneFolderPath.toString());
                        luceneDatabase.setPath(luceneFolderPath.toString());
                        config.setDatabase(luceneDatabase);
                        Processor processor = new Processor(config, false);

                        Path h2DatabasePath = deduplicationDataFolder.resolve("linkdatabase");
                        File h2DatabaseFolder = h2DatabasePath.toFile().getParentFile();
                        wasCreated = h2DatabaseFolder.mkdirs();
                        if (wasCreated) {
                            logger.info("    Created the folder '{}'.", h2DatabaseFolder.getAbsolutePath());
                        }
                        if (!h2DatabaseFolder.exists()) {
                            throw new RuntimeException(String.format("Failed to create the folder '%s'!", h2DatabasePath.toString()));
                        }

                        logger.info("    Using this folder for the h2 deduplication database: '{}'.", h2DatabasePath.toString());

                        JDBCLinkDatabase linkDatabase = new JDBCLinkDatabase("org.h2.Driver",
                                                                         "jdbc:h2://" + h2DatabasePath.toString(),
                                                                         "h2",
                                                                         null
                                                                         );
                        linkDatabase.init();
                        IncrementalDeduplicationMatchListener incrementalDeduplicationMatchListener  = new IncrementalDeduplicationMatchListener(
                                deduplicationName,
                                config,
                                linkDatabase
                                );
                        processor.addMatchListener(incrementalDeduplicationMatchListener);

                        // Load the datasources
                        Map<String, IncrementalDeduplicationDataSource> dataSetId2dataSource = new HashMap<>();
                        Collection<DataSource> dataSources = config.getDataSources();

                        if (dataSources.isEmpty()) {
                            throw new RuntimeException(
                                    String.format(
                                            "Got zero datasources in the deduplication '%s'!",
                                            deduplicationName));
                        }

                        for (DataSource dataSource : dataSources) {
                            if (dataSource instanceof IncrementalDeduplicationDataSource) {
                                IncrementalDeduplicationDataSource incrementalDeduplicationDataSource=
                                        (IncrementalDeduplicationDataSource) dataSource;
                                String datasetId = incrementalDeduplicationDataSource.getDatasetId();
                                if (datasetId == null || datasetId.isEmpty()) {
                                    throw new RuntimeException(
                                            String.format("Got a DataSource with no datasetId property in the deduplication '%s'!",
                                                          deduplicationName));
                                }

                                for (Column column : incrementalDeduplicationDataSource.getColumns()) {
                                    if (column.getName().toLowerCase().equals("_id") || column.getName().toLowerCase().equals("id")) {
                                        throw new RuntimeException(
                                                String.format("The DataSource '%s' in the deduplication '%s' contained an '%s' column!!",
                                                              datasetId, deduplicationName, column.getName()));
                                    }
                                }

                                dataSetId2dataSource.put(datasetId, incrementalDeduplicationDataSource);
                                logger.info("    Added the datasource '{}'", datasetId);
                            } else {
                                throw new RuntimeException(
                                        String.format("Got a DataSource of the unsupported type '%s' in the deduplication '%s'!",
                                                      dataSource.getClass().getName(), deduplicationName));
                            }

                        }

                        deduplications.put(deduplicationName, new Deduplication(deduplicationName,
                                                                                dataSetId2dataSource,
                                                                                incrementalDeduplicationMatchListener,
                                                                                linkDatabase,
                                                                                processor,
                                                                                config,
                                                                                luceneDatabase));

                    }
                    break;

                    case "RecordLinkage": {
                        String recordLinkageName = element.getAttributes().getNamedItem("name").getTextContent();
                        logger.info("  Parsing the config for the '{}' recordLinkage.", recordLinkageName);

                        String linkMode = element.getAttributes().getNamedItem("link-mode").getTextContent();

                        Path recordLinkDataFolder = rootDataFolder.resolve("recordLinkage").resolve(recordLinkageName);

                        ConfigurationImpl config = parseDukeConfig("recordLinkage '" + recordLinkageName + "'",
                                                                   element);

                        // Add the special "id", "datasetId" and "groupNo" properties.
                        List<Property> properties = config.getProperties();
                        for (Property property : properties) {
                            if (property.isIdProperty()) {
                                throw new RuntimeException(String.format("    The schema contained an 'id'-property: '%s'", property.getName()));
                            }
                        }

                        PropertyImpl IdProperty = new PropertyImpl("ID");
                        assert IdProperty.isIdProperty();
                        properties.add(IdProperty);

                        PropertyImpl datasetIdProperty = new PropertyImpl(DATASET_ID_PROPERTY_NAME, null, 0, 0);
                        datasetIdProperty.setIgnoreProperty(true);
                        properties.add(datasetIdProperty);

                        PropertyImpl originalEntityIdProperty = new PropertyImpl(ORIGINAL_ENTITY_ID_PROPERTY_NAME, null, 0, 0);
                        originalEntityIdProperty.setIgnoreProperty(true);
                        properties.add(originalEntityIdProperty);

                        PropertyImpl groupNoProperty = new PropertyImpl(GROUP_NO_PROPERTY_NAME, null, 0, 0);
                        groupNoProperty.setIgnoreProperty(true);
                        properties.add(groupNoProperty);

                        config.setProperties(properties);

                        logger.info("    Created the Duke config for the recordLinkage '{}' ok.", recordLinkageName);

                        IncrementalRecordLinkageLuceneDatabase database = new IncrementalRecordLinkageLuceneDatabase();
                        Path luceneFolderPath = recordLinkDataFolder.resolve("lucene-index");
                        File luceneFolderFile = luceneFolderPath.toFile();
                        boolean wasCreated = luceneFolderFile.mkdirs();
                        if (wasCreated) {
                            logger.info("    Created the folder '{}'.", luceneFolderPath.toString());
                        }
                        if (!luceneFolderFile.exists()) {
                            throw new RuntimeException(String.format("Failed to create the folder '%s'!", luceneFolderPath.toString()));
                        }
                        logger.info("    Using this folder for the lucene index: '{}'.", luceneFolderPath.toString());
                        database.setPath(luceneFolderPath.toString());
                        config.setDatabase(database);
                        Processor processor = new Processor(config, false);

                        Path h2DatabasePath = recordLinkDataFolder.resolve("recordlinkdatabase");
                        File h2DatabaseFolder = h2DatabasePath.toFile().getParentFile();
                        wasCreated = h2DatabaseFolder.mkdirs();
                        if (wasCreated) {
                            logger.info("    Created the folder '{}'.", h2DatabaseFolder.getAbsolutePath());
                        }
                        if (!h2DatabaseFolder.exists()) {
                            throw new RuntimeException(String.format("Failed to create the folder '%s'!", h2DatabasePath.toString()));
                        }

                        logger.info("    Using this folder for the h2 record-link database: '{}'.", h2DatabasePath.toString());

                        IncrementalRecordLinkageMatchListener incrementalRecordLinkageMatchListener = new IncrementalRecordLinkageMatchListener(
                                this,
                                recordLinkageName,
                                h2DatabasePath.toString());
                        processor.addMatchListener(incrementalRecordLinkageMatchListener);

                        // Load the datasources for the two groups
                        Map<String, IncrementalRecordLinkageDataSource> dataSetId2dataSource = new HashMap<>();
                        java.util.function.Consumer<Integer> addDataSourcesForGroup = (Integer groupNo) ->
                        {
                            Collection<DataSource> dataSources = config.getDataSources(groupNo);

                            if (dataSources.isEmpty()) {
                                throw new RuntimeException(
                                        String.format(
                                                "Got zero datasources for group %d in the recordLinkage '%s'!",
                                                groupNo,
                                                recordLinkageName));
                            }

                            for (DataSource dataSource : dataSources) {
                                if (dataSource instanceof IncrementalRecordLinkageDataSource) {
                                    IncrementalRecordLinkageDataSource incrementalRecordLinkageDataSource =
                                            (IncrementalRecordLinkageDataSource) dataSource;
                                    String datasetId = incrementalRecordLinkageDataSource.getDatasetId();
                                    if (datasetId == null || datasetId.isEmpty()) {
                                        throw new RuntimeException(
                                                String.format("Got a DataSource with no datasetId property in the recordLinkage '%s'!",
                                                              recordLinkageName));
                                    }

                                    for (Column column : incrementalRecordLinkageDataSource.getColumns()) {
                                        if (column.getName().toLowerCase().equals("_id") || column.getName().toLowerCase().equals("id")) {
                                            throw new RuntimeException(
                                                    String.format("The DataSource '%s' in the recordLinkage '%s' contained an '%s' column!!",
                                                                  datasetId, recordLinkageName, column.getName()));
                                        }
                                    }

                                    incrementalRecordLinkageDataSource.setGroupNo(groupNo);
                                    dataSetId2dataSource.put(datasetId, incrementalRecordLinkageDataSource);
                                    logger.info("    Added the datasource '{}' to group {}", datasetId, groupNo);
                                } else {
                                    throw new RuntimeException(
                                            String.format("Got a DataSource of the unsupported type '%s' in the recordLinkage '%s'!",
                                                          dataSource.getClass().getName(), recordLinkageName));
                                }
                            }
                        };
                        addDataSourcesForGroup.accept(1);
                        addDataSourcesForGroup.accept(2);

                        recordLinkages.put(recordLinkageName, new RecordLinkage(recordLinkageName,
                                                                                linkMode,
                                                                                dataSetId2dataSource,
                                                                                incrementalRecordLinkageMatchListener,
                                                                                processor,
                                                                                config));
                    }
                    break;

                    default:
                        throw new RuntimeException(String.format("Unknown element '%s' found in the configuration file!", tagName));
                }
            }

        }
        logger.info("Done parsing the config-file.");
    }

    private ConfigurationImpl parseDukeConfig(String parentElementLabel, Element element) throws IOException, TransformerException, SAXException {
        Node dukeConfigNode = null;
        NodeList childNodes = element.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node childNode = childNodes.item(i);
            if (childNode instanceof Element) {
                Element childElement = (Element) childNode;
                String childElementTagName = childElement.getTagName();
                if (childElementTagName.equals("duke")) {
                    dukeConfigNode = childNode;
                } else {
                    throw new RuntimeException(String.format("Unknown element '%s' found in the %s!", childElementTagName, parentElementLabel));
                }
            }
        }
        if (dukeConfigNode == null) {
            throw new RuntimeException(String.format("The %s didn't contain a <duke> element!", parentElementLabel));
        }

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        File dukeConfigFile = File.createTempFile("dukeconfig_", ".xml");
        ConfigurationImpl config;
        try {
            FileOutputStream resultStream = new FileOutputStream(dukeConfigFile);
            StreamResult result = new StreamResult(resultStream);
            transformer.transform(new DOMSource(dukeConfigNode), result);
            resultStream.close();

            config = (ConfigurationImpl) ConfigLoader.load(dukeConfigFile.getAbsolutePath());
        } finally {
            dukeConfigFile.delete();
        }
        return config;
    }

    public static void main(String[] args) throws Exception {
        App app = new App();
        Gson gson = new Gson();

        /*
         * This endpoint is used to do record linkage. The results can be read from the
         * GET /recordlinkage/:recordLinkage endpoint.
         */
        post("/recordlinkage/:recordLinkage/:datasetId", (Request req, Response res) -> {
            String recordLinkageName = req.params("recordLinkage");
            String datasetId = req.params("datasetId");

            if (recordLinkageName.isEmpty()) {
                halt(400, "The recordLinkage cannot be an empty string!");
            }

            if (datasetId.isEmpty()) {
                halt(400, "The datasetId cannot be an empty string!");
            }

            RecordLinkage recordLinkage = app.recordLinkages.get(recordLinkageName);
            if (recordLinkage == null) {
                halt(400, String.format("Unknown recordLinkage '%s'! (All recordLinkages must be specified in the configuration)",
                                        recordLinkageName));
            }

            recordLinkage.lock.lock();
            try {

                IncrementalRecordLinkageDataSource dataSource = recordLinkage.dataSetId2dataSource.get(datasetId);
                if (dataSource == null) {
                    halt(400, String.format("Unknown dataset-id '%s' for the recordLinkage '%s'!", datasetId, recordLinkageName));
                }

                res.type("application/json");

                JsonArray elementsInBatch;
                // We assume that we get the records in small batches, so that it is ok to load the entire request into memory.
                String requestBody = req.body();
                try {
                    elementsInBatch = gson.fromJson(requestBody, JsonArray.class);
                } catch (JsonSyntaxException e) {
                    // The request can contain either an array of entities, or one single entity.
                    JsonObject singleElement = gson.fromJson(requestBody, JsonObject.class);
                    elementsInBatch = new JsonArray();
                    elementsInBatch.add(singleElement);
                }

                // use the DataSource for the datasetId to convert the JsonObjects into Record objects.
                dataSource.setDatasetEntitiesBatch(elementsInBatch);
                RecordIterator it = dataSource.getRecords();
                List<Record> records = new LinkedList<>();
                List<Record> deletedRecords = new LinkedList<>();
                while (it.hasNext()) {
                    Record record = it.next();
                    if ("true".equals(record.getValue(DELETED_PROPERTY_NAME))) {
                        deletedRecords.add(record);
                    } else {
                        records.add(record);
                    }
                }

                Processor processor = recordLinkage.processor;
                IncrementalRecordLinkageLuceneDatabase database = (IncrementalRecordLinkageLuceneDatabase) processor.getDatabase();

                IncrementalRecordLinkageMatchListener incrementalRecordLinkageMatchListener = recordLinkage.matchListener;

                try {
                    // When we get a record with "_deleted"=True, we must do the following:
                    // 1. Delete the record from the lucene index
                    // 2. If the record appears in the RECORDLINKAGE table:
                    //      Mark that row as "deleted=true".
                    //      Mark the other record in that row as needing a rematching (add a row to the RECORDS_THAT_NEEDS_REMATCH table)
                    // 3. If the record appears in the RECORDS_THAT_NEEDS_REMATCH table:
                    //      Delete the row.
                    Set<String> recordsIdThatNeedsReprocessing = new HashSet<>();
                    for (Record record : deletedRecords) {
                        database.delete(record);

                        recordsIdThatNeedsReprocessing.addAll(incrementalRecordLinkageMatchListener.delete(record));
                    }

                    List<Record> recordsThatNeedsReprocessing = new LinkedList<>();
                    for (String recordId : recordsIdThatNeedsReprocessing) {
                        Record record = processor.getDatabase().findRecordById(recordId);
                        if (record != null) {
                            app.logger.info("Re-processing the record '{}', because of some other record being deleted. ", recordId);
                            recordsThatNeedsReprocessing.add(record);
                        }
                    }
                    if (!recordsThatNeedsReprocessing.isEmpty()) {
                        processor.deduplicate(recordsThatNeedsReprocessing);
                    }

                    // Process the actual records in the batch.
                    if (!records.isEmpty()) {
                        processor.deduplicate(records);
                    }

                    // The call to deduplicate() might have removed the matches for existing records. In such cases we want to fallback
                    // to the second best match for the existing records.
                    int reprocessingRunNumber = 0;
                    while (true) {
                        if (reprocessingRunNumber > 10) {
                            app.logger
                                    .warn("Giving up reprocessing after {} attempts. Something is odd here; the matches don't seem to settle down...",
                                          reprocessingRunNumber);
                            break;
                        }
                        reprocessingRunNumber++;
                        recordsThatNeedsReprocessing.clear();
                        for (String recordId : incrementalRecordLinkageMatchListener.getRecordIdsThatNeedReprocessing()) {
                            Record record = processor.getDatabase().findRecordById(recordId);
                            if (record != null) {
                                app.logger.info("Reprocessing-run {} processing the record '{}'", reprocessingRunNumber, recordId);
                                recordsThatNeedsReprocessing.add(record);
                            } else {
                                app.logger.warn("Failed to reprocess the record '{}', since it couldn't be found!", recordId);
                            }
                        }
                        incrementalRecordLinkageMatchListener.clearRecordIdsThatNeedReprocessing();

                        if (recordsThatNeedsReprocessing.isEmpty()) {
                            break;
                        }
                        processor.deduplicate(recordsThatNeedsReprocessing);
                    }

                    incrementalRecordLinkageMatchListener.commitCurrentDatabaseTransaction();
                } catch (Exception e) {
                    // something went wrong, so rollback any changes to the RECORDLINK database table.
                    incrementalRecordLinkageMatchListener.rollbackCurrentDatabaseTransaction();
                    throw e;
                }

                Writer writer = res.raw().getWriter();
                writer.append("{\"success\": true}");
                writer.flush();
                return "";
            } finally {
                recordLinkage.lock.unlock();
            }
        });

        get("/recordlinkage/:recordLinkage", (req, res) -> {
            try {
                String recordLinkageName = req.params("recordLinkage");
                if (recordLinkageName.isEmpty()) {
                    halt(400, "The recordLinkageName cannot be an empty string!");
                }

                RecordLinkage recordLinkage = app.recordLinkages.get(recordLinkageName);
                IncrementalRecordLinkageMatchListener incrementalRecordLinkageMatchListener = recordLinkage.matchListener;
                if (incrementalRecordLinkageMatchListener == null) {
                    halt(400, String.format("Unknown recordLinkage '%s'! (All recordLinkages must be specified in the configuration)",
                                            recordLinkageName));
                }

                if (!recordLinkage.lock.tryLock(1000, TimeUnit.MILLISECONDS)) {
                    // failed to get the log
                    res.status(503);
                    res.type("text/plain");
                    Writer writer = res.raw().getWriter();
                    writer.append("The recordLinkage is being written to, so reading is not currently possible. Please wait a bit and try again later.");
                    writer.flush();
                    return "";
                } else {
                    try {
                        String since = req.queryParams("since");

                        res.status(200);
                        res.type("application/json");
                        Writer writer = res.raw().getWriter();

                        incrementalRecordLinkageMatchListener.streamRecordLinksSince(since, writer);

                        writer.flush();
                        return "";
                    } finally {
                        recordLinkage.lock.unlock();
                    }
                }
            } catch (Exception e) {
                if (e.getCause() instanceof org.eclipse.jetty.io.EofException) {
                    app.logger.info("Ignoring a EofException when serving GET '" + req.url() + ".");
                } else {
                    throw e;
                }
            }
            return "";
        });


        
        /*
         * This endpoint is used to do deduplication. The results can be read from the
         * GET /deduplication/:deduplication endpoint.
         */
        post("/deduplication/:deduplication/:datasetId", (Request req, Response res) -> {
            String deduplicationName = req.params("deduplication");
            String datasetId = req.params("datasetId");

            if (deduplicationName.isEmpty()) {
                halt(400, "The deduplicationName cannot be an empty string!");
            }

            if (datasetId.isEmpty()) {
                halt(400, "The datasetId cannot be an empty string!");
            }

            Deduplication deduplication = app.deduplications.get(deduplicationName);
            if (deduplication == null) {
                halt(400, String.format("Unknown deduplication '%s'! (All deduplications must be specified in the configuration)",
                                        deduplicationName));
            }

            deduplication.lock.lock();
            try {

                IncrementalDeduplicationDataSource dataSource = deduplication.dataSetId2dataSource.get(datasetId);
                if (dataSource == null) {
                    halt(400, String.format("Unknown dataset-id '%s' for the deduplication '%s'!", datasetId, deduplicationName));
                }

                res.type("application/json");

                JsonArray elementsInBatch;
                // We assume that we get the records in small batches, so that it is ok to load the entire request into memory.
                String requestBody = req.body();
                try {
                    elementsInBatch = gson.fromJson(requestBody, JsonArray.class);
                } catch (JsonSyntaxException e) {
                    // The request can contain either an array of entities, or one single entity.
                    JsonObject singleElement = gson.fromJson(requestBody, JsonObject.class);
                    elementsInBatch = new JsonArray();
                    elementsInBatch.add(singleElement);
                }

                // use the DataSource for the datasetId to convert the JsonObjects into Record objects.
                dataSource.setDatasetEntitiesBatch(elementsInBatch);
                RecordIterator it = dataSource.getRecords();
                List<Record> records = new LinkedList<>();
                List<Record> deletedRecords = new LinkedList<>();
                while (it.hasNext()) {
                    Record record = it.next();
                    if ("true".equals(record.getValue("_deleted"))) {
                        deletedRecords.add(record);
                    } else {
                        records.add(record);
                    }
                }

                Processor processor = deduplication.processor;
                IncrementalDeduplicationLuceneDatabase database = deduplication.luceneDatabase;

                IncrementalDeduplicationMatchListener incrementalDeduplicationMatchListener = deduplication.matchListener;
                JDBCLinkDatabase linkDatabase = deduplication.linkDatabase;

                try {
                    for (Record record : deletedRecords) {
                        String recordId = record.getValue("ID");

                        // 1. mark the record as deleted in the lucene index. We cant just delete it alltogether, since we
                        //    need to do a lookup in lucine in the GET-handler (since the LinkDatabase doesn't contain all
                        //    the information we need)
                        database.index(record);

                        // retract all links for this record
                        for (Link link : linkDatabase.getAllLinksFor(recordId)) {
                            link.retract();
                            linkDatabase.assertLink(link);
                        }
                    }

                    // Process the actual records in the batch.
                    if (!records.isEmpty()) {
                        processor.deduplicate(records);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                res.status(200);
                res.type("application/json");
                Writer writer = res.raw().getWriter();
                writer.append("{\"success\": true}");
                writer.flush();
                return "";
            } finally {
                deduplication.lock.unlock();
            }
        });

        get("/deduplication/:deduplication", (req, res) -> {
            try {
                String deduplicationName = req.params("deduplication");
                if (deduplicationName.isEmpty()) {
                    halt(400, "The deduplicationName cannot be an empty string!");
                }

                Deduplication deduplication = app.deduplications.get(deduplicationName);
                if (deduplication == null) {
                    halt(400, String.format("Unknown deduplication '%s'! (All deduplications must be specified in the configuration)",
                                            deduplicationName));
                }

                IncrementalDeduplicationLuceneDatabase luceneDatabase = deduplication.luceneDatabase;

                String sinceAsString = req.queryParams("since");
                long since = 0;
                if (sinceAsString != null && !sinceAsString.isEmpty()) {
                    since = Long.parseLong(sinceAsString);
                }

                if (!deduplication.lock.tryLock(1000, TimeUnit.MILLISECONDS)) {
                    // failed to get the log
                    res.status(503);
                    res.type("text/plain");
                    Writer writer = res.raw().getWriter();
                    writer.append("The deduplication is being written to, so reading is not currently possible. Please wait a bit and try again later.");
                    writer.flush();
                    return "";
                } else {
                    try {
                        res.status(200);
                        res.type("application/json");
                        Writer writer = res.raw().getWriter();

                        writer.append("[");
                        boolean isFirstEntity = true;
                        for (Link link : deduplication.linkDatabase.getChangesSince(since)) {

                            if (isFirstEntity) {
                                isFirstEntity = false;
                            } else {
                                writer.append(",\n");
                            }

                            String record1Id = link.getID1();
                            String record2Id = link.getID2();

                            Record record1 = luceneDatabase.findRecordById(record1Id);
                            Record record2 = luceneDatabase.findRecordById(record2Id);

                            JsonObject entity = new JsonObject();
                            entity.addProperty("_id", link.getID1() + "_" + link.getID1());
                            entity.addProperty("_updated", link.getTimestamp());
                            entity.addProperty("_deleted", link.getStatus().equals(LinkStatus.RETRACTED));
                            entity.addProperty("entity1", record1 != null ? record1.getValue(ORIGINAL_ENTITY_ID_PROPERTY_NAME) : null);
                            entity.addProperty("entity2", record2 != null ? record2.getValue(ORIGINAL_ENTITY_ID_PROPERTY_NAME) : null);
                            entity.addProperty("dataset1", record1 != null ? record1.getValue(DATASET_ID_PROPERTY_NAME) : null);
                            entity.addProperty("dataset2", record2 != null ? record2.getValue(DATASET_ID_PROPERTY_NAME) : null);
                            entity.addProperty("confidence", link.getConfidence());

                            String entityLinkAsString = entity.toString();
                            writer.append(entityLinkAsString);
                        }

                        writer.append("]");
                        writer.flush();
                        return "";
                    } finally {
                        deduplication.lock.unlock();
                    }
                }
            } catch (Exception e) {
                if (e.getCause() instanceof org.eclipse.jetty.io.EofException) {
                    app.logger.info("Ignoring a EofException when serving GET '" + req.url() + ".");
                } else {
                    throw e;
                }
            }
            return "";
        });
    }
}