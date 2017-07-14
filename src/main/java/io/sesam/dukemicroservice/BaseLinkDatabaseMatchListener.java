package io.sesam.dukemicroservice;

import static io.sesam.dukemicroservice.IncrementalLuceneDatabase.DATASET_ID_PROPERTY_NAME;
import static io.sesam.dukemicroservice.IncrementalLuceneDatabase.ORIGINAL_ENTITY_ID_PROPERTY_NAME;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import no.priv.garshol.duke.Configuration;
import no.priv.garshol.duke.LinkDatabase;
import no.priv.garshol.duke.Record;
import no.priv.garshol.duke.matchers.LinkDatabaseMatchListener;
import no.priv.garshol.duke.matchers.MatchListener;

/**
 *
 */

public class BaseLinkDatabaseMatchListener implements MatchListener {
    public class Match {
        private final Record record;
        private final double confidence;

        Match(Record record, double confidence) {
            this.record = record;
            this.confidence = confidence;
        }

        public Record getRecord() {
            return record;
        }

        public double getConfidence() {
            return confidence;
        }
    }
    private LinkDatabaseMatchListener wrappedLinkDatabaseMatchListener;
    private boolean linkDatabaseUpdatedIsDisabled;

    private Map<String, List<Match>> entityIdToMatches = new HashMap<>();


    BaseLinkDatabaseMatchListener (Configuration config, LinkDatabase linkdb) {
        wrappedLinkDatabaseMatchListener = new LinkDatabaseMatchListener(config, linkdb);
    }

    @Override
    public void batchReady(int size) {
        entityIdToMatches = new HashMap<>();
        if (!linkDatabaseUpdatedIsDisabled) {
            wrappedLinkDatabaseMatchListener.batchReady(size);
        }
    }

    @Override
    public void batchDone() {
        if (!linkDatabaseUpdatedIsDisabled) {
            wrappedLinkDatabaseMatchListener.batchDone();
        }
    }

    @Override
    public void matches(Record r1, Record r2, double confidence) {
        if (!linkDatabaseUpdatedIsDisabled) {
            wrappedLinkDatabaseMatchListener.matches(r1, r2, confidence);
        }
        updateEntityIdToMatches(r1, r2, confidence);
    }

    @Override
    public void matchesPerhaps(Record r1, Record r2, double confidence) {
        if (!linkDatabaseUpdatedIsDisabled) {
            wrappedLinkDatabaseMatchListener.matchesPerhaps(r1, r2, confidence);
        }
        updateEntityIdToMatches(r1, r2, confidence);
    }

    private void updateEntityIdToMatches(Record r1, Record r2, double confidence) {
        String entityId = r1.getValue(ORIGINAL_ENTITY_ID_PROPERTY_NAME);
        List<Match> matches = entityIdToMatches.computeIfAbsent(entityId, k -> new LinkedList<>());
        matches.add(new Match(r2, confidence));
    }

    @Override
    public void noMatchFor(Record record) {
        if (!linkDatabaseUpdatedIsDisabled) {
            wrappedLinkDatabaseMatchListener.noMatchFor(record);
        }
    }

    @Override
    public void startProcessing() {
        if (!linkDatabaseUpdatedIsDisabled) {
            wrappedLinkDatabaseMatchListener.startProcessing();
        }
    }

    @Override
    public void endProcessing() {
        if (!linkDatabaseUpdatedIsDisabled) {
            wrappedLinkDatabaseMatchListener.endProcessing();
        }
    }

    void setLinkDatabaseUpdatedIsDisabled(boolean linkDatabaseUpdatedIsDisabled) {
        this.linkDatabaseUpdatedIsDisabled = linkDatabaseUpdatedIsDisabled;
    }

    JsonArray getLinksForObject(JsonObject responseObject) {
        JsonArray result = new JsonArray();
        String idElement = responseObject.get("_id").getAsString();
        List<Match> matches = entityIdToMatches.get(idElement);
        if (matches != null) {
            for (Match match : matches) {
                JsonObject matchInfo = new JsonObject();
                Record record = match.getRecord();

                String datasetId = record.getValue(DATASET_ID_PROPERTY_NAME);
                String entityId = record.getValue(ORIGINAL_ENTITY_ID_PROPERTY_NAME);

                matchInfo.addProperty("datasetId", datasetId);
                matchInfo.addProperty("entityId", entityId);
                matchInfo.addProperty("confidence", match.getConfidence());

                result.add(matchInfo);
            }
        }

        return result;
    }
}
