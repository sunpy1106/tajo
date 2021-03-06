/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.tajo.catalog;

import com.google.common.collect.Sets;
import org.apache.hadoop.fs.Path;
import org.apache.tajo.TajoConstants;
import org.apache.tajo.catalog.exception.CatalogException;
import org.apache.tajo.catalog.exception.NoSuchFunctionException;
import org.apache.tajo.catalog.function.Function;
import org.apache.tajo.catalog.partition.PartitionMethodDesc;
import org.apache.tajo.catalog.proto.CatalogProtos;
import org.apache.tajo.catalog.proto.CatalogProtos.FunctionType;
import org.apache.tajo.catalog.proto.CatalogProtos.IndexMethod;
import org.apache.tajo.catalog.proto.CatalogProtos.StoreType;
import org.apache.tajo.catalog.store.DerbyStore;
import org.apache.tajo.catalog.store.MySQLStore;
import org.apache.tajo.common.TajoDataTypes;
import org.apache.tajo.common.TajoDataTypes.Type;
import org.apache.tajo.conf.TajoConf;
import org.apache.tajo.util.CommonTestingUtil;
import org.apache.tajo.util.TUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.*;

import static org.apache.tajo.TajoConstants.DEFAULT_DATABASE_NAME;
import static org.apache.tajo.catalog.CatalogConstants.CATALOG_URI;
import static org.junit.Assert.*;

public class TestCatalog {
	static final String FieldName1="f1";
	static final String FieldName2="f2";
	static final String FieldName3="f3";	

	Schema schema1;
	
	static CatalogServer server;
	static CatalogService catalog;

	@BeforeClass
	public static void setUp() throws Exception {
    final String HCATALOG_CLASS_NAME = "org.apache.tajo.catalog.store.HCatalogStore";

    String driverClass = System.getProperty(CatalogConstants.STORE_CLASS);

    // here, we don't choose HCatalogStore due to some dependency problems.
    if (driverClass == null || driverClass.equals(HCATALOG_CLASS_NAME)) {
      driverClass = DerbyStore.class.getCanonicalName();
    }
    String catalogURI = System.getProperty(CatalogConstants.CATALOG_URI);
    if (catalogURI == null) {
      Path path = CommonTestingUtil.getTestDir();
      catalogURI = String.format("jdbc:derby:%s/db;create=true", path.toUri().getPath());
    }
    String connectionId = System.getProperty(CatalogConstants.CONNECTION_ID);
    String password = System.getProperty(CatalogConstants.CONNECTION_PASSWORD);

    TajoConf conf = new TajoConf();
    conf.set(CatalogConstants.STORE_CLASS, driverClass);
    conf.set(CATALOG_URI, catalogURI);
    conf.setVar(TajoConf.ConfVars.CATALOG_ADDRESS, "127.0.0.1:0");

    // MySQLStore requires password
    if (driverClass.equals(MySQLStore.class.getCanonicalName())) {
      if (connectionId == null) {
        throw new CatalogException(String.format("%s driver requires %s", driverClass, CatalogConstants.CONNECTION_ID));
      }
      conf.set(CatalogConstants.CONNECTION_ID, connectionId);
      if (password != null) {
        conf.set(CatalogConstants.CONNECTION_PASSWORD, password);
      }
    }

    Path defaultTableSpace = CommonTestingUtil.getTestDir();

	  server = new CatalogServer();
    server.init(conf);
    server.start();
    catalog = new LocalCatalogWrapper(server);
    if (!catalog.existTablespace(TajoConstants.DEFAULT_TABLESPACE_NAME)) {
      catalog.createTablespace(TajoConstants.DEFAULT_TABLESPACE_NAME, defaultTableSpace.toUri().toString());
    }
    if (!catalog.existDatabase(DEFAULT_DATABASE_NAME)) {
      catalog.createDatabase(DEFAULT_DATABASE_NAME, TajoConstants.DEFAULT_TABLESPACE_NAME);
    }

    for(String table : catalog.getAllTableNames(DEFAULT_DATABASE_NAME)) {
      catalog.dropTable(table);
    }
	}
	
	@AfterClass
	public static void tearDown() throws IOException {
	  server.stop();
	}

