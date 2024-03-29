// Copyright 2013 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.analysis;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import junit.framework.Assert;

import org.apache.hive.service.cli.thrift.TGetColumnsReq;
import org.apache.hive.service.cli.thrift.TGetSchemasReq;
import org.apache.hive.service.cli.thrift.TGetTablesReq;
import org.apache.sentry.provider.file.LocalGroupResourceAuthorizationProvider;
import org.apache.sentry.provider.file.ResourceAuthorizationProvider;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.authorization.AuthorizationConfig;
import com.cloudera.impala.authorization.AuthorizeableDb;
import com.cloudera.impala.authorization.AuthorizeableServer;
import com.cloudera.impala.authorization.AuthorizeableTable;
import com.cloudera.impala.authorization.AuthorizeableUri;
import com.cloudera.impala.authorization.Privilege;
import com.cloudera.impala.authorization.User;
import com.cloudera.impala.catalog.AuthorizationException;
import com.cloudera.impala.catalog.Catalog;
import com.cloudera.impala.catalog.ImpaladCatalog;
import com.cloudera.impala.catalog.ScalarFunction;
import com.cloudera.impala.catalog.Type;
import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.common.ImpalaException;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.service.Frontend;
import com.cloudera.impala.testutil.ImpaladTestCatalog;
import com.cloudera.impala.testutil.TestUtils;
import com.cloudera.impala.thrift.TMetadataOpRequest;
import com.cloudera.impala.thrift.TMetadataOpcode;
import com.cloudera.impala.thrift.TNetworkAddress;
import com.cloudera.impala.thrift.TQueryCtx;
import com.cloudera.impala.thrift.TResultSet;
import com.cloudera.impala.thrift.TSessionState;
import com.cloudera.impala.util.SentryPolicyService;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

@RunWith(Parameterized.class)
public class AuthorizationTest {
  private final static Logger LOG =
      LoggerFactory.getLogger(AuthorizationTest.class);

  // Policy file has defined current user and 'test_user' have:
  //   ALL permission on 'tpch' database and 'newdb' database
  //   ALL permission on 'functional_seq_snap' database
  //   SELECT permissions on all tables in 'tpcds' database
  //   SELECT permissions on 'functional.alltypesagg' (no INSERT permissions)
  //   SELECT permissions on 'functional.complex_view' (no INSERT permissions)
  //   SELECT permissions on 'functional.view_view' (no INSERT permissions)
  //   INSERT permissions on 'functional.alltypes' (no SELECT permissions)
  //   INSERT permissions on all tables in 'functional_parquet' database
  //   No permissions on database 'functional_rc'
  private final static String AUTHZ_POLICY_FILE = "/test-warehouse/authz-policy.ini";
  private final static User USER = new User(System.getProperty("user.name"));

  // The admin_user has ALL privileges on the server.
  private final static User ADMIN_USER = new User("admin_user");

  private final AuthorizationConfig authzConfig_;
  private final ImpaladCatalog catalog_;
  private final TQueryCtx queryCtxt_;
  private final AnalysisContext analysisContext_;
  private final Frontend fe_;
  protected static final String SERVER_HOST = "localhost";
  private static boolean isSetup_ = false;

  // Parameterize the test suite to run all tests using a file based
  // authorization policy policy using metadata pulled from the Sentry Policy
  // service.
  @Parameters
  public static Collection testVectors() {
    return Arrays.asList(new Object[][] {{AUTHZ_POLICY_FILE}});
  }

  public AuthorizationTest(String policyFile) throws Exception {
    authzConfig_ = AuthorizationConfig.createHadoopGroupAuthConfig("server1", policyFile,
        System.getenv("IMPALA_HOME") + "/fe/src/test/resources/sentry-site.xml");
    authzConfig_.validateConfig();
    catalog_ = new ImpaladTestCatalog();
    queryCtxt_ = TestUtils.createQueryContext(Catalog.DEFAULT_DB, USER.getName());
    analysisContext_ = new AnalysisContext(catalog_, queryCtxt_);
    fe_ = new Frontend(authzConfig_, new ImpaladTestCatalog());
  }

  private void setup() throws Exception {
    SentryPolicyService sentryService =
        new SentryPolicyService(authzConfig_.getSentryConfig(), "server1");
    // Server admin. Don't grant to any groups, that is done within
    // the test cases.
    String roleName = "admin";
    sentryService.createRole(roleName, true);
    sentryService.grantRolePrivilege(roleName, new AuthorizeableServer("server1"),
        Privilege.ALL);
    sentryService.revokeRoleFromGroup("admin", USER.getName());

    // insert functional alltypes
    roleName = "insert_functional_alltypes";
    roleName = roleName.toLowerCase();
    sentryService.createRole(roleName, true);
    sentryService.grantRoleToGroup(roleName, USER.getName());

    AuthorizeableTable table = new AuthorizeableTable("functional", "alltypes");
    sentryService.grantRolePrivilege(roleName, table, Privilege.INSERT);

    // insert_parquet
    roleName = "insert_parquet";
    sentryService.createRole(roleName, true);
    sentryService.grantRoleToGroup(roleName, USER.getName());

    table = new AuthorizeableTable("functional_parquet",
        AuthorizeableTable.ANY_TABLE_NAME);
    sentryService.grantRolePrivilege(roleName, table, Privilege.INSERT);

    // all newdb
    roleName = "all_newdb";
    sentryService.createRole(roleName, true);
    sentryService.grantRoleToGroup(roleName, USER.getName());

    AuthorizeableDb db = new AuthorizeableDb("newdb");
    sentryService.grantRolePrivilege(roleName, db, Privilege.ALL);
    AuthorizeableUri uri = new AuthorizeableUri(
        "hdfs://localhost:20500/test-warehouse/new_table");
    sentryService.grantRolePrivilege(roleName, uri, Privilege.ALL);

    // all tpch
    roleName = "all_tpch";
    sentryService.createRole(roleName, true);
    sentryService.grantRoleToGroup(roleName, USER.getName());
    uri = new AuthorizeableUri(
        "hdfs://localhost:20500/test-warehouse/tpch.lineitem");
    sentryService.grantRolePrivilege(roleName, uri, Privilege.ALL);

    db = new AuthorizeableDb("tpch");
    sentryService.grantRolePrivilege(roleName, db, Privilege.ALL);

    // select tpcds
    roleName = "select_tpcds";
    sentryService.createRole(roleName, true);
    sentryService.grantRoleToGroup(roleName, USER.getName());

    table = new AuthorizeableTable("tpcds", AuthorizeableTable.ANY_TABLE_NAME);
    sentryService.grantRolePrivilege(roleName, table, Privilege.SELECT);

    // select_functional_alltypesagg
    roleName = "select_functional_alltypesagg";
    sentryService.createRole(roleName, true);
    sentryService.grantRoleToGroup(roleName, USER.getName());

    table = new AuthorizeableTable("functional", "alltypesagg");
    sentryService.grantRolePrivilege(roleName, table, Privilege.SELECT);

    // select_functional_complex_view
    roleName = "select_functional_complex_view";
    sentryService.createRole(roleName, true);
    sentryService.grantRoleToGroup(roleName, USER.getName());

    table = new AuthorizeableTable("functional", "complex_view");
    sentryService.grantRolePrivilege(roleName, table, Privilege.SELECT);

    // select_functional_view_view
    roleName = "select_functional_view_view";
    sentryService.createRole(roleName, true);
    sentryService.grantRoleToGroup(roleName, USER.getName());

    table = new AuthorizeableTable("functional", "view_view");
    sentryService.grantRolePrivilege(roleName, table, Privilege.SELECT);

    // all_functional_seq_snap
    roleName = "all_functional_seq_snap";
    // Verify we are able to drop a role.
    sentryService.dropRole(roleName, true);
    sentryService.createRole(roleName, true);
    sentryService.grantRoleToGroup(roleName, USER.getName());
    db = new AuthorizeableDb("functional_seq_snap");
    sentryService.grantRolePrivilege(roleName, db, Privilege.ALL);
  }

