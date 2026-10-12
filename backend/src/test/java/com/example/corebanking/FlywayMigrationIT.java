package com.example.corebanking;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.corebanking.support.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FlywayMigrationIT extends IntegrationTestBase {

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("Flyway creates schema core and applies V1 baseline migration successfully")
    void flywayMigration_shouldCreateCoreSchemaAndRecordMigrationHistory() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            // Verify schema 'core' exists
            try (ResultSet rs = connection.getMetaData().getSchemas(null, "core")) {
                assertThat(rs.next()).as("Schema 'core' should exist in the database").isTrue();
            }

            // Verify table 'flyway_schema_history' exists inside schema 'core'
            try (ResultSet rs =
                    connection
                            .getMetaData()
                            .getTables(null, "core", "flyway_schema_history", null)) {
                assertThat(rs.next())
                        .as("Table 'flyway_schema_history' should exist in schema 'core'")
                        .isTrue();
            }

            // Verify migration record details in core.flyway_schema_history
            try (Statement statement = connection.createStatement();
                    ResultSet rs =
                            statement.executeQuery(
                                    "SELECT version, description, type, script, success "
                                            + "FROM core.flyway_schema_history "
                                            + "WHERE version = '1'")) {
                assertThat(rs.next())
                        .as("Migration version 1 should be recorded in flyway_schema_history")
                        .isTrue();
                assertThat(rs.getString("version")).isEqualTo("1");
                assertThat(rs.getString("description")).isEqualTo("init schema");
                assertThat(rs.getString("type")).isEqualTo("SQL");
                assertThat(rs.getString("script")).isEqualTo("V1__init_schema.sql");
                assertThat(rs.getBoolean("success")).isTrue();
            }
        }
    }

    @Test
    @DisplayName(
            "Flyway applies V2 customer migration successfully creating customer table and"
                    + " sequence")
    void flywayMigration_shouldApplyV2CustomerMigrationSuccessfully() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            // Verify migration version 2 record in core.flyway_schema_history
            try (Statement statement = connection.createStatement();
                    ResultSet rs =
                            statement.executeQuery(
                                    "SELECT version, description, type, script, success "
                                            + "FROM core.flyway_schema_history "
                                            + "WHERE version = '2'")) {
                assertThat(rs.next())
                        .as("Migration version 2 should be recorded in flyway_schema_history")
                        .isTrue();
                assertThat(rs.getString("version")).isEqualTo("2");
                assertThat(rs.getString("description")).isEqualTo("customer");
                assertThat(rs.getString("type")).isEqualTo("SQL");
                assertThat(rs.getString("script")).isEqualTo("V2__customer.sql");
                assertThat(rs.getBoolean("success")).isTrue();
            }

            // Verify table 'customer' exists inside schema 'core'
            try (ResultSet rs =
                    connection.getMetaData().getTables(null, "core", "customer", null)) {
                assertThat(rs.next()).as("Table 'customer' should exist in schema 'core'").isTrue();
            }

            // Verify sequence 'customer_code_seq' produces valid sequence values
            try (Statement statement = connection.createStatement();
                    ResultSet rs =
                            statement.executeQuery("SELECT nextval('core.customer_code_seq')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isPositive();
            }
        }
    }

    @Test
    @DisplayName(
            "Flyway applies V3 account migration successfully creating account table and sequence")
    void flywayMigration_shouldApplyV3AccountMigrationSuccessfully() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            // Verify migration version 3 record in core.flyway_schema_history
            try (Statement statement = connection.createStatement();
                    ResultSet rs =
                            statement.executeQuery(
                                    "SELECT version, description, type, script, success "
                                            + "FROM core.flyway_schema_history "
                                            + "WHERE version = '3'")) {
                assertThat(rs.next())
                        .as("Migration version 3 should be recorded in flyway_schema_history")
                        .isTrue();
                assertThat(rs.getString("version")).isEqualTo("3");
                assertThat(rs.getString("description")).isEqualTo("account");
                assertThat(rs.getString("type")).isEqualTo("SQL");
                assertThat(rs.getString("script")).isEqualTo("V3__account.sql");
                assertThat(rs.getBoolean("success")).isTrue();
            }

            // Verify table 'account' exists inside schema 'core'
            try (ResultSet rs = connection.getMetaData().getTables(null, "core", "account", null)) {
                assertThat(rs.next()).as("Table 'account' should exist in schema 'core'").isTrue();
            }

            // Verify sequence 'account_number_seq' produces valid sequence values
            try (Statement statement = connection.createStatement();
                    ResultSet rs =
                            statement.executeQuery("SELECT nextval('core.account_number_seq')")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isPositive();
            }
        }
    }

    @Test
    @DisplayName(
            "Flyway applies V4 transaction migration successfully creating transaction table and"
                    + " indexes")
    void flywayMigration_shouldApplyV4TransactionMigrationSuccessfully() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            // Verify migration version 4 record in core.flyway_schema_history
            try (Statement statement = connection.createStatement();
                    ResultSet rs =
                            statement.executeQuery(
                                    "SELECT version, description, type, script, success "
                                            + "FROM core.flyway_schema_history "
                                            + "WHERE version = '4'")) {
                assertThat(rs.next())
                        .as("Migration version 4 should be recorded in flyway_schema_history")
                        .isTrue();
                assertThat(rs.getString("version")).isEqualTo("4");
                assertThat(rs.getString("description")).isEqualTo("transaction");
                assertThat(rs.getString("type")).isEqualTo("SQL");
                assertThat(rs.getString("script")).isEqualTo("V4__transaction.sql");
                assertThat(rs.getBoolean("success")).isTrue();
            }

            // Verify table 'transaction' exists inside schema 'core'
            try (ResultSet rs =
                    connection.getMetaData().getTables(null, "core", "transaction", null)) {
                assertThat(rs.next())
                        .as("Table 'transaction' should exist in schema 'core'")
                        .isTrue();
            }
        }
    }
}