  @Test
  public void testCreateAndDropDatabases() throws Exception {
    assertFalse(catalog.existDatabase("testCreateAndDropDatabases"));
    assertTrue(catalog.createDatabase("testCreateAndDropDatabases", TajoConstants.DEFAULT_TABLESPACE_NAME));
    assertTrue(catalog.existDatabase("testCreateAndDropDatabases"));
    assertTrue(catalog.dropDatabase("testCreateAndDropDatabases"));
  }

  @Test
  public void testCreateAndDropManyDatabases() throws Exception {
    List<String> createdDatabases = new ArrayList<String>();
    String namePrefix = "database_";
    final int NUM = 10;
    for (int i = 0; i < NUM; i++) {
      String databaseName = namePrefix + i;
      assertFalse(catalog.existDatabase(databaseName));
      assertTrue(catalog.createDatabase(databaseName, TajoConstants.DEFAULT_TABLESPACE_NAME));
      assertTrue(catalog.existDatabase(databaseName));
      createdDatabases.add(databaseName);
    }

    Collection<String> allDatabaseNames = catalog.getAllDatabaseNames();
    for (String databaseName : allDatabaseNames) {
      assertTrue(databaseName.equals(DEFAULT_DATABASE_NAME) || createdDatabases.contains(databaseName));
    }
    // additional one is 'default' database.
    assertEquals(NUM + 1, allDatabaseNames.size());

    Collections.shuffle(createdDatabases);
    for (String tobeDropped : createdDatabases) {
      assertTrue(catalog.existDatabase(tobeDropped));
      assertTrue(catalog.dropDatabase(tobeDropped));
      assertFalse(catalog.existDatabase(tobeDropped));
    }
  }

  private TableDesc createMockupTable(String databaseName, String tableName) throws IOException {
    schema1 = new Schema();
    schema1.addColumn(FieldName1, Type.BLOB);
    schema1.addColumn(FieldName2, Type.INT4);
    schema1.addColumn(FieldName3, Type.INT8);
    Path path = new Path(CommonTestingUtil.getTestDir(), tableName);
    TableDesc table = new TableDesc(
        CatalogUtil.buildFQName(databaseName, tableName),
        schema1,
        new TableMeta(StoreType.CSV, new Options()),
        path, true);
    return table;
  }

  @Test
  public void testCreateAndDropTable() throws Exception {
    assertTrue(catalog.createDatabase("tmpdb1", TajoConstants.DEFAULT_TABLESPACE_NAME));
    assertTrue(catalog.existDatabase("tmpdb1"));
    assertTrue(catalog.createDatabase("tmpdb2", TajoConstants.DEFAULT_TABLESPACE_NAME));
    assertTrue(catalog.existDatabase("tmpdb2"));

    TableDesc table1 = createMockupTable("tmpdb1", "table1");
    assertTrue(catalog.createTable(table1));

    TableDesc table2 = createMockupTable("tmpdb2", "table2");
    assertTrue(catalog.createTable(table2));

    Set<String> tmpdb1 = Sets.newHashSet(catalog.getAllTableNames("tmpdb1"));
    assertEquals(1, tmpdb1.size());
    assertTrue(tmpdb1.contains("table1"));


    Set<String> tmpdb2 = Sets.newHashSet(catalog.getAllTableNames("tmpdb2"));
    assertEquals(1, tmpdb2.size());
    assertTrue(tmpdb2.contains("table2"));

    assertTrue(catalog.dropDatabase("tmpdb1"));
    assertFalse(catalog.existDatabase("tmpdb1"));

    tmpdb2 = Sets.newHashSet(catalog.getAllTableNames("tmpdb2"));
    assertEquals(1, tmpdb2.size());
    assertTrue(tmpdb2.contains("table2"));

    assertTrue(catalog.dropDatabase("tmpdb2"));
    assertFalse(catalog.existDatabase("tmpdb2"));
  }

  static String dbPrefix = "db_";
  static String tablePrefix = "tb_";
  static final int DB_NUM = 5;
  static final int TABLE_NUM_PER_DB = 3;
  static final int TOTAL_TABLE_NUM = DB_NUM * TABLE_NUM_PER_DB;