  @After
  public void TestTPCHCleanup() throws AuthorizationException, AnalysisException {
    // Failure to cleanup TPCH can cause:
    // TestDropDatabase(com.cloudera.impala.analysis.AuthorizationTest):
    // Cannot drop non-empty database: tpch
    if (catalog_.getDb("tpch").numFunctions() != 0) {
      fail("Failed to clean up functions in tpch.");
    }
  }

  @Test
  public void TestSelect() throws AuthorizationException, AnalysisException {
    // Can select from table that user has privileges on.
    AuthzOk("select * from functional.alltypesagg");

    AuthzOk("select * from functional_seq_snap.alltypes");

    // Can select from view that user has privileges on even though he/she doesn't
    // have privileges on underlying tables.
    AuthzOk("select * from functional.complex_view");

    // User has permission to select the view but not on the view (alltypes_view)
    // referenced in its view definition.
    AuthzOk("select * from functional.view_view");

    // User does not have SELECT privileges on this view.
    AuthzError("select * from functional.complex_view_sub",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.complex_view_sub");

    // User has SELECT privileges on the view and the join table.
    AuthzOk("select a.id from functional.view_view a "
        + "join functional.alltypesagg b ON (a.id = b.id)");

    // User has SELECT privileges on the view, but does not have privileges
    // to select join table.
    AuthzError("select a.id from functional.view_view a "
        + "join functional.alltypes b ON (a.id = b.id)",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.alltypes");

    // Constant select.
    AuthzOk("select 1");

    // Unqualified table name.
    AuthzError("select * from alltypes",
        "User '%s' does not have privileges to execute 'SELECT' on: default.alltypes");

    // Select with no privileges on table.
    AuthzError("select * from functional.alltypes",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.alltypes");

    // Select with no privileges on view.
    AuthzError("select * from functional.complex_view_sub",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.complex_view_sub");

    // Select without referencing a column.
    AuthzError("select 1 from functional.alltypes",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.alltypes");

    // Select from non-existent table.
    AuthzError("select 1 from functional.notbl",
        "User '%s' does not have privileges to execute 'SELECT' on: functional.notbl");

    // Select from non-existent db.
    AuthzError("select 1 from nodb.alltypes",
        "User '%s' does not have privileges to execute 'SELECT' on: nodb.alltypes");

    // Table within inline view is authorized properly.
    AuthzError("select a.* from (select * from functional.alltypes) a",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.alltypes");
    // Table within inline view is authorized properly (user has permission).
    AuthzOk("select a.* from (select * from functional.alltypesagg) a");
  }

  @Test
  public void TestUnion() throws AuthorizationException, AnalysisException {
    AuthzOk("select * from functional.alltypesagg union all " +
        "select * from functional.alltypesagg");

    AuthzError("select * from functional.alltypesagg union all " +
        "select * from functional.alltypes",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
         "functional.alltypes");
  }

  @Test
  public void TestInsert() throws AuthorizationException, AnalysisException {
    AuthzOk("insert into functional_parquet.alltypes " +
        "partition(month,year) select * from functional_seq_snap.alltypes");

    // Insert + inline view (user has permissions).
    AuthzOk("insert into functional.alltypes partition(month,year) " +
        "select b.* from functional.alltypesagg a join (select * from " +
        "functional_seq_snap.alltypes) b on (a.int_col = b.int_col)");

    // User doesn't have INSERT permissions in the target table.
    AuthzError("insert into functional.alltypesagg select 1",
        "User '%s' does not have privileges to execute 'INSERT' on: " +
        "functional.alltypesagg");

    // User doesn't have INSERT permissions in the target view.
    // Inserting into a view is not allowed.
    AuthzError("insert into functional.alltypes_view select 1",
        "User '%s' does not have privileges to execute 'INSERT' on: " +
        "functional.alltypes_view");

    // User doesn't have SELECT permissions on source table.
    AuthzError("insert into functional.alltypes " +
        "select * from functional.alltypes",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.alltypes");

    // User doesn't have permissions on source table within inline view.
    AuthzError("insert into functional.alltypes " +
        "select * from functional.alltypesagg a join (select * from " +
        "functional_seq.alltypes) b on (a.int_col = b.int_col)",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional_seq.alltypes");
  }

  @Test
  public void TestWithClause() throws AuthorizationException, AnalysisException {
    // User has SELECT privileges on table in WITH-clause view.
    AuthzOk("with t as (select * from functional.alltypesagg) select * from t");
    // User doesn't have SELECT privileges on table in WITH-clause view.
    AuthzError("with t as (select * from functional.alltypes) select * from t",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.alltypes");
    // User has SELECT privileges on view in WITH-clause view.
    AuthzOk("with t as (select * from functional.complex_view) select * from t");

    // User has SELECT privileges on table in WITH-clause view in INSERT.
    AuthzOk("with t as (select * from functional_seq_snap.alltypes) " +
        "insert into functional_parquet.alltypes partition(month,year) select * from t");
    // User doesn't have SELECT privileges on table in WITH-clause view in INSERT.
    AuthzError("with t as (select * from functional_parquet.alltypes) " +
        "insert into functional_parquet.alltypes partition(month,year) select * from t",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
         "functional_parquet.alltypes");
    // User doesn't have SELECT privileges on view in WITH-clause view in INSERT.
    AuthzError("with t as (select * from functional.alltypes_view) " +
        "insert into functional_parquet.alltypes partition(month,year) select * from t",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
         "functional.alltypes_view");
  }

  @Test
  public void TestExplain() throws AuthorizationException, AnalysisException {
    AuthzOk("explain select * from functional.alltypesagg");
    AuthzOk("explain insert into functional_parquet.alltypes " +
        "partition(month,year) select * from functional_seq_snap.alltypes");

    // Select without permissions.
    AuthzError("explain select * from functional.alltypes",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.alltypes");

    // Insert with no select permissions on source table.
    AuthzError("explain insert into functional_parquet.alltypes " +
        "partition(month,year) select * from functional.alltypes",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.alltypes");
    // Insert, user doesn't have permissions on source table.
    AuthzError("explain insert into functional.alltypes " +
        "select * from functional.alltypes",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.alltypes");

    // Test explain on views. User has permissions on all the underlying tables.
    AuthzOk("explain select * from functional_seq_snap.alltypes_view");
    AuthzOk("explain insert into functional_parquet.alltypes " +
        "partition(month,year) select * from functional_seq_snap.alltypes_view");

    // Select on view without permissions on view.
    AuthzError("explain select * from functional.alltypes_view",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.alltypes_view");
    // Insert into view without permissions on view.
    AuthzError("explain insert into functional.alltypes_view " +
        "select * from functional_seq_snap.alltypes ",
        "User '%s' does not have privileges to execute 'INSERT' on: " +
        "functional.alltypes_view");

    // User has permission on view, but not on underlying tables.
    AuthzError("explain select * from functional.complex_view",
        "User '%s' does not have privileges to EXPLAIN this statement.");
    // User has permission on view in WITH clause, but not on underlying tables.
    AuthzError("explain with t as (select * from functional.complex_view) " +
        "select * from t",
        "User '%s' does not have privileges to EXPLAIN this statement.");

    // User has permission on view in WITH clause, but not on underlying tables.
    AuthzError("explain with t as (select * from functional.complex_view) " +
        "select * from t",
        "User '%s' does not have privileges to EXPLAIN this statement.");
    // User has permission on view and on view inside view,
    // but not on tables in view inside view.
    AuthzError("explain select * from functional.view_view",
        "User '%s' does not have privileges to EXPLAIN this statement.");
    // User doesn't have permission on tables in view inside view.
    AuthzError("explain insert into functional_seq_snap.alltypes " +
        "partition(month,year) select * from functional.view_view",
        "User '%s' does not have privileges to EXPLAIN this statement.");

    // User has SELECT privileges on the view, but does not have privileges
    // to select join table. Should get standard auth message back.
    AuthzError("explain select a.id from functional.view_view a "
        + "join functional.alltypes b ON (a.id = b.id)",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.alltypes");

    // User has privileges on tables inside the first view, but not the second
    // view. Should get masked auth error back.
    AuthzError("explain select a.id from functional.view_view a "
        + "join functional.complex_view b ON (a.id = b.id)",
        "User '%s' does not have privileges to EXPLAIN this statement.");
  }

  @Test
  public void TestUseDb() throws AnalysisException, AuthorizationException {
    // Positive cases (user has privileges on these tables).
    AuthzOk("use functional");
    AuthzOk("use tpcds");
    AuthzOk("use tpch");

    // Should always be able to use default, even if privilege was not explicitly
    // granted.
    AuthzOk("use default");

    AuthzError("use functional_seq",
        "User '%s' does not have privileges to access: functional_seq.*");

    // Database does not exist, user does not have access.
    AuthzError("use nodb",
        "User '%s' does not have privileges to access: nodb.*");

    // Database does not exist, user has access:
    try {
      AuthzOk("use newdb");
      fail("Expected AnalysisException");
    } catch (AnalysisException e) {
      Assert.assertEquals(e.getMessage(), "Database does not exist: newdb");
    }

    // All users should be able to use the system db.
    AuthzOk("use _impala_builtins");
  }

  @Test
  public void TestResetMetadata() throws ImpalaException {
    // Positive cases (user has privileges on these tables/views).
    AuthzOk("invalidate metadata functional.alltypesagg");
    AuthzOk("refresh functional.alltypesagg");
    AuthzOk("invalidate metadata functional.view_view");
    AuthzOk("refresh functional.view_view");

    AuthzError("invalidate metadata unknown_db.alltypessmall",
        "User '%s' does not have privileges to access: unknown_db.alltypessmall");
    AuthzError("invalidate metadata functional_seq.alltypessmall",
        "User '%s' does not have privileges to access: functional_seq.alltypessmall");
    AuthzError("invalidate metadata functional.alltypes_view",
        "User '%s' does not have privileges to access: functional.alltypes_view");
    AuthzError("invalidate metadata functional.unknown_table",
        "User '%s' does not have privileges to access: functional.unknown_table");
    AuthzError("invalidate metadata functional.alltypessmall",
        "User '%s' does not have privileges to access: functional.alltypessmall");
    AuthzError("refresh functional.alltypessmall",
        "User '%s' does not have privileges to access: functional.alltypessmall");
    AuthzError("refresh functional.alltypes_view",
        "User '%s' does not have privileges to access: functional.alltypes_view");

    AuthzError("invalidate metadata",
        "User '%s' does not have privileges to access: server");

    // TODO: Add test support for dynamically changing privileges for
    // file-based policy.
    if (authzConfig_.isFileBasedPolicy()) return;
    SentryPolicyService sentryService = createSentryService();

    try {
      sentryService.grantRoleToGroup("admin", USER.getName());
      ((ImpaladTestCatalog) catalog_).reset();
      AuthzOk("invalidate metadata");
    } finally {
      sentryService.revokeRoleFromGroup("admin", USER.getName());
      ((ImpaladTestCatalog) catalog_).reset();
    }
  }

  @Test
  public void TestCreateTable() throws AnalysisException, AuthorizationException {
    AuthzOk("create table tpch.new_table (i int)");
    AuthzOk("create table tpch.new_lineitem like tpch.lineitem");
    // Create table IF NOT EXISTS, user has permission and table exists.
    AuthzOk("create table if not exists tpch.lineitem (i int)");
    try {
      AuthzOk("create table tpch.lineitem (i int)");
      fail("Expected analysis error.");
    } catch (AnalysisException e) {
      Assert.assertEquals(e.getMessage(), "Table already exists: tpch.lineitem");
    }

    // Create table AS SELECT positive and negative cases for SELECT privilege.
    AuthzOk("create table tpch.new_table as select * from functional.alltypesagg");
    AuthzError("create table tpch.new_table as select * from functional.alltypes",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.alltypes");

    AuthzError("create table functional.tbl tblproperties('a'='b')" +
        " as select 1",
        "User '%s' does not have privileges to execute 'CREATE' on: " +
        "functional.tbl");

    // Create table IF NOT EXISTS, user does not have permission and table exists.
    AuthzError("create table if not exists functional_seq.alltypes (i int)",
        "User '%s' does not have privileges to execute 'CREATE' on: " +
        "functional_seq.alltypes");

    // User has permission to create at given location.
    AuthzOk("create table tpch.new_table (i int) location " +
        "'hdfs://localhost:20500/test-warehouse/new_table'");

    // No permissions on source table.
    AuthzError("create table tpch.new_lineitem like tpch_seq.lineitem",
        "User '%s' does not have privileges to access: tpch_seq.lineitem");

    // No permissions on target table.
    AuthzError("create table tpch_rc.new like tpch.lineitem",
        "User '%s' does not have privileges to execute 'CREATE' on: tpch_rc.new");

    // Unqualified table name.
    AuthzError("create table new_table (i int)",
        "User '%s' does not have privileges to execute 'CREATE' on: default.new_table");

    // Table already exists (user does not have permission).
    AuthzError("create table functional.alltypes (i int)",
        "User '%s' does not have privileges to execute 'CREATE' on: " +
        "functional.alltypes");

    // Database does not exist, user does not have access.
    AuthzError("create table nodb.alltypes (i int)",
        "User '%s' does not have privileges to execute 'CREATE' on: " +
        "nodb.alltypes");

    // User does not have permission to create table at the specified location..
    AuthzError("create table tpch.new_table (i int) location " +
        "'hdfs://localhost:20500/test-warehouse/alltypes'",
        "User '%s' does not have privileges to access: " +
        "hdfs://localhost:20500/test-warehouse/alltypes");

    AuthzError("create table _impala_builtins.tbl(i int)",
        "Cannot modify system database.");

    // Check that create like file follows authorization rules for HDFS files
    AuthzError("create table tpch.table_DNE like parquet "
        + "'hdfs://localhost:20500/test-warehouse/alltypes'",
        "User '%s' does not have privileges to access: "
        + "hdfs://localhost:20500/test-warehouse/alltypes");
  }

  @Test
  public void TestCreateView() throws AuthorizationException, AnalysisException {
    AuthzOk("create view tpch.new_view as select * from functional.alltypesagg");
    AuthzOk("create view tpch.new_view (a, b, c) as " +
        "select int_col, string_col, timestamp_col from functional.alltypesagg");
    // Create view IF NOT EXISTS, user has permission and table exists.
    AuthzOk("create view if not exists tpch.lineitem as " +
        "select * from functional.alltypesagg");
    // Create view IF NOT EXISTS, user has permission and table/view exists.
    try {
      AuthzOk("create view tpch.lineitem as select * from functional.alltypesagg");
      fail("Expected analysis error.");
    } catch (AnalysisException e) {
      Assert.assertEquals(e.getMessage(), "Table already exists: tpch.lineitem");
    }

    // Create view IF NOT EXISTS, user does not have permission and table/view exists.
    AuthzError("create view if not exists functional_seq.alltypes as " +
        "select * from functional.alltypesagg",
        "User '%s' does not have privileges to execute 'CREATE' on: " +
        "functional_seq.alltypes");

    // No permissions on source table.
    AuthzError("create view tpch.new_view as select * from functional.alltypes",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.alltypes");

    // No permissions on target table.
    AuthzError("create view tpch_rc.new as select * from functional.alltypesagg",
        "User '%s' does not have privileges to execute 'CREATE' on: tpch_rc.new");

    // Unqualified view name.
    AuthzError("create view new_view as select * from functional.alltypesagg",
        "User '%s' does not have privileges to execute 'CREATE' on: default.new_view");

    // Table already exists (user does not have permission).
    AuthzError("create view functional.alltypes_view as " +
        "select * from functional.alltypesagg",
        "User '%s' does not have privileges to execute 'CREATE' on: " +
        "functional.alltypes_view");

    // Database does not exist, user does not have access.
    AuthzError("create view nodb.alltypes as select * from functional.alltypesagg",
        "User '%s' does not have privileges to execute 'CREATE' on: " +
        "nodb.alltypes");

    AuthzError("create view _impala_builtins.new_view as "
        + "select * from functional.alltypesagg",
        "Cannot modify system database.");
  }

  @Test
  public void TestCreateDatabase() throws ImpalaException {
    // Database already exists (no permissions).
    AuthzError("create database functional",
        "User '%s' does not have privileges to execute 'CREATE' on: functional");

    // Non existent db (no permissions).
    AuthzError("create database nodb",
        "User '%s' does not have privileges to execute 'CREATE' on: nodb");

    // Non existent db (no permissions).
    AuthzError("create database if not exists _impala_builtins",
        "Cannot modify system database.");

    // TODO: Add test support for dynamically changing privileges for
    // file-based policy.
    if (authzConfig_.isFileBasedPolicy()) return;

    SentryPolicyService sentryService =
        new SentryPolicyService(authzConfig_.getSentryConfig(), "server1");
    try {
      sentryService.grantRoleToGroup("admin", USER.getName());
      ((ImpaladTestCatalog) catalog_).reset();

      // User has permissions to create database.
      AuthzOk("create database newdb");

      // Create database with location specified explicitly (user has permission).
      // Since create database requires server-level privileges there should never
      // be a case where the user has privileges to create a database does not have
      // privileges on the URI.
      AuthzOk("create database newdb location " +
          "'hdfs://localhost:20500/test-warehouse/new_table'");
    } finally {
      sentryService.revokeRoleFromGroup("admin", USER.getName());
      ((ImpaladTestCatalog) catalog_).reset();
    }
  }

  @Test
  public void TestDropDatabase() throws AnalysisException, AuthorizationException {
    // User has permissions.
    AuthzOk("drop database tpch");
    // User has permissions, database does not exists and IF EXISTS specified
    AuthzOk("drop database if exists newdb");
    // User has permission, database does not exists, IF EXISTS not specified.
    try {
      AuthzOk("drop database newdb");
      fail("Expected analysis error");
    } catch (AnalysisException e) {
      Assert.assertEquals(e.getMessage(), "Database does not exist: newdb");
    }

    // Database exists, user doesn't have permission to drop.
    AuthzError("drop database functional",
        "User '%s' does not have privileges to execute 'DROP' on: functional");
    AuthzError("drop database if exists functional",
        "User '%s' does not have privileges to execute 'DROP' on: functional");

    // Database does not exist, user doesn't have permission to drop.
    AuthzError("drop database nodb",
        "User '%s' does not have privileges to execute 'DROP' on: nodb");
    AuthzError("drop database if exists nodb",
        "User '%s' does not have privileges to execute 'DROP' on: nodb");

    AuthzError("drop database _impala_builtins",
        "Cannot modify system database.");
  }

  @Test
  public void TestDropTable() throws AnalysisException, AuthorizationException {
    // Drop table (user has permission).
    AuthzOk("drop table tpch.lineitem");
    AuthzOk("drop table if exists tpch.lineitem");

    // Drop table (user does not have permission).
    AuthzError("drop table functional.alltypes",
        "User '%s' does not have privileges to execute 'DROP' on: functional.alltypes");
    AuthzError("drop table if exists functional.alltypes",
        "User '%s' does not have privileges to execute 'DROP' on: functional.alltypes");

    // Drop table with unqualified table name.
    AuthzError("drop table alltypes",
        "User '%s' does not have privileges to execute 'DROP' on: default.alltypes");

    // Drop table with non-existent database.
    AuthzError("drop table nodb.alltypes",
        "User '%s' does not have privileges to execute 'DROP' on: nodb.alltypes");

    // Drop table with non-existent table.
    AuthzError("drop table functional.notbl",
        "User '%s' does not have privileges to execute 'DROP' on: functional.notbl");

    // Using DROP TABLE on a view does not reveal privileged information.
    AuthzError("drop table functional.view_view",
        "User '%s' does not have privileges to execute 'DROP' on: functional.view_view");

    // Using DROP TABLE on a view does not reveal privileged information.
    AuthzError("drop table if exists _impala_builtins.tbl",
        "Cannot modify system database.");
  }

  @Test
  public void TestDropView() throws AnalysisException, AuthorizationException {
    // Drop view (user has permission).
    AuthzOk("drop view functional_seq_snap.alltypes_view");
    AuthzOk("drop view if exists functional_seq_snap.alltypes_view");

    // Drop view (user does not have permission).
    AuthzError("drop view functional.alltypes_view",
        "User '%s' does not have privileges to execute 'DROP' on: functional.alltypes");
    AuthzError("drop view if exists functional.alltypes_view",
        "User '%s' does not have privileges to execute 'DROP' on: functional.alltypes");

    // Drop view with unqualified table name.
    AuthzError("drop view alltypes",
        "User '%s' does not have privileges to execute 'DROP' on: default.alltypes");

    // Drop view with non-existent database.
    AuthzError("drop view nodb.alltypes",
        "User '%s' does not have privileges to execute 'DROP' on: nodb.alltypes");

    // Drop view with non-existent table.
    AuthzError("drop view functional.notbl",
        "User '%s' does not have privileges to execute 'DROP' on: functional.notbl");

    // Using DROP VIEW on a table does not reveal privileged information.
    AuthzError("drop view functional.alltypes",
        "User '%s' does not have privileges to execute 'DROP' on: functional.alltypes");

    // Using DROP VIEW on a table does not reveal privileged information.
    AuthzError("drop view _impala_builtins.my_view",
        "Cannot modify system database.");
  }

  @Test
  public void AlterTable() throws AnalysisException, AuthorizationException {
    // User has permissions to modify tables.
    AuthzOk("ALTER TABLE functional_seq_snap.alltypes ADD COLUMNS (c1 int)");
    AuthzOk("ALTER TABLE functional_seq_snap.alltypes REPLACE COLUMNS (c1 int)");
    AuthzOk("ALTER TABLE functional_seq_snap.alltypes CHANGE int_col c1 int");
    AuthzOk("ALTER TABLE functional_seq_snap.alltypes DROP int_col");
    AuthzOk("ALTER TABLE functional_seq_snap.alltypes RENAME TO functional_seq_snap.t1");
    AuthzOk("ALTER TABLE functional_seq_snap.alltypes SET FILEFORMAT PARQUET");
    AuthzOk("ALTER TABLE functional_seq_snap.alltypes SET LOCATION " +
        "'/test-warehouse/new_table'");
    AuthzOk("ALTER TABLE functional_seq_snap.alltypes SET TBLPROPERTIES " +
        "('a'='b', 'c'='d')");
    AuthzOk("ALTER TABLE functional_seq_snap.alltypes SET LOCATION " +
        "'hdfs://localhost:20500/test-warehouse/new_table'");
    AuthzOk("ALTER TABLE functional_seq_snap.alltypes PARTITION(year=2009, month=1) " +
        "SET LOCATION 'hdfs://localhost:20500/test-warehouse/new_table'");

    try {
      AuthzOk("ALTER TABLE functional_seq_snap.alltypes SET CACHED IN 'testPool'");
      fail("Expected AnalysisException");
    } catch (AnalysisException e) {
      Assert.assertEquals(e.getMessage(), "HDFS caching is not supported on CDH4");
    }

    // Alter table and set location to a path the user does not have access to.
    AuthzError("ALTER TABLE functional_seq_snap.alltypes SET LOCATION " +
        "'hdfs://localhost:20500/test-warehouse/no_access'",
        "User '%s' does not have privileges to access: " +
        "hdfs://localhost:20500/test-warehouse/no_access");
    AuthzError("ALTER TABLE functional_seq_snap.alltypes SET LOCATION " +
        "'/test-warehouse/no_access'",
        "User '%s' does not have privileges to access: " +
        "hdfs://localhost:20500/test-warehouse/no_access");
    AuthzError("ALTER TABLE functional_seq_snap.alltypes PARTITION(year=2009, month=1) " +
        "SET LOCATION '/test-warehouse/no_access'",
        "User '%s' does not have privileges to access: " +
        "hdfs://localhost:20500/test-warehouse/no_access");

    // Different file system, user has permission to base path.
    AuthzError("ALTER TABLE functional_seq_snap.alltypes SET LOCATION " +
        "'hdfs://localhost:20510/test-warehouse/new_table'",
        "User '%s' does not have privileges to access: " +
        "hdfs://localhost:20510/test-warehouse/new_table");

    AuthzError("ALTER TABLE functional.alltypes SET FILEFORMAT PARQUET",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.alltypes");
    AuthzError("ALTER TABLE functional.alltypes ADD COLUMNS (c1 int)",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.alltypes");
    AuthzError("ALTER TABLE functional.alltypes REPLACE COLUMNS (c1 int)",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.alltypes");
    AuthzError("ALTER TABLE functional.alltypes CHANGE int_col c1 int",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.alltypes");
    AuthzError("ALTER TABLE functional.alltypes DROP int_col",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.alltypes");
    AuthzError("ALTER TABLE functional.alltypes rename to functional_seq_snap.t1",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.alltypes");
    AuthzError("ALTER TABLE functional.alltypes add partition (year=1, month=1)",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.alltypes");

    // Trying to ALTER TABLE a view does not reveal any privileged information.
    AuthzError("ALTER TABLE functional.view_view SET FILEFORMAT PARQUET",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.view_view");
    AuthzError("ALTER TABLE functional.view_view ADD COLUMNS (c1 int)",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.view_view");
    AuthzError("ALTER TABLE functional.view_view REPLACE COLUMNS (c1 int)",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.view_view");
    AuthzError("ALTER TABLE functional.view_view CHANGE int_col c1 int",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.view_view");
    AuthzError("ALTER TABLE functional.view_view DROP int_col",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.view_view");
    AuthzError("ALTER TABLE functional.view_views rename to functional_seq_snap.t1",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.view_view");

    // No privileges on target (existing table).
    AuthzError("ALTER TABLE functional_seq_snap.alltypes rename to functional.alltypes",
        "User '%s' does not have privileges to execute 'CREATE' on: " +
        "functional.alltypes");

    // No privileges on target (existing view).
    AuthzError("ALTER TABLE functional_seq_snap.alltypes rename to " +
        "functional.alltypes_view",
        "User '%s' does not have privileges to execute 'CREATE' on: " +
        "functional.alltypes");

    // ALTER TABLE on a view does not reveal privileged information.
    AuthzError("ALTER TABLE functional.alltypes_view rename to " +
        "functional_seq_snap.new_view",
        "User '%s' does not have privileges to execute 'ALTER' on: " +
        "functional.alltypes_view");

    // Rename table that does not exist (no permissions).
    AuthzError("ALTER TABLE functional.notbl rename to functional_seq_snap.newtbl",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.notbl");

    // Rename table in db that does not exist (no permissions).
    AuthzError("ALTER TABLE nodb.alltypes rename to functional_seq_snap.newtbl",
        "User '%s' does not have privileges to execute 'ALTER' on: nodb.alltypes");

    // Alter table that does not exist (no permissions).
    AuthzError("ALTER TABLE functional.notbl ADD COLUMNS (c1 int)",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.notbl");

    // Alter table in db that does not exist (no permissions).
    AuthzError("ALTER TABLE nodb.alltypes ADD COLUMNS (c1 int)",
        "User '%s' does not have privileges to execute 'ALTER' on: nodb.alltypes");

    // Unqualified table name.
    AuthzError("ALTER TABLE alltypes ADD COLUMNS (c1 int)",
        "User '%s' does not have privileges to execute 'ALTER' on: default.alltypes");

    AuthzError("ALTER TABLE alltypes SET TBLPROPERTIES ('a'='b', 'c'='d')",
        "User '%s' does not have privileges to execute 'ALTER' on: default.alltypes");
  }

  @Test
  public void TestAlterView() throws AnalysisException, AuthorizationException {
    AuthzOk("ALTER VIEW functional_seq_snap.alltypes_view rename to " +
        "functional_seq_snap.v1");

    // No privileges on target (existing table).
    AuthzError("ALTER VIEW functional_seq_snap.alltypes_view rename to " +
        "functional.alltypes",
        "User '%s' does not have privileges to execute 'CREATE' on: " +
        "functional.alltypes");

    // No privileges on target (existing view).
    AuthzError("ALTER VIEW functional_seq_snap.alltypes_view rename to " +
        "functional.alltypes_view",
        "User '%s' does not have privileges to execute 'CREATE' on: " +
        "functional.alltypes_view");

    // ALTER VIEW on a table does not reveal privileged information.
    AuthzError("ALTER VIEW functional.alltypes rename to " +
        "functional_seq_snap.new_view",
        "User '%s' does not have privileges to execute 'ALTER' on: " +
        "functional.alltypes");

    // Rename view that does not exist (no permissions).
    AuthzError("ALTER VIEW functional.notbl rename to functional_seq_snap.newtbl",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.notbl");

    // Rename view in db that does not exist (no permissions).
    AuthzError("ALTER VIEW nodb.alltypes rename to functional_seq_snap.newtbl",
        "User '%s' does not have privileges to execute 'ALTER' on: nodb.alltypes");

    // Alter view that does not exist (no permissions).
    AuthzError("ALTER VIEW functional.notbl rename to functional_seq_snap.new_view",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.notbl");

    // Alter view in db that does not exist (no permissions).
    AuthzError("ALTER VIEW nodb.alltypes rename to functional_seq_snap.new_view",
        "User '%s' does not have privileges to execute 'ALTER' on: nodb.alltypes");

    // Unqualified view name.
    AuthzError("ALTER VIEW alltypes rename to functional_seq_snap.new_view",
        "User '%s' does not have privileges to execute 'ALTER' on: default.alltypes");

    // No permissions on target view.
    AuthzError("alter view functional.alltypes_view as " +
        "select * from functional.alltypesagg",
        "User '%s' does not have privileges to execute 'ALTER' on: " +
        "functional.alltypes_view");

    // No permissions on source view.
    AuthzError("alter view functional_seq_snap.alltypes_view " +
        "as select * from functional.alltypes_view",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.alltypes_view");
  }

  @Test
  public void TestComputeStatsTable() throws AnalysisException, AuthorizationException {
    AuthzOk("compute stats functional_seq_snap.alltypes");

    AuthzError("compute stats functional.alltypes",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.alltypes");
    AuthzError("compute stats functional.alltypesagg",
        "User '%s' does not have privileges to execute 'ALTER' on: " +
        "functional.alltypesagg");
  }

  @Test
  public void TestDropStats() throws AnalysisException, AuthorizationException {
    AuthzOk("drop stats functional_seq_snap.alltypes");

    AuthzError("drop stats functional.alltypes",
        "User '%s' does not have privileges to execute 'ALTER' on: functional.alltypes");
    AuthzError("drop stats functional.alltypesagg",
        "User '%s' does not have privileges to execute 'ALTER' on: " +
        "functional.alltypesagg");
  }

  @Test
  public void TestDescribe() throws AuthorizationException, AnalysisException {
    AuthzOk("describe functional.alltypesagg");
    AuthzOk("describe functional.alltypes");
    AuthzOk("describe functional.complex_view");

    // Unqualified table name.
    AuthzError("describe alltypes",
        "User '%s' does not have privileges to access: default.alltypes");
    // Database doesn't exist.
    AuthzError("describe nodb.alltypes",
        "User '%s' does not have privileges to access: nodb.alltypes");
    // Insufficient privileges on table.
    AuthzError("describe functional.alltypestiny",
        "User '%s' does not have privileges to access: functional.alltypestiny");
    // Insufficient privileges on view.
    AuthzError("describe functional.alltypes_view",
        "User '%s' does not have privileges to access: functional.alltypes_view");
    // Insufficient privileges on db.
    AuthzError("describe functional_rc.alltypes",
        "User '%s' does not have privileges to access: functional_rc.alltypes");
  }

  @Test
  public void TestLoad() throws AuthorizationException, AnalysisException {
    // User has permission on table and URI.
    AuthzOk("load data inpath 'hdfs://localhost:20500/test-warehouse/tpch.lineitem'" +
        " into table functional.alltypes partition(month=10, year=2009)");

    // User does not have permission on table.
    AuthzError("load data inpath 'hdfs://localhost:20500/test-warehouse/tpch.lineitem'" +
        " into table functional.alltypesagg",
        "User '%s' does not have privileges to execute 'INSERT' on: " +
        "functional.alltypes");

    // User does not have permission on URI.
    AuthzError("load data inpath 'hdfs://localhost:20500/test-warehouse/tpch.part'" +
        " into table functional.alltypes partition(month=10, year=2009)",
        "User '%s' does not have privileges to access: " +
        "hdfs://localhost:20500/test-warehouse/tpch.part");

    // URI does not exist and user does not have permission.
    AuthzError("load data inpath 'hdfs://localhost:20500/test-warehouse/nope'" +
        " into table functional.alltypes partition(month=10, year=2009)",
        "User '%s' does not have privileges to access: " +
        "hdfs://localhost:20500/test-warehouse/nope");

    // Table/Db does not exist, user does not have permission.
    AuthzError("load data inpath 'hdfs://localhost:20500/test-warehouse/tpch.lineitem'" +
        " into table functional.notable",
        "User '%s' does not have privileges to execute 'INSERT' on: " +
        "functional.notable");
    AuthzError("load data inpath 'hdfs://localhost:20500/test-warehouse/tpch.lineitem'" +
        " into table nodb.alltypes",
        "User '%s' does not have privileges to execute 'INSERT' on: " +
        "nodb.alltypes");

    // Trying to LOAD a view does not reveal privileged information.
    AuthzError("load data inpath 'hdfs://localhost:20500/test-warehouse/tpch.lineitem'" +
        " into table functional.alltypes_view",
        "User '%s' does not have privileges to execute 'INSERT' on: " +
        "functional.alltypes_view");
  }

  @Test
  public void TestShowPermissions() throws AuthorizationException, AnalysisException {
    AuthzOk("show tables in functional");
    AuthzOk("show databases");
    AuthzOk("show tables in _impala_builtins");
    AuthzOk("show functions in _impala_builtins");

    // Database exists, user does not have access.
    AuthzError("show tables in functional_rc",
        "User '%s' does not have privileges to access: functional_rc.*");

    // Database does not exist, user does not have access.
    AuthzError("show tables in nodb",
        "User '%s' does not have privileges to access: nodb.*");

    AuthzError("show tables",
        "User '%s' does not have privileges to access: default.*");

    // Database does not exist, user has access.
    try {
      AuthzOk("show tables in newdb");
      fail("Expected AnalysisException");
    } catch (AnalysisException e) {
      Assert.assertEquals(e.getMessage(), "Database does not exist: newdb");
    }

    // Show partitions and show table/column stats.
    String[] statsQuals = new String[] { "partitions", "table stats", "column stats" };
    for (String qual: statsQuals) {
      AuthzOk(String.format("show %s functional.alltypesagg", qual));
      AuthzOk(String.format("show %s functional.alltypes", qual));
      // User does not have access to db/table.
      AuthzError(String.format("show %s nodb.tbl", qual),
          "User '%s' does not have privileges to access: nodb.tbl");
      AuthzError(String.format("show %s functional.badtbl", qual),
          "User '%s' does not have privileges to access: functional.badtbl");
      AuthzError(String.format("show %s functional_rc.alltypes", qual),
          "User '%s' does not have privileges to access: functional_rc.alltypes");
    }
  }

  @Test
  public void TestShowDbResultsFiltered() throws ImpalaException {
    // These are the only dbs that should show up because they are the only
    // dbs the user has any permissions on.
    List<String> expectedDbs = Lists.newArrayList("default", "functional",
        "functional_parquet", "functional_seq_snap", "tpcds", "tpch");

    List<String> dbs = fe_.getDbNames("*", USER);
    Assert.assertEquals(expectedDbs, dbs);

    dbs = fe_.getDbNames(null, USER);
    Assert.assertEquals(expectedDbs, dbs);
  }

  @Test
  public void TestShowTableResultsFiltered() throws ImpalaException {
    // The user only has permission on these tables/views in the functional databases.
    List<String> expectedTbls =
        Lists.newArrayList("alltypes", "alltypesagg", "complex_view", "view_view");

    List<String> tables = fe_.getTableNames("functional", "*", USER);
    Assert.assertEquals(expectedTbls, tables);

    tables = fe_.getTableNames("functional", null, USER);
    Assert.assertEquals(expectedTbls, tables);
  }

  @Test
  public void TestShowCreateTable() throws ImpalaException {
    AuthzOk("show create table functional.alltypesagg");
    AuthzOk("show create table functional.alltypes");

    // Unqualified table name.
    AuthzError("show create table alltypes",
        "User '%s' does not have privileges to access: default.alltypes");
    // Database doesn't exist.
    AuthzError("show create table nodb.alltypes",
        "User '%s' does not have privileges to access: nodb.alltypes");
    // Insufficient privileges on table.
    AuthzError("show create table functional.alltypestiny",
        "User '%s' does not have privileges to access: functional.alltypestiny");
    // Insufficient privileges on db.
    AuthzError("show create table functional_rc.alltypes",
        "User '%s' does not have privileges to access: functional_rc.alltypes");
  }

  @Test
  public void TestHs2GetTables() throws ImpalaException {
    TMetadataOpRequest req = new TMetadataOpRequest();
    req.setSession(createSessionState("default", USER));
    req.opcode = TMetadataOpcode.GET_TABLES;
    req.get_tables_req = new TGetTablesReq();
    req.get_tables_req.setSchemaName("functional");
    // Get all tables
    req.get_tables_req.setTableName("%");
    TResultSet resp = fe_.execHiveServer2MetadataOp(req);
    assertEquals(4, resp.rows.size());
    assertEquals("alltypes",
        resp.rows.get(0).colVals.get(2).string_val.toLowerCase());
    assertEquals(
        "alltypesagg", resp.rows.get(1).colVals.get(2).string_val.toLowerCase());
    assertEquals(
        "complex_view", resp.rows.get(2).colVals.get(2).string_val.toLowerCase());
    assertEquals(
        "view_view", resp.rows.get(3).colVals.get(2).string_val.toLowerCase());
  }

  @Test
  public void TestHs2GetSchema() throws ImpalaException {
    TMetadataOpRequest req = new TMetadataOpRequest();
    req.setSession(createSessionState("default", USER));
    req.opcode = TMetadataOpcode.GET_SCHEMAS;
    req.get_schemas_req = new TGetSchemasReq();
    // Get all schema (databases).
    req.get_schemas_req.setSchemaName("%");
    TResultSet resp = fe_.execHiveServer2MetadataOp(req);
    List<String> expectedDbs = Lists.newArrayList("default", "functional",
        "functional_parquet", "functional_seq_snap", "tpcds", "tpch");
    assertEquals(expectedDbs.size(), resp.rows.size());
    for (int i = 0; i < resp.rows.size(); ++i) {
      assertEquals(expectedDbs.get(i),
          resp.rows.get(i).colVals.get(0).string_val.toLowerCase());
    }
  }

  @Test
  public void TestHs2GetColumns() throws ImpalaException {
    // It should return one column: alltypes.string_col.
    TMetadataOpRequest req = new TMetadataOpRequest();
    req.opcode = TMetadataOpcode.GET_COLUMNS;
    req.setSession(createSessionState("default", USER));
    req.get_columns_req = new TGetColumnsReq();
    req.get_columns_req.setSchemaName("functional");
    req.get_columns_req.setTableName("alltypes");
    req.get_columns_req.setColumnName("stri%");
    TResultSet resp = fe_.execHiveServer2MetadataOp(req);
    assertEquals(1, resp.rows.size());

    // User does not have permission to access the table, no results should be returned.
    req.get_columns_req.setTableName("alltypesnopart");
    resp = fe_.execHiveServer2MetadataOp(req);
    assertEquals(0, resp.rows.size());

    // User does not have permission to access db or table, no results should be
    // returned.
    req.get_columns_req.setSchemaName("functional_seq_gzip");
    req.get_columns_req.setTableName("alltypes");
    resp = fe_.execHiveServer2MetadataOp(req);
    assertEquals(0, resp.rows.size());
  }

  @Test
  public void TestShortUsernameUsed() throws AnalysisException,
      AuthorizationException {
    // Different long variations of the same username.
    List<User> users = Lists.newArrayList(
        new User(USER.getName() + "/abc.host.com"),
        new User(USER.getName() + "/abc.host.com@REAL.COM"),
        new User(USER.getName() + "@REAL.COM"));
    for (User user: users) {
      ImpaladCatalog catalog = new ImpaladTestCatalog();
      AnalysisContext context = new AnalysisContext(catalog_,
          TestUtils.createQueryContext(Catalog.DEFAULT_DB, user.getName()));

      // Can select from table that user has privileges on.
      AuthzOk(context, "select * from functional.alltypesagg");

      // Unqualified table name.
      AuthzError(context, "select * from alltypes",
          "User '%s' does not have privileges to execute 'SELECT' on: default.alltypes",
          user);
    }
    // If the first character is '/' or '@', the short username should be the same as
    // the full username.
    assertEquals("/" + USER.getName(), new User("/" + USER.getName()).getShortName());
    assertEquals("@" + USER.getName(), new User("@" + USER.getName()).getShortName());
  }

  @Test
  public void TestFunction() throws ImpalaException {
    // First try with the less privileged user.
    User currentUser = USER;
    AnalysisContext context = new AnalysisContext(catalog_,
        TestUtils.createQueryContext(Catalog.DEFAULT_DB, currentUser.getName()));
    AuthzError(context, "show functions",
        "User '%s' does not have privileges to access: default", currentUser);
    AuthzOk(context, "show functions in tpch");

    AuthzError(context, "create function f() returns int location " +
        "'/test-warehouse/libTestUdfs.so' symbol='NoArgs'",
        "User '%s' does not have privileges to CREATE/DROP functions.", currentUser);

    AuthzError(context, "create function tpch.f() returns int location " +
        "'/test-warehouse/libTestUdfs.so' symbol='NoArgs'",
        "User '%s' does not have privileges to CREATE/DROP functions.", currentUser);

    AuthzError(context, "create function notdb.f() returns int location " +
        "'/test-warehouse/libTestUdfs.so' symbol='NoArgs'",
        "User '%s' does not have privileges to CREATE/DROP functions.", currentUser);

    AuthzError(context, "drop function if exists f()",
        "User '%s' does not have privileges to CREATE/DROP functions.", currentUser);

    AuthzError(context, "drop function notdb.f()",
        "User '%s' does not have privileges to CREATE/DROP functions.", currentUser);

    // TODO: Add test support for dynamically changing privileges for
    // file-based policy.
    if (authzConfig_.isFileBasedPolicy()) return;

    // Admin should be able to do everything

    SentryPolicyService sentryService =
        new SentryPolicyService(authzConfig_.getSentryConfig(), "server1");
    try {
      sentryService.grantRoleToGroup("admin", USER.getName());
      ((ImpaladTestCatalog) catalog_).reset();

      AuthzOk("show functions");
      AuthzOk("show functions in tpch");

      AuthzOk("create function f() returns int location " +
          "'/test-warehouse/libTestUdfs.so' symbol='NoArgs'");
      AuthzOk("create function tpch.f() returns int location " +
          "'/test-warehouse/libTestUdfs.so' symbol='NoArgs'");
      AuthzOk("drop function if exists f()");

      // Can't add function to system db
      AuthzError("create function _impala_builtins.f() returns int location " +
          "'/test-warehouse/libTestUdfs.so' symbol='NoArgs'",
          "Cannot modify system database.");
      AuthzError("drop function if exists pi()",
          "Cannot modify system database.");

      // Add default.f(), tpch.f()
      catalog_.addFunction(new ScalarFunction(new FunctionName("default", "f"),
          new ArrayList<Type>(), Type.INT, null, null, null, null));
      catalog_.addFunction(new ScalarFunction(new FunctionName("tpch", "f"),
          new ArrayList<Type>(), Type.INT, null, null, null, null));

      AuthzOk("drop function tpch.f()");
    } finally {
      sentryService.revokeRoleFromGroup("admin", USER.getName());
      ((ImpaladTestCatalog) catalog_).reset();

      AuthzError(context, "create function tpch.f() returns int location " +
          "'/test-warehouse/libTestUdfs.so' symbol='NoArgs'",
          "User '%s' does not have privileges to CREATE/DROP functions.", currentUser);

      // Couldn't create tpch.f() but can run it.
      AuthzOk("select tpch.f()");

      //Other tests don't expect tpch to contain functions
      //Specifically, if these functions are not cleaned up, TestDropDatabase() will fail
      catalog_.removeFunction(new ScalarFunction(new FunctionName("default", "f"),
          new ArrayList<Type>(), Type.INT, null, null, null, null));
      catalog_.removeFunction(new ScalarFunction(new FunctionName("tpch", "f"),
          new ArrayList<Type>(), Type.INT, null, null, null, null));
    }
  }

  @Test
  public void TestServerNameAuthorized() throws AnalysisException {
    if (authzConfig_.isFileBasedPolicy()) {
      // Authorization config that has a different server name from policy file.
      TestWithIncorrectConfig(AuthorizationConfig.createHadoopGroupAuthConfig(
          "differentServerName", AUTHZ_POLICY_FILE, ""),
          new User(System.getProperty("user.name")));
    } // TODO: Test using policy server.
  }

  @Test
  public void TestNoPermissionsWhenPolicyFileDoesNotExist() throws AnalysisException {
    // Test doesn't make sense except for file based policies.
    if (!authzConfig_.isFileBasedPolicy()) return;

    // Validate a non-existent policy file.
    // Use a HadoopGroupProvider in this case so the user -> group mappings can still be
    // resolved in the absence of the policy file.
    TestWithIncorrectConfig(AuthorizationConfig.createHadoopGroupAuthConfig("server1",
        AUTHZ_POLICY_FILE + "_does_not_exist", ""),
        new User(System.getProperty("user.name")));
  }

  @Test
  public void TestConfigValidation() throws InternalException {
    String sentryConfig = authzConfig_.getSentryConfig().getConfigFile();
    // Valid configs pass validation.
    AuthorizationConfig config = AuthorizationConfig.createHadoopGroupAuthConfig(
        "server1", AUTHZ_POLICY_FILE, sentryConfig);
    config.validateConfig();
    Assert.assertTrue(config.isEnabled());
    Assert.assertTrue(config.isFileBasedPolicy());

    config = AuthorizationConfig.createHadoopGroupAuthConfig("server1", null,
        sentryConfig);
    config.validateConfig();
    Assert.assertTrue(config.isEnabled());
    Assert.assertTrue(!config.isFileBasedPolicy());

    // Invalid configs
    // No sentry configuration file.
    config = AuthorizationConfig.createHadoopGroupAuthConfig(
        "server1", AUTHZ_POLICY_FILE, null);
    Assert.assertTrue(config.isEnabled());
    try {
      config.validateConfig();
    } catch (Exception e) {
      Assert.assertEquals(e.getMessage(), "A valid path to a sentry-site.xml config " +
          "file must be set using --sentry_config to enable authorization.");
    }

    // Empty / null server name.
    config = AuthorizationConfig.createHadoopGroupAuthConfig(
        "", AUTHZ_POLICY_FILE, sentryConfig);
    Assert.assertTrue(config.isEnabled());
    try {
      config.validateConfig();
      fail("Expected configuration to fail.");
    } catch (IllegalArgumentException e) {
      Assert.assertEquals(e.getMessage(),
          "Authorization is enabled but the server name is null or empty. Set the " +
          "server name using the impalad --server_name flag.");
    }
    config = AuthorizationConfig.createHadoopGroupAuthConfig(null, AUTHZ_POLICY_FILE,
        sentryConfig);
    Assert.assertTrue(config.isEnabled());
    try {
      config.validateConfig();
      fail("Expected configuration to fail.");
    } catch (IllegalArgumentException e) {
      Assert.assertEquals(e.getMessage(),
          "Authorization is enabled but the server name is null or empty. Set the " +
          "server name using the impalad --server_name flag.");
    }

    // Sentry config file does not exist.
    config = AuthorizationConfig.createHadoopGroupAuthConfig("server1", "",
        "/path/does/not/exist.xml");
    Assert.assertTrue(config.isEnabled());
    try {
      config.validateConfig();
      fail("Expected configuration to fail.");
    } catch (Exception e) {
      Assert.assertEquals(e.getMessage(),
          "Sentry configuration file does not exist: /path/does/not/exist.xml");
    }

    // Invalid ResourcePolicyProvider class name.
    config = new AuthorizationConfig("server1", AUTHZ_POLICY_FILE, "",
        "ClassDoesNotExist");
    Assert.assertTrue(config.isEnabled());
    try {
      config.validateConfig();
      fail("Expected configuration to fail.");
    } catch (IllegalArgumentException e) {
      Assert.assertEquals(e.getMessage(),
          "The authorization policy provider class 'ClassDoesNotExist' was not found.");
    }

    // Valid class name, but class is not derived from ResourcePolicyProvider
    config = new AuthorizationConfig("server1", AUTHZ_POLICY_FILE, "",
        this.getClass().getName());
    Assert.assertTrue(config.isEnabled());
    try {
      config.validateConfig();
      fail("Expected configuration to fail.");
    } catch (IllegalArgumentException e) {
      Assert.assertEquals(e.getMessage(), String.format("The authorization policy " +
          "provider class '%s' must be a subclass of '%s'.", this.getClass().getName(),
          ResourceAuthorizationProvider.class.getName()));
    }

    // Config validations skipped if authorization disabled
    config = new AuthorizationConfig("", "", "", "");
    Assert.assertFalse(config.isEnabled());
    config = new AuthorizationConfig(null, "", "", null);
    Assert.assertFalse(config.isEnabled());
    config = new AuthorizationConfig("", null, "", "");
    Assert.assertFalse(config.isEnabled());
    config = new AuthorizationConfig(null, null, null, null);
    Assert.assertFalse(config.isEnabled());
  }

  @Test
  public void TestLocalGroupPolicyProvider() throws AnalysisException,
      AuthorizationException {
    // Use an authorization configuration that uses the
    // LocalGroupResourceAuthorizationProvider.
    AuthorizationConfig authzConfig = new AuthorizationConfig("server1",
        AUTHZ_POLICY_FILE, "",
        LocalGroupResourceAuthorizationProvider.class.getName());
    ImpaladCatalog catalog = new ImpaladTestCatalog(authzConfig);

    // Create an analysis context + FE with the test user (as defined in the policy file)
    User user = new User("test_user");
    AnalysisContext context = new AnalysisContext(catalog,
        TestUtils.createQueryContext(Catalog.DEFAULT_DB, user.getName()));
    Frontend fe = new Frontend(authzConfig, catalog);

    // Can select from table that user has privileges on.
    AuthzOk(fe, context, "select * from functional.alltypesagg");
    // Does not have privileges to execute a query
    AuthzError(fe, context, "select * from functional.alltypes",
        "User '%s' does not have privileges to execute 'SELECT' on: functional.alltypes",
        user);

    // Verify with the admin user
    user = new User("admin_user");
    context = new AnalysisContext(catalog,
        TestUtils.createQueryContext(Catalog.DEFAULT_DB, user.getName()));
    fe = new Frontend(authzConfig, catalog);

    // Admin user should have privileges to do anything
    AuthzOk(fe, context, "select * from functional.alltypesagg");
    AuthzOk(fe, context, "select * from functional.alltypes");
    AuthzOk(fe, context, "invalidate metadata");
  }

  private void TestWithIncorrectConfig(AuthorizationConfig authzConfig, User user)
      throws AnalysisException {
    Frontend fe = new Frontend(authzConfig, catalog_);
    AnalysisContext ac = new AnalysisContext(new ImpaladTestCatalog(),
        TestUtils.createQueryContext(Catalog.DEFAULT_DB, user.getName()));
    AuthzError(fe, ac, "select * from functional.alltypesagg",
        "User '%s' does not have privileges to execute 'SELECT' on: " +
        "functional.alltypesagg", user);
    AuthzError(fe, ac, "ALTER TABLE functional_seq_snap.alltypes ADD COLUMNS (c1 int)",
        "User '%s' does not have privileges to execute 'ALTER' on: " +
        "functional_seq_snap.alltypes", user);
    AuthzError(fe, ac, "drop table tpch.lineitem",
        "User '%s' does not have privileges to execute 'DROP' on: tpch.lineitem",
        user);
    AuthzError(fe, ac, "show tables in functional",
        "User '%s' does not have privileges to access: functional.*", user);
  }

  private void AuthzOk(String stmt) throws AuthorizationException,
      AnalysisException {
    AuthzOk(analysisContext_, stmt);
  }

  private void AuthzOk(AnalysisContext context, String stmt)
      throws AuthorizationException, AnalysisException {
    AuthzOk(fe_, context, stmt);
  }

  private static void AuthzOk(Frontend fe, AnalysisContext context, String stmt)
      throws AuthorizationException, AnalysisException {
    context.analyze(stmt);
    context.getAnalyzer().authorize(fe.getAuthzChecker());
  }

  /**
   * Verifies that a given statement fails authorization and the expected error
   * string matches.
   */
  private void AuthzError(String stmt, String expectedErrorString)
      throws AnalysisException {
    AuthzError(analysisContext_, stmt, expectedErrorString, USER);
  }

  private void AuthzError(AnalysisContext analysisContext,
      String stmt, String expectedErrorString, User user) throws AnalysisException {
    AuthzError(fe_, analysisContext, stmt, expectedErrorString, user);
  }

  private static void AuthzError(Frontend fe, AnalysisContext analysisContext,
      String stmt, String expectedErrorString, User user) throws AnalysisException {
    Preconditions.checkNotNull(expectedErrorString);
    try {
      try {
        analysisContext.analyze(stmt);
      } finally {
        analysisContext.getAnalyzer().authorize(fe.getAuthzChecker());
      }
    } catch (AuthorizationException e) {
      // Insert the username into the error.
      expectedErrorString = String.format(expectedErrorString, user.getName());
      String errorString = e.getMessage();
      Assert.assertTrue(
          "got error:\n" + errorString + "\nexpected:\n" + expectedErrorString,
          errorString.startsWith(expectedErrorString));
      return;
    }
    fail("Stmt didn't result in authorization error: " + stmt);
  }

  private static TSessionState createSessionState(String defaultDb, User user) {
    return new TSessionState(null, null,
        defaultDb, user.getName(), new TNetworkAddress("", 0));
  }

  private SentryPolicyService createSentryService() {
    return new SentryPolicyService(authzConfig_.getSentryConfig(), "server1");
  }
}
