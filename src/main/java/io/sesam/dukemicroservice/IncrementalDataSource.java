package io.sesam.dukemicroservice;

import static io.sesam.dukemicroservice.IncrementalLuceneDatabase.DELETED_PROPERTY_NAME;
import static io.sesam.dukemicroservice.IncrementalRecordLinkageLuceneDatabase.DATASET_ID_PROPERTY_NAME;
import static io.sesam.dukemicroservice.IncrementalRecordLinkageLuceneDatabase.GROUP_NO_PROPERTY_NAME;
import static io.sesam.dukemicroservice.IncrementalRecordLinkageLuceneDatabase.ORIGINAL_ENTITY_ID_PROPERTY_NAME;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import no.priv.garshol.duke.ModifiableRecord;
import no.priv.garshol.duke.Record;
import no.priv.garshol.duke.RecordIterator;
import no.priv.garshol.duke.datasources.Column;
import no.priv.garshol.duke.datasources.ColumnarDataSource;
import no.priv.garshol.duke.datasources.RecordBuilder;

public class IncrementalDataSource extends ColumnarDataSource {
    private JsonArray currentDatasetEntitiesBatch = null;
    private String datasetId;
    private Integer groupNo = null;

    public IncrementalDataSource() {
        super();
    }

    public String getDatasetId() {
        return datasetId;
    }

    void setDatasetEntitiesBatch(JsonArray datasetEntitiesBatch) {
        this.currentDatasetEntitiesBatch = datasetEntitiesBatch;
    }

    class DatasetDataSourceRecordIterator extends RecordIterator {
        private final RecordBuilder builder;
        int currentEntityIndex = 0;

        DatasetDataSourceRecordIterator() {
            this.builder = new RecordBuilder(IncrementalDataSource.this);
        }
        
        @Override
        public boolean hasNext() {
            return this.currentEntityIndex < currentDatasetEntitiesBatch.size();
        }

        @Override
        public Record next() {
            JsonObject entity = (JsonObject) currentDatasetEntitiesBatch.get(this.currentEntityIndex);
            this.currentEntityIndex++;

            JsonElement entityIdElement = entity.get("_id");
            String entityId = null;
            if (entityIdElement != null) {
                entityId = entityIdElement.getAsString();
            }
            if (entityId == null || entityId.isEmpty()) {
                throw new RuntimeException("Got an entity with no '_id' attribute!");
            }

            // build a record from the current row
            builder.newRecord();

            for (Column column : getColumns()) {
                JsonElement jsonElement = entity.get(column.getName());
                if (jsonElement != null) {
                    if (jsonElement.isJsonArray()){
                        for (JsonElement v: jsonElement.getAsJsonArray()) {
                            builder.addValue(column, jsonElement.getAsString());
                        }
                    } 
                    else builder.addValue(column, jsonElement.getAsString());
                }
            }

            ModifiableRecord record = (ModifiableRecord)builder.getRecord();
            String recordId;
            if (groupNo != null) {
                String groupNoAsString = Integer.toString(groupNo);
                recordId = groupNoAsString + "__" + datasetId + "__" + entityId;
                record.addValue(GROUP_NO_PROPERTY_NAME, groupNoAsString);
            } else {
                recordId = datasetId + "__" + entityId;
            }

            record.addValue("ID", recordId);
            record.addValue(ORIGINAL_ENTITY_ID_PROPERTY_NAME, entityId);
            record.addValue(DATASET_ID_PROPERTY_NAME, datasetId);

            JsonElement deletedjsonElement = entity.get("_deleted");
            if (deletedjsonElement != null) {
                boolean deleted = deletedjsonElement.getAsBoolean();
                if (deleted) {
                    record.addValue(DELETED_PROPERTY_NAME, "true");
                }
            }

            return record;
        }
    }

    @Override
    protected String getSourceName() {
        return "Dataset";
    }

    @Override
    public RecordIterator getRecords() {
        return new DatasetDataSourceRecordIterator();
    }

    @SuppressWarnings("unused")  // this is used by the configloader
    public void setDatasetId(String datasetId) {
        this.datasetId = datasetId;
    }

    void setGroupNo(int groupNo) {
        if (groupNo < 1 || groupNo > 2) {
            throw new RuntimeException(String.format("Invalid group: %d!", groupNo));
        }
        this.groupNo = groupNo;
    }

}