  private Map<String, List<String>> createBaseDatabaseAndTables() throws IOException {

    Map<String, List<String>> createdDatabaseAndTablesMap = new HashMap<String, List<String>>();

    // add and divide all tables to multiple databases in a round robin manner
    for (int tableId = 0; tableId < TOTAL_TABLE_NUM; tableId++) {
      int dbIdx = tableId % DB_NUM;
      String databaseName = dbPrefix + dbIdx;

      if (!catalog.existDatabase(databaseName)) {
        assertTrue(catalog.createDatabase(databaseName, TajoConstants.DEFAULT_TABLESPACE_NAME));
      }

      String tableName = tablePrefix + tableId;
      TableDesc table = createMockupTable(databaseName, tableName);
      assertTrue(catalog.createTable(table));

      TUtil.putToNestedList(createdDatabaseAndTablesMap, databaseName, tableName);
    }

    // checking all tables for each database
    for (int dbIdx = 0; dbIdx < DB_NUM; dbIdx++) {
      String databaseName = dbPrefix + dbIdx;

      Collection<String> tableNames = catalog.getAllTableNames(databaseName);
      assertTrue(createdDatabaseAndTablesMap.containsKey(databaseName));

      assertEquals(createdDatabaseAndTablesMap.get(databaseName).size(), tableNames.size());
      for (String tableName : tableNames) {
        assertTrue(createdDatabaseAndTablesMap.get(databaseName).contains(tableName));
      }
    }

    return createdDatabaseAndTablesMap;
  }

  @Test
  public void testDropDatabaseWithAllTables() throws Exception {
    Map<String, List<String>> createdTablesMap = createBaseDatabaseAndTables();

    // Each time we drop one database, check all databases and their tables.
    Iterator<String> it = new ArrayList<String>(createdTablesMap.keySet()).iterator();
    while(it.hasNext()) {
      // drop one database
      String databaseName = it.next();
      assertTrue(catalog.existDatabase(databaseName));
      catalog.dropDatabase(databaseName);
      createdTablesMap.remove(databaseName);

      // check all tables which belong to other databases
      for (Map.Entry<String, List<String>> entry : createdTablesMap.entrySet()) {
        assertTrue(catalog.existDatabase(entry.getKey()));

        // checking all tables for this database
        Collection<String> tablesForThisDatabase = catalog.getAllTableNames(entry.getKey());
        assertEquals(createdTablesMap.get(entry.getKey()).size(), tablesForThisDatabase.size());
        for (String tableName : tablesForThisDatabase) {
          assertTrue(createdTablesMap.get(entry.getKey()).contains(CatalogUtil.extractSimpleName(tableName)));
        }
      }
    }

    // Finally, only default database will remain. So, its result is 1.
    assertEquals(1, catalog.getAllDatabaseNames().size());
  }
	
	@Test
	public void testGetTable() throws Exception {
		schema1 = new Schema();
		schema1.addColumn(FieldName1, Type.BLOB);
		schema1.addColumn(FieldName2, Type.INT4);
		schema1.addColumn(FieldName3, Type.INT8);
    Path path = new Path(CommonTestingUtil.getTestDir(), "table1");
    TableDesc meta = new TableDesc(
        CatalogUtil.buildFQName(DEFAULT_DATABASE_NAME, "getTable"),
        schema1,
        StoreType.CSV,
        new Options(),
        path);

		assertFalse(catalog.existsTable(DEFAULT_DATABASE_NAME, "getTable"));
    catalog.createTable(meta);
    assertTrue(catalog.existsTable(DEFAULT_DATABASE_NAME, "getTable"));

    catalog.dropTable(CatalogUtil.buildFQName(DEFAULT_DATABASE_NAME, "getTable"));
    assertFalse(catalog.existsTable(DEFAULT_DATABASE_NAME, "getTable"));
	}

  static IndexDesc desc1;
  static IndexDesc desc2;
  static IndexDesc desc3;

  static {
    desc1 = new IndexDesc(
        "idx_test", DEFAULT_DATABASE_NAME, "indexed", new Column("id", Type.INT4),
        IndexMethod.TWO_LEVEL_BIN_TREE, true, true, true);

    desc2 = new IndexDesc(
        "idx_test2", DEFAULT_DATABASE_NAME, "indexed", new Column("score", Type.FLOAT8),
        IndexMethod.TWO_LEVEL_BIN_TREE, false, false, false);

    desc3 = new IndexDesc(
        "idx_test", DEFAULT_DATABASE_NAME, "indexed", new Column("id", Type.INT4),
        IndexMethod.TWO_LEVEL_BIN_TREE, true, true, true);
  }

