/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.Consumed;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsBuilderTest;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.GlobalKTable;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KeyValueMapper;
import org.apache.kafka.streams.test.ConsumerRecordFactory;
import org.apache.kafka.test.MockProcessorSupplier;
import org.apache.kafka.test.MockValueJoiner;
import org.apache.kafka.test.TestUtils;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.Properties;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class KStreamGlobalKTableLeftJoinTest {

    final private String streamTopic = "streamTopic";
    final private String globalTableTopic = "globalTableTopic";
    final private Serde<Integer> intSerde = Serdes.Integer();
    final private Serde<String> stringSerde = Serdes.String();
    private TopologyTestDriver driver;
    private MockProcessorSupplier<Integer, String> processor;
    private final int[] expectedKeys = {0, 1, 2, 3};
    private StreamsBuilder builder;

    @Before
    public void setUp() throws IOException {

        builder = new StreamsBuilder();
        final KStream<Integer, String> stream;
        final GlobalKTable<String, String> table; // value of stream optionally contains key of table
        final KeyValueMapper<Integer, String, String> keyMapper;

        processor = new MockProcessorSupplier<>();
        final Consumed<Integer, String> streamConsumed = Consumed.with(intSerde, stringSerde);
        final Consumed<String, String> tableConsumed = Consumed.with(stringSerde, stringSerde);
        stream = builder.stream(streamTopic, streamConsumed);
        table = builder.globalTable(globalTableTopic, tableConsumed);
        keyMapper = new KeyValueMapper<Integer, String, String>() {
            @Override
            public String apply(final Integer key, final String value) {
                final String[] tokens = value.split(",");
                // Value is comma delimited. If second token is present, it's the key to the global ktable.
                // If not present, use null to indicate no match
                return tokens.length > 1 ? tokens[1] : null;
            }
        };
        stream.leftJoin(table, keyMapper, MockValueJoiner.TOSTRING_JOINER).process(processor);

        final Properties props = new Properties();
        props.setProperty(StreamsConfig.APPLICATION_ID_CONFIG, "kstream-global-ktable-left-join-test");
        props.setProperty(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9091");
        props.setProperty(StreamsConfig.STATE_DIR_CONFIG, TestUtils.tempDirectory().getAbsolutePath());
        props.setProperty(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.Integer().getClass().getName());
        props.setProperty(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());

        driver = new TopologyTestDriver(builder.build(), props);
    }

    private void pushToStream(final int messageCount, final String valuePrefix, final boolean includeForeignKey) {
        final ConsumerRecordFactory<Integer, String> recordFactory = new ConsumerRecordFactory<>(new IntegerSerializer(), new StringSerializer());
        for (int i = 0; i < messageCount; i++) {
            String value = valuePrefix + expectedKeys[i];
            if (includeForeignKey) {
                value = value + ",FKey" + expectedKeys[i];
            }
            driver.pipeInput(recordFactory.create(streamTopic, expectedKeys[i], value));
        }
    }

    private void pushToGlobalTable(final int messageCount, final String valuePrefix) {
        final ConsumerRecordFactory<String, String> recordFactory = new ConsumerRecordFactory<>(new StringSerializer(), new StringSerializer());
        for (int i = 0; i < messageCount; i++) {
            driver.pipeInput(recordFactory.create(globalTableTopic, "FKey" + expectedKeys[i], valuePrefix + expectedKeys[i]));
        }
    }

    private void pushNullValueToGlobalTable(final int messageCount) {
        final ConsumerRecordFactory<String, String> recordFactory = new ConsumerRecordFactory<>(new StringSerializer(), new StringSerializer());
        for (int i = 0; i < messageCount; i++) {
            driver.pipeInput(recordFactory.create(globalTableTopic, "FKey" + expectedKeys[i], null));
        }
    }

    @Test
    public void shouldNotRequireCopartitioning() {
        final Collection<Set<String>> copartitionGroups = StreamsBuilderTest.getCopartitionedGroups(builder);

        assertEquals("KStream-GlobalKTable joins do not need to be co-partitioned", 0, copartitionGroups.size());
    }

    @Test
    public void shouldNotJoinWithEmptyGlobalTableOnStreamUpdates() {

        // push two items to the primary stream. the globalTable is empty

        pushToStream(2, "X", true);
        processor.checkAndClearProcessResult("0:X0,FKey0+null", "1:X1,FKey1+null");
    }

    @Test
    public void shouldNotJoinOnGlobalTableUpdates() {

        // push two items to the primary stream. the globalTable is empty

        pushToStream(2, "X", true);
        processor.checkAndClearProcessResult("0:X0,FKey0+null", "1:X1,FKey1+null");

        // push two items to the globalTable. this should not produce any item.

        pushToGlobalTable(2, "Y");
        processor.checkAndClearProcessResult();

        // push all four items to the primary stream. this should produce four items.

        pushToStream(4, "X", true);
        processor.checkAndClearProcessResult("0:X0,FKey0+Y0", "1:X1,FKey1+Y1", "2:X2,FKey2+null", "3:X3,FKey3+null");

        // push all items to the globalTable. this should not produce any item

        pushToGlobalTable(4, "YY");
        processor.checkAndClearProcessResult();

        // push all four items to the primary stream. this should produce four items.

        pushToStream(4, "X", true);
        processor.checkAndClearProcessResult("0:X0,FKey0+YY0", "1:X1,FKey1+YY1", "2:X2,FKey2+YY2", "3:X3,FKey3+YY3");

        // push all items to the globalTable. this should not produce any item

        pushToGlobalTable(4, "YYY");
        processor.checkAndClearProcessResult();
    }

    @Test
    public void shouldJoinRegardlessIfMatchFoundOnStreamUpdates() {

        // push two items to the globalTable. this should not produce any item.

        pushToGlobalTable(2, "Y");
        processor.checkAndClearProcessResult();

        // push all four items to the primary stream. this should produce four items.

        pushToStream(4, "X", true);
        processor.checkAndClearProcessResult("0:X0,FKey0+Y0", "1:X1,FKey1+Y1", "2:X2,FKey2+null", "3:X3,FKey3+null");

    }

    @Test
    public void shouldClearGlobalTableEntryOnNullValueUpdates() {

        // push all four items to the globalTable. this should not produce any item.

        pushToGlobalTable(4, "Y");
        processor.checkAndClearProcessResult();

        // push all four items to the primary stream. this should produce four items.

        pushToStream(4, "X", true);
        processor.checkAndClearProcessResult("0:X0,FKey0+Y0", "1:X1,FKey1+Y1", "2:X2,FKey2+Y2", "3:X3,FKey3+Y3");

        // push two items with null to the globalTable as deletes. this should not produce any item.

        pushNullValueToGlobalTable(2);
        processor.checkAndClearProcessResult();

        // push all four items to the primary stream. this should produce four items.

        pushToStream(4, "XX", true);
        processor.checkAndClearProcessResult("0:XX0,FKey0+null", "1:XX1,FKey1+null", "2:XX2,FKey2+Y2", "3:XX3,FKey3+Y3");
    }

    @Test
    public void shouldJoinOnNullKeyMapperValues() {

        // push all items to the globalTable. this should not produce any item

        pushToGlobalTable(4, "Y");
        processor.checkAndClearProcessResult();

        // push all four items to the primary stream with no foreign key, resulting in null keyMapper values.
        // this should produce four items.

        pushToStream(4, "XXX", false);
        processor.checkAndClearProcessResult("0:XXX0+null", "1:XXX1+null", "2:XXX2+null", "3:XXX3+null");
    }

}
