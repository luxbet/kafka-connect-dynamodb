/*
 * Copyright 2016 Shikhar Bhushan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dynamok.sink;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AmazonDynamoDBException;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemRequest;
import com.amazonaws.services.dynamodbv2.model.BatchWriteItemResult;
import com.amazonaws.services.dynamodbv2.model.LimitExceededException;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughputExceededException;
import com.amazonaws.services.dynamodbv2.model.PutRequest;
import com.amazonaws.services.dynamodbv2.model.WriteRequest;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;

import dynamok.Version;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.errors.RetriableException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


public class DynamoDbSinkTask extends SinkTask {

    private enum ValueSource {
        RECORD_KEY {
            @Override
            String topAttributeName(ConnectorConfig config) {
                return config.topKeyAttribute;
            }
        },
        RECORD_VALUE {
            @Override
            String topAttributeName(ConnectorConfig config) {
                return config.topValueAttribute;
            }
        };

        abstract String topAttributeName(ConnectorConfig config);
    }

    private final Logger log = LoggerFactory.getLogger(DynamoDbSinkTask.class);

    private ConnectorConfig config;
    private AmazonDynamoDBClient client;
    private int remainingRetries;

    @Override
    public void start(Map<String, String> props) {
        config = new ConnectorConfig(props);

        if (config.accessKeyId.value().isEmpty() || config.secretKey.value().isEmpty()) {
            client = new AmazonDynamoDBClient(DefaultAWSCredentialsProviderChain.getInstance());
            log.debug("AmazonDynamoDBStreamsClient created with DefaultAWSCredentialsProviderChain");
        } else {
            final BasicAWSCredentials awsCreds = new BasicAWSCredentials(config.accessKeyId.value(), config.secretKey.value());
            client = new AmazonDynamoDBClient(awsCreds);
            log.debug("AmazonDynamoDBClient created with AWS credentials from connector configuration");
        }

        client.configureRegion(config.region);
        remainingRetries = config.maxRetries;
    }

    @Override
    @Trace (metricName="PUBLISH_TO_DYNAMODB", dispatcher=true)
    public void put(Collection<SinkRecord> records) {
        if (records.isEmpty()) return;

        try {
            log.info("LPDEBUG {} records", records.size());
            if (records.size() == 1 || config.batchSize == 1) {
                for (final SinkRecord record : records) {
                    final String tableName = tableName(record);
                    log.info("LPDEBUG 1 tableName {}", tableName);
                    client.putItem(tableName, toPutRequest(record).getItem());
                }
            } else {
                final Iterator<SinkRecord> recordIterator = records.iterator();
                while (recordIterator.hasNext()) {
                    final Map<String, List<WriteRequest>> writesByTable = toWritesByTable(recordIterator);
                    final BatchWriteItemResult batchWriteResponse = client.batchWriteItem(new BatchWriteItemRequest(writesByTable));
                    if (!batchWriteResponse.getUnprocessedItems().isEmpty()) {
                        throw new UnprocessedItemsException(batchWriteResponse.getUnprocessedItems());
                    }
                }
            }
        } catch (LimitExceededException | ProvisionedThroughputExceededException e) {
            log.debug("Write failed with Limit/Throughput Exceeded exception; backing off");
            context.timeout(config.retryBackoffMs);
            NewRelic.noticeError("Write failed with Limit/Throughput Exceeded exception; backing off",false);
            throw new RetriableException(e);
        } catch (AmazonDynamoDBException | UnprocessedItemsException e) {
            log.warn("Write failed, remainingRetries={}", 0, remainingRetries, e);
            if (remainingRetries == 0) {
            	NewRelic.noticeError("Write failed with exception " + e.getMessage(),false);
                throw new ConnectException(e);
            } else {
                remainingRetries--;
                context.timeout(config.retryBackoffMs);
                throw new RetriableException(e);
            }
        }

        remainingRetries = config.maxRetries;
    }

    private Map<String, List<WriteRequest>> toWritesByTable(Iterator<SinkRecord> recordIterator) {
        final Map<String, List<WriteRequest>> writesByTable = new HashMap<>();
        for (int count = 0; recordIterator.hasNext() && count < config.batchSize; count++) {
            final SinkRecord record = recordIterator.next();
            final WriteRequest writeRequest = new WriteRequest(toPutRequest(record));
            final String tableName = tableName(record);
            log.info("LPDEBUG n tableName {}", tableName);
            writesByTable.computeIfAbsent(tableName, k -> new ArrayList<>(config.batchSize)).add(writeRequest);
        }
        return writesByTable;
    }

    private PutRequest toPutRequest(SinkRecord record) {
        final PutRequest put = new PutRequest();
        if (!config.ignoreRecordValue) {
            final Schema schema = record.valueSchema();
            final Object key = record.key();
            log.info("LPDEBUG insert value schema {} {} {}", schema, key.getClass().getCanonicalName(), key);
            insert(ValueSource.RECORD_VALUE, schema, record.value(), put);
        }
        if (!config.ignoreRecordKey) {
            final Schema schema = record.keySchema();
            final Object key = record.key();
            log.info("LPDEBUG insert key schema {} {} {}", schema, key.getClass().getCanonicalName(), key);
            insert(ValueSource.RECORD_KEY, schema, key, put);
        }
        if (config.kafkaCoordinateNames != null) {
            log.info("LPDEBUG insert config.kafkaCoordinateNames {}", config.kafkaCoordinateNames);
            put.addItemEntry(config.kafkaCoordinateNames.topic, new AttributeValue().withS(record.topic()));
            put.addItemEntry(config.kafkaCoordinateNames.partition, new AttributeValue().withN(String.valueOf(record.kafkaPartition())));
            put.addItemEntry(config.kafkaCoordinateNames.offset, new AttributeValue().withN(String.valueOf(record.kafkaOffset())));
        }
        return put;
    }

    private void insert(ValueSource valueSource, Schema schema, Object value, PutRequest put) {
        final AttributeValue attributeValue;
        try {
            attributeValue = schema == null
                    ? AttributeValueConverter.toAttributeValueSchemaless(value)
                    : AttributeValueConverter.toAttributeValue(schema, value);
        } catch (DataException e) {
            log.error("Failed to convert record with schema={} value={}", schema, value, e);
            throw e;
        }

        log.info("ARUN -> " +  attributeValue.getM().keySet());

        if(attributeValue!=null && attributeValue.getM()!=null && attributeValue.getM().containsKey("markets") ){
            log.info("DAVIDE -> " + attributeValue.getM().keySet());
        }


        final String topAttributeName = valueSource.topAttributeName(config);
        if (!topAttributeName.isEmpty()) {
            put.addItemEntry(topAttributeName, attributeValue);
        } else if (attributeValue.getM() != null) {
            put.setItem(attributeValue.getM());
        } else {
            throw new ConnectException("No top attribute name configured for " + valueSource + ", and it could not be converted to Map: " + attributeValue);
        }
    }

    private String tableName(SinkRecord record) {
        return config.tableFormat.replace("${topic}", record.topic());
    }

    @Override
    public void flush(Map<TopicPartition, OffsetAndMetadata> offsets) {
    }

    @Override
    public void stop() {
        if (client != null) {
            client.shutdown();
            client = null;
        }
    }

    @Override
    public String version() {
        return Version.get();
    }

}