  public static TableDesc prepareTable() throws IOException {
    Schema schema = new Schema();
    schema.addColumn("indexed.id", Type.INT4)
        .addColumn("indexed.name", Type.TEXT)
        .addColumn("indexed.age", Type.INT4)
        .addColumn("indexed.score", Type.FLOAT8);

    String tableName = "indexed";

    TableMeta meta = CatalogUtil.newTableMeta(StoreType.CSV);
    return new TableDesc(
        CatalogUtil.buildFQName(TajoConstants.DEFAULT_DATABASE_NAME, tableName), schema, meta,
        new Path(CommonTestingUtil.getTestDir(), "indexed"));
  }

  @Test
  public void testCreateSameTables() throws IOException {
    assertTrue(catalog.createDatabase("tmpdb3", TajoConstants.DEFAULT_TABLESPACE_NAME));
    assertTrue(catalog.existDatabase("tmpdb3"));
    assertTrue(catalog.createDatabase("tmpdb4", TajoConstants.DEFAULT_TABLESPACE_NAME));
    assertTrue(catalog.existDatabase("tmpdb4"));

    TableDesc table1 = createMockupTable("tmpdb3", "table1");
    assertTrue(catalog.createTable(table1));
    TableDesc table2 = createMockupTable("tmpdb3", "table2");
    assertTrue(catalog.createTable(table2));
    assertTrue(catalog.existsTable("tmpdb3", "table1"));
    assertTrue(catalog.existsTable("tmpdb3", "table2"));

    TableDesc table3 = createMockupTable("tmpdb4", "table1");
    assertTrue(catalog.createTable(table3));
    TableDesc table4 = createMockupTable("tmpdb4", "table2");
    assertTrue(catalog.createTable(table4));
    assertTrue(catalog.existsTable("tmpdb4", "table1"));
    assertTrue(catalog.existsTable("tmpdb4", "table2"));

    assertTrue(catalog.dropTable("tmpdb3.table1"));
    assertTrue(catalog.dropTable("tmpdb3.table2"));
    assertTrue(catalog.dropTable("tmpdb4.table1"));
    assertTrue(catalog.dropTable("tmpdb4.table2"));

    assertFalse(catalog.existsTable("tmpdb3.table1"));
    assertFalse(catalog.existsTable("tmpdb3.table2"));
    assertFalse(catalog.existsTable("tmpdb4.table1"));
    assertFalse(catalog.existsTable("tmpdb4.table2"));
  }
	
	@Test
	public void testAddAndDelIndex() throws Exception {
	  TableDesc desc = prepareTable();
	  assertTrue(catalog.createTable(desc));
	  
	  assertFalse(catalog.existIndexByName("db1", desc1.getIndexName()));
	  assertFalse(catalog.existIndexByColumn(DEFAULT_DATABASE_NAME, "indexed", "id"));
	  catalog.createIndex(desc1);
	  assertTrue(catalog.existIndexByName(DEFAULT_DATABASE_NAME, desc1.getIndexName()));
	  assertTrue(catalog.existIndexByColumn(DEFAULT_DATABASE_NAME, "indexed", "id"));


	  assertFalse(catalog.existIndexByName(DEFAULT_DATABASE_NAME, desc2.getIndexName()));
	  assertFalse(catalog.existIndexByColumn(DEFAULT_DATABASE_NAME, "indexed", "score"));
	  catalog.createIndex(desc2);
	  assertTrue(catalog.existIndexByName(DEFAULT_DATABASE_NAME, desc2.getIndexName()));
	  assertTrue(catalog.existIndexByColumn(DEFAULT_DATABASE_NAME, "indexed", "score"));
	  
	  catalog.dropIndex(DEFAULT_DATABASE_NAME, desc1.getIndexName());
	  assertFalse(catalog.existIndexByName(DEFAULT_DATABASE_NAME, desc1.getIndexName()));
	  catalog.dropIndex(DEFAULT_DATABASE_NAME, desc2.getIndexName());
	  assertFalse(catalog.existIndexByName(DEFAULT_DATABASE_NAME, desc2.getIndexName()));
	  
	  catalog.dropTable(desc.getName());
    assertFalse(catalog.existsTable(desc.getName()));
  }
	
