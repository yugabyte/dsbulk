/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.oss.dsbulk.runner.simulacron;

import static com.datastax.oss.driver.api.core.type.DataTypes.BOOLEAN;
import static com.datastax.oss.driver.api.core.type.DataTypes.FLOAT;
import static com.datastax.oss.driver.api.core.type.DataTypes.INT;
import static com.datastax.oss.driver.api.core.type.DataTypes.TEXT;
import static com.datastax.oss.dsbulk.runner.ExitStatus.STATUS_ABORTED_FATAL_ERROR;
import static com.datastax.oss.dsbulk.runner.ExitStatus.STATUS_ABORTED_TOO_MANY_ERRORS;
import static com.datastax.oss.dsbulk.runner.ExitStatus.STATUS_COMPLETED_WITH_ERRORS;
import static com.datastax.oss.dsbulk.runner.ExitStatus.STATUS_OK;
import static com.datastax.oss.dsbulk.runner.tests.EndToEndUtils.INSERT_INTO_IP_BY_COUNTRY;
import static com.datastax.oss.dsbulk.runner.tests.EndToEndUtils.IP_BY_COUNTRY_MAPPING_NAMED;
import static com.datastax.oss.dsbulk.runner.tests.EndToEndUtils.SELECT_FROM_IP_BY_COUNTRY;
import static com.datastax.oss.dsbulk.runner.tests.EndToEndUtils.assertStatus;
import static com.datastax.oss.dsbulk.runner.tests.EndToEndUtils.createParameterizedQuery;
import static com.datastax.oss.dsbulk.runner.tests.EndToEndUtils.createQueryWithError;
import static com.datastax.oss.dsbulk.runner.tests.EndToEndUtils.createQueryWithResultSet;
import static com.datastax.oss.dsbulk.runner.tests.EndToEndUtils.createSimpleParameterizedQuery;
import static com.datastax.oss.dsbulk.runner.tests.EndToEndUtils.primeIpByCountryTable;
import static com.datastax.oss.dsbulk.runner.tests.EndToEndUtils.validateExceptionsLog;
import static com.datastax.oss.dsbulk.runner.tests.EndToEndUtils.validateNumberOfBadRecords;
import static com.datastax.oss.dsbulk.runner.tests.EndToEndUtils.validateOutputFiles;
import static com.datastax.oss.dsbulk.runner.tests.EndToEndUtils.validatePrepare;
import static com.datastax.oss.dsbulk.runner.tests.EndToEndUtils.validateQueryCount;
import static com.datastax.oss.dsbulk.tests.logging.StreamType.STDERR;
import static com.datastax.oss.dsbulk.tests.logging.StreamType.STDOUT;
import static com.datastax.oss.simulacron.common.codec.ConsistencyLevel.LOCAL_ONE;
import static com.datastax.oss.simulacron.common.codec.ConsistencyLevel.ONE;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.datastax.oss.driver.shaded.guava.common.collect.Lists;
import com.datastax.oss.dsbulk.config.ConfigUtils;
import com.datastax.oss.dsbulk.connectors.api.ConnectorFeature;
import com.datastax.oss.dsbulk.connectors.api.Record;
import com.datastax.oss.dsbulk.connectors.json.JsonConnector;
import com.datastax.oss.dsbulk.runner.DataStaxBulkLoader;
import com.datastax.oss.dsbulk.runner.ExitStatus;
import com.datastax.oss.dsbulk.runner.tests.JsonUtils;
import com.datastax.oss.dsbulk.runner.tests.MockConnector;
import com.datastax.oss.dsbulk.tests.logging.LogCapture;
import com.datastax.oss.dsbulk.tests.logging.LogInterceptor;
import com.datastax.oss.dsbulk.tests.logging.StreamCapture;
import com.datastax.oss.dsbulk.tests.logging.StreamInterceptor;
import com.datastax.oss.dsbulk.tests.simulacron.SimulacronUtils;
import com.datastax.oss.dsbulk.tests.simulacron.SimulacronUtils.Column;
import com.datastax.oss.dsbulk.tests.simulacron.SimulacronUtils.Table;
import com.datastax.oss.dsbulk.tests.utils.FileUtils;
import com.datastax.oss.dsbulk.tests.utils.StringUtils;
import com.datastax.oss.simulacron.common.cluster.RequestPrime;
import com.datastax.oss.simulacron.common.codec.WriteType;
import com.datastax.oss.simulacron.common.result.FunctionFailureResult;
import com.datastax.oss.simulacron.common.result.ReadTimeoutResult;
import com.datastax.oss.simulacron.common.result.SuccessResult;
import com.datastax.oss.simulacron.common.result.SyntaxErrorResult;
import com.datastax.oss.simulacron.common.result.WriteFailureResult;
import com.datastax.oss.simulacron.common.result.WriteTimeoutResult;
import com.datastax.oss.simulacron.common.stubbing.Prime;
import com.datastax.oss.simulacron.server.BoundCluster;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.reactivestreams.Publisher;

