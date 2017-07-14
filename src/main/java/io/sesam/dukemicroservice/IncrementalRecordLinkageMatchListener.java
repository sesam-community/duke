package io.sesam.dukemicroservice;

import static io.sesam.dukemicroservice.IncrementalRecordLinkageLuceneDatabase.DATASET_ID_PROPERTY_NAME;
import static io.sesam.dukemicroservice.IncrementalRecordLinkageLuceneDatabase.GROUP_NO_PROPERTY_NAME;
import static io.sesam.dukemicroservice.IncrementalRecordLinkageLuceneDatabase.ORIGINAL_ENTITY_ID_PROPERTY_NAME;

import java.io.IOException;
import java.io.Writer;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import no.priv.garshol.duke.Configuration;
import no.priv.garshol.duke.LinkDatabase;
import no.priv.garshol.duke.Record;
import no.priv.garshol.duke.matchers.LinkDatabaseMatchListener;
import no.priv.garshol.duke.matchers.MatchListener;

public class IncrementalRecordLinkageMatchListener extends BaseLinkDatabaseMatchListener {

    private final Logger logger;
    private long batchStartTime;

    public IncrementalRecordLinkageMatchListener(String deduplicationName, Configuration config, LinkDatabase linkdb) {
        super(config, linkdb);
        this.logger = LoggerFactory.getLogger("recordLinkageMatchListener-" + deduplicationName);
    }

    public void batchReady(int size) {
        batchStartTime = System.nanoTime();
        logger.info("batchReady(size={})", size);
        super.batchReady(size);
    }

    public void batchDone() {
        super.batchDone();
        long batchElapsedTime = System.nanoTime() - this.batchStartTime;
        logger.info("batchDone() batchElapsedTime: {} seconds.", batchElapsedTime / 1e9);
    }
}