	public static class TestFunc1 extends Function {
		public TestFunc1() {
			super(					
					new Column [] {
							new Column("name", TajoDataTypes.Type.INT4)
					}
			);
		}

    public CatalogProtos.FunctionType getFunctionType() {
      return FunctionType.GENERAL;
    }
	}

  public static class TestFunc2 extends Function {
    public TestFunc2() {
      super(
          new Column [] {
              new Column("name", TajoDataTypes.Type.INT4),
              new Column("bytes", TajoDataTypes.Type.BLOB)
          }
      );
    }
    public CatalogProtos.FunctionType getFunctionType() {
      return FunctionType.GENERAL;
    }
  }

  @Test
  public final void testRegisterAndFindFunc() throws Exception {
    assertFalse(catalog.containFunction("test10", FunctionType.GENERAL));
    FunctionDesc meta = new FunctionDesc("test10", TestFunc2.class, FunctionType.GENERAL,
        CatalogUtil.newSimpleDataType(Type.INT4),
        CatalogUtil.newSimpleDataTypeArray(Type.INT4, Type.BLOB));

    catalog.createFunction(meta);
    assertTrue(catalog.containFunction("test10", CatalogUtil.newSimpleDataTypeArray(Type.INT4, Type.BLOB)));
    FunctionDesc retrived = catalog.getFunction("test10", CatalogUtil.newSimpleDataTypeArray(Type.INT4, Type.BLOB));

    assertEquals(retrived.getSignature(), "test10");
    assertEquals(retrived.getFuncClass(), TestFunc2.class);
    assertEquals(retrived.getFuncType(), FunctionType.GENERAL);

    assertFalse(catalog.containFunction("test10", CatalogUtil.newSimpleDataTypeArray(Type.BLOB, Type.INT4)));
  }
  

	@Test
	public final void testRegisterFunc() throws Exception { 
		assertFalse(catalog.containFunction("test2", FunctionType.UDF));
		FunctionDesc meta = new FunctionDesc("test2", TestFunc1.class, FunctionType.UDF,
        CatalogUtil.newSimpleDataType(Type.INT4),
        CatalogUtil.newSimpleDataTypeArray(Type.INT4));

    catalog.createFunction(meta);
		assertTrue(catalog.containFunction("test2", CatalogUtil.newSimpleDataTypeArray(Type.INT4)));
		FunctionDesc retrived = catalog.getFunction("test2", CatalogUtil.newSimpleDataTypeArray(Type.INT4));

		assertEquals(retrived.getSignature(),"test2");
		assertEquals(retrived.getFuncClass(),TestFunc1.class);
		assertEquals(retrived.getFuncType(),FunctionType.UDF);
	}

  @Test
  public final void testSuchFunctionException() throws Exception {
    try {
      assertFalse(catalog.containFunction("test123", CatalogUtil.newSimpleDataTypeArray(Type.INT4)));
      catalog.getFunction("test123", CatalogUtil.newSimpleDataTypeArray(Type.INT4));
      fail();
    } catch (NoSuchFunctionException nsfe) {
      // succeed test
    } catch (Throwable e) {
      fail(e.getMessage());
    }
  }

  @Test
  public final void testDropFunction() throws Exception {
    assertFalse(catalog.containFunction("test3", CatalogUtil.newSimpleDataTypeArray(Type.INT4)));
    FunctionDesc meta = new FunctionDesc("test3", TestFunc1.class, FunctionType.UDF,
        CatalogUtil.newSimpleDataType(Type.INT4),
        CatalogUtil.newSimpleDataTypeArray(Type.INT4));
    catalog.createFunction(meta);
    assertTrue(catalog.containFunction("test3", CatalogUtil.newSimpleDataTypeArray(Type.INT4)));
    catalog.dropFunction("test3");
    assertFalse(catalog.containFunction("test3", CatalogUtil.newSimpleDataTypeArray(Type.INT4)));

    assertFalse(catalog.containFunction("test3", CatalogUtil.newSimpleDataTypeArray(Type.INT4, Type.BLOB)));
    FunctionDesc overload = new FunctionDesc("test3", TestFunc2.class, FunctionType.GENERAL,
        CatalogUtil.newSimpleDataType(Type.INT4),
        CatalogUtil.newSimpleDataTypeArray(Type.INT4, Type.BLOB));
    catalog.createFunction(overload);
    assertTrue(catalog.containFunction("test3", CatalogUtil.newSimpleDataTypeArray(Type.INT4, Type.BLOB)));
  }