class JsonEndToEndSimulacronIT extends EndToEndSimulacronITBase {

  private Path urlFileTwoFiles;
  private Path urlFileOneFileOneDir;
  private Path urlFileTwoDirs;

  JsonEndToEndSimulacronIT(
      BoundCluster simulacron,
      @LogCapture(loggerName = "com.datastax.oss.dsbulk") LogInterceptor logs,
      @StreamCapture(STDOUT) StreamInterceptor stdOut,
      @StreamCapture(STDERR) StreamInterceptor stdErr) {
    super(simulacron, logs, stdOut, stdErr);
  }

  @BeforeAll
  void setupURLFiles() throws IOException {
    urlFileTwoFiles =
        FileUtils.createURLFile(
            JsonUtils.JSON_RECORDS_UNIQUE_PART_1, JsonUtils.JSON_RECORDS_UNIQUE_PART_2);
    urlFileOneFileOneDir =
        FileUtils.createURLFile(
            JsonUtils.JSON_RECORDS_UNIQUE_PART_1_DIR, JsonUtils.JSON_RECORDS_UNIQUE_PART_2);
    urlFileTwoDirs =
        FileUtils.createURLFile(
            JsonUtils.JSON_RECORDS_UNIQUE_PART_1_DIR, JsonUtils.JSON_RECORDS_UNIQUE_PART_2_DIR);
  }

  @AfterAll
  void cleanupURLFiles() throws IOException {
    Files.delete(urlFileTwoFiles);
    Files.delete(urlFileOneFileOneDir);
    Files.delete(urlFileTwoDirs);
  }