  @Test
  public final void testAddAndDeleteTablePartitionByHash1() throws Exception {
    Schema schema = new Schema();
    schema.addColumn("id", Type.INT4)
        .addColumn("name", Type.TEXT)
        .addColumn("age", Type.INT4)
        .addColumn("score", Type.FLOAT8);

    String tableName = CatalogUtil.buildFQName(DEFAULT_DATABASE_NAME, "addedtable");
    Options opts = new Options();
    opts.put("file.delimiter", ",");
    TableMeta meta = CatalogUtil.newTableMeta(StoreType.CSV, opts);


    Schema partSchema = new Schema();
    partSchema.addColumn("id", Type.INT4);

    PartitionMethodDesc partitionDesc =
        new PartitionMethodDesc(DEFAULT_DATABASE_NAME, tableName,
            CatalogProtos.PartitionType.HASH, "id", partSchema);

    TableDesc desc =
        new TableDesc(tableName, schema, meta,
            new Path(CommonTestingUtil.getTestDir(), "addedtable"));
    desc.setPartitionMethod(partitionDesc);

    assertFalse(catalog.existsTable(tableName));
    catalog.createTable(desc);
    assertTrue(catalog.existsTable(tableName));
    TableDesc retrieved = catalog.getTableDesc(tableName);

    assertEquals(retrieved.getName(), tableName);
    assertEquals(retrieved.getPartitionMethod().getPartitionType(), CatalogProtos.PartitionType.HASH);
    assertEquals(retrieved.getPartitionMethod().getExpressionSchema().getColumn(0).getSimpleName(), "id");

    catalog.dropTable(tableName);
    assertFalse(catalog.existsTable(tableName));
  }


  @Test
  public final void testAddAndDeleteTablePartitionByHash2() throws Exception {
    Schema schema = new Schema();
    schema.addColumn("id", Type.INT4)
        .addColumn("name", Type.TEXT)
        .addColumn("age", Type.INT4)
        .addColumn("score", Type.FLOAT8);

    String tableName = CatalogUtil.buildFQName(DEFAULT_DATABASE_NAME, "addedtable");
    Options opts = new Options();
    opts.put("file.delimiter", ",");
    TableMeta meta = CatalogUtil.newTableMeta(StoreType.CSV, opts);

    Schema partSchema = new Schema();
    partSchema.addColumn("id", Type.INT4);
    PartitionMethodDesc partitionDesc =
        new PartitionMethodDesc(DEFAULT_DATABASE_NAME, tableName,
            CatalogProtos.PartitionType.HASH, "id", partSchema);

    TableDesc desc =
        new TableDesc(tableName, schema, meta,
            new Path(CommonTestingUtil.getTestDir(), "addedtable"));
    desc.setPartitionMethod(partitionDesc);

    assertFalse(catalog.existsTable(tableName));
    catalog.createTable(desc);
    assertTrue(catalog.existsTable(tableName));

    TableDesc retrieved = catalog.getTableDesc(tableName);

    assertEquals(retrieved.getName(), tableName);
    assertEquals(retrieved.getPartitionMethod().getPartitionType(), CatalogProtos.PartitionType.HASH);
    assertEquals(retrieved.getPartitionMethod().getExpressionSchema().getColumn(0).getSimpleName(), "id");

    catalog.dropTable(tableName);
    assertFalse(catalog.existsTable(tableName));
  }

  @Test
  public final void testAddAndDeleteTablePartitionByList() throws Exception {
    Schema schema = new Schema();
    schema.addColumn("id", Type.INT4)
        .addColumn("name", Type.TEXT)
        .addColumn("age", Type.INT4)
        .addColumn("score", Type.FLOAT8);

    String tableName = CatalogUtil.buildFQName(TajoConstants.DEFAULT_DATABASE_NAME, "addedtable");
    Options opts = new Options();
    opts.put("file.delimiter", ",");
    TableMeta meta = CatalogUtil.newTableMeta(StoreType.CSV, opts);

    Schema partSchema = new Schema();
    partSchema.addColumn("id", Type.INT4);
    PartitionMethodDesc partitionDesc =
        new PartitionMethodDesc(DEFAULT_DATABASE_NAME, tableName,
            CatalogProtos.PartitionType.LIST, "id", partSchema);

    TableDesc desc =
        new TableDesc(tableName, schema, meta,
            new Path(CommonTestingUtil.getTestDir(), "addedtable"));
    desc.setPartitionMethod(partitionDesc);
    assertFalse(catalog.existsTable(tableName));
    catalog.createTable(desc);
    assertTrue(catalog.existsTable(tableName));

    TableDesc retrieved = catalog.getTableDesc(tableName);

    assertEquals(retrieved.getName(), tableName);
    assertEquals(retrieved.getPartitionMethod().getPartitionType(), CatalogProtos.PartitionType.LIST);
    assertEquals(retrieved.getPartitionMethod().getExpressionSchema().getColumn(0).getSimpleName(), "id");

    catalog.dropTable(tableName);
    assertFalse(catalog.existsTable(tableName));
  }

  @Test
  public final void testAddAndDeleteTablePartitionByRange() throws Exception {
    Schema schema = new Schema();
    schema.addColumn("id", Type.INT4)
        .addColumn("name", Type.TEXT)
        .addColumn("age", Type.INT4)
        .addColumn("score", Type.FLOAT8);

    String tableName = CatalogUtil.buildFQName(TajoConstants.DEFAULT_DATABASE_NAME, "addedtable");
    Options opts = new Options();
    opts.put("file.delimiter", ",");
    TableMeta meta = CatalogUtil.newTableMeta(StoreType.CSV, opts);

    Schema partSchema = new Schema();
    partSchema.addColumn("id", Type.INT4);
    PartitionMethodDesc partitionDesc =
        new PartitionMethodDesc(DEFAULT_DATABASE_NAME, tableName, CatalogProtos.PartitionType.RANGE,
            "id", partSchema);

    TableDesc desc =
        new TableDesc(tableName, schema, meta,
            new Path(CommonTestingUtil.getTestDir(), "addedtable"));
    desc.setPartitionMethod(partitionDesc);
    assertFalse(catalog.existsTable(tableName));
    catalog.createTable(desc);
    assertTrue(catalog.existsTable(tableName));

    TableDesc retrieved = catalog.getTableDesc(tableName);

    assertEquals(retrieved.getName(), tableName);
    assertEquals(retrieved.getPartitionMethod().getPartitionType(), CatalogProtos.PartitionType.RANGE);
    assertEquals(retrieved.getPartitionMethod().getExpressionSchema().getColumn(0).getSimpleName(), "id");

    catalog.dropTable(tableName);
    assertFalse(catalog.existsTable(tableName));
  }

  @Test
  public final void testAddAndDeleteTablePartitionByColumn() throws Exception {
    Schema schema = new Schema();
    schema.addColumn("id", Type.INT4)
        .addColumn("name", Type.TEXT)
        .addColumn("age", Type.INT4)
        .addColumn("score", Type.FLOAT8);

    String tableName = CatalogUtil.buildFQName(DEFAULT_DATABASE_NAME, "addedtable");
    Options opts = new Options();
    opts.put("file.delimiter", ",");
    TableMeta meta = CatalogUtil.newTableMeta(StoreType.CSV, opts);

    Schema partSchema = new Schema();
    partSchema.addColumn("id", Type.INT4);

    PartitionMethodDesc partitionDesc =
        new PartitionMethodDesc(DEFAULT_DATABASE_NAME, tableName,
            CatalogProtos.PartitionType.COLUMN, "id", partSchema);

    TableDesc desc =
        new TableDesc(tableName, schema, meta,
            new Path(CommonTestingUtil.getTestDir(), "addedtable"));
    desc.setPartitionMethod(partitionDesc);
    assertFalse(catalog.existsTable(tableName));
    catalog.createTable(desc);
    assertTrue(catalog.existsTable(tableName));

    TableDesc retrieved = catalog.getTableDesc(tableName);

    assertEquals(retrieved.getName(), tableName);
    assertEquals(retrieved.getPartitionMethod().getPartitionType(), CatalogProtos.PartitionType.COLUMN);
    assertEquals(retrieved.getPartitionMethod().getExpressionSchema().getColumn(0).getSimpleName(), "id");

    catalog.dropTable(tableName);
    assertFalse(catalog.existsTable(tableName));
  }
}