  @Test
  void full_load() {

    primeIpByCountryTable(simulacron);
    RequestPrime insert = createSimpleParameterizedQuery(INSERT_INTO_IP_BY_COUNTRY);
    simulacron.prime(new Prime(insert));

    String[] args = {
      "load",
      "-c",
      "json",
      "--connector.json.url",
      StringUtils.quoteJson(JsonUtils.JSON_RECORDS_UNIQUE),
      "--schema.keyspace",
      "ks1",
      "--schema.query",
      INSERT_INTO_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING_NAMED
    };

    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_OK);
    assertThat(logs.getAllMessagesAsString())
        .contains("Records: total: 24, successful: 24, failed: 0")
        .contains("Batches: total: 24, size: 1.00 mean, 1 min, 1 max")
        .contains("Writes: total: 24, successful: 24, failed: 0");
    validateQueryCount(simulacron, 24, "INSERT INTO ip_by_country", LOCAL_ONE);
  }

  @ParameterizedTest
  @MethodSource("multipleUrlsProvider")
  void full_load_multiple_urls(Path urlfile) {

    primeIpByCountryTable(simulacron);
    RequestPrime insert = createSimpleParameterizedQuery(INSERT_INTO_IP_BY_COUNTRY);
    simulacron.prime(new Prime(insert));

    String[] args = {
      "load",
      "-c",
      "json",
      "--connector.json.urlfile",
      StringUtils.quoteJson(urlfile.toAbsolutePath()),
      "--schema.keyspace",
      "ks1",
      "--schema.query",
      INSERT_INTO_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING_NAMED
    };

    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_OK);
    assertThat(logs.getAllMessagesAsString())
        .contains("Records: total: 24, successful: 24, failed: 0")
        .contains("Batches: total: 24, size: 1.00 mean, 1 min, 1 max")
        .contains("Writes: total: 24, successful: 24, failed: 0");
    validateQueryCount(simulacron, 24, "INSERT INTO ip_by_country", LOCAL_ONE);
  }

  @SuppressWarnings("unused")
  private List<Arguments> multipleUrlsProvider() {
    return Lists.newArrayList(
        arguments(urlFileTwoFiles), arguments(urlFileOneFileOneDir), arguments(urlFileTwoDirs));
  }

  @Test
  void full_load_dry_run() {

    primeIpByCountryTable(simulacron);
    RequestPrime insert = createSimpleParameterizedQuery(INSERT_INTO_IP_BY_COUNTRY);
    simulacron.prime(new Prime(insert));

    String[] args = {
      "load",
      "-c",
      "json",
      "--connector.json.url",
      StringUtils.quoteJson(JsonUtils.JSON_RECORDS_UNIQUE),
      "-dryRun",
      "true",
      "--schema.keyspace",
      "ks1",
      "--schema.query",
      INSERT_INTO_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING_NAMED
    };

    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_OK);
    validateQueryCount(simulacron, 0, "INSERT INTO ip_by_country", ONE);
  }

  @Test
  void full_load_crlf() {

    primeIpByCountryTable(simulacron);
    RequestPrime insert = createSimpleParameterizedQuery(INSERT_INTO_IP_BY_COUNTRY);
    simulacron.prime(new Prime(insert));

    String[] args = {
      "load",
      "-c",
      "json",
      "--connector.json.url",
      StringUtils.quoteJson(JsonUtils.JSON_RECORDS_CRLF),
      "--schema.keyspace",
      "ks1",
      "--schema.query",
      INSERT_INTO_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING_NAMED
    };

    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_OK);
    validateQueryCount(simulacron, 24, "INSERT INTO ip_by_country", LOCAL_ONE);
  }

  @Test
  void full_load_custom_features() {

    SimulacronUtils.primeTables(
        simulacron,
        new SimulacronUtils.Keyspace(
            "ks1", new Table("table1", new Column("key", INT), new Column("value", FLOAT))));

    String[] args = {
      "load",
      "-c",
      "json",
      "--connector.json.url",
      StringUtils.quoteJson(JsonUtils.JSON_RECORDS_WITH_COMMENTS),
      "--schema.query",
      "INSERT INTO ks1.table1 (key, value) VALUES (:key, :value)",
      "--connector.json.parserFeatures",
      "{ALLOW_COMMENTS = true}",
      "--connector.json.deserializationFeatures",
      "{USE_BIG_INTEGER_FOR_INTS = false, USE_BIG_DECIMAL_FOR_FLOATS = false}",
    };

    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_OK);
    validateQueryCount(
        simulacron, 1, "INSERT INTO ks1.table1 (key, value) VALUES (:key, :value)", LOCAL_ONE);
  }

  @Test
  void partial_load() throws Exception {

    primeIpByCountryTable(simulacron);
    RequestPrime insert = createSimpleParameterizedQuery(INSERT_INTO_IP_BY_COUNTRY);
    simulacron.prime(new Prime(insert));

    String[] args = {
      "load",
      "-c",
      "json",
      "--connector.json.url",
      StringUtils.quoteJson(JsonUtils.JSON_RECORDS_PARTIAL_BAD),
      "--schema.keyspace",
      "ks1",
      "--schema.query",
      INSERT_INTO_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING_NAMED,
      "--schema.allowMissingFields",
      "true"
    };

    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_COMPLETED_WITH_ERRORS);
    validateQueryCount(simulacron, 21, "INSERT INTO ip_by_country", LOCAL_ONE);
    validateNumberOfBadRecords(2);
    validateExceptionsLog(2, "Source:", "mapping-errors.log");
  }

  @Test
  void load_errors() throws Exception {

    primeIpByCountryTable(simulacron);

    Map<String, Object> params = new HashMap<>();
    params.put("country_name", "Sweden");
    RequestPrime prime1 =
        createParameterizedQuery(
            INSERT_INTO_IP_BY_COUNTRY, params, new SuccessResult(emptyList(), emptyMap()));
    simulacron.prime(new Prime(prime1));

    // recoverable errors only

    params.put("country_name", "France");
    prime1 =
        createParameterizedQuery(
            INSERT_INTO_IP_BY_COUNTRY, params, new ReadTimeoutResult(LOCAL_ONE, 1, 0, false));
    simulacron.prime(new Prime(prime1));

    params.put("country_name", "Gregistan");
    prime1 =
        createParameterizedQuery(
            INSERT_INTO_IP_BY_COUNTRY,
            params,
            new WriteTimeoutResult(LOCAL_ONE, 0, 0, WriteType.BATCH_LOG));
    simulacron.prime(new Prime(prime1));

    params.put("country_name", "Andybaijan");
    prime1 =
        createParameterizedQuery(
            INSERT_INTO_IP_BY_COUNTRY,
            params,
            new WriteFailureResult(ONE, 0, 0, emptyMap(), WriteType.BATCH));
    simulacron.prime(new Prime(prime1));

    params = new HashMap<>();
    params.put("country_name", "United States");
    prime1 =
        createParameterizedQuery(
            INSERT_INTO_IP_BY_COUNTRY,
            params,
            new FunctionFailureResult("keyspace", "function", emptyList(), "bad function call"));
    simulacron.prime(new Prime(prime1));

    String[] args = {
      "load",
      "-c",
      "json",
      "--connector.json.url",
      StringUtils.quoteJson(JsonUtils.JSON_RECORDS_ERROR),
      "--driver.advanced.retry-policy.max-retries",
      "1",
      "--schema.keyspace",
      "ks1",
      "--schema.query",
      INSERT_INTO_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING_NAMED
    };

    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_COMPLETED_WITH_ERRORS);

    // There are 24 rows of data, but two extra queries due to the retry for the write timeout and
    // the unavailable.
    validateQueryCount(simulacron, 26, "INSERT INTO ip_by_country", LOCAL_ONE);
    validateNumberOfBadRecords(4);
    validateExceptionsLog(4, "Source:", "load-errors.log");
  }

  @Test
  void skip_test_load() throws Exception {

    primeIpByCountryTable(simulacron);
    RequestPrime insert = createSimpleParameterizedQuery(INSERT_INTO_IP_BY_COUNTRY);
    simulacron.prime(new Prime(insert));

    String[] args = {
      "load",
      "-c",
      "json",
      "--connector.json.url",
      StringUtils.quoteJson(JsonUtils.JSON_RECORDS_SKIP),
      "--connector.json.skipRecords",
      "3",
      "--connector.json.maxRecords",
      "24",
      "--schema.keyspace",
      "ks1",
      "--schema.query",
      INSERT_INTO_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING_NAMED,
      "--schema.allowMissingFields",
      "true"
    };

    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_COMPLETED_WITH_ERRORS);
    validateQueryCount(simulacron, 21, "INSERT INTO ip_by_country", LOCAL_ONE);
    validateNumberOfBadRecords(3);
    validateExceptionsLog(3, "Source:", "mapping-errors.log");
  }

  @Test
  void error_load_missing_field() throws Exception {

    SimulacronUtils.primeTables(
        simulacron,
        new SimulacronUtils.Keyspace(
            "ks1",
            new Table(
                "table1",
                new Column("a", INT),
                new Column("b", TEXT),
                new Column("c", BOOLEAN),
                new Column("d", INT))));

    String[] args = {
      "load",
      "-c",
      "json",
      "--log.maxErrors",
      "2",
      "--log.verbosity",
      "2",
      "--connector.json.url",
      StringUtils.quoteJson(getClass().getResource("/missing-extra.json")),
      "--schema.query",
      "INSERT INTO ks1.table1 (a, b, c, d) VALUES (:a, :b, :c, :d)",
      "--schema.mapping",
      "A = a, B = b, C = c, D = d",
      "--schema.allowMissingFields",
      "false"
    };
    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_ABORTED_TOO_MANY_ERRORS);
    assertThat(logs.getAllMessagesAsString())
        .contains("aborted: Too many errors, the maximum allowed is 2")
        .contains("Records: total: 3, successful: 0, failed: 3");
    validateNumberOfBadRecords(3);
    validateExceptionsLog(
        2, "Required field C (mapped to column c) was missing from record", "mapping-errors.log");
    validateExceptionsLog(
        1, "Required field D (mapped to column d) was missing from record", "mapping-errors.log");
  }

  @Test
  void error_load_extra_field() throws Exception {

    SimulacronUtils.primeTables(
        simulacron,
        new SimulacronUtils.Keyspace(
            "ks1", new Table("table1", new Column("a", INT), new Column("b", TEXT))));

    String[] args = {
      "load",
      "-c",
      "json",
      "--log.maxErrors",
      "1",
      "--log.verbosity",
      "2",
      "--connector.json.url",
      StringUtils.quoteJson(getClass().getResource("/missing-extra.json")),
      "--schema.query",
      "INSERT INTO ks1.table1 (a, b) VALUES (:a, :b)",
      "--schema.mapping",
      "A = a, B = b",
      "--schema.allowExtraFields",
      "false"
    };
    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_ABORTED_TOO_MANY_ERRORS);
    assertThat(logs.getAllMessagesAsString())
        .contains("aborted: Too many errors, the maximum allowed is 1")
        .contains("Records: total: 3, successful: 1, failed: 2");
    validateNumberOfBadRecords(2);
    validateExceptionsLog(1, "Extraneous field C was found in record", "mapping-errors.log");
    validateExceptionsLog(1, "Extraneous field D was found in record", "mapping-errors.log");
  }

  @Test
  void full_unload() throws Exception {

    primeIpByCountryTable(simulacron);
    RequestPrime prime = createQueryWithResultSet(SELECT_FROM_IP_BY_COUNTRY, 24);
    simulacron.prime(new Prime(prime));

    String[] args = {
      "unload",
      "-c",
      "json",
      "--connector.json.url",
      StringUtils.quoteJson(unloadDir),
      "--schema.keyspace",
      "ks1",
      "--schema.query",
      SELECT_FROM_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING_NAMED
    };

    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_OK);
    assertThat(logs.getAllMessagesAsString())
        .contains("Records: total: 24, successful: 24, failed: 0")
        .contains("Reads: total: 24, successful: 24, failed: 0");
    validateQueryCount(simulacron, 1, SELECT_FROM_IP_BY_COUNTRY, LOCAL_ONE);
    validateOutputFiles(24, unloadDir);
  }

  @Test
  void full_unload_large_result_set() throws Exception {

    primeIpByCountryTable(simulacron);
    RequestPrime prime = createQueryWithResultSet(SELECT_FROM_IP_BY_COUNTRY, 10_000);
    simulacron.prime(new Prime(prime));

    String[] args = {
      "unload",
      "-c",
      "json",
      "--connector.json.url",
      StringUtils.quoteJson(unloadDir),
      "--connector.json.maxConcurrentFiles",
      "1C",
      "--schema.keyspace",
      "ks1",
      "--schema.query",
      SELECT_FROM_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING_NAMED
    };

    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_OK);
    validateQueryCount(simulacron, 1, SELECT_FROM_IP_BY_COUNTRY, LOCAL_ONE);
    validateOutputFiles(10_000, unloadDir);
  }

  @Test
  void full_unload_custom_features() throws Exception {

    List<Map<String, Object>> rows = new ArrayList<>();
    HashMap<String, Object> row = new HashMap<>();
    row.put("pk", 1);
    row.put("c1", null);
    rows.add(row);

    SimulacronUtils.primeTables(
        simulacron,
        new SimulacronUtils.Keyspace(
            "ks1",
            new Table(
                "table1",
                singletonList(new Column("pk", INT)),
                emptyList(),
                singletonList(new Column("c1", TEXT)),
                rows)));

    String[] args = {
      "unload",
      "-c",
      "json",
      "--connector.json.url",
      StringUtils.quoteJson(unloadDir),
      "--connector.json.maxConcurrentFiles",
      "1",
      "--schema.query",
      "SELECT pk, c1 FROM ks1.table1",
      "--connector.json.generatorFeatures",
      "{QUOTE_FIELD_NAMES = false}",
      "--connector.json.serializationStrategy",
      "NON_NULL"
    };

    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_OK);
    validateQueryCount(
        simulacron,
        1,
        "SELECT pk, c1 FROM ks1.table1 WHERE token(pk) >= :ystart AND token(pk) < :yend",
        LOCAL_ONE);
    validateOutputFiles(1, unloadDir);
    Optional<String> line = FileUtils.readAllLinesInDirectoryAsStream(unloadDir).findFirst();
    assertThat(line).isPresent().hasValue("{pk:1}");
  }

  @Test
  void unload_failure_during_read_single_thread() {

    primeIpByCountryTable(simulacron);
    RequestPrime prime =
        createQueryWithError(
            SELECT_FROM_IP_BY_COUNTRY, new SyntaxErrorResult("Invalid table", 0L, false));
    simulacron.prime(new Prime(prime));

    String[] args = {
      "unload",
      "-c",
      "json",
      "--connector.json.url",
      StringUtils.quoteJson(unloadDir),
      "--connector.json.maxConcurrentFiles",
      "1",
      "--schema.keyspace",
      "ks1",
      "--schema.query",
      SELECT_FROM_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING_NAMED
    };

    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_ABORTED_FATAL_ERROR);
    validateQueryCount(simulacron, 0, SELECT_FROM_IP_BY_COUNTRY, LOCAL_ONE);
    validatePrepare(simulacron, SELECT_FROM_IP_BY_COUNTRY);
  }

  @Test
  void unload_failure_during_read_multi_thread() {

    primeIpByCountryTable(simulacron);
    RequestPrime prime =
        createQueryWithError(
            SELECT_FROM_IP_BY_COUNTRY, new SyntaxErrorResult("Invalid table", 0L, false));
    simulacron.prime(new Prime(prime));

    String[] args = {
      "unload",
      "-c",
      "json",
      "--connector.json.url",
      StringUtils.quoteJson(unloadDir),
      "--connector.json.maxConcurrentFiles",
      "4",
      "--schema.keyspace",
      "ks1",
      "--schema.query",
      SELECT_FROM_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING_NAMED
    };

    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_ABORTED_FATAL_ERROR);
    validateQueryCount(simulacron, 0, SELECT_FROM_IP_BY_COUNTRY, LOCAL_ONE);
    validatePrepare(simulacron, SELECT_FROM_IP_BY_COUNTRY);
  }

  @Test
  void unload_write_error() {

    Path file1 = unloadDir.resolve("output-000001.json");
    Path file2 = unloadDir.resolve("output-000002.json");
    Path file3 = unloadDir.resolve("output-000003.json");
    Path file4 = unloadDir.resolve("output-000004.json");

    MockConnector.setDelegate(
        new JsonConnector() {

          @Override
          public void configure(@NonNull Config settings, boolean read) {
            settings =
                ConfigFactory.parseString(
                        "url = " + StringUtils.quoteJson(unloadDir) + ", maxConcurrentFiles = 4")
                    .withFallback(
                        ConfigUtils.createReferenceConfig().getConfig("dsbulk.connector.json"));
            super.configure(settings, read);
          }

          @NonNull
          @Override
          public Function<Publisher<Record>, Publisher<Record>> write() {
            // will cause the write workers to fail because the files already exist
            try {
              Files.createFile(file1);
              Files.createFile(file2);
              Files.createFile(file3);
              Files.createFile(file4);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
            return super.write();
          }

          @Override
          public boolean supports(@NonNull ConnectorFeature feature) {
            return true;
          }
        });

    primeIpByCountryTable(simulacron);
    RequestPrime prime = createQueryWithResultSet(SELECT_FROM_IP_BY_COUNTRY, 10);
    simulacron.prime(new Prime(prime));

    String[] args = {
      "unload",
      "--connector.name",
      "mock",
      "--schema.keyspace",
      "ks1",
      "--schema.query",
      SELECT_FROM_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING_NAMED
    };

    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_ABORTED_FATAL_ERROR);
    assertThat(stdErr.getStreamAsString())
        .contains("failed")
        .containsPattern("output-00000[1-4].json");
    assertThat(logs.getAllMessagesAsString())
        .contains("failed")
        .containsPattern("output-00000[1-4].json");
  }

  @Test
  void validate_stdout() {

    primeIpByCountryTable(simulacron);
    RequestPrime prime = createQueryWithResultSet(SELECT_FROM_IP_BY_COUNTRY, 24);
    simulacron.prime(new Prime(prime));

    String[] args = {
      "unload",
      "-c",
      "json",
      "--connector.json.url",
      "-",
      "--schema.keyspace",
      "ks1",
      "--schema.query",
      SELECT_FROM_IP_BY_COUNTRY,
      "--schema.mapping",
      IP_BY_COUNTRY_MAPPING_NAMED
    };

    ExitStatus status = new DataStaxBulkLoader(addCommonSettings(args)).run();
    assertStatus(status, STATUS_OK);
    validateQueryCount(simulacron, 1, SELECT_FROM_IP_BY_COUNTRY, LOCAL_ONE);
    assertThat(stdOut.getStreamLines().size()).isEqualTo(24);
  }
}
